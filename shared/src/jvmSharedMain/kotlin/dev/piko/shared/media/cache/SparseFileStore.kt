package dev.piko.shared.media.cache

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.PikPakStreamReader
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write

actual fun fileCacheName(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

actual fun persistentFileStore(path: String, key: String, size: Long, scope: CoroutineScope, durable: Boolean): PersistentFileStore =
    if (path.endsWith(".pack")) PackFileStore(path, key, size, scope, durable = durable)
    else SparseFileStore(path, key, size, scope, durable = durable)

actual suspend fun copyCachedFile(source: String, destination: String) = withContext(Dispatchers.IO) {
    val from = File(source)
    val to = File(destination)
    if (from.canonicalPath != to.canonicalPath) {
        to.parentFile?.mkdirs()
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val rawStagingName = ".${to.name}.$token.piko-export"
        val stagingName = if (rawStagingName.toByteArray(Charsets.UTF_8).size <= 240) rawStagingName
            else ".${fileCacheName(to.name).take(24)}.$token.piko-export"
        val staging = File(to.parentFile, stagingName)
        var moved = false
        try {
            FileInputStream(from).channel.use { input ->
                FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    val total = input.size()
                    var offset = 0L
                    var fallbackBuffer: ByteBuffer? = null
                    while (offset < total) {
                        coroutineContext.ensureActive()
                        val chunk = minOf(COPY_CHUNK_BYTES, total - offset)
                        val transferred = input.transferTo(offset, chunk, output)
                        if (transferred > 0L) {
                            offset += transferred
                        } else {
                            val buffer = fallbackBuffer ?: ByteBuffer.allocateDirect(64 * 1024).also { fallbackBuffer = it }
                            buffer.clear()
                            buffer.limit(minOf(buffer.capacity().toLong(), total - offset).toInt())
                            val read = input.read(buffer, offset)
                            if (read < 0) break
                            buffer.flip()
                            while (buffer.hasRemaining()) {
                                output.write(buffer)
                            }
                            offset += read
                        }
                    }
                    output.force(true)
                }
            }
            coroutineContext.ensureActive()
            try {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            moved = true
        } finally {
            if (!moved) staging.delete()
        }
    }
    Unit
}

actual suspend fun persistedFileBytes(path: String, key: String, size: Long): Long = withContext(Dispatchers.IO) {
    if (path.endsWith(".pack")) {
        PackFileStore.persistedBytes(File(path), key, size)
    } else {
        SparseFileStore.readBits(File(path), key, size).mapIndexed { index, held ->
            if (held) minOf(BLOCK_BYTES, size - index * BLOCK_BYTES) else 0L
        }.sum()
    }
}

actual suspend fun ownedCacheFiles(examplePath: String): List<String> = withContext(Dispatchers.IO) {
    val parent = File(examplePath).parentFile
    if (examplePath.endsWith(".pack")) {
        parent?.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.pack"))
        }.map { it.path }.distinct()
    } else {
        parent?.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.data(?:\\.blocks(?:\\.tmp)?)?"))
        }.map { it.path.removeSuffix(".tmp").removeSuffix(".blocks") }.distinct()
    }
}

private fun deleteSparseFilesOnDisk(path: String) {
    val tempBitmap = File("$path.blocks.tmp")
    if (tempBitmap.exists()) tempBitmap.delete()
    val bitmapFile = File("$path.blocks")
    check(!bitmapFile.exists() || bitmapFile.delete()) { "Failed to delete sparse cache bitmap file" }
    val dataFile = File(path)
    check(!dataFile.exists() || dataFile.delete()) { "Failed to delete sparse cache data file" }
}

actual suspend fun deleteCacheFiles(path: String) = withContext(Dispatchers.IO) {
    if (path.endsWith(".pack")) {
        val packFile = File(path)
        PackFileStore.evictRecoveryCache(packFile)
        check(!packFile.exists() || packFile.delete()) { "Failed to delete pack cache file" }
    } else {
        deleteSparseFilesOnDisk(path)
    }
}

