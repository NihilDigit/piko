package dev.piko.shared.media.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.IO
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logRangeAttempt
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakFileCache
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.fileCache
import io.github.nihildigit.pikpak.fileHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.Clock

/**
 * 某一档视频/文件在本地持久化缓存中的覆盖快照，供缓存感知 Auto ABR 与雪碧图零流量复用查询。
 */
data class CachedRenditionInfo(
    val cachedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val coverageRatio: Float = 0f,
    /** 当查询指定了字节窗口时，表示该窗口所需的全部 256 KiB 块是否均已缓存在本地。 */
    val windowCached: Boolean = false,
)

/**
 * 持久化视频播放缓存的单条明细，供设置页「存储与缓存」展示具体缓存视频、直开播放与单条清理。
 */
data class PlaybackCacheItem(
    val cachePath: String,
    val fileId: String,
    val fileName: String,
    val qualityLabel: String,
    val account: String,
    val cachedBytes: Long,
    val totalBytes: Long,
    val diskBytes: Long,
    val lastAccessEpochMillis: Long,
    val isActive: Boolean,
) {
    val coverageRatio: Float
        get() = if (totalBytes > 0L) (cachedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * 同一账号的同一原文件共用句柄、缓存与稀疏存储；每个使用者单独释放引用。
 *
 * 暂存文件的位置每次都由内容身份经 [target] 推出，不从任务表里取：存下的绝对路径在便携目录挪位、
 * 换盘符之后指向旧处，续传会在那里把目录重新建出来。
 *
 * 同时承载**持久化播放缓存**（带 `.pplay` 侧车标记）：
 * - 活跃/暂停的下载租约登记在 [owners] 中，绝不会被播放缓存清理或 LRU 淘汰删除；
 * - 仅由播放器写入的持久化分块经 `.pplay` 记录访问时间戳与块位图，关闭播放后保留在盘上供重播与 ABR 复用，
 *   并受 [PikoCacheManager] 统一容量上限与 TTL 策略约束。
 */
class PikoFileCachePool(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val target: suspend (name: String) -> String,
) {
    constructor(
        scope: CoroutineScope,
        target: suspend (name: String) -> String,
    ) : this(scope, { Clock.System.now().toEpochMilliseconds() }, target)
    private val lock = Mutex()
    private val entries = mutableMapOf<String, Entry>()
    private val diskReaders = mutableMapOf<String, DiskReader>()
    private val paths = MutableStateFlow<Map<String, String>>(emptyMap())
    private val owners = mutableMapOf<String, MutableSet<String>>()
    private val cleanupScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    private class DiskReader(
        val key: String,
        val path: String,
        val store: PersistentFileStore,
        var references: Int = 0,
        val closed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private fun activePathsLocked(): Set<String> {
        if (entries.isEmpty() && diskReaders.isEmpty()) return emptySet()
        val out = HashSet<String>(entries.size + diskReaders.size)
        for (e in entries.values) out += e.store.path
        for (r in diskReaders.values) out += r.path
        return out
    }

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            cleanupScope.launch {
                try { lock.withLock {
                    diskReaders.values.forEach { reader ->
                        runCatching { reader.store.close() }
                        reader.closed.complete(Unit)
                    }
                    diskReaders.clear()
                    entries.values.forEach { entry ->
                        entry.cache.close()
                        entry.handle.close()
                        runCatching {
                            closeOrDeleteEntryStore(entry)
                        }.logFailure(TAG, "Failed to close/delete cache store on pool shutdown: cache=${fileCacheName(entry.key).take(12)}, retained=${entry.retained}")
                    }
                    entries.clear()
                } } finally { cleanupScope.cancel() }
            }
        }
    }

    private suspend fun pathOf(key: String): String = target(fileCacheName(key))

    suspend fun remember(account: String, gcid: String, size: Long, owner: String, mediaId: String? = null) = lock.withLock {
        val key = identity(account, gcid, size, mediaId)
        val path = pathOf(key)
        paths.update { it + (key to path) }
        owners.getOrPut(key) { mutableSetOf() }.add(owner)
        entries[key]?.let {
            it.retained = true
            it.store.setDurable(true)
        }
    }

    class Entry internal constructor(
        internal val key: String,
        internal val client: PikPakClient,
        val handle: PikPakFileHandle,
        val cache: PikPakFileCache,
        val store: PersistentFileStore,
        internal var references: Int = 0,
        internal var retained: Boolean = false,
        internal var playbackCached: Boolean = false,
        internal var fileId: String = "",
        internal var fileName: String = "",
        internal var qualityLabel: String = "",
        internal var account: String = "",
    )

    class Lease internal constructor(val entry: Entry, private val release: suspend () -> Unit) : AutoCloseable {
        private val closed = MutableStateFlow(false)
        internal var releaseInBackground: (() -> Unit)? = null
        suspend fun release() {
            if (closed.compareAndSet(false, true)) withContext(NonCancellable) { release.invoke() }
        }
        override fun close() { releaseInBackground?.invoke() }
    }

    suspend fun acquire(
        client: PikPakClient,
        fileId: String,
        gcid: String,
        size: Long,
        name: String,
        parentId: String = "",
        leased: Boolean = false,
        detail: FileDetail? = null,
        retained: Boolean = false,
        concurrency: Int = client.connectionBudget,
        owner: String = fileId,
        /** 转码档的 media ID，原画为 null。转码档另占一份暂存，[size] 仍是原画的大小（重建文件对象时用）。 */
        mediaId: String? = null,
        /** 要读的那一档的长度，即暂存的大小。原画就是 [size]。 */
        streamSize: Long = size,
        /** 已知的媒体平均字节消耗率（字节/秒），用于自适应播放缓冲余量与前后台并发调度。 */
        mediaBytesPerSecond: Long? = null,
        /** 是否作为持久化播放缓存保留在盘上（受 [PikoCacheManager] LRU+TTL 约束，独立于下载租约）。 */
        retainForPlayback: Boolean = false,
        /** 供播放缓存明细展示的画质标签（如 Original / 1080P）。 */
        qualityLabel: String = "",
    ): Lease {
        val key = identity(client.account, gcid, streamSize, mediaId)
        while (true) {
            val pendingDiskReader = lock.withLock {
                val activeReader = diskReaders[key]
                if (activeReader != null) {
                    activeReader.closed
                } else {
                    val old = entries[key]
                    // 换号后的旧客户端不能用于新请求；旧读者会随账号切换结束。
                    if (old != null && old.client !== client) {
                        check(old.references == 0) { tr("正在切换账号，请稍后重试", "Switching account. Try again shortly", "アカウントを切り替え中です。しばらくしてから再試行してください", "계정을 전환 중입니다. 잠시 후 다시 시도해 주세요", "正在切換帳號，請稍後重試") }
                    }
                    val path = old?.store?.path ?: pathOf(key)
                    val alreadyPlaybackMarked = if (old != null) old.playbackCached else hasPlaybackMarker(path)
                    val willRetain = retained || key in paths.value || owners[key]?.isNotEmpty() == true
                    val willPlaybackCache = retainForPlayback || alreadyPlaybackMarked
                    val durable = willRetain || willPlaybackCache
                    val entry = old ?: run {
                        val store = persistentFileStore(path, key, streamSize, scope, durable = durable)
                        val handle = if (detail != null) {
                            client.fileHandle(detail, mediaId = mediaId, streamSize = streamSize, leased = leased, onRangeAttempt = ::logRangeAttempt)
                        } else {
                            PikPakFileHandle(
                                client, gcid, size, name,
                                initialFileId = fileId.takeUnless { leased }, mediaId = mediaId, parentId = parentId,
                                leased = leased, connectionBudget = concurrency, onRangeAttempt = ::logRangeAttempt,
                                streamSize = streamSize,
                            )
                        }
                        try {
                            Entry(
                                key = key,
                                client = client,
                                handle = handle,
                                cache = client.fileCache(
                                    source = handle, size = streamSize, storeKey = handle.contentKey,
                                    blockStore = store, connectionBudget = concurrency, coroutineContext = scope.coroutineContext + Dispatchers.IO,
                                    mediaBytesPerSecond = mediaBytesPerSecond,
                                ),
                                store = store,
                                retained = willRetain,
                                playbackCached = willPlaybackCache,
                                fileId = fileId,
                                fileName = name,
                                qualityLabel = qualityLabel,
                                account = client.account,
                            ).also { entries[key] = it }
                        } catch (e: Throwable) {
                            handle.close()
                            withContext(NonCancellable) { store.close() }
                            throw e
                        }
                    }
                    if (fileId.isNotBlank()) entry.fileId = fileId
                    if (name.isNotBlank()) entry.fileName = name
                    if (qualityLabel.isNotBlank()) entry.qualityLabel = qualityLabel
                    if (client.account.isNotBlank()) entry.account = client.account
                    if (old != null && mediaBytesPerSecond != null && mediaBytesPerSecond > 0L) {
                        entry.cache.updateMediaBytesPerSecond(mediaBytesPerSecond)
                    }
                    entry.references++
                    if (willRetain) {
                        entry.retained = true
                        entry.store.setDurable(true)
                    }
                    if (willPlaybackCache) {
                        entry.playbackCached = true
                        entry.store.setDurable(true)
                        touchPlaybackMarkerAccess(path, nowMillis(), entry)
                    }
                    if (retained) {
                        paths.update { it + (key to entry.store.path) }
                        owners.getOrPut(key) { mutableSetOf() }.add(owner)
                    }
                    return Lease(entry) { release(entry) }.also { lease ->
                        lease.releaseInBackground = { cleanupScope.launch { lease.release() } }
                    }
                }
            }
            pendingDiskReader.await()
        }
    }

    suspend fun progress(account: String, gcid: String, size: Long, mediaId: String? = null): Long {
        val key = identity(account, gcid, size, mediaId)
        return lock.withLock { entries[key]?.store?.heldBytes?.value }
            ?: persistedFileBytes(pathOf(key), key, size)
    }

    /**
     * 查询某档媒体在本地的已缓存字节数、覆盖比例以及指定字节窗口是否已完全命中本地缓存。
     * 优先读内存中的活跃 store；未打开时直接读轻量 `.pplay` 侧车位图（O(1)、不打开大文件），
     * 缺失 `.pplay` 时回退探测下载暂存位图。
     */
    suspend fun cachedCoverage(
        account: String,
        gcid: String,
        totalBytes: Long,
        mediaId: String? = null,
        windowStartByte: Long? = null,
        windowLengthBytes: Long = 0L,
    ): CachedRenditionInfo {
        if (gcid.isBlank() || totalBytes <= 0L) return CachedRenditionInfo(totalBytes = totalBytes.coerceAtLeast(0L))
        val key = identity(account, gcid, totalBytes, mediaId)
        val pinnedEntry = lock.withLock {
            entries[key]?.also { it.references++ }
        }
        if (pinnedEntry != null) {
            return try {
                val held = pinnedEntry.store.heldBytes.value.coerceIn(0L, totalBytes)
                val windowHit = if (windowStartByte != null && windowLengthBytes > 0L) {
                    val start = windowStartByte.coerceIn(0L, totalBytes - 1L)
                    val end = (start + windowLengthBytes - 1L).coerceAtMost(totalBytes - 1L)
                    if (start <= end) pinnedEntry.store.missing(key, listOf(start..end)).isEmpty() else false
                } else {
                    false
                }
                val ratio = (held.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                CachedRenditionInfo(
                    cachedBytes = held,
                    totalBytes = totalBytes,
                    coverageRatio = ratio,
                    windowCached = windowHit,
                )
            } finally {
                withContext(NonCancellable) { release(pinnedEntry) }
            }
        }
        val path = pathOf(key)
        return withContext(Dispatchers.IO) {
            val marker = readPlaybackMarker(path)
            if (marker != null && marker.totalBytes == totalBytes && SystemFileSystem.exists(Path(path))) {
                val held = marker.cachedBytes.coerceIn(0L, totalBytes)
                val windowHit = if (windowStartByte != null && windowLengthBytes > 0L) {
                    marker.isRangeCached(windowStartByte, windowLengthBytes)
                } else {
                    false
                }
                val ratio = (held.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                CachedRenditionInfo(
                    cachedBytes = held,
                    totalBytes = totalBytes,
                    coverageRatio = ratio,
                    windowCached = windowHit,
                )
            } else {
                val held = runCatching { persistedFileBytes(path, key, totalBytes) }.getOrDefault(0L).coerceIn(0L, totalBytes)
                val ratio = (held.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                CachedRenditionInfo(
                    cachedBytes = held,
                    totalBytes = totalBytes,
                    coverageRatio = ratio,
                    windowCached = held >= totalBytes && totalBytes > 0L,
                )
            }
        }
    }

    /**
     * 仅从本地缓存读取 `[offset, offset + length)` 字节；若任一块未命中本地缓存则返回 `null`（绝不发起网络请求）。
     * 供雪碧图提取器零流量复用已缓存的 `<= 480P` MPEG-TS 分块。
     */
    suspend fun readCachedBytes(
        account: String,
        gcid: String,
        totalBytes: Long,
        mediaId: String? = null,
        offset: Long,
        length: Int,
    ): ByteArray? {
        if (gcid.isBlank() || totalBytes <= 0L || offset < 0L || length <= 0 || offset >= totalBytes) return null
        val effectiveLen = minOf(length.toLong(), totalBytes - offset).toInt()
        if (effectiveLen <= 0) return null
        val key = identity(account, gcid, totalBytes, mediaId)
        val start = offset
        val end = offset + effectiveLen - 1L

        val pinnedEntry = lock.withLock {
            entries[key]?.also { it.references++ }
        }
        if (pinnedEntry != null) {
            return try {
                readBlocksFromStore(pinnedEntry.store, key, totalBytes, start, end, effectiveLen)
            } finally {
                withContext(NonCancellable) { release(pinnedEntry) }
            }
        }

        val path = pathOf(key)
        return withContext(Dispatchers.IO) {
            val marker = readPlaybackMarker(path) ?: return@withContext null
            if (marker.totalBytes != totalBytes || !marker.isRangeCached(start, effectiveLen.toLong())) {
                return@withContext null
            }
            // 通过同一把 lock 协调单路径唯一 PersistentFileStore 租约：
            // 优先复用并发刚打开的 Entry 或共享 DiskReader，绝不脱离管理表重复打开底层文件通道。
            var racedEntry: Entry? = null
            var reader: DiskReader? = null
            lock.withLock {
                val existingEntry = entries[key]
                if (existingEntry != null) {
                    existingEntry.references++
                    racedEntry = existingEntry
                } else {
                    val existingReader = diskReaders[key]
                    if (existingReader != null) {
                        existingReader.references++
                        reader = existingReader
                    } else if (SystemFileSystem.exists(Path(path))) {
                        val store = persistentFileStore(path, key, totalBytes, cleanupScope, durable = false)
                        val created = DiskReader(key = key, path = path, store = store, references = 1)
                        diskReaders[key] = created
                        reader = created
                    }
                }
            }
            racedEntry?.let { entry ->
                return@withContext try {
                    readBlocksFromStore(entry.store, key, totalBytes, start, end, effectiveLen)
                } finally {
                    withContext(NonCancellable) { release(entry) }
                }
            }
            val pinnedReader = reader ?: return@withContext null
            try {
                readBlocksFromStore(pinnedReader.store, key, totalBytes, start, end, effectiveLen)
            } finally {
                withContext(NonCancellable) { releaseDiskReader(pinnedReader) }
            }
        }
    }

    private suspend fun releaseDiskReader(reader: DiskReader) = lock.withLock {
        if (diskReaders[reader.key] !== reader) return@withLock
        reader.references--
        if (reader.references != 0) return@withLock
        diskReaders.remove(reader.key)
        try {
            runCatching { reader.store.close() }
        } finally {
            reader.closed.complete(Unit)
        }
    }

    private suspend fun readBlocksFromStore(
        store: PersistentFileStore,
        key: String,
        totalBytes: Long,
        start: Long,
        end: Long,
        effectiveLen: Int,
    ): ByteArray? {
        if (store.missing(key, listOf(start..end)).isNotEmpty()) return null
        val out = ByteArray(effectiveLen)
        val firstBlock = (start / BLOCK_BYTES).toInt()
        val lastBlock = (end / BLOCK_BYTES).toInt()
        var written = 0
        for (blockIdx in firstBlock..lastBlock) {
            val blockOffset = blockIdx.toLong() * BLOCK_BYTES
            val blockLen = minOf(BLOCK_BYTES, totalBytes - blockOffset).toInt()
            val blockBytes = store.read(key, blockOffset, blockLen) ?: return null
            val sliceStart = if (blockIdx == firstBlock) (start - blockOffset).toInt() else 0
            val sliceEnd = if (blockIdx == lastBlock) (end - blockOffset + 1L).toInt() else blockLen
            val copyLen = sliceEnd - sliceStart
            blockBytes.copyInto(out, destinationOffset = written, startIndex = sliceStart, endIndex = sliceEnd)
            written += copyLen
        }
        return if (written == effectiveLen) out else null
    }

    data class Snapshot(val heldBytes: Long, val deliveredBytes: Long)

    /** 按 [identity] 分组。 */
    suspend fun progressSnapshot(): Map<String, Snapshot> = lock.withLock {
        entries.values.associate { it.key to Snapshot(it.store.heldBytes.value, it.cache.deliveredBytes) }
    }

    suspend fun complete(lease: Lease, owner: String) = lock.withLock {
        unpin(lease.entry.key, owner)
        val stillRetained = owners[lease.entry.key]?.isNotEmpty() == true
        lease.entry.retained = stillRetained
        if (!stillRetained) {
            // 文件已完整导出至本机下载目录：摘除同内容的持久化播放缓存标记，避免 cache/files 中残存多 GB 重复副本。
            lease.entry.playbackCached = false
            deletePlaybackMarkerSafe(lease.entry.store.path)
        }
        lease.entry.store.setDurable(stillRetained)
    }

    /** 正在播放的缓存延迟到最后一个读者释放再删。 */
    suspend fun discard(account: String, gcid: String, size: Long, owner: String, mediaId: String? = null) {
        discardKey(identity(account, gcid, size, mediaId), owner)
    }

    /** 释放指定 [owner] 持有的全部缓存固定引用，并按与 [discard] 一致的规则清理无主临时缓存。 */
    suspend fun discardByOwner(owner: String) {
        if (owner.isBlank()) return
        val ownedKeys = lock.withLock {
            owners.entries.mapNotNull { (key, set) -> key.takeIf { owner in set } }
        }
        for (key in ownedKeys) {
            discardKey(key, owner)
        }
    }

    private suspend fun discardKey(key: String, owner: String) {
        while (true) {
            val pendingReader = lock.withLock {
                unpin(key, owner)
                if (owners[key]?.isNotEmpty() == true) return
                entries[key]?.let {
                    it.retained = false
                    it.store.setDurable(it.playbackCached)
                    return
                }
                val activeReader = diskReaders[key]
                if (activeReader != null) {
                    activeReader.closed
                } else {
                    val path = pathOf(key)
                    if (!hasPlaybackMarker(path)) {
                        deleteCacheFiles(path)
                    }
                    return
                }
            }
            pendingReader.await()
        }
    }

    private fun unpin(key: String, owner: String) {
        owners[key]?.remove(owner)
        if (owners[key].isNullOrEmpty()) {
            owners.remove(key)
            paths.update { it - key }
        }
    }

    /**
     * 清理未归属任何在途下载且无持久化播放缓存标记的孤儿暂存文件。
     */
    suspend fun prune() {
        val candidates = ownedCacheFiles(target("probe"))
        var deletedCount = 0
        lock.withLock {
            val retained = paths.value.values.toSet() + activePathsLocked()
            for (path in candidates) {
                if (path !in retained && !hasPlaybackMarker(path)) {
                    try {
                        deleteCacheFiles(path)
                        deletedCount++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val safeId = path.substringAfterLast('/').substringAfterLast('\\').take(12)
                        PikoLog.w(TAG, "Failed to prune orphan sparse cache entry: cache=$safeId", e)
                    }
                }
            }
        }
        if (deletedCount > 0) {
            PikoLog.i(TAG, "Pruned $deletedCount orphan sparse cache entries")
        }
    }

    private data class PlaybackDiskEntry(
        val path: String,
        val diskBytes: Long,
        val lastAccessEpochMillis: Long,
    )

    /** 扫描所有可清理的持久化播放缓存条目（严格排除活跃下载租约）。 */
    suspend fun scanPlaybackCache(): CacheCategoryStats = withContext(Dispatchers.IO) {
        val candidates = runCatching { ownedCacheFiles(target("probe")) }.getOrDefault(emptyList())
        val (downloadPaths, livePlaybackEntries) = lock.withLock {
            val pinned = paths.value.values.toSet()
            val live = entries.values
                .filter { it.playbackCached && !it.retained && it.store.path !in pinned }
                .associate { it.store.path to it.store.heldBytes.value }
            pinned to live
        }
        var totalBytes = 0L
        var fileCount = 0
        val seen = HashSet<String>()
        for (path in candidates) {
            if (path in downloadPaths) continue
            val liveHeld = livePlaybackEntries[path]
            val marker = readPlaybackMarker(path)
            if (liveHeld != null) {
                seen += path
                totalBytes += estimateEntryDiskBytes(path, liveHeld)
                fileCount++
            } else if (marker != null) {
                seen += path
                totalBytes += estimateEntryDiskBytes(path, marker.cachedBytes)
                fileCount++
            }
        }
        for ((path, liveHeld) in livePlaybackEntries) {
            if (path !in seen && liveHeld > 0L) {
                totalBytes += estimateEntryDiskBytes(path, liveHeld)
                fileCount++
            }
        }
        CacheCategoryStats(
            category = CacheCategory.PLAYBACK,
            bytes = totalBytes,
            fileCount = fileCount,
            clearable = true,
        )
    }

    /**
     * 安全清理所有未在使用中且不属于在途下载的持久化播放缓存，返回释放的字节数。
     * 正在下载的租约与当前播放中的流绝不删除。
     */
    suspend fun clearPlaybackCache(): Long = withContext(Dispatchers.IO) {
        val candidates = runCatching { ownedCacheFiles(target("probe")) }.getOrDefault(emptyList())
        var freed = 0L
        lock.withLock {
            val downloadPaths = paths.value.values.toSet()
            val activePaths = activePathsLocked()
            // 若某活跃播放流已开启持久化标记，先取消其关闭时落盘，避免清完后立即又写回
            for (entry in entries.values) {
                if (entry.playbackCached && !entry.retained) {
                    entry.playbackCached = false
                    entry.store.setDurable(false)
                    deletePlaybackMarkerSafe(entry.store.path)
                }
            }
            for (path in candidates) {
                val marker = readPlaybackMarker(path) ?: continue
                if (path in downloadPaths) {
                    // 同时被活跃下载占用：仅摘除播放缓存标记，不动下载暂存！
                    deletePlaybackMarkerSafe(path)
                    continue
                }
                if (path in activePaths) continue
                val size = estimateEntryDiskBytes(path, marker.cachedBytes)
                runCatching {
                    deleteCacheFiles(path)
                    deletePlaybackMarkerSafe(path)
                    freed += size
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    PikoLog.w(TAG, "Failed to clear playback cache entry", err)
                }
            }
        }
        freed
    }

    /**
     * 按 TTL（[maxAgeMillis] > 0）与容量上限（[targetMaxBytes] >= 0）以 LRU 顺序淘汰持久化播放缓存。
     * 严格跳过活跃下载租约与当前打开的播放流。
     */
    suspend fun prunePlaybackCache(
        maxAgeMillis: Long,
        targetMaxBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        val candidates = runCatching { ownedCacheFiles(target("probe")) }.getOrDefault(emptyList())
        val now = nowMillis()
        var freed = 0L
        lock.withLock {
            val downloadPaths = paths.value.values.toSet()
            val activePaths = activePathsLocked()
            val evictable = ArrayList<PlaybackDiskEntry>()
            var protectedActiveBytes = 0L

            for (path in candidates) {
                if (path in downloadPaths) continue
                val marker = readPlaybackMarker(path) ?: continue
                val size = estimateEntryDiskBytes(path, marker.cachedBytes)
                if (path in activePaths) {
                    protectedActiveBytes += size
                    continue
                }
                evictable += PlaybackDiskEntry(
                    path = path,
                    diskBytes = size,
                    lastAccessEpochMillis = marker.lastAccessEpochMillis,
                )
            }

            // 1. TTL 过期清理
            val remaining = ArrayList<PlaybackDiskEntry>(evictable.size)
            for (item in evictable) {
                val expired = maxAgeMillis > 0L && (now - item.lastAccessEpochMillis) > maxAgeMillis
                if (expired) {
                    val deleted = runCatching {
                        deleteCacheFiles(item.path)
                        deletePlaybackMarkerSafe(item.path)
                        true
                    }.getOrDefault(false)
                    if (deleted) freed += item.diskBytes else remaining += item
                } else {
                    remaining += item
                }
            }

            // 2. LRU 容量上限淘汰
            if (targetMaxBytes >= 0L) {
                var currentTotal = protectedActiveBytes + remaining.sumOf { it.diskBytes }
                if (currentTotal > targetMaxBytes) {
                    for (item in remaining.sortedBy { it.lastAccessEpochMillis }) {
                        if (currentTotal <= targetMaxBytes) break
                        val deleted = runCatching {
                            deleteCacheFiles(item.path)
                            deletePlaybackMarkerSafe(item.path)
                            true
                        }.getOrDefault(false)
                        if (deleted) {
                            currentTotal -= item.diskBytes
                            freed += item.diskBytes
                        }
                    }
                }
            }
        }
        freed
    }

    /**
     * 列出持久化播放缓存的视频明细（按最近访问时间降序），严格排除在途下载租约。
     * 若指定 [account]，仅返回该账号或历史无账号元数据的条目。
     */
    suspend fun listPlaybackCacheEntries(account: String? = null): List<PlaybackCacheItem> = withContext(Dispatchers.IO) {
        val candidates = runCatching { ownedCacheFiles(target("probe")) }.getOrDefault(emptyList())
        val now = nowMillis()
        val (downloadPaths, activePaths, liveByPath) = lock.withLock {
            val pinned = paths.value.values.toSet()
            val active = activePathsLocked()
            val live = entries.values
                .filter { it.playbackCached && !it.retained && it.store.path !in pinned }
                .associate { entry ->
                    entry.store.path to PlaybackCacheItem(
                        cachePath = entry.store.path,
                        fileId = entry.fileId,
                        fileName = entry.fileName,
                        qualityLabel = entry.qualityLabel,
                        account = entry.account,
                        cachedBytes = entry.store.heldBytes.value.coerceIn(0L, entry.store.size.coerceAtLeast(0L)),
                        totalBytes = entry.store.size.coerceAtLeast(0L),
                        diskBytes = estimateEntryDiskBytes(entry.store.path, entry.store.heldBytes.value),
                        lastAccessEpochMillis = now,
                        isActive = true,
                    )
                }
            Triple(pinned, active, live)
        }
        val items = ArrayList<PlaybackCacheItem>()
        val seen = HashSet<String>()
        for (path in candidates) {
            if (path in downloadPaths) continue
            val live = liveByPath[path]
            val marker = readPlaybackMarker(path)
            val item = when {
                live != null -> live.copy(
                    fileId = live.fileId.ifBlank { marker?.fileId.orEmpty() },
                    fileName = live.fileName.ifBlank { marker?.fileName.orEmpty() },
                    qualityLabel = live.qualityLabel.ifBlank { marker?.qualityLabel.orEmpty() },
                    account = live.account.ifBlank { marker?.account.orEmpty() },
                )
                marker != null -> PlaybackCacheItem(
                    cachePath = path,
                    fileId = marker.fileId,
                    fileName = marker.fileName,
                    qualityLabel = marker.qualityLabel,
                    account = marker.account,
                    cachedBytes = marker.cachedBytes.coerceIn(0L, marker.totalBytes.coerceAtLeast(0L)),
                    totalBytes = marker.totalBytes.coerceAtLeast(0L),
                    diskBytes = estimateEntryDiskBytes(path, marker.cachedBytes),
                    lastAccessEpochMillis = marker.lastAccessEpochMillis,
                    isActive = path in activePaths,
                )
                else -> null
            } ?: continue
            seen += path
            if (account.isNullOrBlank() || item.account.isBlank() || item.account == account) {
                items += item
            }
        }
        for ((path, live) in liveByPath) {
            if (path !in seen && live.cachedBytes > 0L) {
                if (account.isNullOrBlank() || live.account.isBlank() || live.account == account) {
                    items += live
                }
            }
        }
        items.sortedByDescending { it.lastAccessEpochMillis }
    }

    /**
     * 将反查到的真实文件元数据回写升级至 `.pplay` v2 侧车，避免后续重复扫描目录树。
     */
    suspend fun backfillPlaybackMetadata(
        cachePath: String,
        fileId: String,
        fileName: String,
        qualityLabel: String,
        account: String,
    ): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = readPlaybackMarker(cachePath) ?: return@withLock
            val nextFileId = existing.fileId.ifBlank { fileId }
            val nextFileName = existing.fileName.ifBlank { fileName }
            val nextQuality = existing.qualityLabel.ifBlank { qualityLabel }
            val nextAccount = existing.account.ifBlank { account }
            if (
                nextFileId == existing.fileId &&
                nextFileName == existing.fileName &&
                nextQuality == existing.qualityLabel &&
                nextAccount == existing.account
            ) {
                return@withLock
            }
            runCatching {
                writePlaybackMarker(
                    cachePath,
                    existing.copy(
                        fileId = nextFileId,
                        fileName = nextFileName,
                        qualityLabel = nextQuality,
                        account = nextAccount,
                    ),
                )
            }
        }
    }

    /**
     * 安全删除单条持久化播放缓存，返回释放的字节数。
     * 若条目正被下载任务或活跃播放流占用则拒绝删除（下载租约仅摘除 `.pplay` 标记）。
     */
    suspend fun deletePlaybackCacheEntry(path: String): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val downloadPaths = paths.value.values.toSet()
            val activePaths = activePathsLocked()
            if (path in activePaths) return@withLock 0L
            val marker = readPlaybackMarker(path)
            if (path in downloadPaths) {
                deletePlaybackMarkerSafe(path)
                return@withLock 0L
            }
            val size = estimateEntryDiskBytes(path, marker?.cachedBytes ?: 0L)
            runCatching {
                deleteCacheFiles(path)
                deletePlaybackMarkerSafe(path)
                size
            }.onFailure { err ->
                if (err is CancellationException) throw err
                PikoLog.w(TAG, "Failed to delete single playback cache entry", err)
            }.getOrDefault(0L)
        }
    }

    /** 构造接入 [PikoCacheManager] 的 [CacheCategory.PLAYBACK] 后端适配器。 */
    fun asCacheBackend(): CacheCategoryBackend = object : CacheCategoryBackend {
        override val category: CacheCategory = CacheCategory.PLAYBACK
        override val clearable: Boolean = true
        override suspend fun scan(): CacheCategoryStats = scanPlaybackCache()
        override suspend fun clear(): Long = clearPlaybackCache()
        override suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long =
            prunePlaybackCache(maxAgeMillis = maxAgeMillis, targetMaxBytes = targetMaxBytes)
        override suspend fun listPlaybackEntries(account: String?): List<PlaybackCacheItem> =
            listPlaybackCacheEntries(account)
        override suspend fun deletePlaybackEntry(path: String): Long =
            deletePlaybackCacheEntry(path)
        override suspend fun backfillPlaybackMetadata(
            cachePath: String,
            fileId: String,
            fileName: String,
            qualityLabel: String,
            account: String,
        ) = this@PikoFileCachePool.backfillPlaybackMetadata(cachePath, fileId, fileName, qualityLabel, account)
    }

    private suspend fun release(entry: Entry) = lock.withLock {
        if (entries[entry.key] !== entry) return@withLock
        entry.references--
        if (entry.references != 0) return@withLock
        entry.cache.close()
        entry.handle.close()
        try {
            closeOrDeleteEntryStore(entry)
        } finally {
            if (entries[entry.key] === entry) entries.remove(entry.key)
        }
    }

    private suspend fun closeOrDeleteEntryStore(entry: Entry) {
        when {
            entry.retained -> {
                entry.store.setDurable(true)
                val flushed = runCatching { entry.store.flush() }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "Failed to flush retained cache store before close", err)
                    }
                    .isSuccess
                if (entry.playbackCached && entry.store.heldBytes.value > 0L) {
                    if (flushed) {
                        writePlaybackMarkerSafe(entry, nowMillis())
                    } else {
                        deletePlaybackMarkerSafe(entry.store.path)
                    }
                }
                entry.store.close()
            }
            entry.playbackCached && entry.store.heldBytes.value > 0L -> {
                entry.store.setDurable(true)
                val flushed = runCatching { entry.store.flush() }
                    .onFailure { err ->
                        if (err is CancellationException) {
                            runCatching { entry.store.close() }
                            throw err
                        }
                        PikoLog.w(TAG, "Failed to flush playback cache store; skipping .pplay marker", err)
                    }
                    .isSuccess
                if (flushed) {
                    try {
                        writePlaybackMarkerSafe(entry, nowMillis())
                    } finally {
                        entry.store.close()
                    }
                } else {
                    deletePlaybackMarkerSafe(entry.store.path)
                    runCatching { entry.store.close() }
                        .logFailure(TAG, "Failed to close faulted playback cache store")
                }
            }
            else -> {
                entry.store.delete()
                deletePlaybackMarkerSafe(entry.store.path)
            }
        }
    }

    private fun estimateEntryDiskBytes(path: String, heldBytes: Long): Long {
        val markerSize = fileSizeOrZero("$path$PLAYBACK_MARKER_SUFFIX")
        return if (path.endsWith(".pack")) {
            val packSize = fileSizeOrZero(path)
            (if (packSize > 0L) packSize else heldBytes.coerceAtLeast(0L)) + markerSize
        } else {
            val dataSize = fileSizeOrZero(path)
            val bitmapSize = fileSizeOrZero("$path.blocks") + fileSizeOrZero("$path.ios-blocks")
            val effectiveData = if (heldBytes > 0L && dataSize > 0L) minOf(heldBytes, dataSize) else heldBytes.coerceAtLeast(0L)
            effectiveData + bitmapSize + markerSize
        }
    }

    private fun fileSizeOrZero(path: String): Long =
        runCatching { SystemFileSystem.metadataOrNull(Path(path))?.size?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)

    private fun hasPlaybackMarker(path: String): Boolean =
        runCatching { SystemFileSystem.exists(Path("$path$PLAYBACK_MARKER_SUFFIX")) }.getOrDefault(false)

    private suspend fun writePlaybackMarkerSafe(entry: Entry, accessTimeMs: Long) {
        val store = entry.store
        val key = entry.key
        runCatching {
            val existing = readPlaybackMarker(store.path)
            val total = store.size.coerceAtLeast(0L)
            val held = store.heldBytes.value.coerceIn(0L, total)
            val blockCount = if (total == 0L) 0 else ((total + BLOCK_BYTES - 1L) / BLOCK_BYTES).toInt()
            val packedLen = (blockCount + 7) / 8
            val packed = ByteArray(packedLen)
            if (blockCount > 0 && held > 0L) {
                val missingRanges = store.missing(key, listOf(0L until total))
                // 默认全部置 1，再把缺失区间对应的块清 0
                for (i in 0 until blockCount) {
                    packed[i ushr 3] = (packed[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
                }
                for (range in missingRanges) {
                    if (range.isEmpty()) continue
                    val fromBlock = (range.first.coerceAtLeast(0L) / BLOCK_BYTES).toInt()
                    val toBlock = (range.last.coerceAtMost(total - 1L) / BLOCK_BYTES).toInt()
                    for (b in fromBlock..toBlock) {
                        if (b in 0 until blockCount) {
                            packed[b ushr 3] = (packed[b ushr 3].toInt() and (1 shl (b and 7)).inv()).toByte()
                        }
                    }
                }
            }
            writePlaybackMarker(
                path = store.path,
                marker = PlaybackCacheMarker(
                    lastAccessEpochMillis = accessTimeMs,
                    totalBytes = total,
                    cachedBytes = held,
                    blockCount = blockCount,
                    packedBitmap = packed,
                    fileId = entry.fileId.ifBlank { existing?.fileId.orEmpty() },
                    fileName = entry.fileName.ifBlank { existing?.fileName.orEmpty() },
                    qualityLabel = entry.qualityLabel.ifBlank { existing?.qualityLabel.orEmpty() },
                    account = entry.account.ifBlank { existing?.account.orEmpty() },
                ),
            )
        }.onFailure { err ->
            if (err is CancellationException) throw err
            PikoLog.w(TAG, "Failed to write playback cache marker", err)
        }
    }

    private fun touchPlaybackMarkerAccess(path: String, accessTimeMs: Long, entry: Entry) {
        val existing = readPlaybackMarker(path) ?: return
        val needsMetadata = (existing.fileId.isBlank() && entry.fileId.isNotBlank()) ||
            (existing.fileName.isBlank() && entry.fileName.isNotBlank()) ||
            (existing.qualityLabel.isBlank() && entry.qualityLabel.isNotBlank()) ||
            (existing.account.isBlank() && entry.account.isNotBlank())
        if (!needsMetadata && accessTimeMs - existing.lastAccessEpochMillis < TOUCH_THROTTLE_MS) return
        runCatching {
            writePlaybackMarker(
                path,
                existing.copy(
                    lastAccessEpochMillis = accessTimeMs,
                    fileId = entry.fileId.ifBlank { existing.fileId },
                    fileName = entry.fileName.ifBlank { existing.fileName },
                    qualityLabel = entry.qualityLabel.ifBlank { existing.qualityLabel },
                    account = entry.account.ifBlank { existing.account },
                ),
            )
        }
    }

    private fun deletePlaybackMarkerSafe(path: String) {
        runCatching {
            SystemFileSystem.delete(Path("$path$PLAYBACK_MARKER_SUFFIX"), mustExist = false)
            SystemFileSystem.delete(Path("$path$PLAYBACK_MARKER_SUFFIX.tmp"), mustExist = false)
        }
    }

    private data class PlaybackCacheMarker(
        val lastAccessEpochMillis: Long,
        val totalBytes: Long,
        val cachedBytes: Long,
        val blockCount: Int,
        val packedBitmap: ByteArray,
        val fileId: String = "",
        val fileName: String = "",
        val qualityLabel: String = "",
        val account: String = "",
    ) {
        fun isRangeCached(startByte: Long, lengthBytes: Long): Boolean {
            if (totalBytes <= 0L || blockCount <= 0 || lengthBytes <= 0L) return false
            val start = startByte.coerceIn(0L, totalBytes - 1L)
            val end = (start + lengthBytes - 1L).coerceAtMost(totalBytes - 1L)
            if (start > end) return false
            val firstBlock = (start / BLOCK_BYTES).toInt()
            val lastBlock = (end / BLOCK_BYTES).toInt()
            if (firstBlock < 0 || lastBlock >= blockCount) return false
            for (b in firstBlock..lastBlock) {
                val byteIdx = b ushr 3
                if (byteIdx >= packedBitmap.size) return false
                if ((packedBitmap[byteIdx].toInt() and (1 shl (b and 7))) == 0) return false
            }
            return true
        }
    }

    private fun writePlaybackMarker(path: String, marker: PlaybackCacheMarker) {
        val targetPath = Path("$path$PLAYBACK_MARKER_SUFFIX")
        val tempPath = Path("$path$PLAYBACK_MARKER_SUFFIX.tmp")
        targetPath.parent?.let { SystemFileSystem.createDirectories(it) }
        try {
            SystemFileSystem.sink(tempPath).buffered().use { sink ->
                sink.writeInt(PLAYBACK_MARKER_MAGIC)
                sink.writeInt(PLAYBACK_MARKER_VERSION_V2)
                sink.writeLong(marker.lastAccessEpochMillis)
                sink.writeLong(marker.totalBytes)
                sink.writeLong(marker.cachedBytes)
                sink.writeInt(marker.blockCount)
                sink.writeInt(marker.packedBitmap.size)
                sink.write(marker.packedBitmap)
                val idBytes = marker.fileId.encodeToByteArray()
                val nameBytes = marker.fileName.encodeToByteArray()
                val qualityBytes = marker.qualityLabel.encodeToByteArray()
                val accountBytes = marker.account.encodeToByteArray()
                sink.writeInt(idBytes.size)
                sink.write(idBytes)
                sink.writeInt(nameBytes.size)
                sink.write(nameBytes)
                sink.writeInt(qualityBytes.size)
                sink.write(qualityBytes)
                sink.writeInt(accountBytes.size)
                sink.write(accountBytes)
            }
            SystemFileSystem.atomicMove(tempPath, targetPath)
        } finally {
            runCatching { SystemFileSystem.delete(tempPath, mustExist = false) }
        }
    }

    private fun readPlaybackMarker(path: String): PlaybackCacheMarker? = runCatching {
        val markerPath = Path("$path$PLAYBACK_MARKER_SUFFIX")
        if (!SystemFileSystem.exists(markerPath)) return@runCatching null
        SystemFileSystem.source(markerPath).buffered().use { source ->
            val magic = source.readInt()
            if (magic != PLAYBACK_MARKER_MAGIC) return@use null
            val version = source.readInt()
            if (version != PLAYBACK_MARKER_VERSION && version != PLAYBACK_MARKER_VERSION_V2) return@use null
            val lastAccess = source.readLong()
            val total = source.readLong()
            val cached = source.readLong()
            val blockCount = source.readInt()
            val packedLen = source.readInt()
            if (total < 0L || cached < 0L || blockCount < 0 || packedLen < 0 || packedLen > MAX_BITMAP_BYTES) return@use null
            val packed = source.readByteArray(packedLen)
            var fileId = ""
            var fileName = ""
            var qualityLabel = ""
            var account = ""
            if (version >= PLAYBACK_MARKER_VERSION_V2) {
                fun readMetaString(): String? {
                    val len = source.readInt()
                    if (len < 0 || len > MAX_METADATA_STRING_BYTES) return null
                    return if (len == 0) "" else source.readByteArray(len).decodeToString()
                }
                fileId = readMetaString() ?: return@use null
                fileName = readMetaString() ?: return@use null
                qualityLabel = readMetaString() ?: return@use null
                account = readMetaString() ?: return@use null
            }
            PlaybackCacheMarker(
                lastAccessEpochMillis = lastAccess,
                totalBytes = total,
                cachedBytes = cached,
                blockCount = blockCount,
                packedBitmap = packed,
                fileId = fileId,
                fileName = fileName,
                qualityLabel = qualityLabel,
                account = account,
            )
        }
    }.getOrNull()

    companion object {
        private const val TAG = "FileCachePool"
        private const val BLOCK_BYTES = 256L * 1024L
        private const val PLAYBACK_MARKER_SUFFIX = ".pplay"
        private const val PLAYBACK_MARKER_MAGIC = 0x50504C59 // "PPLY"
        private const val PLAYBACK_MARKER_VERSION = 1
        private const val PLAYBACK_MARKER_VERSION_V2 = 2
        private const val MAX_BITMAP_BYTES = 8 * 1024 * 1024
        private const val MAX_METADATA_STRING_BYTES = 64 * 1024
        private const val TOUCH_THROTTLE_MS = 30_000L

        /**
         * 暂存的身份，暂存文件名由它推出。[size] 是暂存里那一档的长度；[mediaId] 为 null 是原画，
         * 末段写 origin，与没有转码档之前存下的暂存同名，旧的续传照常认。
         */
        fun identity(account: String, gcid: String, size: Long, mediaId: String? = null): String =
            "$account\u0000${gcid.uppercase()}\u0000$size\u0000${mediaId ?: "origin"}"
    }
}
