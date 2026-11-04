@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.media.cache

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.*

private const val BLOCK_BYTES = 256L * 1024
private const val BITMAP_SUFFIX = ".ios-blocks"

actual fun fileCacheName(key: String): String {
    val input = key.encodeToByteArray()
    val digest = ByteArray(32)
    // Keep a valid pointer even for the empty string; the extra byte is not hashed.
    (input + byteArrayOf(0)).usePinned { source ->
        digest.usePinned { target ->
            CC_SHA256(source.addressOf(0), input.size.toUInt(), target.addressOf(0).reinterpret())
        }
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
        for (byte in digest) {
            val value = byte.toInt() and 255
            append(hex[value ushr 4]); append(hex[value and 15])
        }
    }
}

actual fun persistentFileStore(path: String, key: String, size: Long, scope: CoroutineScope): PersistentFileStore =
    IosSparseFileStore(path, key, size, scope)

actual suspend fun persistedFileBytes(path: String, key: String, size: Long): Long = withContext(Dispatchers.IO) {
    val bits = readBits(path, key, size)
    bits.indices.sumOf { if (bits[it]) blockLength(size, it) else 0L }
}

actual suspend fun ownedCacheFiles(examplePath: String): List<String> = withContext(Dispatchers.IO) {
    val directory = parentOf(examplePath)
    val pattern = Regex("[0-9a-f]{64}\\.data(?:\\.ios-blocks(?:\\.tmp)?)?")
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null).orEmpty()
        .filterIsInstance<String>().filter { pattern.matches(it) }
        .filter { name ->
            val candidate = "$directory/$name"
            // Do not follow directories or symlinks during cache pruning.
            platform.posix.open(candidate, O_RDONLY or O_NOFOLLOW).let { fd ->
                if (fd < 0) false else try {
                    memScoped {
                        val info = alloc<stat>()
                        fstat(fd, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFREG
                    }
                } finally { platform.posix.close(fd) }
            }
        }
        .map { "$directory/${it.removeSuffix(".tmp").removeSuffix(BITMAP_SUFFIX)}" }.distinct()
}

actual suspend fun deleteCacheFiles(path: String): Unit = withContext(Dispatchers.IO) {
    deleteFiles(path)
}

actual suspend fun copyCachedFile(source: String, destination: String): Unit = withContext(Dispatchers.IO) {
    if (canonical(source) == canonical(destination)) return@withContext
    ensureParent(destination)
    val temp = "${parentOf(destination)}/.piko-export-${NSUUID().UUIDString}"
    val input = openRead(source)
    try {
        val output = openWrite(temp, exclusive = true)
        try {
            val length = fileLength(input)
            val buffer = ByteArray(BLOCK_BYTES.toInt())
            var offset = 0L
            while (offset < length) {
                currentCoroutineContext().ensureActive()
                val amount = minOf(buffer.size.toLong(), length - offset).toInt()
                checkIo(readFully(input, offset, buffer, amount), "Cache export source truncated")
                writeFully(output, offset, buffer, amount)
                offset += amount
            }
            syncFd(output)
        } finally { platform.posix.close(output) }
        currentCoroutineContext().ensureActive()
        checkIo(rename(temp, destination) == 0, "Cache export rename failed")
        syncDirectory(parentOf(destination))
    } finally {
        platform.posix.close(input)
        removeIfPresent(temp)
    }
}

/**
 * One live store per path, owned by PikoFileCachePool. POSIX positional writes leave holes on APFS;
 * neither file length nor zero-filled holes are evidence that a block is held.
 *
 * The iOS bitmap is intentionally independent of the JVM format. A complete write publishes an
 * in-memory bit immediately. flush first fsyncs data, then fsyncs and atomically renames a CRC32
 * protected bitmap. A malformed snapshot is discarded in full, never partially trusted.
 */
