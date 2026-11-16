package dev.piko.shared.media.cache

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext

private const val PACK_BLOCK_BYTES = 256L * 1024
private const val EXPORT_BUFFER_BYTES = 8 * 1024 * 1024
private const val BACKWARD_SCAN_WINDOW_BYTES = 1024 * 1024
private const val CHECKPOINT_BYTE_THRESHOLD = 64L * 1024 * 1024
private const val FLUSH_WARN_INTERVAL_NANOS = 60_000_000_000L

private const val FILE_HEADER_BYTES = 80
private const val RECORD_HEADER_BYTES = 40
private const val FOOTER_BYTES = 48
private const val CHECKPOINT_BODY_PREFIX_BYTES = 24
private const val MAX_CHECKPOINT_PAYLOAD_BYTES = 32 * 1024 * 1024

private const val FILE_MAGIC = 0x50494B50       // "PIKP"
private const val FORMAT_VERSION = 1
private const val DATA_RECORD_MAGIC = 0x504B4454 // "PKDT"
private const val CHECKPOINT_MAGIC = 0x504B4350  // "PKCP"
private const val FOOTER_MAGIC = 0x504B4654      // "PKFT"

private const val ENCODING_SPARSE: Byte = 0
private const val ENCODING_DENSE: Byte = 1
private const val PACK_TAG = "PackCache"

/**
 * 单文件顺序追加块包存储（`.pack`）：
 * - 不启用 `StandardOpenOption.SPARSE`，不建伴随 `.blocks` 文件，所有网络并发线程完成的 256 KiB 逻辑块经单一写锁在文件末尾纯顺序追加；
 * - 定期及显式 `flush()`/`close()` 时先对数据刷盘，再在文件尾追加带校验和的检查点索引与固定 48 字节尾标并二次刷盘；
 * - 崩溃恢复若发现未封口尾部残帧，仅在内存中丢弃残帧并紧接物理 EOF 追加新检查点封口，严禁调用 `truncate` 截断重写。
 */
