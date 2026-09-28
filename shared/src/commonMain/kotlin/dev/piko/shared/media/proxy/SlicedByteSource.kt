package dev.piko.shared.media.proxy

import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.Deferred

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
) : ProxyByteSource {
    init {
        require(offset >= 0 && size > 0 && offset + size <= inner.size) { "切片 $offset+$size 超出 ${inner.size}" }
    }

    override suspend fun openReader(): ProxyReader = SlicedReader(inner.openReader())

    override suspend fun openReader(role: StreamRole): ProxyReader = SlicedReader(inner.openReader(role))

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit>? =
        inner.prefetch(ranges.map { it.first.coerceAtLeast(0) + offset..it.last.coerceAtMost(size - 1) + offset }, role, priority)

    override fun close() = inner.close()

    private inner class SlicedReader(private val reader: ProxyReader) : ProxyReader {
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

        override val position: Long get() = reader.position - offset

        override suspend fun seekTo(position: Long) = reader.seekTo(position + offset)

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            // 新开的 reader 读位置在 inner 的 0 处，先挪到切片起点
            if (reader.position < this@SlicedByteSource.offset) reader.seekTo(this@SlicedByteSource.offset)
            val remaining = size - position
            if (remaining <= 0) return -1
            return reader.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        }

        override fun close() = reader.close()
    }
}