internal class IosSparseFileStore(
    override val path: String,
    private val key: String,
    override val size: Long,
    scope: CoroutineScope,
    private val syncData: (Int) -> Unit = ::syncFd,
) : PersistentFileStore {
    private val lock = NSLock()
    private val bits = readBits(path, key, size)
    private var descriptor = -1
    private var dirty = false
    private var snapshotRetired = false
    private var closed = false
    private val progress = MutableStateFlow(bits.indices.sumOf { if (bits[it]) blockLength(size, it) else 0L })
    override val heldBytes: StateFlow<Long> get() = progress

    private val persistence = scope.launch(Dispatchers.IO) {
        while (true) {
            delay(2_000)
            try { flush() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Keep dirty bits for retry. Explicit flush/close still reports the error.
            }
        }
    }

    // The SDK's file is a handle content key, not the account-scoped pool key. This instance
    // binds one content identity; deliberately do not compare these two different key schemes.
    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        lock.locked {
            checkOpen()
            val index = indexOf(offset) ?: return@locked null
            if (!bits[index] || length.toLong() != blockLength(size, index)) return@locked null
            val fd = openData()
            ByteArray(length).also { checkIo(readFully(fd, offset, it), "Held cache block truncated") }
        }
    }

    override suspend fun write(file: String, offset: Long, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        lock.locked {
            checkOpen()
            val index = requireNotNull(indexOf(offset)) { "Invalid cache block offset" }
            require(bytes.size.toLong() == blockLength(size, index)) { "Invalid cache block length" }
            if (!bits[index]) {
                writeFully(openData(forWrite = true), offset, bytes)
                bits[index] = true
                dirty = true
                progress.value += bytes.size.toLong()
            }
        }
    }

    override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = lock.locked {
        checkOpen()
        buildList {
            for (range in ranges) {
                if (range.isEmpty()) continue
                val start = maxOf(0L, range.first)
                val end = minOf(this@IosSparseFileStore.size - 1, range.last)
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

    override suspend fun importPrefix(source: String): Unit = withContext(Dispatchers.IO) {
        lock.locked { checkOpen() }
        if (canonical(source) == canonical(path)) return@withContext
        val input = platform.posix.open(source, O_RDONLY)
        if (input < 0) {
            if (errno == ENOENT) return@withContext
            throw IOException("Opening cache prefix failed: errno=$errno")
        }
        try {
            val available = minOf(size, fileLength(input))
            var offset = 0L
            while (offset < available) {
                currentCoroutineContext().ensureActive()
                val length = minOf(BLOCK_BYTES, size - offset)
                if (length > available - offset) break
                val bytes = ByteArray(length.toInt())
                checkIo(readFully(input, offset, bytes), "Cache prefix truncated")
                write(key, offset, bytes)
                offset += length
            }
            flush()
        } finally { platform.posix.close(input) }
    }

    override suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        lock.locked { if (!closed) flushLocked() }
    }

    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        persistence.cancelAndJoin()
        lock.locked {
            if (!closed) {
                try { flushLocked() } finally {
                    closed = true
                    if (descriptor >= 0) platform.posix.close(descriptor)
                    descriptor = -1
                }
            }
        }
    }

    override suspend fun delete(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        // Even a failed final sync must not leave resources open or prevent explicit deletion.
        try { close() } finally { lock.locked { deleteFiles(path) } }
    }

    private fun flushLocked() {
        if (size == 0L && descriptor < 0) { openData(forWrite = true); dirty = true }
        if (!dirty) return
        syncData(descriptor)
        val bytes = encodeBits(key, size, bits)
        val temp = path + BITMAP_SUFFIX + ".tmp"
        try {
            val output = openWrite(temp)
            try {
                writeFully(output, 0, bytes)
                syncFd(output)
            } finally { platform.posix.close(output) }
            checkIo(rename(temp, path + BITMAP_SUFFIX) == 0, "Cache bitmap rename failed")
            syncDirectory(parentOf(path))
            dirty = false
        } finally { removeIfPresent(temp) }
    }

    private fun openData(forWrite: Boolean = false): Int {
        checkOpen()
        if (descriptor < 0) ensureParent(path)
        if (forWrite && !snapshotRetired) {
            // Retire even a mismatched old snapshot before overwriting any bytes. Conservatively
            // retire a valid snapshot too: a crash before the next flush may redownload old blocks,
            // but can never resurrect bits from another identity or a truncated/replaced data file.
            // Merely reading a restored store leaves its durable snapshot untouched.
            removeIfPresent(path + BITMAP_SUFFIX)
            syncDirectory(parentOf(path))
            snapshotRetired = true
            dirty = true
        }
        if (descriptor < 0) {
            descriptor = platform.posix.open(path, O_RDWR or O_CREAT, 384)
            checkIo(descriptor >= 0, "Opening sparse cache failed")
        }
        return descriptor
    }

    private fun checkOpen() = check(!closed) { "Cache store is closed" }
    private fun indexOf(offset: Long): Int? {
        if (offset < 0 || offset % BLOCK_BYTES != 0L || offset >= size) return null
        return (offset / BLOCK_BYTES).toInt()
    }
}

private inline fun <T> NSLock.locked(action: () -> T): T {
    lock()
    return try { action() } finally { unlock() }
}

private fun blockLength(size: Long, index: Int): Long = minOf(BLOCK_BYTES, size - index * BLOCK_BYTES)
private fun blockCount(size: Long): Int {
    require(size >= 0)
    val count = size / BLOCK_BYTES + if (size % BLOCK_BYTES == 0L) 0 else 1
    require(count <= Int.MAX_VALUE) { "Cache file too large" }
    return count.toInt()
}

private fun bitmapHeader(key: String, size: Long, count: Int): ByteArray =
    "PIKO-IOS-BLOCKS-1\n${fileCacheName(key)}\n$size\n$BLOCK_BYTES\n$count\n".encodeToByteArray()

