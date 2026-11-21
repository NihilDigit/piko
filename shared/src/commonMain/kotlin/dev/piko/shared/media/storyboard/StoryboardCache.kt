package dev.piko.shared.media.storyboard

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.cache.CacheCategory
import dev.piko.shared.media.cache.CacheCategoryBackend
import dev.piko.shared.media.cache.CacheCategoryStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.time.Clock

/**
 * 雪碧图磁盘缓存（按内容 `gcid` 索引，单视频打包为紧凑 `.sb2` 文件，384×216 清晰缩略图）。
 * 接入 [dev.piko.shared.media.cache.PikoCacheManager] 的 [CacheCategory.STORYBOARD] 统一管理。
 */
interface StoryboardCache {
    suspend fun read(gcid: String): StoryboardBundle?
    suspend fun write(bundle: StoryboardBundle)
    suspend fun delete(gcid: String)
    fun asCacheBackend(): CacheCategoryBackend
}

/**
 * 紧凑 `.sb2` 单文件二进制编解码：
 * - Header: `magic("PKSB")` + `version(2)` + `gcidUtf8` + `durationMs` + `width` + `height` + `completedByte` + `lastAccessMs` + `frameCount`
 * - Index table: `[slotIndex: Int, ptsMs: Long, length: Int] * frameCount`
 * - Payload: 连续紧凑 JPEG 字节块
 */
object StoryboardBundleCodec {
    private const val MAGIC = 0x504B5342 // "PKSB"
    private const val VERSION = 2
    private const val MAX_FRAMES = 512
    private const val MAX_FRAME_BYTES = 512 * 1024
    private const val MAX_GCID_BYTES = 256

    fun encode(bundle: StoryboardBundle, sink: Sink) {
        val gcidBytes = bundle.gcid.uppercase().encodeToByteArray()
        require(gcidBytes.size <= MAX_GCID_BYTES) { "GCID too long: ${gcidBytes.size}" }
        val sortedFrames = bundle.frames
            .filter { it.jpegBytes.isNotEmpty() && it.jpegBytes.size <= MAX_FRAME_BYTES }
            .sortedBy { it.ptsMs }
            .take(MAX_FRAMES)

        sink.writeInt(MAGIC)
        sink.writeInt(VERSION)
        sink.writeInt(gcidBytes.size)
        sink.write(gcidBytes)
        sink.writeLong(bundle.durationMillis.coerceAtLeast(0L))
        sink.writeInt(bundle.width.coerceAtLeast(0))
        sink.writeInt(bundle.height.coerceAtLeast(0))
        sink.writeByte(if (bundle.completed) 1 else 0)
        sink.writeLong(bundle.lastAccessEpochMillis)
        sink.writeInt(sortedFrames.size)
        for (frame in sortedFrames) {
            sink.writeInt(frame.slotIndex)
            sink.writeLong(frame.ptsMs)
            sink.writeInt(frame.jpegBytes.size)
        }
        for (frame in sortedFrames) {
            sink.write(frame.jpegBytes)
        }
    }

    fun decode(source: Source): StoryboardBundle? = runCatching {
        if (source.readInt() != MAGIC) return@runCatching null
        if (source.readInt() != VERSION) return@runCatching null
        val gcidLen = source.readInt()
        if (gcidLen !in 1..MAX_GCID_BYTES) return@runCatching null
        val gcid = source.readString(gcidLen.toLong())
        val durationMs = source.readLong()
        val width = source.readInt()
        val height = source.readInt()
        val completed = source.readByte().toInt() != 0
        val lastAccessMs = source.readLong()
        val frameCount = source.readInt()
        if (durationMs < 0L || width < 0 || height < 0 || frameCount !in 0..MAX_FRAMES) return@runCatching null
        val slots = IntArray(frameCount)
        val pts = LongArray(frameCount)
        val lengths = IntArray(frameCount)
        for (i in 0 until frameCount) {
            slots[i] = source.readInt()
            pts[i] = source.readLong()
            val len = source.readInt()
            if (len !in 1..MAX_FRAME_BYTES) return@runCatching null
            lengths[i] = len
        }
        val frames = ArrayList<StoryboardFrame>(frameCount)
        for (i in 0 until frameCount) {
            val bytes = source.readByteArray(lengths[i])
            if (bytes.size != lengths[i]) return@runCatching null
            frames += StoryboardFrame(slotIndex = slots[i], ptsMs = pts[i], jpegBytes = bytes)
        }
        StoryboardBundle(
            gcid = gcid,
            durationMillis = durationMs,
            width = width,
            height = height,
            completed = completed,
            lastAccessEpochMillis = lastAccessMs,
            frames = frames,
        )
    }.getOrNull()

