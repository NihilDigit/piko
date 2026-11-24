package dev.piko.shared.media.cache

import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * 统一缓存分类，与设置页「存储与缓存」分组一一对应。
 */
enum class CacheCategory {
    /** Coil 缩略图与图片磁盘缓存。 */
    IMAGES,
    /** 视频播放持久化分块缓存（位于 [PikoFileCachePool]，严格避开在途下载租约）。 */
    PLAYBACK,
    /** 低清 MPEG-TS 全片进度条雪碧图与「速览」缓存。 */
    STORYBOARD,
    /** 信息流 30s/180s 片段切片缓存（[dev.piko.shared.media.FileClipCache]）。 */
    CLIPS,
    /** 本地 SQLite 全盘目录树索引库（只读统计，禁止盲删打开中的 WAL）。 */
    TREE_INDEX,
}

data class CacheCategoryStats(
    val category: CacheCategory,
    val bytes: Long = 0L,
    val fileCount: Int = 0,
    /** 是否允许在缓存管理中一键清理；目录树索引库为只读统计，不直接删除打开的 WAL。 */
    val clearable: Boolean = category != CacheCategory.TREE_INDEX,
)

data class CachePolicy(
    /** 可淘汰缓存总量上限（字节）；`0L` 表示不限容量。 */
    val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /** 未访问缓存自动清理期限（天）；`0` 表示永不按时间过期。 */
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
) {
    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 2L * 1024L * 1024L * 1024L // 2 GiB
        const val DEFAULT_MAX_AGE_DAYS: Int = 14
    }
}

data class CacheSummary(
    val categories: Map<CacheCategory, CacheCategoryStats> = CacheCategory.entries.associateWith {
        CacheCategoryStats(category = it)
    },
    val totalBytes: Long = 0L,
    val clearableBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val policy: CachePolicy = CachePolicy(),
    val isScanning: Boolean = false,
    val isClearing: Boolean = false,
    val lastUpdatedEpochMillis: Long = 0L,
) {
    /** 剩余可用缓存配额（字节）；未设定上限（`policy.maxTotalBytes <= 0L`）时返回 `null`。 */
    val remainingQuotaBytes: Long?
        get() = policy.maxTotalBytes.takeIf { it > 0L }?.let { (it - clearableBytes).coerceAtLeast(0L) }

    fun statsOf(category: CacheCategory): CacheCategoryStats =
        categories[category] ?: CacheCategoryStats(category = category)
}

/**
 * 单个缓存分类的底层存储后端，由各模块按自身安全边界实现（跳过在途下载与正在播放的流）。
 */
interface CacheCategoryBackend {
    val category: CacheCategory
    val clearable: Boolean get() = category != CacheCategory.TREE_INDEX

    suspend fun scan(): CacheCategoryStats

    /** 清理本分类全部可安全释放的缓存文件，返回释放的字节数。 */
    suspend fun clear(): Long

    /**
     * 按 [maxAgeMillis]（`> 0` 时删除超过该时长未访问的条目）与 [targetMaxBytes]（`>= 0` 时按 LRU 淘汰至不超此上限）
     * 执行淘汰，返回释放的字节数。
     */
    suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long = 0L

    /** 列出本分类下的持久化播放缓存视频明细（仅 [CacheCategory.PLAYBACK] 实现）。 */
    suspend fun listPlaybackEntries(account: String? = null): List<PlaybackCacheItem> = emptyList()

    /** 删除单条持久化播放缓存条目并返回释放的字节数（仅 [CacheCategory.PLAYBACK] 实现）。 */
    suspend fun deletePlaybackEntry(path: String): Long = 0L

    /** 将反查到的真实文件元数据回写升级至 `.pplay` v2（仅 [CacheCategory.PLAYBACK] 实现）。 */
    suspend fun backfillPlaybackMetadata(
        cachePath: String,
        fileId: String,
        fileName: String,
        qualityLabel: String,
        account: String,
    ) {}
}

