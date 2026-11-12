package dev.piko.shared.media

import dev.piko.shared.media.proxy.ProxyByteSource
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.ReadEndReason
import io.github.nihildigit.pikpak.ReadTicket
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
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
 * 在一张有界 [ReadTicket] 上实现按偏移读：位置连续时直接顺延复用，位置跳变时以 [ReadEndReason.Repositioned] 结束旧票据并换新票据。
 * 随机读取声明 [BufferBudget.NONE]（零跨块投机预读）；当前票据活跃块可与共享下载缓存共用。
 */
internal class ReaderRandomAccessSource(
    private val source: ProxyByteSource,
) : RandomAccessMediaSource {
    private val lock = Mutex()
    private val demand = TransferDemand(
        ConsumerIntent(
            service = ServiceIntent.WAITING,
            bufferBudget = BufferBudget.NONE,
        ),
    )
    private var ticket: ReadTicket? = null

    override val size: Long get() = source.size

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (position >= size) return@withLock -1
        val existing = ticket
        val current = if (existing != null && existing.position == position) {
            existing
        } else {
            existing?.endRead(ReadEndReason.Repositioned)
            source.beginRead(demand, position, size).also { ticket = it }
        }
        try {
            current.readSome(buffer, offset, length)
        } catch (e: CancellationException) {
            current.endRead(ReadEndReason.Cancelled)
            if (ticket === current) ticket = null
            throw e
        } catch (e: Exception) {
            // 读失败的票据不再复用，下一次读取重新开启
            current.endRead(ReadEndReason.SourceFailed)
            if (ticket === current) ticket = null
            throw e
        }
    }

    override fun close() {
        ticket?.endRead(ReadEndReason.ConsumerClosed)
        ticket = null
        demand.close()
        source.close()
    }
}