internal class PackFileStore(
    override val path: String,
    private val key: String,
    override val size: Long,
    scope: CoroutineScope,
    durable: Boolean = true,
    private val syncChannel: ((FileChannel, Boolean) -> Unit)? = null,
) : PersistentFileStore {
    init {
        require(size >= 0L) { "Negative cache size: $size" }
    }

    private val file = File(path)
    private val count: Int = run {
        val blocks = size / PACK_BLOCK_BYTES + if (size % PACK_BLOCK_BYTES != 0L) 1L else 0L
        require(blocks <= Int.MAX_VALUE) { "Too many blocks: $blocks" }
        blocks.toInt()
    }
    private val keySha256: ByteArray = sha256Of(key)

    private val deleteMutex = Mutex()
    private val writeMutex = Mutex()
    private val ioLock = ReentrantReadWriteLock()
    private val indexLock = Any()

    private val initialRecovery = recoverOrFresh(file, key, keySha256, size, count)
    private val offsetByBlock: LongArray = initialRecovery.offsetByBlock
    private var incarnationId: Long = initialRecovery.incarnationId
    private var appendCursor: Long = initialRecovery.appendCursor
    private var headerWritten: Boolean = initialRecovery.headerWritten
    private var resetInvalidExistingFile: Boolean = initialRecovery.resetInvalidExistingFile
    private var nextRecordSeq: Long = initialRecovery.maxRecordSeq + 1L
    private var committedGeneration: Long = initialRecovery.committedGeneration
    private var needsSealCheckpoint: Boolean = initialRecovery.needsSealCheckpoint
    private var uncheckpointedBytes: Long = 0L
    private var mutationSeq: Long = 0L
    private var flushedSeq: Long = 0L
    private var heldCount: Int = initialRecovery.heldCount
    private var isDurable: Boolean = durable
    private var closed: Boolean = false
    private var writerFaulted: Throwable? = null
    private var channel: FileChannel? = null
    private var appendScratch: ByteBuffer? = null

    private val progress = MutableStateFlow(initialRecovery.heldBytes)
    override val heldBytes: StateFlow<Long> get() = progress

    override fun setDurable(durable: Boolean) {
        synchronized(indexLock) {
            isDurable = durable
        }
    }

    private var lastFlushWarnNanos = 0L

    private val persistence = scope.launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            if (!synchronized(indexLock) { isDurable && !closed }) continue
            try {
                flushInternal(metaData = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val now = System.nanoTime()
                if (lastFlushWarnNanos == 0L || now - lastFlushWarnNanos >= FLUSH_WARN_INTERVAL_NANOS) {
                    lastFlushWarnNanos = now
                    PikoLog.w(PACK_TAG, "Periodic pack cache checkpoint failed: cache=${fileCacheName(key).take(12)}, size=$size", e)
                }
            }
        }
    }

    override suspend fun read(file: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        ioLock.read {
            val payloadOffset: Long
            val ch: FileChannel
            synchronized(indexLock) {
                if (closed) return@withContext null
                val index = indexOf(offset) ?: return@withContext null
                if (length.toLong() != lengthOf(index)) return@withContext null
                payloadOffset = offsetByBlock[index]
                if (payloadOffset < 0L) return@withContext null
                ch = openChannelForReadLocked() ?: return@withContext null
            }
            val out = ByteArray(length)
            if (!readFullyAt(ch, payloadOffset, ByteBuffer.wrap(out))) return@withContext null
            out
        }
    }

    override suspend fun write(file: String, offset: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val index = requireNotNull(indexOf(offset)) { "Invalid cache block offset: $offset" }
        val expectedLength = lengthOf(index).toInt()
        require(bytes.size == expectedLength) { "Unexpected block byte size: ${bytes.size} != $expectedLength" }

        writeMutex.withLock {
            ioLock.read {
                val shouldWrite: Boolean
                val durableNow: Boolean
                val ch: FileChannel
                synchronized(indexLock) {
                    check(!closed) { "PackFileStore is closed" }
                    writerFaulted?.let { throw IOException("PackFileStore writer previously faulted", it) }
                    if (offsetByBlock[index] >= 0L) {
                        shouldWrite = false
                        durableNow = isDurable
                        ch = openChannelForWriteLocked()
                    } else {
                        shouldWrite = true
                        durableNow = isDurable
                        ch = openChannelForWriteLocked()
                    }
                }
                if (!shouldWrite) return@withContext

                try {
                    ensureHeaderWrittenLocked(ch)
                    if (needsSealCheckpoint && durableNow) {
                        writeCheckpointLocked(ch, metaData = true)
                    }

                    val recordOffset = appendCursor
                    val seq = nextRecordSeq++
                    val totalBytes = RECORD_HEADER_BYTES + bytes.size
                    val buf = obtainAppendScratchLocked(totalBytes)
                    buf.clear()
                    buf.limit(totalBytes)
                    val payloadCrc = crc32Int(bytes, 0, bytes.size)
                    putRecordHeader(
                        buf = buf,
                        recordMagic = DATA_RECORD_MAGIC,
                        incarnationId = incarnationId,
                        sequenceOrGen = seq,
                        indexOrHeldCount = index,
                        payloadBytes = bytes.size,
                        payloadCrc32 = payloadCrc,
                    )
                    buf.put(bytes)
                    buf.flip()
                    writeFullyAt(ch, recordOffset, buf)

                    appendCursor = Math.addExact(recordOffset, totalBytes.toLong())
                    uncheckpointedBytes = Math.addExact(uncheckpointedBytes, totalBytes.toLong())

                    synchronized(indexLock) {
                        check(!closed) { "PackFileStore closed during write" }
                        if (offsetByBlock[index] < 0L) {
                            offsetByBlock[index] = recordOffset + RECORD_HEADER_BYTES
                            heldCount++
                            mutationSeq++
                            progress.value += bytes.size
                        }
                    }

                    if (durableNow && uncheckpointedBytes >= CHECKPOINT_BYTE_THRESHOLD) {
                        writeCheckpointLocked(ch, metaData = false)
                    }
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
                        synchronized(indexLock) {
                            if (writerFaulted == null) writerFaulted = e
                        }
                    }
                    throw e
                }
            }
        }
    }

    override suspend fun missing(file: String, ranges: List<LongRange>): List<LongRange> = synchronized(indexLock) {
        buildList {
            for (range in ranges) {
                val start = range.first.coerceAtLeast(0L)
                val end = range.last.coerceAtMost(this@PackFileStore.size - 1L)
                if (start > end) continue
                var run = -1L
                for (index in (start / PACK_BLOCK_BYTES).toInt()..(end / PACK_BLOCK_BYTES).toInt()) {
                    val from = maxOf(start, index * PACK_BLOCK_BYTES)
                    if (offsetByBlock[index] < 0L) {
                        if (run < 0L) run = from
                    } else if (run >= 0L) {
                        add(run until from)
                        run = -1L
                    }
                }
                if (run >= 0L) add(run..end)
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
                val length = minOf(PACK_BLOCK_BYTES, size - offset)
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

    override suspend fun exportTo(destination: String) = withContext(Dispatchers.IO) {
        val to = File(destination)
        if (file.canonicalPath == to.canonicalPath) return@withContext
        to.parentFile?.mkdirs()
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val rawStagingName = ".${to.name}.$token.piko-export"
        val stagingName = if (rawStagingName.toByteArray(Charsets.UTF_8).size <= 240) rawStagingName
            else ".${fileCacheName(to.name).take(24)}.$token.piko-export"
        val staging = File(to.parentFile, stagingName)
        var moved = false
        try {
            FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                if (size > 0L) {
                    val outBuf = ByteBuffer.allocateDirect(minOf(EXPORT_BUFFER_BYTES.toLong(), size).toInt().coerceAtLeast(1))
                    var outOffset = 0L
                    ioLock.read {
                        val ch: FileChannel
                        val snapshotOffsets: LongArray
                        synchronized(indexLock) {
                            check(!closed) { "PackFileStore is closed" }
                            snapshotOffsets = offsetByBlock.copyOf()
                            for (i in 0 until count) {
                                check(snapshotOffsets[i] >= 0L) { "Missing block $i/$count during pack export" }
                            }
                            ch = checkNotNull(openChannelForReadLocked()) { "Pack cache file missing during export" }
                        }
                        for (index in 0 until count) {
                            coroutineContext.ensureActive()
                            val blockLen = lengthOf(index).toInt()
                            if (outBuf.remaining() < blockLen) {
                                outBuf.flip()
                                writeBufferAt(output, outOffset, outBuf)
                                outOffset += outBuf.limit()
                                outBuf.clear()
                            }
                            val startPos = outBuf.position()
                            outBuf.limit(startPos + blockLen)
                            val ok = readFullyAt(ch, snapshotOffsets[index], outBuf)
                            if (!ok) throw IOException("Unexpected EOF in pack cache at block $index")
                            outBuf.limit(outBuf.capacity())
                        }
                        if (outBuf.position() > 0) {
                            outBuf.flip()
                            writeBufferAt(output, outOffset, outBuf)
                        }
                    }
                }
                output.force(true)
            }
            coroutineContext.ensureActive()
            try {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) staging.delete()
        }
    }

    override fun logicalSourceFactory(): () -> RandomAccessMediaSource = {
        object : RandomAccessMediaSource {
            @Volatile
            private var sourceClosed = false

            override val size: Long get() = this@PackFileStore.size

            override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = withContext(Dispatchers.IO) {
                require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                    "Invalid buffer bounds: offset=$offset, length=$length, bufferSize=${buffer.size}"
                }
                if (sourceClosed) throw IOException("Logical pack source is closed")
                if (position < 0L) throw IOException("Negative read position: $position")
                if (length == 0) return@withContext 0
                if (position >= this@PackFileStore.size) return@withContext -1

                val targetTotal = minOf(length.toLong(), this@PackFileStore.size - position).toInt()
                var totalRead = 0
                ioLock.read {
                    val ch: FileChannel = synchronized(indexLock) {
                        if (closed) throw IOException("PackFileStore is closed")
                        openChannelForReadLocked() ?: throw IOException("Pack cache file is not available")
                    }
                    while (totalRead < targetTotal) {
                        coroutineContext.ensureActive()
                        val curLogicalPos = position + totalRead
                        val blockIdx = (curLogicalPos / PACK_BLOCK_BYTES).toInt()
                        val intraOffset = (curLogicalPos % PACK_BLOCK_BYTES).toInt()
                        val blockAvail = (lengthOf(blockIdx) - intraOffset).toInt()
                        val step = minOf(targetTotal - totalRead, blockAvail)
                        val payloadOffset = synchronized(indexLock) {
                            if (closed) throw IOException("PackFileStore is closed")
                            offsetByBlock[blockIdx]
                        }
                        if (payloadOffset < 0L) {
                            throw IOException("Missing cached block $blockIdx at logical offset $curLogicalPos")
                        }
                        val destSlice = ByteBuffer.wrap(buffer, offset + totalRead, step)
                        if (!readFullyAt(ch, payloadOffset + intraOffset, destSlice)) {
                            throw IOException("Truncated cached block $blockIdx at logical offset $curLogicalPos")
                        }
                        totalRead += step
                    }
                }
                totalRead
            }

            override fun close() {
                sourceClosed = true
            }
        }
    }

    override suspend fun flush(): Unit = flushInternal(metaData = true)

    private suspend fun flushInternal(metaData: Boolean): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ioLock.read {
                val ch: FileChannel
                val shouldWriteCheckpoint: Boolean
                synchronized(indexLock) {
                    if (closed) return@withContext
                    writerFaulted?.let { throw IOException("PackFileStore writer previously faulted", it) }
                    if (size == 0L && !headerWritten) {
                        ch = openChannelForWriteLocked()
                        shouldWriteCheckpoint = true
                    } else if (mutationSeq != flushedSeq || needsSealCheckpoint) {
                        ch = openChannelForWriteLocked()
                        shouldWriteCheckpoint = true
                    } else {
                        return@withContext
                    }
                }
                if (shouldWriteCheckpoint) {
                    try {
                        ensureHeaderWrittenLocked(ch)
                        writeCheckpointLocked(ch, metaData = metaData)
                    } catch (e: Throwable) {
                        if (e !is CancellationException) {
                            synchronized(indexLock) {
                                if (writerFaulted == null) writerFaulted = e
                            }
                        }
                        throw e
                    }
                }
            }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        persistence.cancelAndJoin()
        try {
            if (synchronized(indexLock) { isDurable && !closed && writerFaulted == null }) {
                flush()
            }
        } finally {
            writeMutex.withLock {
                ioLock.write {
                    synchronized(indexLock) {
                        closed = true
                        appendScratch = null
                        closeChannelLocked()
                    }
                }
            }
        }
    }

    override suspend fun delete(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        persistence.cancelAndJoin()
        deleteMutex.withLock {
            val cacheId = fileCacheName(key).take(12)
            writeMutex.withLock {
                ioLock.write {
                    synchronized(indexLock) {
                        closed = true
                        appendScratch = null
                        runCatching { closeChannelLocked() }
                            .logFailure(PACK_TAG, "Failed to close pack cache channel before delete: cache=$cacheId")
                        Unit
                    }
                }
            }
            evictRecoveryCache(file)
            val deleted = !file.exists() || file.delete()
            check(deleted) { "Failed to delete pack cache file" }
        }
    }

    private fun openChannelForReadLocked(): FileChannel? {
        channel?.let { return it }
        if (!file.isFile || resetInvalidExistingFile) return null
        return FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).also { channel = it }
    }

    private fun openChannelForWriteLocked(): FileChannel {
        channel?.let { return it }
        file.parentFile?.mkdirs()
        if (resetInvalidExistingFile && file.exists()) {
            evictRecoveryCache(file)
            check(file.delete() || !file.exists()) { "Failed to replace invalid pack cache file" }
            resetInvalidExistingFile = false
        }
        if (!file.exists()) {
            try {
                // 严禁传 StandardOpenOption.SPARSE：保持普通顺序文件形态
                Files.newByteChannel(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
            } catch (_: FileAlreadyExistsException) { }
        }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).also { channel = it }
    }

    private fun closeChannelLocked() {
        val current = channel
        channel = null
        appendScratch = null
        current?.close()
    }

    private fun obtainAppendScratchLocked(minBytes: Int): ByteBuffer {
        val current = appendScratch
        if (current != null && current.capacity() >= minBytes) return current
        val cap = maxOf(minBytes, RECORD_HEADER_BYTES + PACK_BLOCK_BYTES.toInt())
        return ByteBuffer.allocateDirect(cap).also { appendScratch = it }
    }

    private fun ensureHeaderWrittenLocked(ch: FileChannel) {
        if (headerWritten) return
        val buf = ByteBuffer.allocate(FILE_HEADER_BYTES)
        buf.putInt(FILE_MAGIC)
        buf.putInt(FORMAT_VERSION)
        buf.putLong(PACK_BLOCK_BYTES)
        buf.putLong(size)
        buf.putInt(count)
        buf.putInt(0)
        buf.put(keySha256)
        buf.putLong(incarnationId)
        val crc = CRC32().apply { update(buf.array(), 0, FILE_HEADER_BYTES - 8) }.value
        buf.putLong(crc)
        buf.flip()
        writeFullyAt(ch, 0L, buf)
        appendCursor = FILE_HEADER_BYTES.toLong()
        headerWritten = true
    }

    /**
     * 两阶段刷盘提交检查点：
     * 1. 先 `force` 此前追加的全部数据块，确保检查点不会引用尚未落盘的块；
     * 2. 追加检查点索引与固定 48 字节尾标后二次 `force`。
     */
    private fun writeCheckpointLocked(ch: FileChannel, metaData: Boolean) {
        syncFileChannel(ch, metaData = true)

        val snapshotOffsets: LongArray
        val snapshotHeldCount: Int
        val snapshotHeldBytes: Long
        val targetSeq: Long
        val nextGen: Long
        synchronized(indexLock) {
            snapshotOffsets = offsetByBlock.copyOf()
            snapshotHeldCount = heldCount
            snapshotHeldBytes = progress.value
            targetSeq = mutationSeq
            nextGen = Math.addExact(committedGeneration, 1L)
        }

        val checkpointOffset = appendCursor
        val sparseBytes = Math.multiplyExact(snapshotHeldCount.toLong(), 12L)
        val denseBytes = Math.multiplyExact(count.toLong(), 8L)
        val useSparse = sparseBytes <= denseBytes
        val mapBytesLong = if (useSparse) sparseBytes else denseBytes
        val payloadBytesLong = CHECKPOINT_BODY_PREFIX_BYTES.toLong() + mapBytesLong + FOOTER_BYTES.toLong()
        check(payloadBytesLong <= MAX_CHECKPOINT_PAYLOAD_BYTES.toLong()) {
            "Checkpoint payload exceeds limit: $payloadBytesLong B"
        }
        val payloadBytes = payloadBytesLong.toInt()
        val recordTotalBytes = Math.addExact(RECORD_HEADER_BYTES, payloadBytes)

        val payload = ByteArray(payloadBytes)
        val pBuf = ByteBuffer.wrap(payload)
        pBuf.put(if (useSparse) ENCODING_SPARSE else ENCODING_DENSE)
        pBuf.put(0)
        pBuf.put(0)
        pBuf.put(0)
        pBuf.putInt(if (useSparse) snapshotHeldCount else count)
        pBuf.putLong(snapshotHeldBytes)
        pBuf.putLong(checkpointOffset)
        if (useSparse) {
            var writtenEntries = 0
            for (i in 0 until count) {
                val off = snapshotOffsets[i]
                if (off >= 0L) {
                    pBuf.putInt(i)
                    pBuf.putLong(off)
                    writtenEntries++
                }
            }
            check(writtenEntries == snapshotHeldCount)
        } else {
            for (i in 0 until count) {
                pBuf.putLong(snapshotOffsets[i])
            }
        }

        val footerStart = payloadBytes - FOOTER_BYTES
        check(pBuf.position() == footerStart)
        pBuf.putInt(FOOTER_MAGIC)
        pBuf.putInt(FORMAT_VERSION)
        pBuf.putLong(incarnationId)
        pBuf.putLong(nextGen)
        pBuf.putLong(checkpointOffset)
        pBuf.putLong(snapshotHeldBytes)
        pBuf.putInt(recordTotalBytes)
        val footerCrc = crc32Int(payload, footerStart, FOOTER_BYTES - 4)
        pBuf.putInt(footerCrc)
        check(!pBuf.hasRemaining())

        val payloadCrc = crc32Int(payload, 0, payloadBytes)
        val headerBuf = ByteBuffer.allocate(RECORD_HEADER_BYTES)
        putRecordHeader(
            buf = headerBuf,
            recordMagic = CHECKPOINT_MAGIC,
            incarnationId = incarnationId,
            sequenceOrGen = nextGen,
            indexOrHeldCount = snapshotHeldCount,
            payloadBytes = payloadBytes,
            payloadCrc32 = payloadCrc,
        )
        headerBuf.flip()

        writeFullyAt(ch, checkpointOffset, headerBuf)
        writeFullyAt(ch, checkpointOffset + RECORD_HEADER_BYTES, ByteBuffer.wrap(payload))
        appendCursor = Math.addExact(checkpointOffset, recordTotalBytes.toLong())
        syncFileChannel(ch, metaData = metaData || true)

        synchronized(indexLock) {
            committedGeneration = nextGen
            uncheckpointedBytes = 0L
            needsSealCheckpoint = false
            if (flushedSeq < targetSeq) flushedSeq = targetSeq
        }
        evictRecoveryCache(file)
    }

    private fun syncFileChannel(target: FileChannel, metaData: Boolean) {
        val custom = syncChannel
        if (custom != null) custom(target, metaData) else target.force(metaData)
    }

    private fun indexOf(offset: Long): Int? = (offset / PACK_BLOCK_BYTES).toInt()
        .takeIf { offset >= 0L && offset % PACK_BLOCK_BYTES == 0L && it in 0 until count }

    private fun lengthOf(index: Int): Long = lengthOfBlock(size, index)

    internal data class FileHeaderInfo(
        val logicalSize: Long,
        val count: Int,
        val incarnationId: Long,
    )

    internal data class ParsedCheckpoint(
        val generation: Long,
        val checkpointOffset: Long,
        val endOffset: Long,
        val heldCount: Int,
        val heldBytes: Long,
        val offsetByBlock: LongArray?,
    )

    internal data class RecoveredPack(
        val offsetByBlock: LongArray,
        val heldCount: Int,
        val heldBytes: Long,
        val incarnationId: Long,
        val appendCursor: Long,
        val headerWritten: Boolean,
        val resetInvalidExistingFile: Boolean,
        val maxRecordSeq: Long,
        val committedGeneration: Long,
        val needsSealCheckpoint: Boolean,
    )

    private data class CacheStamp(
        val canonicalPath: String,
        val fileLength: Long,
        val lastModified: Long,
        val key: String,
        val expectedSize: Long,
    )

    companion object {
        private val rng = SecureRandom()
        private val recoveryCacheLock = Any()
        private val recoveryCache = object : LinkedHashMap<CacheStamp, RecoveredPack>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheStamp, RecoveredPack>?): Boolean = size > 16
        }

        internal fun evictRecoveryCache(file: File) {
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
            synchronized(recoveryCacheLock) {
                val it = recoveryCache.keys.iterator()
                while (it.hasNext()) {
                    if (it.next().canonicalPath == canonical) it.remove()
                }
            }
        }

        private fun nextIncarnationId(): Long {
            while (true) {
                val candidate = rng.nextLong()
                if (candidate != 0L) return candidate
            }
        }

        private fun sha256Of(key: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))

        private fun lengthOfBlock(totalSize: Long, index: Int): Long =
            minOf(PACK_BLOCK_BYTES, totalSize - index.toLong() * PACK_BLOCK_BYTES)

        private fun crc32Int(bytes: ByteArray, offset: Int, length: Int): Int =
            CRC32().apply { update(bytes, offset, length) }.value.toInt()

        private fun putRecordHeader(
            buf: ByteBuffer,
            recordMagic: Int,
            incarnationId: Long,
            sequenceOrGen: Long,
            indexOrHeldCount: Int,
            payloadBytes: Int,
            payloadCrc32: Int,
        ) {
            val tmp = ByteArray(RECORD_HEADER_BYTES)
            val h = ByteBuffer.wrap(tmp)
            h.putInt(recordMagic)
            h.putLong(incarnationId)
            h.putLong(sequenceOrGen)
            h.putInt(indexOrHeldCount)
            h.putInt(payloadBytes)
            h.putInt(payloadCrc32)
            h.putInt(0)
            val headerCrc = crc32Int(tmp, 0, RECORD_HEADER_BYTES - 4)
            h.putInt(headerCrc)
            buf.put(tmp)
        }

        internal fun persistedBytes(file: File, key: String, size: Long): Long {
            if (size < 0L || !file.isFile) return 0L
            val blocks = size / PACK_BLOCK_BYTES + if (size % PACK_BLOCK_BYTES != 0L) 1L else 0L
            if (blocks > Int.MAX_VALUE) return 0L
            val count = blocks.toInt()
            val keySha256 = sha256Of(key)

            val stamp = runCatching {
                CacheStamp(file.canonicalPath, file.length(), file.lastModified(), key, size)
            }.getOrNull()
            if (stamp != null) {
                synchronized(recoveryCacheLock) { recoveryCache[stamp] }?.let { return it.heldBytes }
            }

            return try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                    val channelSize = ch.size()
                    val header = parseFileHeader(ch, channelSize, keySha256, size, count) ?: return 0L
                    // 干净收尾快速路径：仅校验 EOF 尾标与对应检查点 CRC，不分配完整块表
                    if (channelSize >= FILE_HEADER_BYTES + RECORD_HEADER_BYTES + FOOTER_BYTES) {
                        val fast = tryParseCheckpointAtFooter(
                            ch = ch,
                            footerOffset = channelSize - FOOTER_BYTES,
                            header = header,
                            summaryOnly = true,
                        )
                        if (fast != null && fast.endOffset == channelSize) {
                            return fast.heldBytes
                        }
                    }
                    val full = recoverFromOpenChannel(ch, channelSize, header, file, key)
                    if (stamp != null) {
                        synchronized(recoveryCacheLock) { recoveryCache[stamp] = full }
                    }
                    full.heldBytes
                }
            } catch (e: Throwable) {
                PikoLog.w(PACK_TAG, "Failed to query persisted pack cache bytes: cache=${fileCacheName(key).take(12)}", e)
                0L
            }
        }

        internal fun recoverOrFresh(
            file: File,
            key: String,
            keySha256: ByteArray,
            size: Long,
            count: Int,
        ): RecoveredPack {
            if (!file.exists()) {
                return freshEmpty(count, resetExisting = false)
            }
            val stamp = runCatching {
                CacheStamp(file.canonicalPath, file.length(), file.lastModified(), key, size)
            }.getOrNull()
            if (stamp != null) {
                synchronized(recoveryCacheLock) { recoveryCache.remove(stamp) }?.let { cached ->
                    return cached.copy(offsetByBlock = cached.offsetByBlock.copyOf())
                }
            }
            return try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                    val channelSize = ch.size()
                    if (channelSize == 0L) {
                        return freshEmpty(count, resetExisting = false)
                    }
                    val header = parseFileHeader(ch, channelSize, keySha256, size, count)
                        ?: run {
                            PikoLog.w(
                                PACK_TAG,
                                "Discarding invalid or mismatched pack cache header (${channelSize} B): cache=${fileCacheName(key).take(12)}, expectedSize=$size",
                            )
                            return freshEmpty(count, resetExisting = true)
                        }
                    recoverFromOpenChannel(ch, channelSize, header, file, key)
                }
            } catch (e: Throwable) {
                PikoLog.w(PACK_TAG, "Failed to recover pack cache, starting fresh: cache=${fileCacheName(key).take(12)}", e)
                freshEmpty(count, resetExisting = file.exists())
            }
        }

        private fun freshEmpty(count: Int, resetExisting: Boolean): RecoveredPack = RecoveredPack(
            offsetByBlock = LongArray(count) { -1L },
            heldCount = 0,
            heldBytes = 0L,
            incarnationId = nextIncarnationId(),
            appendCursor = 0L,
            headerWritten = false,
            resetInvalidExistingFile = resetExisting,
            maxRecordSeq = 0L,
            committedGeneration = 0L,
            needsSealCheckpoint = false,
        )

        private fun parseFileHeader(
            ch: FileChannel,
            channelSize: Long,
            expectedKeySha256: ByteArray,
            expectedSize: Long,
            expectedCount: Int,
        ): FileHeaderInfo? {
            if (channelSize < FILE_HEADER_BYTES.toLong()) return null
            val raw = ByteArray(FILE_HEADER_BYTES)
            if (!readFullyAt(ch, 0L, ByteBuffer.wrap(raw))) return null
            val expectedCrc = ByteBuffer.wrap(raw, FILE_HEADER_BYTES - 8, 8).getLong()
            val actualCrc = CRC32().apply { update(raw, 0, FILE_HEADER_BYTES - 8) }.value
            if (actualCrc != expectedCrc) return null

            val buf = ByteBuffer.wrap(raw, 0, FILE_HEADER_BYTES - 8)
            if (buf.getInt() != FILE_MAGIC) return null
            if (buf.getInt() != FORMAT_VERSION) return null
            if (buf.getLong() != PACK_BLOCK_BYTES) return null
            val storedSize = buf.getLong()
            if (storedSize != expectedSize) return null
            val storedCount = buf.getInt()
            if (storedCount != expectedCount) return null
            buf.getInt() // reserved
            val storedSha = ByteArray(32).also { buf.get(it) }
            if (!storedSha.contentEquals(expectedKeySha256)) return null
            val incarnationId = buf.getLong()
            if (incarnationId == 0L) return null
            return FileHeaderInfo(storedSize, storedCount, incarnationId)
        }

        private fun recoverFromOpenChannel(
            ch: FileChannel,
            channelSize: Long,
            header: FileHeaderInfo,
            file: File,
            key: String,
        ): RecoveredPack {
            val checkpoint = findLatestValidCheckpoint(ch, channelSize, header)
            val offsets = checkpoint?.offsetByBlock ?: LongArray(header.count) { -1L }
            var heldCount = checkpoint?.heldCount ?: 0
            var heldBytes = checkpoint?.heldBytes ?: 0L
            var maxGen = checkpoint?.generation ?: 0L
            var maxSeq = heldCount.toLong()

            val scanStart = checkpoint?.endOffset ?: FILE_HEADER_BYTES.toLong()
            var pos = scanStart
            var hadTornTail = false

            val recHeaderBytes = ByteArray(RECORD_HEADER_BYTES)
            while (pos < channelSize) {
                if (channelSize - pos < RECORD_HEADER_BYTES.toLong()) {
                    hadTornTail = true
                    break
                }
                if (!readFullyAt(ch, pos, ByteBuffer.wrap(recHeaderBytes))) {
                    hadTornTail = true
                    break
                }
                val expectedHeaderCrc = ByteBuffer.wrap(recHeaderBytes, RECORD_HEADER_BYTES - 4, 4).getInt()
                val actualHeaderCrc = crc32Int(recHeaderBytes, 0, RECORD_HEADER_BYTES - 4)
                if (actualHeaderCrc != expectedHeaderCrc) {
                    hadTornTail = true
                    break
                }
                val h = ByteBuffer.wrap(recHeaderBytes, 0, RECORD_HEADER_BYTES - 4)
                val magic = h.getInt()
                val inc = h.getLong()
                val seqOrGen = h.getLong()
                val indexOrHeld = h.getInt()
                val payloadLen = h.getInt()
                val expectedPayloadCrc = h.getInt()
                if (inc != header.incarnationId || seqOrGen <= 0L || payloadLen < 0 || payloadLen > MAX_CHECKPOINT_PAYLOAD_BYTES) {
                    hadTornTail = true
                    break
                }
                val totalRecordBytes = RECORD_HEADER_BYTES.toLong() + payloadLen.toLong()
                if (pos > channelSize - totalRecordBytes) {
                    hadTornTail = true
                    break
                }
                val payload = ByteArray(payloadLen)
                if (!readFullyAt(ch, pos + RECORD_HEADER_BYTES, ByteBuffer.wrap(payload))) {
                    hadTornTail = true
                    break
                }
                if (crc32Int(payload, 0, payloadLen) != expectedPayloadCrc) {
                    hadTornTail = true
                    break
                }
                when (magic) {
                    DATA_RECORD_MAGIC -> {
                        if (indexOrHeld !in 0 until header.count) {
                            hadTornTail = true
                            break
                        }
                        val expectedBlockBytes = lengthOfBlock(header.logicalSize, indexOrHeld)
                        if (payloadLen.toLong() != expectedBlockBytes) {
                            hadTornTail = true
                            break
                        }
                        if (offsets[indexOrHeld] < 0L) {
                            heldCount++
                            heldBytes += expectedBlockBytes
                        }
                        offsets[indexOrHeld] = pos + RECORD_HEADER_BYTES
                        if (seqOrGen > maxSeq) maxSeq = seqOrGen
                    }
                    CHECKPOINT_MAGIC -> {
                        if (seqOrGen > maxGen) maxGen = seqOrGen
                    }
                    else -> {
                        hadTornTail = true
                        break
                    }
                }
                pos += totalRecordBytes
            }

            if (hadTornTail) {
                PikoLog.w(
                    PACK_TAG,
                    "Abandoned torn tail in pack cache without truncating (validUntil=$pos, fileLength=$channelSize): " +
                        "cache=${fileCacheName(key).take(12)}, file=${file.name.take(12)}",
                )
            }
            val uncheckpointedRecords = pos > scanStart
            return RecoveredPack(
                offsetByBlock = offsets,
                heldCount = heldCount,
                heldBytes = heldBytes,
                incarnationId = header.incarnationId,
                appendCursor = channelSize,
                headerWritten = true,
                resetInvalidExistingFile = false,
                maxRecordSeq = maxSeq,
                committedGeneration = maxGen,
                needsSealCheckpoint = hadTornTail || uncheckpointedRecords,
            )
        }

        private fun findLatestValidCheckpoint(
            ch: FileChannel,
            channelSize: Long,
            header: FileHeaderInfo,
        ): ParsedCheckpoint? {
            val minFooterOffset = FILE_HEADER_BYTES.toLong() + RECORD_HEADER_BYTES.toLong() + CHECKPOINT_BODY_PREFIX_BYTES.toLong()
            if (channelSize < minFooterOffset + FOOTER_BYTES) return null

            // 先试文件末尾的快速检查点
            tryParseCheckpointAtFooter(ch, channelSize - FOOTER_BYTES, header, summaryOnly = false)?.let { return it }

            var windowEnd = channelSize
            val overlap = FOOTER_BYTES - 1
            while (windowEnd >= minFooterOffset + FOOTER_BYTES) {
                val windowStart = maxOf(minFooterOffset, windowEnd - BACKWARD_SCAN_WINDOW_BYTES.toLong())
                val len = (windowEnd - windowStart).toInt()
                val buf = ByteArray(len)
                if (!readFullyAt(ch, windowStart, ByteBuffer.wrap(buf))) return null

                var i = len - FOOTER_BYTES
                while (i >= 0) {
                    if (
                        buf[i] == 0x50.toByte() &&
                        buf[i + 1] == 0x4B.toByte() &&
                        buf[i + 2] == 0x46.toByte() &&
                        buf[i + 3] == 0x54.toByte()
                    ) {
                        val candidateOffset = windowStart + i.toLong()
                        tryParseCheckpointAtFooter(ch, candidateOffset, header, summaryOnly = false)?.let { return it }
                    }
                    i--
                }

                if (windowStart == minFooterOffset) break
                windowEnd = windowStart + overlap.toLong()
            }
            return null
        }

        private fun tryParseCheckpointAtFooter(
            ch: FileChannel,
            footerOffset: Long,
            header: FileHeaderInfo,
            summaryOnly: Boolean,
        ): ParsedCheckpoint? {
            val footerBytes = ByteArray(FOOTER_BYTES)
            if (!readFullyAt(ch, footerOffset, ByteBuffer.wrap(footerBytes))) return null
            val expectedFooterCrc = ByteBuffer.wrap(footerBytes, FOOTER_BYTES - 4, 4).getInt()
            if (crc32Int(footerBytes, 0, FOOTER_BYTES - 4) != expectedFooterCrc) return null

            val f = ByteBuffer.wrap(footerBytes, 0, FOOTER_BYTES - 4)
            if (f.getInt() != FOOTER_MAGIC) return null
            if (f.getInt() != FORMAT_VERSION) return null
            if (f.getLong() != header.incarnationId) return null
            val generation = f.getLong()
            val checkpointOffset = f.getLong()
            val footerHeldBytes = f.getLong()
            val recordTotalBytes = f.getInt()

            if (generation <= 0L || footerHeldBytes < 0L || footerHeldBytes > header.logicalSize) return null
            if (recordTotalBytes < RECORD_HEADER_BYTES + CHECKPOINT_BODY_PREFIX_BYTES + FOOTER_BYTES) return null
            val payloadBytes = recordTotalBytes - RECORD_HEADER_BYTES
            if (payloadBytes > MAX_CHECKPOINT_PAYLOAD_BYTES) return null
            if (checkpointOffset < FILE_HEADER_BYTES.toLong()) return null
            val endOffset = footerOffset + FOOTER_BYTES.toLong()
            if (checkpointOffset + recordTotalBytes.toLong() != endOffset) return null

            val recHeader = ByteArray(RECORD_HEADER_BYTES)
            if (!readFullyAt(ch, checkpointOffset, ByteBuffer.wrap(recHeader))) return null
            val expectedRecHeaderCrc = ByteBuffer.wrap(recHeader, RECORD_HEADER_BYTES - 4, 4).getInt()
            if (crc32Int(recHeader, 0, RECORD_HEADER_BYTES - 4) != expectedRecHeaderCrc) return null

            val rh = ByteBuffer.wrap(recHeader, 0, RECORD_HEADER_BYTES - 4)
            if (rh.getInt() != CHECKPOINT_MAGIC) return null
            if (rh.getLong() != header.incarnationId) return null
            if (rh.getLong() != generation) return null
            val headerHeldCount = rh.getInt()
            val headerPayloadBytes = rh.getInt()
            val expectedPayloadCrc = rh.getInt()
            if (headerHeldCount !in 0..header.count || headerPayloadBytes != payloadBytes) return null

            val payload = ByteArray(payloadBytes)
            if (!readFullyAt(ch, checkpointOffset + RECORD_HEADER_BYTES, ByteBuffer.wrap(payload))) return null
            if (crc32Int(payload, 0, payloadBytes) != expectedPayloadCrc) return null

            val pb = ByteBuffer.wrap(payload, 0, payloadBytes - FOOTER_BYTES)
            val mode = pb.get()
            pb.get(); pb.get(); pb.get()
            val entryCount = pb.getInt()
            val bodyHeldBytes = pb.getLong()
            val coveredOffset = pb.getLong()
            if (bodyHeldBytes != footerHeldBytes || coveredOffset != checkpointOffset) return null

            when (mode) {
                ENCODING_SPARSE -> {
                    if (entryCount != headerHeldCount) return null
                    if (pb.remaining().toLong() != entryCount.toLong() * 12L) return null
                    if (summaryOnly) {
                        return ParsedCheckpoint(generation, checkpointOffset, endOffset, headerHeldCount, footerHeldBytes, null)
                    }
                    val offsets = LongArray(header.count) { -1L }
                    var computedBytes = 0L
                    for (k in 0 until entryCount) {
                        val idx = pb.getInt()
                        val off = pb.getLong()
                        if (idx !in 0 until header.count || offsets[idx] >= 0L) return null
                        val blockLen = lengthOfBlock(header.logicalSize, idx)
                        if (off < FILE_HEADER_BYTES + RECORD_HEADER_BYTES || off > checkpointOffset - blockLen) return null
                        offsets[idx] = off
                        computedBytes += blockLen
                    }
                    if (computedBytes != footerHeldBytes) return null
                    return ParsedCheckpoint(generation, checkpointOffset, endOffset, headerHeldCount, footerHeldBytes, offsets)
                }
                ENCODING_DENSE -> {
                    if (entryCount != header.count) return null
                    if (pb.remaining().toLong() != header.count.toLong() * 8L) return null
                    if (summaryOnly) {
                        return ParsedCheckpoint(generation, checkpointOffset, endOffset, headerHeldCount, footerHeldBytes, null)
                    }
                    val offsets = LongArray(header.count)
                    var computedCount = 0
                    var computedBytes = 0L
                    for (idx in 0 until header.count) {
                        val off = pb.getLong()
                        if (off == -1L) {
                            offsets[idx] = -1L
                        } else {
                            val blockLen = lengthOfBlock(header.logicalSize, idx)
                            if (off < FILE_HEADER_BYTES + RECORD_HEADER_BYTES || off > checkpointOffset - blockLen) return null
                            offsets[idx] = off
                            computedCount++
                            computedBytes += blockLen
                        }
                    }
                    if (computedCount != headerHeldCount || computedBytes != footerHeldBytes) return null
                    return ParsedCheckpoint(generation, checkpointOffset, endOffset, headerHeldCount, footerHeldBytes, offsets)
                }
                else -> return null
            }
        }

        private fun readFullyAt(ch: FileChannel, offset: Long, dst: ByteBuffer): Boolean {
            var pos = offset
            while (dst.hasRemaining()) {
                val n = ch.read(dst, pos)
                if (n < 0) return false
                pos += n
            }
            return true
        }

        private fun writeFullyAt(ch: FileChannel, offset: Long, src: ByteBuffer) {
            var pos = offset
            while (src.hasRemaining()) {
                pos += ch.write(src, pos)
            }
        }

        private fun writeBufferAt(ch: FileChannel, offset: Long, src: ByteBuffer) {
            writeFullyAt(ch, offset, src)
        }
    }
}
