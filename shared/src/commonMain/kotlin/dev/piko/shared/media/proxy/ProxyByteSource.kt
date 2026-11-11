package dev.piko.shared.media.proxy

import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakFileCache
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

private val InactiveReactivation: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * 代理会话背后的字节来源。
 *
 * 可以同时开多个 [ProxyReader]，各有各的读位置；播放器每个 HTTP 请求用一个。来源本身随会话关闭。
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
     * 把 [ranges] 取进缓存，不占任何读位置；[priority] 为 null 时按 [role] 的预取档位。
     * 没有缓存可填的来源返回 null。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int? = null): Deferred<Unit>? = null

    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?, demand: TransferDemand?): Deferred<Unit>? =
        prefetch(ranges, role, priority)

    /** 更新底层共享缓存的媒体平均字节速率（字节/秒）。 */
    fun updateMediaBytesPerSecond(bytesPerSecond: Long?) = Unit
}

/** 单游标的顺序读取器，不支持并发调用。 */
interface ProxyReader : AutoCloseable {
    var demand: TransferDemand?
        get() = null
        set(_) {}
    fun reportPlaybackBuffer(snapshot: PlaybackBufferSnapshot) = Unit
    /**
     * 前台是眼前在放的，后台是为之后预热的；后台的请求整体让着前台，见 SDK 的 PikPakStreamReader.role。
     * 本机文件之类不经连接预算的来源没有这个区别，读写都是空操作。
     */
    var role: StreamRole
        get() = StreamRole.FOREGROUND
        set(_) {}

    /**
     * 往后读多深，见 SDK 的 PikPakStreamReader.readAheadLimit。null 为默认深度；
     * 没有预读的来源读写都是空操作。
     */
    var readAheadLimit: Long?
        get() = null
        set(_) {}

    /** 有人正等着这个读者：拖动后还没出画面、播放卡在缓冲上。见 SDK 的 PikPakStreamReader.urgent。 */
    var urgent: Boolean
        get() = false
        set(_) {}

    /** 向底层流读取器反馈播放器当前已缓冲的媒体时长与字节消耗速率，用于播放头停滞时的期限救援。 */
    fun reportPlaybackBuffer(bufferedDuration: Duration, mediaBytesPerSecond: Long? = null) = Unit

    /** HTTP 请求归还游标时撤回预读需求，复用时恢复；本地来源无需处理。 */
    fun setDemandActive(active: Boolean) {}

    val position: Long

    suspend fun seekTo(position: Long)

    /** 读到的字节数，流尾返回 -1。 */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * PikPak 文件的字节来源。handle 负责直链恢复，cache 负责分块缓存与预读。
 * 所有 reader 共用一份 cache；关闭来源时先释放 cache，再释放 handle。
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

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit> =
        cache.prefetch(ranges, role, priority)

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?, demand: TransferDemand?): Deferred<Unit> =
        if (demand == null) cache.prefetch(ranges, role, priority) else cache.prefetch(ranges, demand, role, priority)

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
            reader.readAheadLimit = value ?: Long.MAX_VALUE
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

    override val position: Long get() = reader.position

    override suspend fun seekTo(position: Long) = reader.seekTo(position)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length)

    override fun close() = reader.close()
}
