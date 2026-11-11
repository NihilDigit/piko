package dev.piko.shared.media.proxy

import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * 把 [inner] 的 [offset] 起 [size] 个字节当作一个完整的文件。播放器看到的第 0 字节是 inner 的 offset 处。
 *
 * 给能从任意位置接着播的流用，也就是 MPEG-TS：它由 188 字节的包组成，每个包自带同步字节与节目表，
 * 本来就是为从中途接入的广播设计的，截一截从包边界开始的字节，解码器从下一个关键帧起就能出画面。
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

    override suspend fun openReader(): ProxyReader = SlicedReader(inner.openReader())

    override suspend fun openReader(role: StreamRole): ProxyReader = SlicedReader(inner.openReader(role))

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit>? =
        inner.prefetch(ranges.map { it.first.coerceAtLeast(0) + offset..it.last.coerceAtMost(size - 1) + offset }, role, priority)

    override fun updateMediaBytesPerSecond(bytesPerSecond: Long?) {
        inner.updateMediaBytesPerSecond(bytesPerSecond)
    }

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?, demand: TransferDemand?): Deferred<Unit>? =
        inner.prefetch(ranges.map { it.first.coerceAtLeast(0) + offset..it.last.coerceAtMost(size - 1) + offset }, role, priority, demand)

    override fun close() = inner.close()

    private inner class SlicedReader(private val reader: ProxyReader) : ProxyReader {
        override var demand: TransferDemand?
            get() = reader.demand
            set(value) { reader.demand = value }
        override fun reportPlaybackBuffer(snapshot: PlaybackBufferSnapshot) = reader.reportPlaybackBuffer(snapshot)
        override var role: StreamRole
            get() = reader.role
            set(value) {
                reader.role = value
            }

        override var readAheadLimit: Long?
            get() = reader.readAheadLimit
            set(value) {
                reader.readAheadLimit = value
            }

        override var urgent: Boolean
            get() = reader.urgent
            set(value) {
                reader.urgent = value
            }

        override fun reportPlaybackBuffer(bufferedDuration: Duration, mediaBytesPerSecond: Long?) {
            reader.reportPlaybackBuffer(bufferedDuration, mediaBytesPerSecond)
        }

        override fun setDemandActive(active: Boolean) = reader.setDemandActive(active)

        override val position: Long get() = reader.position - offset

        override suspend fun seekTo(position: Long) = reader.seekTo(position + offset)

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            // 新开的 reader 读位置在 inner 的 0 处，先挪到切片起点
            if (reader.position < this@SlicedByteSource.offset) reader.seekTo(this@SlicedByteSource.offset)
            val readPos = position
            val remaining = size - readPos
            if (remaining <= 0) return -1
            val n = reader.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n > 0 && nullVideoPid >= 0 && readPos < nullVideoBeforeOffset) {
                maskOrphanVideoPackets(buffer, offset, n, readPos, nullVideoBeforeOffset, nullVideoPid)
            }
            return n
        }

        override fun close() = reader.close()
    }

    private companion object {
        const val TS_PACKET_BYTES = 188

        fun maskOrphanVideoPackets(
            buffer: ByteArray,
            bufOffset: Int,
            length: Int,
            sliceReadPos: Long,
            limitOffset: Long,
            targetPid: Int,
        ) {
            val endPos = minOf(sliceReadPos + length, limitOffset)
            var pktStart = (sliceReadPos / TS_PACKET_BYTES) * TS_PACKET_BYTES
            while (pktStart < endPos) {
                if (pktStart >= sliceReadPos) {
                    val idx = bufOffset + (pktStart - sliceReadPos).toInt()
                    val avail = (bufOffset + length) - idx
                    if (avail >= 4 && buffer[idx] == 0x47.toByte()) {
                        val pid = ((buffer[idx + 1].toInt() and 0x1F) shl 8) or (buffer[idx + 2].toInt() and 0xFF)
                        if (pid == targetPid) {
                            buffer[idx + 1] = 0x1F
                            buffer[idx + 2] = 0xFF.toByte()
                            buffer[idx + 3] = 0x10
                            val fillEnd = minOf(bufOffset + length, idx + TS_PACKET_BYTES)
                            for (i in (idx + 4) until fillEnd) {
                                buffer[i] = 0xFF.toByte()
                            }
                        }
                    }
                }
                pktStart += TS_PACKET_BYTES
            }
        }
    }
}
