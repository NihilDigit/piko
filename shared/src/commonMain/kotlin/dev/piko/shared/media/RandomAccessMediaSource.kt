package dev.piko.shared.media

import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按偏移读取的网盘文件，给只能从数据源读、不宜走 HTTP 的调用方用。
 *
 * Android 的 MediaExtractor 读 URL 时经应用进程里的 HttpURLConnection，受明文流量策略约束，
 * 读不了本机代理的 http 地址；它的缓存层还会同时开预读与定位两条连接，而代理会话只允许
 * 一个读者。改由调用方经这里取字节就绕开了两者，连接预算、直链重取与缓存仍由 SDK 管。
 */
interface RandomAccessMediaSource : AutoCloseable {
    val size: Long

    /** 从 [position] 读至多 [length] 字节，返回读到的字节数，文件尾返回 -1。调用串行进行。 */
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * 在一个 [ProxyReader] 上实现按偏移读：位置不连续时先 seek。reader 在首次读取时才开，
 * 每个 reader 都带一份 SDK 的分块缓存与一组 worker，用不到就不该建。
 */
internal class ReaderRandomAccessSource(
    private val source: ProxyByteSource,
) : RandomAccessMediaSource {
    private val lock = Mutex()
    private var reader: ProxyReader? = null

    override val size: Long get() = source.size

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (position >= size) return@withLock -1
        val current = reader ?: source.openReader().also { reader = it }
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