/**
 * Telegram 级统一缓存管理引擎：
 * - 汇总 5 类磁盘缓存的精确体积与文件数，驱动 `StateFlow<CacheSummary>`；
 * - 支持按分类清理与一键全清，严格保护在途下载租约、活跃播放流与打开中的 SQLite WAL；
 * - 支持总量上限 + TTL 天数策略与启动/周期性 LRU+TTL 自动清理。
 */
class PikoCacheManager(
    private val backends: Map<CacheCategory, CacheCategoryBackend>,
    private val scope: CoroutineScope,
    initialPolicy: CachePolicy = CachePolicy(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deviceFreeBytesProvider: () -> Long = { 0L },
) {
    private val opLock = Mutex()
    private var maintenanceJob: Job? = null

    private val _policy = MutableStateFlow(sanitizePolicy(initialPolicy))
    val policy: StateFlow<CachePolicy> = _policy.asStateFlow()

    private val _summary = MutableStateFlow(
        CacheSummary(
            categories = CacheCategory.entries.associateWith { cat ->
                CacheCategoryStats(
                    category = cat,
                    clearable = backends[cat]?.clearable ?: (cat != CacheCategory.TREE_INDEX),
                )
            },
            policy = _policy.value,
        ),
    )
    val summary: StateFlow<CacheSummary> = _summary.asStateFlow()

    suspend fun refresh(): CacheSummary = withContext(Dispatchers.IO) {
        opLock.withLock {
            _summary.update { it.copy(isScanning = true) }
            try {
                scanAndPublishLocked()
            } finally {
                _summary.update { it.copy(isScanning = false) }
            }
        }
    }

    suspend fun clearCategory(category: CacheCategory): Long = withContext(Dispatchers.IO) {
        val backend = backends[category] ?: return@withContext 0L
        if (!backend.clearable || category == CacheCategory.TREE_INDEX) return@withContext 0L
        opLock.withLock {
            _summary.update { it.copy(isClearing = true) }
            try {
                val freed = runCatching { backend.clear() }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "Failed to clear cache category $category", err)
                    }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                if (freed > 0L) {
                    PikoLog.i(TAG, "Cleared cache category $category: freedBytes=$freed")
                }
                scanAndPublishLocked()
                freed
            } finally {
                _summary.update { it.copy(isClearing = false) }
            }
        }
    }

    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        opLock.withLock {
            _summary.update { it.copy(isClearing = true) }
            try {
                var totalFreed = 0L
                for (category in CLEARABLE_ORDER) {
                    val backend = backends[category] ?: continue
                    if (!backend.clearable) continue
                    val freed = runCatching { backend.clear() }
                        .onFailure { err ->
                            if (err is CancellationException) throw err
                            PikoLog.w(TAG, "Failed to clear cache category $category during clearAll", err)
                        }
                        .getOrDefault(0L)
                        .coerceAtLeast(0L)
                    totalFreed += freed
                }
                if (totalFreed > 0L) {
                    PikoLog.i(TAG, "Cleared all clearable caches: freedBytes=$totalFreed")
                }
                scanAndPublishLocked()
                totalFreed
            } finally {
                _summary.update { it.copy(isClearing = false) }
            }
        }
    }

    suspend fun listPlaybackEntries(account: String? = null): List<PlaybackCacheItem> = withContext(Dispatchers.IO) {
        val backend = backends[CacheCategory.PLAYBACK] ?: return@withContext emptyList()
        runCatching { backend.listPlaybackEntries(account) }
            .onFailure { err ->
                if (err is CancellationException) throw err
                PikoLog.w(TAG, "Failed to list playback cache entries", err)
            }
            .getOrDefault(emptyList())
    }

    suspend fun deletePlaybackEntry(path: String): Long = withContext(Dispatchers.IO) {
        val backend = backends[CacheCategory.PLAYBACK] ?: return@withContext 0L
        opLock.withLock {
            _summary.update { it.copy(isClearing = true) }
            try {
                val freed = runCatching { backend.deletePlaybackEntry(path) }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "Failed to delete playback cache entry", err)
                    }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                scanAndPublishLocked()
                freed
            } finally {
                _summary.update { it.copy(isClearing = false) }
            }
        }
    }

    suspend fun backfillPlaybackMetadata(
        cachePath: String,
        fileId: String,
        fileName: String,
        qualityLabel: String,
        account: String,
    ): Unit = withContext(Dispatchers.IO) {
        val backend = backends[CacheCategory.PLAYBACK] ?: return@withContext
        runCatching {
            backend.backfillPlaybackMetadata(cachePath, fileId, fileName, qualityLabel, account)
        }.onFailure { err ->
            if (err is CancellationException) throw err
            PikoLog.w(TAG, "Failed to backfill playback cache metadata", err)
        }
    }

    suspend fun applyPolicy(maxTotalBytes: Long, maxAgeDays: Int): Long {
        val next = sanitizePolicy(CachePolicy(maxTotalBytes = maxTotalBytes, maxAgeDays = maxAgeDays))
        _policy.value = next
        _summary.update { it.copy(policy = next) }
        return enforcePolicy()
    }

    /**
     * 按当前 [policy] 执行一次 TTL + 全局容量 LRU 淘汰：
     * 1. 先按 `maxAgeDays` 清理过期条目；
     * 2. 若可淘汰缓存总量仍超过 `maxTotalBytes`，按 `PLAYBACK -> CLIPS -> STORYBOARD -> IMAGES` 优先级削减超额字节。
     */
    suspend fun enforcePolicy(): Long = withContext(Dispatchers.IO) {
        opLock.withLock {
            val activePolicy = _policy.value
            val maxAgeMillis = if (activePolicy.maxAgeDays > 0) {
                activePolicy.maxAgeDays.toLong() * MILLIS_PER_DAY
            } else {
                0L
            }
            var totalFreed = 0L

            // Phase 1: TTL expiry pass across evictable categories
            if (maxAgeMillis > 0L) {
                for (category in PRUNE_PRIORITY_ORDER) {
                    val backend = backends[category] ?: continue
                    if (!backend.clearable) continue
                    totalFreed += runCatching { backend.prune(maxAgeMillis = maxAgeMillis, targetMaxBytes = -1L) }
                        .onFailure { err ->
                            if (err is CancellationException) throw err
                            PikoLog.w(TAG, "TTL prune failed for $category", err)
                        }
                        .getOrDefault(0L)
                        .coerceAtLeast(0L)
                }
            }

            // Phase 2: Global byte-cap LRU pass
            val snapshot = scanAllLocked()
            val cap = activePolicy.maxTotalBytes
            if (cap > 0L) {
                val evictableTotal = PRUNE_PRIORITY_ORDER.sumOf { snapshot[it]?.bytes ?: 0L }
                var excess = evictableTotal - cap
                if (excess > 0L) {
                    for (category in PRUNE_PRIORITY_ORDER) {
                        if (excess <= 0L) break
                        val backend = backends[category] ?: continue
                        if (!backend.clearable) continue
                        val currentBytes = snapshot[category]?.bytes ?: 0L
                        if (currentBytes <= 0L) continue
                        val floorBytes = if (category == CacheCategory.IMAGES) minOf(cap, IMAGE_RESERVE_FLOOR_BYTES) else 0L
                        val reducible = (currentBytes - floorBytes).coerceAtLeast(0L)
                        if (reducible <= 0L) continue
                        val targetBytes = (currentBytes - minOf(excess, reducible)).coerceAtLeast(floorBytes)
                        val freed = runCatching { backend.prune(maxAgeMillis = 0L, targetMaxBytes = targetBytes) }
                            .onFailure { err ->
                                if (err is CancellationException) throw err
                                PikoLog.w(TAG, "Cap LRU prune failed for $category", err)
                            }
                            .getOrDefault(0L)
                            .coerceAtLeast(0L)
                        totalFreed += freed
                        excess -= freed
                    }
                }
            }

            if (totalFreed > 0L) {
                PikoLog.i(
                    TAG,
                    "Enforced cache policy (cap=${activePolicy.maxTotalBytes} B, ttl=${activePolicy.maxAgeDays}d): freedBytes=$totalFreed",
                )
                scanAndPublishLocked()
            } else {
                publishStatsLocked(snapshot)
            }
            totalFreed
        }
    }

    fun startPeriodicMaintenance(
        initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MS,
        intervalMillis: Long = DEFAULT_MAINTENANCE_INTERVAL_MS,
    ) {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch(Dispatchers.IO) {
            if (initialDelayMillis > 0L) delay(initialDelayMillis)
            while (isActive) {
                runCatching { enforcePolicy() }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "Periodic cache maintenance failed", err)
                    }
                delay(intervalMillis.coerceAtLeast(MIN_MAINTENANCE_INTERVAL_MS))
            }
        }
    }

    fun stopPeriodicMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = null
    }

    private suspend fun scanAndPublishLocked(): CacheSummary {
        val stats = scanAllLocked()
        return publishStatsLocked(stats)
    }

    private suspend fun scanAllLocked(): Map<CacheCategory, CacheCategoryStats> {
        val result = LinkedHashMap<CacheCategory, CacheCategoryStats>(CacheCategory.entries.size)
        for (category in CacheCategory.entries) {
            val backend = backends[category]
            val stats = if (backend == null) {
                CacheCategoryStats(category = category, clearable = category != CacheCategory.TREE_INDEX)
            } else {
                runCatching { backend.scan() }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "Failed to scan cache category $category", err)
                    }
                    .getOrElse {
                        CacheCategoryStats(category = category, clearable = backend.clearable)
                    }
            }
            result[category] = stats.copy(
                bytes = stats.bytes.coerceAtLeast(0L),
                fileCount = stats.fileCount.coerceAtLeast(0),
                clearable = if (category == CacheCategory.TREE_INDEX) false else stats.clearable,
            )
        }
        return result
    }

    private fun publishStatsLocked(stats: Map<CacheCategory, CacheCategoryStats>): CacheSummary {
        val total = stats.values.sumOf { it.bytes }
        val clearable = stats.values.filter { it.clearable }.sumOf { it.bytes }
        val deviceFree = runCatching { deviceFreeBytesProvider() }.getOrDefault(0L).coerceAtLeast(0L)
        val current = _summary.value
        val updated = current.copy(
            categories = stats,
            totalBytes = total,
            clearableBytes = clearable,
            deviceFreeBytes = deviceFree,
            policy = _policy.value,
            lastUpdatedEpochMillis = nowMillis(),
        )
        _summary.value = updated
        return updated
    }

    private fun sanitizePolicy(policy: CachePolicy): CachePolicy = CachePolicy(
        maxTotalBytes = policy.maxTotalBytes.coerceAtLeast(0L),
        maxAgeDays = policy.maxAgeDays.coerceAtLeast(0),
    )

    companion object {
        private const val TAG = "CacheManager"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val IMAGE_RESERVE_FLOOR_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_INITIAL_DELAY_MS = 15_000L
        const val DEFAULT_MAINTENANCE_INTERVAL_MS = 30L * 60L * 1000L
        private const val MIN_MAINTENANCE_INTERVAL_MS = 60_000L

        private val CLEARABLE_ORDER = listOf(
            CacheCategory.IMAGES,
            CacheCategory.PLAYBACK,
            CacheCategory.STORYBOARD,
            CacheCategory.CLIPS,
        )

        private val PRUNE_PRIORITY_ORDER = listOf(
            CacheCategory.PLAYBACK,
            CacheCategory.CLIPS,
            CacheCategory.STORYBOARD,
            CacheCategory.IMAGES,
        )
    }
}