    /** 快速仅读头部 `lastAccessEpochMillis`，供 LRU/TTL 淘汰避免把整包 JPEG 读入内存。 */
    fun readLastAccessMillis(source: Source): Long? = runCatching {
        if (source.readInt() != MAGIC) return@runCatching null
        if (source.readInt() != VERSION) return@runCatching null
        val gcidLen = source.readInt()
        if (gcidLen !in 1..MAX_GCID_BYTES) return@runCatching null
        source.skip(gcidLen.toLong())
        source.readLong() // durationMs
        source.readInt() // width
        source.readInt() // height
        source.readByte() // completed
        source.readLong() // lastAccessMs
    }.getOrNull()

    fun encodeToByteArray(bundle: StoryboardBundle): ByteArray {
        val buf = Buffer()
        encode(bundle, buf)
        return buf.readByteArray()
    }

    fun decodeFromByteArray(bytes: ByteArray): StoryboardBundle? {
        val buf = Buffer()
        buf.write(bytes)
        return decode(buf)
    }
}

/**
 * 纯 `commonMain` 磁盘雪碧图缓存（基于 `kotlinx.io.files.SystemFileSystem`），Windows / macOS / Linux / Android / iOS 共用。
 */
class FileStoryboardCache(
    directoryPath: String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : StoryboardCache {
    private val directory = Path(directoryPath)
    private val lock = Mutex()

    override suspend fun read(gcid: String): StoryboardBundle? = withContext(Dispatchers.IO) {
        val safeName = safeFileName(gcid) ?: return@withContext null
        val file = Path(directory, "$safeName$EXTENSION")
        lock.withLock {
            if (!SystemFileSystem.exists(file)) return@withLock null
            val bundle = runCatching {
                SystemFileSystem.source(file).buffered().use { StoryboardBundleCodec.decode(it) }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                PikoLog.w(TAG, "Failed to read storyboard bundle", err)
            }.getOrNull() ?: return@withLock null

            // 若上次访问时间超过节流窗口则在同一把锁内刷新 lastAccessEpochMillis，防止并发写盘被旧快照覆盖
            val now = nowMillis()
            if (now - bundle.lastAccessEpochMillis >= TOUCH_THROTTLE_MS) {
                val touched = bundle.copy(lastAccessEpochMillis = now)
                writeLocked(safeName, touched)
                touched
            } else {
                bundle
            }
        }
    }

    override suspend fun write(bundle: StoryboardBundle): Unit = withContext(Dispatchers.IO) {
        val safeName = safeFileName(bundle.gcid) ?: return@withContext
        val normalized = if (bundle.lastAccessEpochMillis > 0L) {
            bundle
        } else {
            bundle.copy(lastAccessEpochMillis = nowMillis())
        }
        lock.withLock {
            writeLocked(safeName, normalized)
        }
    }

    private fun writeLocked(safeName: String, bundle: StoryboardBundle) {
        val target = Path(directory, "$safeName$EXTENSION")
        val temp = Path(directory, "$safeName.${Random.nextLong().toULong()}.tmp")
        runCatching {
            SystemFileSystem.createDirectories(directory)
            SystemFileSystem.sink(temp).buffered().use { sink ->
                StoryboardBundleCodec.encode(bundle, sink)
            }
            SystemFileSystem.atomicMove(temp, target)
        }.onFailure { err ->
            if (err is CancellationException) throw err
            PikoLog.w(TAG, "Failed to write storyboard bundle", err)
        }
        runCatching { SystemFileSystem.delete(temp, mustExist = false) }
    }

    override suspend fun delete(gcid: String): Unit = withContext(Dispatchers.IO) {
        val safeName = safeFileName(gcid) ?: return@withContext
        lock.withLock {
            runCatching {
                SystemFileSystem.delete(Path(directory, "$safeName$EXTENSION"), mustExist = false)
            }
        }
    }

    suspend fun scanStats(): CacheCategoryStats = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!SystemFileSystem.exists(directory)) {
                return@withLock CacheCategoryStats(CacheCategory.STORYBOARD, 0L, 0, clearable = true)
            }
            var totalBytes = 0L
            var count = 0
            for (path in safeList(directory)) {
                if (!path.name.endsWith(EXTENSION) && !path.name.endsWith(LEGACY_EXTENSION)) continue
                val meta = SystemFileSystem.metadataOrNull(path) ?: continue
                if (!meta.isRegularFile) continue
                totalBytes += meta.size.coerceAtLeast(0L)
                count++
            }
            CacheCategoryStats(CacheCategory.STORYBOARD, totalBytes, count, clearable = true)
        }
    }

    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!SystemFileSystem.exists(directory)) return@withLock 0L
            var freed = 0L
            for (path in safeList(directory)) {
                if (!path.name.endsWith(EXTENSION) && !path.name.endsWith(LEGACY_EXTENSION) && !path.name.endsWith(".tmp")) continue
                val size = SystemFileSystem.metadataOrNull(path)?.size?.coerceAtLeast(0L) ?: 0L
                val deleted = runCatching {
                    SystemFileSystem.delete(path, mustExist = false)
                    true
                }.getOrDefault(false)
                if (deleted) freed += size
            }
            freed
        }
    }

    suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!SystemFileSystem.exists(directory)) return@withLock 0L
            val now = nowMillis()
            data class Entry(val path: Path, val size: Long, val lastAccessMs: Long)
            val entries = ArrayList<Entry>()
            var freed = 0L
            for (path in safeList(directory)) {
                if (path.name.endsWith(".tmp") || path.name.endsWith(LEGACY_EXTENSION)) {
                    val obsoleteSize = SystemFileSystem.metadataOrNull(path)?.size?.coerceAtLeast(0L) ?: 0L
                    if (runCatching { SystemFileSystem.delete(path, mustExist = false); true }.getOrDefault(false)) {
                        freed += obsoleteSize
                    }
                    continue
                }
                if (!path.name.endsWith(EXTENSION)) continue
                val meta = SystemFileSystem.metadataOrNull(path) ?: continue
                if (!meta.isRegularFile) continue
                val lastAccess = runCatching {
                    SystemFileSystem.source(path).buffered().use { StoryboardBundleCodec.readLastAccessMillis(it) }
                }.getOrNull() ?: 0L
                entries += Entry(path, meta.size.coerceAtLeast(0L), lastAccess)
            }

            // 1. TTL expiry
            val remaining = ArrayList<Entry>(entries.size)
            for (entry in entries) {
                val expired = maxAgeMillis > 0L && (now - entry.lastAccessMs) > maxAgeMillis
                if (expired) {
                    val ok = runCatching { SystemFileSystem.delete(entry.path, mustExist = false); true }.getOrDefault(false)
                    if (ok) freed += entry.size else remaining += entry
                } else {
                    remaining += entry
                }
            }

            // 2. LRU cap
            if (targetMaxBytes >= 0L) {
                var current = remaining.sumOf { it.size }
                if (current > targetMaxBytes) {
                    for (entry in remaining.sortedBy { it.lastAccessMs }) {
                        if (current <= targetMaxBytes) break
                        val ok = runCatching { SystemFileSystem.delete(entry.path, mustExist = false); true }.getOrDefault(false)
                        if (ok) {
                            current -= entry.size
                            freed += entry.size
                        }
                    }
                }
            }
            freed
        }
    }

    override fun asCacheBackend(): CacheCategoryBackend = object : CacheCategoryBackend {
        override val category: CacheCategory = CacheCategory.STORYBOARD
        override val clearable: Boolean = true
        override suspend fun scan(): CacheCategoryStats = scanStats()
        override suspend fun clear(): Long = clearAll()
        override suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long =
            this@FileStoryboardCache.prune(maxAgeMillis = maxAgeMillis, targetMaxBytes = targetMaxBytes)
    }

    private fun safeList(dir: Path): List<Path> = runCatching {
        SystemFileSystem.list(dir).toList()
    }.getOrDefault(emptyList())

    private fun safeFileName(gcid: String): String? {
        val cleaned = gcid.trim().uppercase().replace(SAFE_NAME_REGEX, "_")
        return cleaned.takeIf { it.isNotBlank() }?.take(96)
    }

    private companion object {
        const val TAG = "StoryboardCache"
        const val EXTENSION = ".sb2"
        const val LEGACY_EXTENSION = ".sb1"
        const val TOUCH_THROTTLE_MS = 60_000L
        val SAFE_NAME_REGEX = Regex("[^0-9A-Za-z_-]")
    }
}

