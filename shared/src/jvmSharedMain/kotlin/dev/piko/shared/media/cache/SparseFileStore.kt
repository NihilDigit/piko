package dev.piko.shared.media.cache

import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write

actual fun fileCacheName(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

actual fun persistentFileStore(path: String, key: String, size: Long, scope: CoroutineScope): PersistentFileStore =
    SparseFileStore(path, key, size, scope)

actual suspend fun copyCachedFile(source: String, destination: String) = withContext(Dispatchers.IO) {
    val from = File(source)
    val to = File(destination)
    if (from.canonicalPath != to.canonicalPath) {
        to.parentFile?.mkdirs()
        val staging = File(to.parentFile, ".${to.name}.piko-export")
        try {
            from.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
            RandomAccessFile(staging, "rw").use { it.channel.force(true) }
            try {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }
    Unit
}

actual suspend fun persistedFileBytes(path: String, key: String, size: Long): Long = withContext(Dispatchers.IO) {
    SparseFileStore.readBits(File(path), key, size).mapIndexed { index, held ->
        if (held) minOf(BLOCK_BYTES, size - index * BLOCK_BYTES) else 0L
    }.sum()
}

actual suspend fun ownedCacheFiles(examplePath: String): List<String> = withContext(Dispatchers.IO) {
    File(examplePath).parentFile?.listFiles().orEmpty().filter {
        it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.data(?:\\.blocks(?:\\.tmp)?)?"))
    }.map { it.path.removeSuffix(".tmp").removeSuffix(".blocks") }.distinct()
}

actual suspend fun deleteCacheFiles(path: String) = withContext(Dispatchers.IO) {
    for (suffix in listOf("", ".blocks", ".blocks.tmp")) {
        val file = File(path + suffix)
        check(!file.exists() || file.delete()) { "无法删除暂存文件" }
    }
}

private const val BLOCK_BYTES = 256L * 1024

/** 数据同步成功后才发布位图；同步失败或位图损坏时，重启后重新下载相应块。 */
internal class SparseFileStore(
    override val path: String,
    private val key: String,
    override val size: Long,
    scope: CoroutineScope,
    private val syncData: (RandomAccessFile) -> Unit = { it.channel.force(true) },
) : PersistentFileStore {
    private val file = File(path)
    private val bitmap = File("$path.blocks")
    private val lock = Any()
    private val flushLock = Any()
    private val ioLock = ReentrantReadWriteLock()
    private val bits = readBits(file, key, size)
    private var dirty = false
    private var closed = false
    private var handle: RandomAccessFile? = null
    private val progress = MutableStateFlow(bits.mapIndexed { index, held -> if (held) lengthOf(index) else 0L }.sum())
    override val heldBytes: StateFlow<Long> get() = progress

    private val persistence = scope.launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            runCatching { flush() }
        }
    }

    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        ioLock.read {
            val input = synchronized(lock) {
                val index = indexOf(offset) ?: return@withContext null
                if (!bits[index] || length.toLong() != lengthOf(index)) return@withContext null
                open()
            }
            val out = ByteArray(length)
            val buf = ByteBuffer.wrap(out)
            var pos = offset
            while (buf.hasRemaining()) {
                val readBytes = input.channel.read(buf, pos)
                if (readBytes < 0) return@withContext null
                pos += readBytes
            }
            out
        }
    }

    override suspend fun write(file: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val index = requireNotNull(indexOf(offset)) { "无效的块偏移：$offset" }
        require(bytes.size.toLong() == lengthOf(index))
        ioLock.read {
            val output = synchronized(lock) {
                check(!closed)
                if (bits[index]) return@withContext
                open()
            }
            val buf = ByteBuffer.wrap(bytes)
            var pos = offset
            while (buf.hasRemaining()) {
                pos += output.channel.write(buf, pos)
            }
            synchronized(lock) {
                check(!closed)
                if (!bits[index]) {
                    bits[index] = true
                    dirty = true
                    progress.value += bytes.size
                }
            }
        }
    }

    override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = synchronized(lock) {
        buildList {
            for (range in ranges) {
                val start = range.first.coerceAtLeast(0)
                val end = range.last.coerceAtMost(this@SparseFileStore.size - 1)
                if (start > end) continue
                var run = -1L
                for (index in (start / BLOCK_BYTES).toInt()..(end / BLOCK_BYTES).toInt()) {
                    val from = maxOf(start, index * BLOCK_BYTES)
                    if (!bits[index]) {
                        if (run < 0) run = from
                    } else if (run >= 0) {
                        add(run until from)
                        run = -1
                    }
                }
                if (run >= 0) add(run..end)
            }
        }
    }

    override suspend fun importPrefix(source: String) = withContext(Dispatchers.IO) {
        val old = File(source)
        if (old.canonicalPath == file.canonicalPath || !old.isFile) return@withContext
        RandomAccessFile(old, "r").use { input ->
            val available = input.length().coerceAtMost(size)
            var offset = 0L
            while (offset < available) {
                val length = minOf(BLOCK_BYTES, size - offset)
                if (offset + length > available) break
                val bytes = ByteArray(length.toInt())
                input.seek(offset)
                input.readFully(bytes)
                write(key, offset, bytes)
                offset += length
            }
        }
        flush()
    }

    override suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        synchronized(flushLock) {
            ioLock.read {
                val (targetHandle, snapshot) = synchronized(lock) {
                    if (size == 0L && !closed && handle == null) { open(); dirty = true }
                    if (!dirty || closed) return@withContext
                    dirty = false
                    checkNotNull(handle) to bits.copyOf()
                }
                try {
                    syncData(targetHandle)
                    val payload = ByteArrayOutputStream().also { buffer ->
                        DataOutputStream(buffer).use { output ->
                            output.writeInt(MAGIC)
                            output.writeUTF(key)
                            output.writeLong(size)
                            output.writeLong(BLOCK_BYTES)
                            output.writeInt(snapshot.size)
                            for (base in snapshot.indices step 8) {
                                var byte = 0
                                for (bit in 0..7) if (snapshot.getOrElse(base + bit) { false }) byte = byte or (1 shl bit)
                                output.writeByte(byte)
                            }
                        }
                    }.toByteArray()
                    val temp = File("${bitmap.path}.tmp")
                    try {
                        DataOutputStream(temp.outputStream()).use {
                            it.write(payload)
                            it.writeLong(CRC32().apply { update(payload) }.value)
                        }
                        RandomAccessFile(temp, "rw").use { it.channel.force(true) }
                        try {
                            Files.move(temp.toPath(), bitmap.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), bitmap.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temp.delete()
                    }
                } catch (e: Throwable) {
                    synchronized(lock) { dirty = true }
                    throw e
                }
            }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        persistence.cancelAndJoin()
        try {
            flush()
        } finally {
            ioLock.write {
                synchronized(lock) { closed = true; handle?.close(); handle = null }
            }
        }
    }

    override suspend fun delete() {
        close()
        withContext(Dispatchers.IO) {
            check(!file.exists() || file.delete()) { "无法删除缓存文件" }
            check(!bitmap.exists() || bitmap.delete()) { "无法删除缓存位图" }
        }
    }

    private fun open(): RandomAccessFile {
        check(!closed)
        return handle ?: run {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                try {
                    Files.newByteChannel(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).close()
                } catch (_: FileAlreadyExistsException) { }
            }
            RandomAccessFile(file, "rw").also { handle = it }
        }
    }

    private fun indexOf(offset: Long): Int? = (offset / BLOCK_BYTES).toInt()
        .takeIf { offset >= 0 && offset % BLOCK_BYTES == 0L && it in bits.indices }
    private fun lengthOf(index: Int): Long = minOf(BLOCK_BYTES, size - index * BLOCK_BYTES)

    companion object {
        private const val MAGIC = 0x50494b31
        fun readBits(file: File, key: String, size: Long): BooleanArray {
            require(size >= 0 && (size + BLOCK_BYTES - 1) / BLOCK_BYTES <= Int.MAX_VALUE)
            val count = ((size + BLOCK_BYTES - 1) / BLOCK_BYTES).toInt()
            return runCatching {
                val raw = File("${file.path}.blocks").readBytes()
                require(raw.size >= 8)
                val payload = raw.copyOf(raw.size - 8)
                val crc = DataInputStream(ByteArrayInputStream(raw, raw.size - 8, 8)).readLong()
                require(CRC32().apply { update(payload) }.value == crc)
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    require(input.readInt() == MAGIC && input.readUTF() == key && input.readLong() == size)
                    require(input.readLong() == BLOCK_BYTES && input.readInt() == count)
                    val packed = ByteArray((count + 7) / 8).also { input.readFully(it) }
                    val length = if (file.isFile) file.length() else 0L
                    BooleanArray(count) { index ->
                        packed[index / 8].toInt() and (1 shl (index % 8)) != 0 &&
                            minOf(size, (index + 1) * BLOCK_BYTES) <= length
                    }
                }
            }.getOrElse { BooleanArray(count) }
        }
    }
}