private const val BLOCK_BYTES = 256L * 1024
private const val COPY_CHUNK_BYTES = 8L * 1024 * 1024
private const val SLOT_ALIGNMENT_BYTES = 4096
private const val MAGIC_V1 = 0x50494b31
private const val MAGIC_V2 = 0x50494b32
private const val TAG = "SparseCache"
private const val FLUSH_WARN_INTERVAL_NANOS = 60_000_000_000L

/** 数据同步成功后才发布位图；使用双槽乒乓直写 .blocks，避免定期写临时文件再原子覆盖。 */
internal class SparseFileStore(
    override val path: String,
    private val key: String,
    override val size: Long,
    scope: CoroutineScope,
    durable: Boolean = true,
    private val syncBitmap: ((RandomAccessFile, Boolean) -> Unit)? = null,
    private val syncData: ((RandomAccessFile) -> Unit)? = null,
) : PersistentFileStore {
    private val file = File(path)
    private val bitmap = File("$path.blocks")
    private val lock = Any()
    private val flushLock = Any()
    private val ioLock = ReentrantReadWriteLock()
    private val deleteMutex = Mutex()
    private val layout = BitmapLayout(key, size)
    private val recovery = recoverBitmap(file, key, size)
    private val bits = recovery.bits
    private var mutationSeq = 0L
    private var flushedSeq = 0L
    private var committedGeneration = recovery.committedGeneration
    private var committedSlot = recovery.committedSlot
    private var needsBitmapSanitize = recovery.needsSanitize
    private var isDurable = durable
    private var needsMetadataSync = false
    private var closed = false
    private var handle: RandomAccessFile? = null
    private var bitmapHandle: RandomAccessFile? = null
    private val progress = MutableStateFlow(bits.mapIndexed { index, held -> if (held) lengthOf(index) else 0L }.sum())
    override val heldBytes: StateFlow<Long> get() = progress

    override fun setDurable(durable: Boolean) {
        synchronized(lock) {
            isDurable = durable
        }
    }

    private var lastFlushWarnNanos = 0L

    private val persistence = scope.launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            if (!synchronized(lock) { isDurable && !closed }) continue
            try {
                flushInternal(metaData = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val now = System.nanoTime()
                if (lastFlushWarnNanos == 0L || now - lastFlushWarnNanos >= FLUSH_WARN_INTERVAL_NANOS) {
                    lastFlushWarnNanos = now
                    PikoLog.w(TAG, "Periodic sparse cache bitmap flush failed: cache=${fileCacheName(key).take(12)}, size=$size", e)
                }
            }
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
        val index = requireNotNull(indexOf(offset)) { "Invalid cache block offset: $offset" }
        require(bytes.size.toLong() == lengthOf(index))
        if (synchronized(lock) { needsBitmapSanitize }) {
            sanitizeBitmapBeforeWrite()
        }
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
                    mutationSeq++
                    needsMetadataSync = true
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

    /** 写数据前若恢复阶段发现失效或截断位图，先将裁剪后的有效位落到双槽并淘汰旧槽，防止 .data 扩长后旧位复活。 */
    private fun sanitizeBitmapBeforeWrite() {
        synchronized(flushLock) {
            ioLock.read {
                val snapshot: BooleanArray
                val gen: Long
                val bmp: RandomAccessFile
                synchronized(lock) {
                    check(!closed)
                    if (!needsBitmapSanitize) return
                    snapshot = bits.copyOf()
                    gen = Math.addExact(maxOf(committedGeneration, 0L), 1L)
                    bmp = openBitmapLocked()
                }
                val slot1Offset = layout.slotStride.toLong()
                if (bmp.channel.size() > slot1Offset) {
                    writeFullyAt(bmp.channel, slot1Offset, ByteArray(16))
                }
                val encoded = encodeV2Slot(layout, gen, snapshot)
                writeFullyAt(bmp.channel, 0L, encoded)
                syncBitmapHandle(bmp, metaData = true)
                synchronized(lock) {
                    committedSlot = 0
                    committedGeneration = gen
                    needsBitmapSanitize = false
                }
            }
        }
    }

    override suspend fun flush(): Unit = flushInternal(metaData = true)

    private suspend fun flushInternal(metaData: Boolean): Unit = withContext(Dispatchers.IO) {
        synchronized(flushLock) {
            ioLock.read {
                var dataOnlyHandle: RandomAccessFile? = null
                var dataHandleToSync: RandomAccessFile? = null
                var snapshot: BooleanArray? = null
                var targetSlot = 0
                var candidateGen = 0L
                var bmp: RandomAccessFile? = null
                var retireOpposite = false
                val targetSeq: Long
                synchronized(lock) {
                    if (size == 0L && !closed && handle == null) {
                        open()
                        mutationSeq++
                        needsMetadataSync = true
                    }
                    if (closed) return@withContext
                    targetSeq = mutationSeq
                    if (targetSeq == flushedSeq && !needsBitmapSanitize) {
                        if (metaData && needsMetadataSync && handle != null) {
                            dataOnlyHandle = checkNotNull(handle)
                        } else {
                            return@withContext
                        }
                    } else {
                        dataHandleToSync = handle
                        snapshot = bits.copyOf()
                        targetSlot = if (committedSlot == 0) 1 else 0
                        candidateGen = Math.addExact(maxOf(committedGeneration, 0L), 1L)
                        bmp = openBitmapLocked()
                        retireOpposite = needsBitmapSanitize
                    }
                }
                dataOnlyHandle?.let { syncOnly ->
                    syncFileHandle(syncOnly, metaData = true)
                    synchronized(lock) {
                        if (mutationSeq == targetSeq) needsMetadataSync = false
                    }
                    return@withContext
                }
                val targetBmp = checkNotNull(bmp)
                val bitsToCommit = checkNotNull(snapshot)
                dataHandleToSync?.let { syncFileHandle(it, metaData) }
                if (retireOpposite) {
                    val oppositeOffset = (1 - targetSlot).toLong() * layout.slotStride.toLong()
                    if (targetBmp.channel.size() > oppositeOffset) {
                        writeFullyAt(targetBmp.channel, oppositeOffset, ByteArray(16))
                    }
                }
                val slotOffset = targetSlot.toLong() * layout.slotStride.toLong()
                writeFullyAt(targetBmp.channel, slotOffset, encodeV2Slot(layout, candidateGen, bitsToCommit))
                syncBitmapHandle(targetBmp, metaData = true)
                synchronized(lock) {
                    committedSlot = targetSlot
                    committedGeneration = candidateGen
                    needsBitmapSanitize = false
                    if (flushedSeq < targetSeq) flushedSeq = targetSeq
                    if (metaData && mutationSeq == targetSeq) needsMetadataSync = false
                }
            }
        }
    }

    private fun syncFileHandle(targetHandle: RandomAccessFile, metaData: Boolean) {
        val custom = syncData
        if (custom != null) custom(targetHandle) else targetHandle.channel.force(metaData)
    }

    private fun syncBitmapHandle(targetBitmap: RandomAccessFile, metaData: Boolean) {
        val custom = syncBitmap
        if (custom != null) custom(targetBitmap, metaData) else targetBitmap.channel.force(metaData)
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        persistence.cancelAndJoin()
        try {
            if (synchronized(lock) { isDurable && !closed }) {
                flush()
            }
        } finally {
            ioLock.write {
                synchronized(lock) {
                    closed = true
                    closeHandlesLocked()
                }
            }
        }
    }

    override suspend fun delete(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        persistence.cancelAndJoin()
        deleteMutex.withLock {
            val cacheId = fileCacheName(key).take(12)
            ioLock.write {
                synchronized(lock) {
                    closed = true
                    needsMetadataSync = false
                    runCatching { closeHandlesLocked() }
                        .logFailure(TAG, "Failed to close sparse cache handles before delete: cache=$cacheId")
                    Unit
                }
            }
            deleteSparseFilesOnDisk(path)
        }
    }

    private fun closeHandlesLocked() {
        var firstError: Throwable? = null
        val dataHandle = handle
        handle = null
        if (dataHandle != null) {
            try {
                dataHandle.close()
            } catch (e: Throwable) {
                firstError = e
            }
        }
        val bmpHandle = bitmapHandle
        bitmapHandle = null
        if (bmpHandle != null) {
            try {
                bmpHandle.close()
            } catch (e: Throwable) {
                if (firstError == null) firstError = e else firstError.addSuppressed(e)
            }
        }
        if (firstError != null) throw firstError
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

    private fun openBitmapLocked(): RandomAccessFile {
        check(!closed)
        return bitmapHandle ?: run {
            bitmap.parentFile?.mkdirs()
            RandomAccessFile(bitmap, "rw").also { bitmapHandle = it }
        }
    }

    private fun indexOf(offset: Long): Int? = (offset / BLOCK_BYTES).toInt()
        .takeIf { offset >= 0 && offset % BLOCK_BYTES == 0L && it in bits.indices }
    private fun lengthOf(index: Int): Long = minOf(BLOCK_BYTES, size - index * BLOCK_BYTES)

    internal class BitmapLayout(key: String, val size: Long) {
        val count: Int
        val packedBytes: Int
        val keyUtf8: ByteArray = key.toByteArray(Charsets.UTF_8)
        val v2PayloadBytes: Int
        val v2RecordBytes: Int
        val v1KeyFramed: ByteArray?
        val v1RecordBytes: Int?
        val slotStride: Int

        init {
            require(size >= 0L)
            val blocks = size / BLOCK_BYTES + if (size % BLOCK_BYTES != 0L) 1L else 0L
            require(blocks <= Int.MAX_VALUE)
            count = blocks.toInt()
            packedBytes = ((count.toLong() + 7L) / 8L).toInt()
            v2PayloadBytes = Math.addExact(36, Math.addExact(keyUtf8.size, packedBytes))
            v2RecordBytes = Math.addExact(v2PayloadBytes, 8)
            v1KeyFramed = runCatching {
                ByteArrayOutputStream().also { buf -> DataOutputStream(buf).use { it.writeUTF(key) } }.toByteArray()
            }.getOrNull()
            v1RecordBytes = v1KeyFramed?.let { Math.addExact(32, Math.addExact(it.size, packedBytes)) }
            val maxRecordBytes = maxOf(v2RecordBytes, v1RecordBytes ?: 0)
            val alignedSlots = Math.addExact(maxRecordBytes, SLOT_ALIGNMENT_BYTES - 1) / SLOT_ALIGNMENT_BYTES
            slotStride = Math.multiplyExact(alignedSlots, SLOT_ALIGNMENT_BYTES)
        }
    }

    internal data class RecoveredBitmap(
        val bits: BooleanArray,
        val committedGeneration: Long,
        val committedSlot: Int,
        val needsSanitize: Boolean,
    )

    private sealed interface SlotParse {
        data object Absent : SlotParse
        data object Empty : SlotParse
        data object NotV2 : SlotParse
        data class Corrupt(val reason: String) : SlotParse
        data class Valid(val generation: Long, val packed: ByteArray) : SlotParse
    }

    companion object {
        internal fun layoutFor(key: String, size: Long): BitmapLayout = BitmapLayout(key, size)

        fun readBits(file: File, key: String, size: Long): BooleanArray = recoverBitmap(file, key, size).bits

        internal fun recoverBitmap(file: File, key: String, size: Long): RecoveredBitmap {
            val layout = BitmapLayout(key, size)
            val bitmapFile = File("${file.path}.blocks")
            if (!bitmapFile.exists()) {
                return RecoveredBitmap(BooleanArray(layout.count), committedGeneration = 0L, committedSlot = -1, needsSanitize = false)
            }
            val cacheId = fileCacheName(key).take(12)
            return try {
                FileChannel.open(bitmapFile.toPath(), StandardOpenOption.READ).use { channel ->
                    val channelSize = channel.size()
                    if (channelSize == 0L) {
                        return RecoveredBitmap(BooleanArray(layout.count), 0L, -1, needsSanitize = false)
                    }
                    val slot0 = if (channelSize >= layout.v2RecordBytes.toLong()) {
                        parseV2Slot(channel, 0L, layout)
                    } else SlotParse.Absent
                    val slot1Offset = layout.slotStride.toLong()
                    val slot1 = if (channelSize >= slot1Offset + layout.v2RecordBytes.toLong()) {
                        parseV2Slot(channel, slot1Offset, layout)
                    } else SlotParse.Absent

                    val selectedPacked: ByteArray
                    val committedGen: Long
                    val committedSlot: Int
                    var sanitize = false
                    when {
                        slot0 is SlotParse.Valid && slot1 is SlotParse.Valid -> {
                            when {
                                slot0.generation == slot1.generation -> {
                                    if (!slot0.packed.contentEquals(slot1.packed)) {
                                        PikoLog.w(TAG, "Discarding sparse cache bitmap (conflicting generation ${slot0.generation}): cache=$cacheId, expectedSize=$size")
                                        return RecoveredBitmap(BooleanArray(layout.count), 0L, -1, needsSanitize = true)
                                    }
                                    selectedPacked = slot0.packed
                                    committedGen = slot0.generation
                                    committedSlot = 0
                                    sanitize = true
                                }
                                slot0.generation > slot1.generation -> {
                                    selectedPacked = slot0.packed
                                    committedGen = slot0.generation
                                    committedSlot = 0
                                }
                                else -> {
                                    selectedPacked = slot1.packed
                                    committedGen = slot1.generation
                                    committedSlot = 1
                                }
                            }
                        }
                        slot0 is SlotParse.Valid -> {
                            selectedPacked = slot0.packed
                            committedGen = slot0.generation
                            committedSlot = 0
                        }
                        slot1 is SlotParse.Valid -> {
                            selectedPacked = slot1.packed
                            committedGen = slot1.generation
                            committedSlot = 1
                        }
                        else -> {
                            val v1Packed = parseV1Prefix(channel, layout, channelSize)
                            if (v1Packed != null) {
                                selectedPacked = v1Packed
                                committedGen = 0L
                                committedSlot = 0
                            } else {
                                val reason = when {
                                    slot0 is SlotParse.Corrupt -> slot0.reason
                                    slot1 is SlotParse.Corrupt -> slot1.reason
                                    else -> "truncated or invalid (${channelSize} B)"
                                }
                                PikoLog.w(TAG, "Discarding sparse cache bitmap ($reason): cache=$cacheId, expectedSize=$size")
                                return RecoveredBitmap(BooleanArray(layout.count), 0L, -1, needsSanitize = true)
                            }
                        }
                    }

                    val length = if (file.isFile) file.length() else 0L
                    var droppedBlocks = 0
                    val result = BooleanArray(layout.count) { index ->
                        val bitSet = selectedPacked[index ushr 3].toInt() and (1 shl (index and 7)) != 0
                        if (!bitSet) {
                            false
                        } else if (minOf(size, (index + 1) * BLOCK_BYTES) <= length) {
                            true
                        } else {
                            droppedBlocks++
                            false
                        }
                    }
                    if (droppedBlocks > 0) {
                        sanitize = true
                        PikoLog.w(
                            TAG,
                            "Sparse cache data file shorter than bitmap ($length B, droppedBlocks=$droppedBlocks/${layout.count}): cache=$cacheId, expectedSize=$size",
                        )
                    }
                    RecoveredBitmap(result, committedGen, committedSlot, sanitize)
                }
            } catch (e: Throwable) {
                PikoLog.w(TAG, "Failed to read sparse cache bitmap, resetting to empty: cache=$cacheId, expectedSize=$size", e)
                RecoveredBitmap(BooleanArray(layout.count), 0L, -1, needsSanitize = true)
            }
        }

        private fun parseV2Slot(channel: FileChannel, offset: Long, layout: BitmapLayout): SlotParse {
            val record = readFullyAt(channel, offset, layout.v2RecordBytes) ?: return SlotParse.Absent
            if (record.all { it == 0.toByte() }) return SlotParse.Empty
            val headerMagic = ByteBuffer.wrap(record, 0, 4).getInt()
            if (headerMagic != MAGIC_V2) return SlotParse.NotV2
            val expectedCrc = ByteBuffer.wrap(record, layout.v2PayloadBytes, 8).getLong()
            val actualCrc = CRC32().apply { update(record, 0, layout.v2PayloadBytes) }.value
            if (actualCrc != expectedCrc) return SlotParse.Corrupt("CRC32 mismatch")

            val buf = ByteBuffer.wrap(record, 0, layout.v2PayloadBytes)
            buf.getInt() // MAGIC_V2 checked above
            val generation = buf.getLong()
            if (generation <= 0L) return SlotParse.Corrupt("invalid generation $generation")
            val storedKeyLen = buf.getInt()
            if (storedKeyLen != layout.keyUtf8.size) return SlotParse.Corrupt("key length mismatch")
            val storedKey = ByteArray(storedKeyLen).also { buf.get(it) }
            if (!storedKey.contentEquals(layout.keyUtf8)) return SlotParse.Corrupt("key mismatch")
            val storedSize = buf.getLong()
            if (storedSize != layout.size) return SlotParse.Corrupt("size mismatch ($storedSize->${layout.size})")
            val storedBlockBytes = buf.getLong()
            if (storedBlockBytes != BLOCK_BYTES) return SlotParse.Corrupt("block size mismatch ($storedBlockBytes)")
            val storedCount = buf.getInt()
            if (storedCount != layout.count) return SlotParse.Corrupt("count mismatch ($storedCount->${layout.count})")
            val packed = ByteArray(layout.packedBytes).also { buf.get(it) }
            if (hasDirtyPaddingBits(packed, layout.count)) return SlotParse.Corrupt("dirty padding bits")
            return SlotParse.Valid(generation, packed)
        }

        private fun parseV1Prefix(channel: FileChannel, layout: BitmapLayout, channelSize: Long): ByteArray? {
            val recordBytes = layout.v1RecordBytes ?: return null
            val expectedKeyFramed = layout.v1KeyFramed ?: return null
            if (channelSize < recordBytes.toLong()) return null
            val raw = readFullyAt(channel, 0L, recordBytes) ?: return null
            val payloadBytes = recordBytes - 8
            val expectedCrc = ByteBuffer.wrap(raw, payloadBytes, 8).getLong()
            val actualCrc = CRC32().apply { update(raw, 0, payloadBytes) }.value
            if (actualCrc != expectedCrc) return null

            val buf = ByteBuffer.wrap(raw, 0, payloadBytes)
            if (buf.getInt() != MAGIC_V1) return null
            val storedKey = ByteArray(expectedKeyFramed.size).also { buf.get(it) }
            if (!storedKey.contentEquals(expectedKeyFramed)) return null
            if (buf.getLong() != layout.size) return null
            if (buf.getLong() != BLOCK_BYTES) return null
            if (buf.getInt() != layout.count) return null
            val packed = ByteArray(layout.packedBytes).also { buf.get(it) }
            if (hasDirtyPaddingBits(packed, layout.count)) return null
            return packed
        }

        private fun hasDirtyPaddingBits(packed: ByteArray, count: Int): Boolean {
            if (packed.isEmpty() || count % 8 == 0) return false
            val unusedMask = ((1 shl (count % 8)) - 1).inv() and 0xFF
            return (packed[packed.lastIndex].toInt() and unusedMask) != 0
        }

        internal fun encodeV2Slot(layout: BitmapLayout, generation: Long, bits: BooleanArray): ByteArray {
            val packed = ByteArray(layout.packedBytes)
            for (index in 0 until layout.count) {
                if (bits[index]) {
                    packed[index ushr 3] = (packed[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
                }
            }
            val record = ByteArray(layout.v2RecordBytes)
            val buf = ByteBuffer.wrap(record)
            buf.putInt(MAGIC_V2)
            buf.putLong(generation)
            buf.putInt(layout.keyUtf8.size)
            buf.put(layout.keyUtf8)
            buf.putLong(layout.size)
            buf.putLong(BLOCK_BYTES)
            buf.putInt(layout.count)
            buf.put(packed)
            check(buf.position() == layout.v2PayloadBytes)
            val crc = CRC32().apply { update(record, 0, layout.v2PayloadBytes) }.value
            buf.putLong(crc)
            check(!buf.hasRemaining())
            return record
        }

        private fun readFullyAt(channel: FileChannel, offset: Long, length: Int): ByteArray? {
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            var pos = offset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, pos)
                if (read < 0) return null
                pos += read
            }
            return bytes
        }

        private fun writeFullyAt(channel: FileChannel, offset: Long, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            var pos = offset
            while (buffer.hasRemaining()) {
                pos += channel.write(buffer, pos)
            }
        }
    }
}
