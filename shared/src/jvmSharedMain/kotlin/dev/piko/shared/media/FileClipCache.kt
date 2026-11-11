package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.BlockStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [ClipCache] 的文件实现：每块一个文件放在 `blocks/` 下，每段的记录放在 `records/` 下。
 * 块的总量超过 [capBytes] 时按最近用过的先后删旧的；记录很小，只限条数。
 *
 * 最近用过靠文件的修改时间：读到就刷新。放在 JVM 共用层而不是 commonMain，就是因为 kotlinx-io
 * 取不到修改时间。写先落临时文件再改名，进程半路被杀不会留下半个文件；读失败一律当作没有。
 */
class FileClipCache(
    directory: File,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
) : ClipCache {
    private val blockDirectory = File(directory, "blocks")
    private val recordDirectory = File(directory, "records")
    private val lock = Mutex()

    // 首次写入时数一遍，之后随写随加；超了才列目录淘汰
    private var storedBytes: Long? = null

    override val blocks: BlockStore = object : BlockStore {
        override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
            val target = blockFile(file, offset)
            runCatching { target.readBytes() }.getOrNull()
                ?.takeIf { it.size == length }
                ?.also { target.setLastModified(System.currentTimeMillis()) }
        }

        override suspend fun write(file: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val target = blockFile(file, offset)
                    val before = if (target.isFile) target.length() else 0L
                    // 头一次数要在写之前：写完再数，刚写的这块已在其中，再加一次就算了两遍，
                    // 两块正好到上限时被当成超了，刚写的前一块跟着被淘汰
                    val stored = storedBytes ?: sizeOf(blockDirectory)
                    writeAtomically(target) { it.write(bytes) }
                    val total = stored + bytes.size - before
                    storedBytes = if (total > capBytes) evictBlocks() else total
                }.onFailure { PikoLog.w(TAG, "Failed to write clip cache block: key=${safe(file)}, offset=$offset, bytes=${bytes.size}", it) }
                Unit
            }
        }
    }

    override suspend fun record(key: String): ClipRecord? = withContext(Dispatchers.IO) {
        val file = recordFile(key)
        if (!file.isFile) return@withContext null
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != FORMAT) return@use null
                ClipRecord(
                    fileId = input.readUTF(),
                    gcid = input.readUTF(),
                    name = input.readUTF(),
                    parentId = input.readUTF(),
                    originalBytes = input.readLong(),
                    mediaId = input.readUTF(),
                    streamBytes = input.readLong(),
                    sliceOffset = input.readLong(),
                    sliceLength = input.readLong(),
                    sliceStartMs = input.readLong(),
                    sliceDurationMs = input.readLong(),
                    keyframeOffset = input.readLong(),
                    videoPid = input.readInt(),
                )
            }
        }.getOrNull()?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    override suspend fun remember(key: String, record: ClipRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                writeAtomically(recordFile(key)) { output ->
                    output.writeInt(FORMAT)
                    output.writeUTF(record.fileId)
                    output.writeUTF(record.gcid)
                    output.writeUTF(record.name)
                    output.writeUTF(record.parentId)
                    output.writeLong(record.originalBytes)
                    output.writeUTF(record.mediaId)
                    output.writeLong(record.streamBytes)
                    output.writeLong(record.sliceOffset)
                    output.writeLong(record.sliceLength)
                    output.writeLong(record.sliceStartMs)
                    output.writeLong(record.sliceDurationMs)
                    output.writeLong(record.keyframeOffset)
                    output.writeInt(record.videoPid)
                }
                val records = recordDirectory.listFiles { it.isFile && !it.name.endsWith(".tmp") } ?: return@runCatching
                val excess = records.size - MAX_RECORDS
                if (excess > 0) {
                    var deleted = 0
                    records.map { it to it.lastModified() }
                        .sortedBy { it.second }
                        .take(excess)
                        .forEach { if (it.first.delete()) deleted++ }
                    if (deleted > 0) {
                        PikoLog.d(TAG, "Evicted $deleted clip cache records (max=$MAX_RECORDS)")
                    }
                }
            }.onFailure { PikoLog.w(TAG, "Failed to write clip cache record: key=${safe(key)}", it) }
            Unit
        }
    }

    /**
     * 删到 [capBytes] 的 [EVICT_TO] 以下，返回剩下的总量。只删到上限的话，下一块写进来又超，
     * 每写一块都要把上千个块文件列一遍、排一遍序，还持着锁；缓存满了之后，实验里取开头的吞吐掉了约三成（2026-09-28，piko-cli bench --store）。
     */
    private fun evictBlocks(): Long {
        val files = blockDirectory.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { Triple(it, it.length(), it.lastModified()) }
            .toList()
        var total = files.sumOf { it.second }
        val target = (capBytes * EVICT_TO).toLong()
        var deletedCount = 0
        var freedBytes = 0L
        for ((file, size, _) in files.sortedBy { it.third }) {
            if (total <= target) break
            if (file.delete()) {
                total -= size
                deletedCount++
                freedBytes += size
            }
        }
        blockDirectory.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
        if (deletedCount > 0) {
            PikoLog.d(TAG, "Evicted clip cache blocks: deleted=$deletedCount, freedBytes=$freedBytes, remainingBytes=$total, capBytes=$capBytes")
        }
        return total
    }

    private fun sizeOf(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeAtomically(target: File, body: (DataOutputStream) -> Unit) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw IOException("Failed to create clip cache directory")
        }
        val temp = File(parent, "${target.name}.tmp")
        try {
            DataOutputStream(temp.outputStream().buffered()).use(body)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    // 内容哈希与媒体 ID 本就只含字母数字，其余一律换掉，免得落成非法文件名
    private fun blockFile(file: String, offset: Long) = File(File(blockDirectory, safe(file)), offset.toString())

    private fun recordFile(key: String) = File(recordDirectory, safe(key))

    private fun safe(name: String) = name.replace(UNSAFE_NAME_CHARS, "_")

    private companion object {
        const val TAG = "Clips"
        // 5 起持久化关键帧偏移与视频 PID，从磁盘恢复切片时同样将前序 GOP 孤儿 P/B 帧置为 MPEG-TS 空包
        const val FORMAT = 5
        const val DEFAULT_CAP_BYTES = 300L * 1024 * 1024
        const val MAX_RECORDS = 4096
        const val EVICT_TO = 0.8
        val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
