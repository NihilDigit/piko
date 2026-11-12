package dev.piko.shared.media.proxy

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.ReadTicket
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 把 [inner] 的 [offset] 起 [size] 个字节当作一个完整的文件。播放器看到的第 0 字节是 inner 的 offset 处。
 *
 * 给能从任意位置接着播的流用，也就是 MPEG-TS：它由 188 字节的包组成，每个包自带同步字节与节目表，
 * 本来就是为从中途接入的广播设计的，截一截从包边界开始的字节，解码器从下一个关键帧起就能出画面。
 *
 * 当前导区间 `[0, nullVideoBeforeOffset)` 需要屏蔽孤儿视频帧时，视图层纯粹按逻辑偏移 `k * 188` 确定性地将
 * `PID == nullVideoPid` 的包改写为 `0x1FFF` 空包，输出字节与调用方单次读取分片大小、底层流分块边界、
 * 随机 `seekTo` 顺序及并发读取完全无关，且绝不改动底层共享缓存字节。
 */
internal class SlicedByteSource(
    private val inner: ProxyByteSource,
    private val offset: Long,
    override val size: Long,
    /** 切片起点 [PAT] 与首个带参数集关键帧 PES 之间需置为 MPEG-TS 空包（PID `0x1FFF`）的前导字节数，消除前序 GOP 孤儿 P/B 帧报错。 */
    private val nullVideoBeforeOffset: Long = 0L,
    private val nullVideoPid: Int = -1,
) : ProxyByteSource {
    override val receivedBytes: Long get() = inner.receivedBytes
    override val isReactivating: StateFlow<Boolean> get() = inner.isReactivating

    init {
        require(offset >= 0 && size > 0 && offset + size <= inner.size) { "切片 $offset+$size 超出 ${inner.size}" }
    }

    private val maskingEnabled: Boolean = nullVideoPid >= 0 && nullVideoBeforeOffset > 0L && size >= 4L
    private val candidatePacketCount: Long = if (!maskingEnabled) {
        0L
    } else {
        minOf(
            (nullVideoBeforeOffset + TS_PACKET_BYTES - 1L) / TS_PACKET_BYTES,
            (size - 4L) / TS_PACKET_BYTES + 1L,
        ).coerceAtLeast(0L)
    }
    private val activeMaskLimit: Long = minOf(size, candidatePacketCount * TS_PACKET_BYTES)
    private val decisionLock = PikoLock()
    private val decisionWords: IntArray = if (candidatePacketCount in 1L..MAX_CACHED_PACKETS.toLong()) {
        IntArray((candidatePacketCount.toInt() + 15) ushr 4)
    } else {
        IntArray(0)
    }

    override suspend fun openReader(): ProxyReader = SlicedReader(inner.openReader())

    override suspend fun openReader(role: StreamRole): ProxyReader = SlicedReader(inner.openReader(role))

    override fun beginRead(owner: TransferDemand, startOffset: Long, endExclusive: Long): ReadTicket {
        val clampedStart = startOffset.coerceIn(0L, size)
        val clampedEnd = endExclusive.coerceIn(clampedStart, size)
        return SlicedReadTicket(
            owner = owner,
            startOffset = clampedStart,
            endExclusive = clampedEnd,
            innerTicket = inner.beginRead(owner, offset + clampedStart, offset + clampedEnd),
        )
    }

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit>? =
        inner.prefetch(shiftRanges(ranges), role, priority)

    override suspend fun prefetch(
        ranges: List<LongRange>,
        owner: TransferDemand?,
        role: StreamRole,
        priority: Int?,
    ): Deferred<Unit>? =
        inner.prefetch(shiftRanges(ranges), owner, role, priority)

    override fun updateMediaBytesPerSecond(bytesPerSecond: Long?) {
        inner.updateMediaBytesPerSecond(bytesPerSecond)
    }

    override fun close() = inner.close()

    private fun shiftRanges(ranges: List<LongRange>): List<LongRange> =
        ranges.mapNotNull { range ->
            val first = range.first.coerceAtLeast(0L)
            val last = range.last.coerceAtMost(size - 1L)
            if (first <= last) (first + offset)..(last + offset) else null
        }

    private fun cachedDecision(pktIndex: Long): Boolean? {
        if (pktIndex < 0L || pktIndex >= MAX_CACHED_PACKETS.toLong()) return null
        val idx = pktIndex.toInt()
        val wordIdx = idx ushr 4
        if (wordIdx >= decisionWords.size) return null
        val shift = (idx and 15) shl 1
        val state = withPikoLock(decisionLock) { (decisionWords[wordIdx] ushr shift) and 0x3 }
        return when (state) {
            DECISION_KEEP -> false
            DECISION_MASK -> true
            else -> null
        }
    }

    private fun recordDecision(pktIndex: Long, mask: Boolean) {
        if (pktIndex < 0L || pktIndex >= MAX_CACHED_PACKETS.toLong()) return
        val idx = pktIndex.toInt()
        val wordIdx = idx ushr 4
        if (wordIdx >= decisionWords.size) return
        val shift = (idx and 15) shl 1
        val bits = (if (mask) DECISION_MASK else DECISION_KEEP) shl shift
        val clearMask = (0x3 shl shift).inv()
        withPikoLock(decisionLock) {
            decisionWords[wordIdx] = (decisionWords[wordIdx] and clearMask) or bits
        }
    }

    private fun isTargetVideoHeader(b0: Byte, b1: Byte, b2: Byte): Boolean {
        if (b0 != TS_SYNC_BYTE) return false
        val pid = ((b1.toInt() and 0x1F) shl 8) or (b2.toInt() and 0xFF)
        return pid == nullVideoPid
    }

    private fun applyMaskDecision(
        buffer: ByteArray,
        rawBufOffset: Int,
        rawStartPos: Long,
        rawEndPos: Long,
        pktStart: Long,
    ) {
        val overlapStart = maxOf(rawStartPos, pktStart)
        val overlapEnd = minOf(rawEndPos, pktStart + TS_PACKET_BYTES)
        var p = overlapStart
        while (p < overlapEnd) {
            val q = (p - pktStart).toInt()
            val bufIdx = rawBufOffset + (p - rawStartPos).toInt()
            buffer[bufIdx] = maskedTsPacketByte(q)
            p++
        }
    }

    private inner class SlicedReadTicket(
        override val owner: TransferDemand,
        override val startOffset: Long,
        override val endExclusive: Long,
        private val innerTicket: ReadTicket,
    ) : ReadTicket {
        override val epoch: Long get() = innerTicket.epoch

        @Volatile
        private var pos: Long = startOffset
        private val peekBuf = ByteArray(3)
        private var peekLen: Int = 0
        private val headerScratch = ByteArray(3)

        override val position: Long get() = pos

        override suspend fun readSome(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "Out of bounds: offset=$offset, length=$length, bufferSize=${buffer.size}"
            }
            val remainingAtStart = endExclusive - pos
            if (remainingAtStart <= 0L) return -1
            if (length == 0) return 0

            var copied = 0
            if (peekLen > 0) {
                val fromPeek = minOf(length, minOf(remainingAtStart, peekLen.toLong()).toInt())
                for (i in 0 until fromPeek) {
                    buffer[offset + i] = peekBuf[i]
                }
                if (fromPeek < peekLen) {
                    for (i in 0 until peekLen - fromPeek) {
                        peekBuf[i] = peekBuf[i + fromPeek]
                    }
                    peekLen -= fromPeek
                    pos += fromPeek
                    return fromPeek
                }
                peekLen = 0
                pos += fromPeek
                copied = fromPeek
                if (copied == length || pos >= endExclusive) {
                    return copied
                }
            }

            val rawStartPos = pos
            val maxRaw = minOf((length - copied).toLong(), endExclusive - rawStartPos).toInt()
            val n = innerTicket.readSome(buffer, offset + copied, maxRaw)
            if (n <= 0) {
                return if (copied > 0) copied else -1
            }
            pos = rawStartPos + n
            if (maskingEnabled && rawStartPos < activeMaskLimit) {
                maskTicketRange(buffer, offset + copied, n, rawStartPos)
            }
            return copied + n
        }

        private suspend fun maskTicketRange(buffer: ByteArray, rawBufOffset: Int, rawLen: Int, rawStartPos: Long) {
            val rawEndPos = rawStartPos + rawLen
            var pktIndex = rawStartPos / TS_PACKET_BYTES
            val lastPkt = minOf((rawEndPos - 1L) / TS_PACKET_BYTES, candidatePacketCount - 1L)
            while (pktIndex <= lastPkt) {
                val pktStart = pktIndex * TS_PACKET_BYTES
                val shouldMask = cachedDecision(pktIndex) ?: resolveTicketDecision(
                    pktIndex = pktIndex,
                    pktStart = pktStart,
                    buffer = buffer,
                    rawBufOffset = rawBufOffset,
                    rawStartPos = rawStartPos,
                    rawEndPos = rawEndPos,
                )
                if (shouldMask) {
                    applyMaskDecision(buffer, rawBufOffset, rawStartPos, rawEndPos, pktStart)
                }
                pktIndex++
            }
        }

        private suspend fun resolveTicketDecision(
            pktIndex: Long,
            pktStart: Long,
            buffer: ByteArray,
            rawBufOffset: Int,
            rawStartPos: Long,
            rawEndPos: Long,
        ): Boolean {
            if (pktStart >= rawStartPos && pktStart + 3L <= rawEndPos) {
                val h = rawBufOffset + (pktStart - rawStartPos).toInt()
                return isTargetVideoHeader(buffer[h], buffer[h + 1], buffer[h + 2]).also { recordDecision(pktIndex, it) }
            }
            if (pktStart < rawStartPos || endExclusive < pktStart + 3L) {
                val hdrTicket = inner.beginRead(
                    owner = owner,
                    startOffset = this@SlicedByteSource.offset + pktStart,
                    endExclusive = this@SlicedByteSource.offset + pktStart + 3L,
                )
                val got = try {
                    readTicketFully(hdrTicket, headerScratch, 0, 3)
                } finally {
                    hdrTicket.endRead(allowFinishInFlight = false)
                }
                val mask = got == 3 && isTargetVideoHeader(headerScratch[0], headerScratch[1], headerScratch[2])
                if (got == 3) recordDecision(pktIndex, mask)
                return mask
            }
            val have = (rawEndPos - pktStart).toInt()
            val need = 3 - have
            val got = readTicketFully(innerTicket, peekBuf, 0, need)
            val h = rawBufOffset + (pktStart - rawStartPos).toInt()
            val mask = if (got == need) {
                val b0 = buffer[h]
                val b1 = if (have >= 2) buffer[h + 1] else peekBuf[0]
                val b2 = peekBuf[need - 1]
                isTargetVideoHeader(b0, b1, b2).also { recordDecision(pktIndex, it) }
            } else {
                false
            }
            if (got > 0) {
                if (mask) {
                    for (i in 0 until got) {
                        peekBuf[i] = maskedTsPacketByte(have + i)
                    }
                }
                peekLen = got
            }
            return mask
        }

        private suspend fun readTicketFully(ticket: ReadTicket, dst: ByteArray, dstOffset: Int, count: Int): Int {
            var total = 0
            while (total < count) {
                val r = ticket.readSome(dst, dstOffset + total, count - total)
                if (r <= 0) break
                total += r
            }
            return total
        }

        override fun endRead(allowFinishInFlight: Boolean) {
            innerTicket.endRead(allowFinishInFlight)
        }
    }

    private inner class SlicedReader(private val reader: ProxyReader) : ProxyReader {
        private var pos: Long = 0L
        private var innerAligned: Boolean = (this@SlicedByteSource.offset == 0L)
        private val peekBuf = ByteArray(3)
        private var peekPos: Long = -1L
        private var peekLen: Int = 0
        private val headerScratch = ByteArray(3)

        override var role: StreamRole
            get() = reader.role
            set(value) {
                reader.role = value
            }

        override val position: Long get() = pos

        override suspend fun seekTo(position: Long) {
            require(position >= 0L) { "Negative seek position: $position" }
            if (peekLen > 0 && position >= peekPos && position < peekPos + peekLen) {
                val drop = (position - peekPos).toInt()
                if (drop > 0) {
                    for (i in 0 until peekLen - drop) {
                        peekBuf[i] = peekBuf[i + drop]
                    }
                    peekPos = position
                    peekLen -= drop
                }
                pos = position
                return
            }
            if (peekLen > 0 && position == peekPos + peekLen && innerAligned) {
                peekLen = 0
                peekPos = -1L
                pos = position
                return
            }
            peekLen = 0
            peekPos = -1L
            reader.seekTo(this@SlicedByteSource.offset + position.coerceAtMost(size))
            pos = position
            innerAligned = true
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
                "Out of bounds: offset=$offset, length=$length, bufferSize=${buffer.size}"
            }
            val remainingAtStart = size - pos
            if (remainingAtStart <= 0L) return -1
            if (length == 0) return 0

            var copied = 0
            if (peekLen > 0) {
                val fromPeek = minOf(length, minOf(remainingAtStart, peekLen.toLong()).toInt())
                for (i in 0 until fromPeek) {
                    buffer[offset + i] = peekBuf[i]
                }
                if (fromPeek < peekLen) {
                    for (i in 0 until peekLen - fromPeek) {
                        peekBuf[i] = peekBuf[i + fromPeek]
                    }
                    peekPos += fromPeek
                    peekLen -= fromPeek
                    pos += fromPeek
                    return fromPeek
                }
                peekLen = 0
                peekPos = -1L
                pos += fromPeek
                copied = fromPeek
                if (copied == length || pos >= size) {
                    return copied
                }
            }

            if (!innerAligned) {
                reader.seekTo(this@SlicedByteSource.offset + pos)
                innerAligned = true
            }

            val rawStartPos = pos
            val maxRaw = minOf((length - copied).toLong(), size - rawStartPos).toInt()
            val n = reader.read(buffer, offset + copied, maxRaw)
            if (n <= 0) {
                return if (copied > 0) copied else -1
            }
            pos = rawStartPos + n
            if (maskingEnabled && rawStartPos < activeMaskLimit) {
                maskRange(buffer, offset + copied, n, rawStartPos)
            }
            return copied + n
        }

        private suspend fun maskRange(buffer: ByteArray, rawBufOffset: Int, rawLen: Int, rawStartPos: Long) {
            val rawEndPos = rawStartPos + rawLen
            var pktIndex = rawStartPos / TS_PACKET_BYTES
            val lastPkt = minOf((rawEndPos - 1L) / TS_PACKET_BYTES, candidatePacketCount - 1L)
            while (pktIndex <= lastPkt) {
                val pktStart = pktIndex * TS_PACKET_BYTES
                val shouldMask = cachedDecision(pktIndex) ?: resolveDecision(
                    pktIndex = pktIndex,
                    pktStart = pktStart,
                    buffer = buffer,
                    rawBufOffset = rawBufOffset,
                    rawStartPos = rawStartPos,
                    rawEndPos = rawEndPos,
                )
                if (shouldMask) {
                    applyMaskDecision(buffer, rawBufOffset, rawStartPos, rawEndPos, pktStart)
                }
                pktIndex++
            }
        }

        private suspend fun resolveDecision(
            pktIndex: Long,
            pktStart: Long,
            buffer: ByteArray,
            rawBufOffset: Int,
            rawStartPos: Long,
            rawEndPos: Long,
        ): Boolean {
            if (pktStart >= rawStartPos && pktStart + 3L <= rawEndPos) {
                val h = rawBufOffset + (pktStart - rawStartPos).toInt()
                val mask = isTargetVideoHeader(buffer[h], buffer[h + 1], buffer[h + 2])
                recordDecision(pktIndex, mask)
                return mask
            }
            if (pktStart < rawStartPos) {
                reader.seekTo(this@SlicedByteSource.offset + pktStart)
                val got = readFromInner(headerScratch, 0, 3)
                reader.seekTo(this@SlicedByteSource.offset + rawEndPos)
                val mask = got == 3 && isTargetVideoHeader(headerScratch[0], headerScratch[1], headerScratch[2])
                if (got == 3) recordDecision(pktIndex, mask)
                return mask
            }
            val have = (rawEndPos - pktStart).toInt()
            val need = 3 - have
            val got = readFromInner(peekBuf, 0, need)
            val h = rawBufOffset + (pktStart - rawStartPos).toInt()
            val mask = if (got == need) {
                val b0 = buffer[h]
                val b1 = if (have >= 2) buffer[h + 1] else peekBuf[0]
                val b2 = peekBuf[need - 1]
                isTargetVideoHeader(b0, b1, b2).also { recordDecision(pktIndex, it) }
            } else {
                false
            }
            if (got > 0) {
                if (mask) {
                    for (i in 0 until got) {
                        peekBuf[i] = maskedTsPacketByte(have + i)
                    }
                }
                peekPos = rawEndPos
                peekLen = got
            }
            return mask
        }

        private suspend fun readFromInner(dst: ByteArray, dstOffset: Int, count: Int): Int {
            var total = 0
            while (total < count) {
                val r = reader.read(dst, dstOffset + total, count - total)
                if (r <= 0) break
                total += r
            }
            return total
        }

        override fun close() = reader.close()
    }

    private companion object {
        const val TS_PACKET_BYTES = 188L
        const val TS_SYNC_BYTE: Byte = 0x47
        const val DECISION_KEEP = 1
        const val DECISION_MASK = 2
        const val MAX_CACHED_PACKETS = 262_144

        fun maskedTsPacketByte(offsetInPacket: Int): Byte = when (offsetInPacket) {
            0 -> TS_SYNC_BYTE
            1 -> 0x1F
            3 -> 0x10
            else -> 0xFF.toByte()
        }
    }
}
