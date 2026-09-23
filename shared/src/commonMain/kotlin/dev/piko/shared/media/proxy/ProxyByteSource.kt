package dev.piko.shared.media.proxy

import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlin.coroutines.CoroutineContext

/**
 * 代理会话背后的字节来源。
 *
 * 会话只持有一个 [ProxyReader]，读失败后向这里重新要一个；来源本身随会话关闭。
 */
interface ProxyByteSource : AutoCloseable {
    val size: Long

    suspend fun openReader(): ProxyReader
}

/** 单游标的顺序读取器，不支持并发调用。 */
interface ProxyReader : AutoCloseable {
    val position: Long

    suspend fun seekTo(position: Long)

    /** 读到的字节数，流尾返回 -1。 */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * PikPak 文件的字节来源。
 *
 * 直链过期重取、连接预算、分块缓存与预读都在 SDK 的 handle 与 reader 里，这里只做转接。
 * handle 可以派生多个 reader，但每个 reader 各带 64 MiB 缓存和一组 worker，
 * 所以会话坚持只用一个，失败时才换新的。
 */
internal class PikPakByteSource(
    private val handle: PikPakFileHandle,
    override val size: Long,
    private val readerContext: CoroutineContext,
) : ProxyByteSource {
    override suspend fun openReader(): ProxyReader =
        PikPakProxyReader(handle.openStream(size = size, parentCoroutineContext = readerContext))

    override fun close() {
        handle.close()
    }
}

private class PikPakProxyReader(private val reader: PikPakStreamReader) : ProxyReader {
    override val position: Long get() = reader.position

    override suspend fun seekTo(position: Long) = reader.seekTo(position)

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length)

    override fun close() = reader.close()
}
