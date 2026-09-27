package dev.piko.shared.media.proxy

import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.Deferred

/**
 * 代理会话背后的字节来源。
 *
 * 可以同时开多个 [ProxyReader]，各有各的读位置；播放器每个 HTTP 请求用一个。来源本身随会话关闭。
 */
interface ProxyByteSource : AutoCloseable {
    val size: Long

    suspend fun openReader(): ProxyReader

    /** 以 [role] 打开。只有 PikPak 的来源分前后台，其余照常打开。 */
    suspend fun openReader(role: StreamRole): ProxyReader = openReader().also { it.role = role }

    /**
     * 把 [ranges] 取进缓存，不占任何读位置；[priority] 为 null 时按 [role] 的预取档位。
     * 没有缓存可填的来源返回 null。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int? = null): Deferred<Unit>? = null
}

/** 单游标的顺序读取器，不支持并发调用。 */
interface ProxyReader : AutoCloseable {
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

    val position: Long

    suspend fun seekTo(position: Long)

    /** 读到的字节数，流尾返回 -1。 */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * PikPak 文件的字节来源。直链过期重取、连接预算、分块缓存与预读都在 SDK 的 handle 里，这里只做转接。
 * 同一个 handle 开出的 reader 共用一份缓存，开多少个都只有一组连接；缓存的 worker 跑在建 handle 时给的上下文里。
 */
internal class PikPakByteSource(
    private val handle: PikPakFileHandle,
    override val size: Long,
) : ProxyByteSource {
    override suspend fun openReader(): ProxyReader = openReader(StreamRole.FOREGROUND)

    override suspend fun openReader(role: StreamRole): ProxyReader = PikPakProxyReader(handle.openStream(role))

    override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit> =
        handle.prefetch(ranges, role, priority)

    override fun close() {
        handle.close()
    }
}

private class PikPakProxyReader(private val reader: PikPakStreamReader) : ProxyReader {
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

    override val position: Long get() = reader.position

    override suspend fun seekTo(position: Long) = reader.seekTo(position)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length)

    override fun close() = reader.close()
}
