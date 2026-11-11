@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.BlockStore
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.posix.memcpy

/**
 * iOS 上的 [ClipCache]：一块一个文件放在 `blocks/` 下，每段的记录用 [ClipRecordCodec] 的文本
 * 写在 `records/` 下，与桌面同一条规则。块的总量超过 [capBytes] 时按最近用过的先后删旧的；
 * 记录只限条数。
 *
 * 为什么不用 kotlinx-io：取不到修改时间与目录列表，而淘汰只能靠最近读过。这里走 NSFileManager，
 * 修改时间用 `NSFileModificationDate`，读到就刷新。写操作先用 `createFileAtPath` 写入 `.tmp` 临时文件，
 * 再删旧目标并用 `moveItemAtPath` 挪入原位，进程半路被杀不会留下半截正式文件；读失败一律当作没有。
 */
class IosClipCache(
    /** 缓存根目录，见 `createIosAppDirectories().caches` 下的 clip-cache。 */
    directory: String,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
) : ClipCache {
    private val blockDirectory = "$directory/blocks"
    private val recordDirectory = "$directory/records"
    private val files = NSFileManager.defaultManager
    private val lock = Mutex()

    // 首次写入时数一遍，之后随写随加；超了才列目录淘汰
    private var storedBytes: Long? = null

    override val blocks: BlockStore = object : BlockStore {
        override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
            if (length < 0) return@withContext null
            val path = blockPath(file, offset)
            runCatching {
                val data = files.contentsAtPath(path) ?: return@runCatching null
                // 长度对不上说明这块不完整，宁可当没有，也不给 SDK 半块
                if (data.length.toLong() != length.toLong()) return@runCatching null
                touch(path)
                data.toByteArray()
            }.getOrNull()
        }

        override suspend fun write(file: String, offset: Long, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val path = blockPath(file, offset)
                    ensureDirectory(path.substringBeforeLast('/'))
                    val before = sizeOfFile(path)
                    // 头一次数要在写之前：写完再数，刚写的这块已在其中，再加一次就算了两遍
                    val stored = storedBytes ?: sizeOfDirectory(blockDirectory)
                    writeAtomically(path, bytes)
                    val total = stored + bytes.size - before
                    storedBytes = if (total > capBytes) evictBlocks() else total
                }.onFailure { PikoLog.w(TAG, "Failed to write clip cache block: key=${clipCacheSafeName(file)}, offset=$offset, bytes=${bytes.size}", it) }
                Unit
            }
        }
    }

    override suspend fun record(key: String): ClipRecord? = withContext(Dispatchers.IO) {
        val path = recordPath(key)
        runCatching {
            val data = files.contentsAtPath(path) ?: return@runCatching null
            val decoded = ClipRecordCodec.decode(data.toByteArray().decodeToString()) ?: return@runCatching null
            touch(path)
            decoded
        }.getOrNull()
    }

    override suspend fun remember(key: String, record: ClipRecord): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val path = recordPath(key)
                ensureDirectory(recordDirectory)
                writeAtomically(path, ClipRecordCodec.encode(record).encodeToByteArray())
                val names = files.contentsOfDirectoryAtPath(recordDirectory, error = null)
                    ?.filterIsInstance<String>()
                    ?.filterNot { it.endsWith(".tmp") }
                    ?: return@runCatching
                val excess = names.size - MAX_RECORDS
                if (excess > 0) {
                    var deleted = 0
                    names.map { it to modifiedAt("$recordDirectory/$it") }
                        .sortedBy { it.second }
                        .take(excess)
                        .forEach { if (files.removeItemAtPath("$recordDirectory/${it.first}", error = null)) deleted++ }
                    if (deleted > 0) {
                        PikoLog.d(TAG, "Evicted $deleted clip cache records (max=$MAX_RECORDS)")
                    }
                }
            }.onFailure { PikoLog.w(TAG, "Failed to write clip cache record: key=${clipCacheSafeName(key)}", it) }
            Unit
        }
    }

    /**
     * 删到 [capBytes] 的 [EVICT_TO] 以下，返回剩下的总量。只删到上限的话，下一块写进来又超，
     * 每写一块都要把上千个块文件列一遍、排一遍序，还持着锁。
     */
    private fun evictBlocks(): Long {
        val candidates = mutableListOf<Triple<String, Long, Double>>()
        for (bucket in files.contentsOfDirectoryAtPath(blockDirectory, error = null)?.filterIsInstance<String>() ?: emptyList()) {
            val bucketPath = "$blockDirectory/$bucket"
            for (name in files.contentsOfDirectoryAtPath(bucketPath, error = null)?.filterIsInstance<String>() ?: emptyList()) {
                if (name.endsWith(".tmp")) continue
                val path = "$bucketPath/$name"
                candidates += Triple(path, sizeOfFile(path), modifiedAt(path))
            }
        }
        var total = candidates.sumOf { it.second }
        val target = (capBytes * EVICT_TO).toLong()
        var deletedCount = 0
        var freedBytes = 0L
        for ((path, size, _) in candidates.sortedBy { it.third }) {
            if (total <= target) break
            if (files.removeItemAtPath(path, error = null)) {
                total -= size
                deletedCount++
                freedBytes += size
            }
        }
        // 空的桶目录顺手删掉，下次写入会再建
        for (bucket in files.contentsOfDirectoryAtPath(blockDirectory, error = null)?.filterIsInstance<String>() ?: emptyList()) {
            val bucketPath = "$blockDirectory/$bucket"
            val remaining = files.contentsOfDirectoryAtPath(bucketPath, error = null)?.filterIsInstance<String>().orEmpty()
            if (remaining.isEmpty()) files.removeItemAtPath(bucketPath, error = null)
        }
        if (deletedCount > 0) {
            PikoLog.d(TAG, "Evicted clip cache blocks: deleted=$deletedCount, freedBytes=$freedBytes, remainingBytes=$total, capBytes=$capBytes")
        }
        return total
    }

    private fun blockPath(file: String, offset: Long) = "$blockDirectory/${clipCacheSafeName(file)}/$offset"

    private fun recordPath(key: String) = "$recordDirectory/${clipCacheSafeName(key)}"

    private fun ensureDirectory(path: String) {
        if (files.fileExistsAtPath(path)) return
        files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    private fun writeAtomically(path: String, bytes: ByteArray) {
        val temp = "$path.tmp"
        val data = bytes.toNSData()
        // 先写 .tmp 临时文件再移除旧目标并改名入位，避免半截写入污染缓存文件
        check(files.createFileAtPath(temp, data, null)) { "Failed to create temporary clip cache file" }
        files.removeItemAtPath(path, error = null)
        if (!files.moveItemAtPath(temp, path, error = null)) {
            files.removeItemAtPath(temp, error = null)
            error("Failed to replace clip cache file")
        }
    }

    private fun sizeOfDirectory(directory: String): Long {
        var total = 0L
        for (name in files.contentsOfDirectoryAtPath(directory, error = null)?.filterIsInstance<String>() ?: emptyList()) {
            val path = "$directory/$name"
            if (isDirectory(path)) total += sizeOfDirectory(path) else total += sizeOfFile(path)
        }
        return total
    }

    private fun sizeOfFile(path: String): Long =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    private fun isDirectory(path: String): Boolean =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeDirectory

    private fun modifiedAt(path: String): Double =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970 ?: 0.0

    /** 刷新「最近用过」：淘汰按修改时间排，读到就要把它挪到队尾。 */
    private fun touch(path: String) {
        files.setAttributes(mapOf(NSFileModificationDate to NSDate()), ofItemAtPath = path, error = null)
    }

    private companion object {
        const val TAG = "Clips"
        const val DEFAULT_CAP_BYTES = 300L * 1024 * 1024
        const val MAX_RECORDS = 4096
        const val EVICT_TO = 0.8
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}
