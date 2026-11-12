package dev.piko.shared.media.proxy

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.PikPakFileCache
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.ReadEndReason
import io.github.nihildigit.pikpak.ReadTicket
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

private val InactiveReactivation: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * 代理会话背后的字节来源。
 *
 * 每个 HTTP Range 请求各自申请一张有界的 [ReadTicket]，独立持有区间游标并在请求结束时立即释放；
 * 来源本身随会话关闭。
 */
interface ProxyByteSource : AutoCloseable {
    val size: Long

    /** 底层缓存已从网络收到的累计字节数（单调递增，用于计算实时网速）。 */
    val receivedBytes: Long get() = 0L

    /** 底层文件是否正在执行云端冷存唤醒轮询。 */
    val isReactivating: StateFlow<Boolean> get() = InactiveReactivation

    suspend fun openReader(): ProxyReader

    /** 以 [role] 打开。只有 PikPak 的来源分前后台，其余照常打开。 */
    suspend fun openReader(role: StreamRole): ProxyReader = openReader().also { it.role = role }

    /**
     * 为单次有界读取区间 `[startOffset, endExclusive)` 开启一张独立的 [ReadTicket]。
     * PikPak 来源直接委托底层块缓存的原生票据；本地文件或测试桩默认基于独立 [openReader] 适配。
     */
    fun beginRead(owner: TransferDemand, startOffset: Long, endExclusive: Long = size): ReadTicket {
        val clampedStart = startOffset.coerceIn(0L, size)
        val clampedEnd = endExclusive.coerceIn(clampedStart, size)
        return ReaderBackedReadTicket(
            source = this,
            owner = owner,
            epoch = owner.intent.epoch,
            startOffset = clampedStart,
            endExclusive = clampedEnd,
        )
    }

    /**
     * 把 [ranges] 取进缓存，不占任何读位置；[priority] 为 null 时按 [owner] 或 [role] 的预取档位。
     * 没有缓存可填的来源返回 null。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int? = null): Deferred<Unit>? = null

    suspend fun prefetch(
        ranges: List<LongRange>,
        owner: TransferDemand?,
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ): Deferred<Unit>? = prefetch(ranges, role, priority)

    /** 更新底层共享缓存的媒体平均字节速率（字节/秒）。 */
    fun updateMediaBytesPerSecond(bytesPerSecond: Long?) = Unit
}

/** 单游标的顺序读取器，不支持并发调用。 */
interface ProxyReader : AutoCloseable {
    var role: StreamRole
        get() = StreamRole.FOREGROUND
        set(_) {}

    val position: Long

    suspend fun seekTo(position: Long)

    /** 读到的字节数，流尾返回 -1。 */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

private class ReaderBackedReadTicket(
    private val source: ProxyByteSource,
    override val owner: TransferDemand,
    override val epoch: Long,
    override val startOffset: Long,
    override val endExclusive: Long,
) : ReadTicket {
    private val lock = PikoLock()
    @Volatile
    private var currentPos: Long = startOffset
    @Volatile
    private var ended: Boolean = false
    private var reader: ProxyReader? = null

    override val position: Long get() = currentPos

    override suspend fun readSome(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "Out of bounds: offset=$offset, length=$length, bufferSize=${buffer.size}"
        }
        if (ended || owner.isClosed) throw CancellationException("read ticket closed")
        val remaining = endExclusive - currentPos
        if (remaining <= 0L) return -1
        if (length == 0) return 0

        val activeReader = withPikoLock(lock) {
            if (ended || owner.isClosed) throw CancellationException("read ticket closed")
            reader
        } ?: run {
            val opened = source.openReader()
            withPikoLock(lock) {
                if (ended || owner.isClosed) {
                    opened.close()
                    throw CancellationException("read ticket closed")
                }
                reader = opened
                opened
            }
        }

        if (activeReader.position != currentPos) {
            activeReader.seekTo(currentPos)
        }
        val toRead = minOf(length.toLong(), remaining).toInt()
        val got = activeReader.read(buffer, offset, toRead)
        if (got <= 0) return -1
        currentPos += got
        return got
    }

    override fun endRead(reason: ReadEndReason) {
        val toClose = withPikoLock(lock) {
            if (ended) return
            ended = true
            reader.also { reader = null }
        }
        toClose?.close()
    }
}

/**
 * PikPak 文件的字节来源。handle 负责直链恢复，cache 负责分块缓存与按需票据调度。
 * 所有读取票据共用一份 cache；关闭来源时先释放 cache，再释放 handle。
 */
internal class PikPakByteSource(
    private val handle: PikPakFileHandle,
    private val cache: PikPakFileCache,
    private val release: (() -> Unit)? = null,
) : ProxyByteSource {
    override val size: Long get() = cache.size

    override val receivedBytes: Long get() = cache.receivedBytes

    override val isReactivating: StateFlow<Boolean> get() = cache.isReactivating

    override suspend fun openReader(): ProxyReader = openReader(StreamRole.FOREGROUND)

    override suspend fun openReader(role: StreamRole): ProxyReader = PikPakProxyReader(cache.openStream(role))

    override fun beginRead(owner: TransferDemand, startOffset: Long, endExclusive: Long): ReadTicket =
        cache.beginRead(owner, startOffset, endExclusive)

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit> =
        cache.prefetch(ranges, role, priority)

    override suspend fun prefetch(
        ranges: List<LongRange>,
        owner: TransferDemand?,
        role: StreamRole,
        priority: Int?,
    ): Deferred<Unit> =
        if (owner != null) cache.prefetch(ranges, owner, priority) else cache.prefetch(ranges, role, priority)

    override fun updateMediaBytesPerSecond(bytesPerSecond: Long?) {
        cache.updateMediaBytesPerSecond(bytesPerSecond)
    }

    override fun close() {
        release?.let { it(); return }
        try {
            cache.close()
        } finally {
            handle.close()
        }
    }
}

private class PikPakProxyReader(private val reader: PikPakStreamReader) : ProxyReader {
    override var role: StreamRole
        get() = reader.role
        set(value) {
            reader.role = value
        }

    override val position: Long get() = reader.position

    override suspend fun seekTo(position: Long) = reader.seekTo(position)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length)

    override fun close() = reader.close()
}
