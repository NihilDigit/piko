package dev.piko.shared.media

import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按偏移读取的网盘文件，给只能从数据源回调读取、不宜走本机 HTTP 代理的调用方使用。
 *
 * Android 的 MediaExtractor 读 URL 时经应用进程内的 HttpURLConnection，受明文流量策略约束无法访问本机回环 http 地址，
 * 且其内部缓存层会发起探测与随机定位两路 HTTP 请求；桌面端 FFmpeg 转封装/截取则直接经自定义 AVIO 回调按偏移拉取字节。
 * 经此接口直接读取可绕开本机 HTTP 套接字开销与策略限制，连接预算、直链刷新与稀疏块缓存仍统一由 SDK 管理。
 */
interface RandomAccessMediaSource : AutoCloseable {
    val size: Long

    /** 从 [position] 读至多 [length] 字节，返回读到的字节数，文件尾返回 -1。调用串行进行。 */
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * 在一个 [ProxyReader] 上实现按偏移读：位置不连续时先 seek。reader 在首次读取时才开，
 * 随机读取不声明连续预读窗口；已开始的缓存块仍可完成，并与下载共用。
 */
internal class ReaderRandomAccessSource(
    private val source: ProxyByteSource,
) : RandomAccessMediaSource {
    private val lock = Mutex()
    private var reader: ProxyReader? = null

    override val size: Long get() = source.size

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (position >= size) return@withLock -1
        val current = reader ?: source.openReader().also {
            it.readAheadLimit = 0
            reader = it
        }
        try {
            if (current.position != position) current.seekTo(position)
            current.read(buffer, offset, length)
        } catch (e: Exception) {
            // 读失败的 reader 不再复用，下一次读取重新开一个
            current.close()
            reader = null
            throw e
        }
    }

    override fun close() {
        reader?.close()
        source.close()
    }
}
