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
                    writeAtomically(target) { it.write(bytes) }
                    val total = (storedBytes ?: sizeOf(blockDirectory)) + bytes.size - before
                    storedBytes = if (total > capBytes) evictBlocks() else total
                }.onFailure { PikoLog.w(TAG, "切片写盘失败", it) }
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
                }
                val records = recordDirectory.listFiles { it.isFile && !it.name.endsWith(".tmp") } ?: return@runCatching
                records.sortedBy { it.lastModified() }.take((records.size - MAX_RECORDS).coerceAtLeast(0)).forEach { it.delete() }
            }.onFailure { PikoLog.w(TAG, "切片记录写盘失败", it) }
            Unit
        }
    }

    /** 删到 [capBytes] 以下，返回剩下的总量。 */
    private fun evictBlocks(): Long {
        val files = blockDirectory.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        blockDirectory.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
        return total
    }

    private fun sizeOf(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun writeAtomically(target: File, body: (DataOutputStream) -> Unit) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        DataOutputStream(temp.outputStream().buffered()).use(body)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    // 内容哈希与媒体 ID 本就只含字母数字，其余一律换掉，免得落成非法文件名
    private fun blockFile(file: String, offset: Long) = File(File(blockDirectory, safe(file)), offset.toString())

    private fun recordFile(key: String) = File(recordDirectory, safe(key))

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private companion object {
        const val TAG = "Clips"
        // 2 起记录带切片的实际起点；1 的记录读不出来，当作没有，再来一次就补上
        const val FORMAT = 2
        const val DEFAULT_CAP_BYTES = 300L * 1024 * 1024
        const val MAX_RECORDS = 4096
    }
}