private fun encodeBits(key: String, size: Long, bits: BooleanArray): ByteArray {
    val header = bitmapHeader(key, size, bits.size)
    val result = ByteArray(header.size + (bits.size.toLong() + 7).div(8).toInt() + 4)
    header.copyInto(result)
    for (index in bits.indices) if (bits[index]) {
        val position = header.size + index / 8
        result[position] = (result[position].toInt() or (1 shl (index % 8))).toByte()
    }
    val crc = crc32(result, result.size - 4)
    for (i in 0..3) result[result.size - 4 + i] = (crc ushr (i * 8)).toByte()
    return result
}

private fun readBits(path: String, key: String, size: Long): BooleanArray {
    val count = blockCount(size)
    val empty = BooleanArray(count)
    val header = bitmapHeader(key, size, count)
    val expected = header.size + (count.toLong() + 7).div(8).toInt() + 4
    try {
        val bitmap = platform.posix.open(path + BITMAP_SUFFIX, O_RDONLY or O_NOFOLLOW)
        if (bitmap < 0) return empty
        val raw = try {
            if (fileLength(bitmap) != expected.toLong()) return empty
            ByteArray(expected).also { if (!readFully(bitmap, 0, it)) return empty }
        } finally { platform.posix.close(bitmap) }
        if (!header.indices.all { raw[it] == header[it] }) return empty
        var storedCrc = 0
        for (i in 0..3) storedCrc = storedCrc or ((raw[raw.size - 4 + i].toInt() and 255) shl (i * 8))
        if (crc32(raw, raw.size - 4) != storedCrc) return empty
        if (count % 8 != 0 && (raw[raw.size - 5].toInt() and 255) ushr (count % 8) != 0) return empty
        val input = platform.posix.open(path, O_RDONLY or O_NOFOLLOW)
        if (input < 0) return empty
        val length = try { fileLength(input) } finally { platform.posix.close(input) }
        return BooleanArray(count) { index ->
            raw[header.size + index / 8].toInt() and (1 shl (index % 8)) != 0 &&
                index * BLOCK_BYTES + blockLength(size, index) <= length
        }
    } catch (_: IOException) {
        return empty
    }
}

private fun crc32(bytes: ByteArray, length: Int): Int {
    var crc = -1
    for (index in 0 until length) {
        crc = crc xor (bytes[index].toInt() and 255)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    return crc.inv()
}

private fun readFully(fd: Int, offset: Long, bytes: ByteArray, length: Int = bytes.size): Boolean {
    if (length == 0) return true
    return bytes.usePinned { pinned ->
        var done = 0
        while (done < length) {
            val amount = pread(fd, pinned.addressOf(done), (length - done).toULong(), offset + done)
            if (amount < 0 && errno == EINTR) continue
            checkIo(amount >= 0, "Cache read failed")
            if (amount == 0L) return@usePinned false
            done += amount.toInt()
        }
        true
    }
}

private fun writeFully(fd: Int, offset: Long, bytes: ByteArray, length: Int = bytes.size) {
    if (length == 0) return
    bytes.usePinned { pinned ->
        var done = 0
        while (done < length) {
            val amount = pwrite(fd, pinned.addressOf(done), (length - done).toULong(), offset + done)
            if (amount < 0 && errno == EINTR) continue
            checkIo(amount > 0, "Cache write failed")
            done += amount.toInt()
        }
    }
}

private fun syncFd(fd: Int) {
    while (true) {
        if (fsync(fd) == 0) return
        if (errno != EINTR) throw IOException("Cache fsync failed: errno=$errno")
    }
}

private fun fileLength(fd: Int): Long = lseek(fd, 0, SEEK_END).also {
    checkIo(it >= 0, "Cache length query failed")
}
private fun parentOf(path: String): String = path.substringBeforeLast('/', ".").ifEmpty { "/" }
private fun canonical(path: String): String =
    NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath?.path ?: path
private fun ensureParent(path: String) {
    checkIo(NSFileManager.defaultManager.createDirectoryAtPath(parentOf(path), true, null, null), "Creating cache directory failed")
}
private fun openRead(path: String): Int = platform.posix.open(path, O_RDONLY).also { checkIo(it >= 0, "Opening cache input failed") }
private fun openWrite(path: String, exclusive: Boolean = false): Int =
    platform.posix.open(path, O_WRONLY or O_CREAT or (if (exclusive) O_EXCL else O_TRUNC), 384)
        .also { checkIo(it >= 0, "Opening cache output failed") }
private fun checkIo(success: Boolean, message: String) { if (!success) throw IOException("$message: errno=$errno") }
private fun removeIfPresent(path: String) {
    if (unlink(path) != 0 && errno != ENOENT) throw IOException("Removing cache file failed: errno=$errno")
}
private fun syncDirectory(directory: String) {
    val fd = openRead(directory)
    try { syncFd(fd) } finally { platform.posix.close(fd) }
}
private fun deleteFiles(path: String) {
    for (suffix in listOf(BITMAP_SUFFIX, "$BITMAP_SUFFIX.tmp", "")) removeIfPresent(path + suffix)
    // The parent may never have been created (discarding an untouched store).
    val fd = platform.posix.open(parentOf(path), O_RDONLY)
    if (fd >= 0) try { syncFd(fd) } finally { platform.posix.close(fd) }
}