/** 测试或无盘环境用的内存雪碧图缓存。 */
class InMemoryStoryboardCache : StoryboardCache {
    private val lock = Mutex()
    private val bundles = LinkedHashMap<String, StoryboardBundle>()

    override suspend fun read(gcid: String): StoryboardBundle? = lock.withLock {
        bundles[gcid.uppercase()]
    }

    override suspend fun write(bundle: StoryboardBundle) = lock.withLock {
        bundles[bundle.gcid.uppercase()] = bundle
    }

    override suspend fun delete(gcid: String) = lock.withLock {
        bundles.remove(gcid.uppercase())
        Unit
    }

    override fun asCacheBackend(): CacheCategoryBackend = object : CacheCategoryBackend {
        override val category: CacheCategory = CacheCategory.STORYBOARD
        override val clearable: Boolean = true
        override suspend fun scan(): CacheCategoryStats = lock.withLock {
            val total = bundles.values.sumOf { b -> b.frames.sumOf { it.jpegBytes.size.toLong() } }
            CacheCategoryStats(CacheCategory.STORYBOARD, total, bundles.size, clearable = true)
        }
        override suspend fun clear(): Long = lock.withLock {
            val total = bundles.values.sumOf { b -> b.frames.sumOf { it.jpegBytes.size.toLong() } }
            bundles.clear()
            total
        }
    }
}
