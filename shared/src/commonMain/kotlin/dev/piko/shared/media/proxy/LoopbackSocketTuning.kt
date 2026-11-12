package dev.piko.shared.media.proxy

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket

/**
 * 本机回环代理连接写出端：直接向底层非阻塞传输通道写出并限制内核 `SO_SNDBUF`，
 * 绕开 Ktor `ByteChannel` 默认 1 MiB 用户态缓冲，使 [io.github.nihildigit.pikpak.ReadTicket] 游标紧贴播放器真实消费进度。
 */
internal interface LoopbackSocketWriter {
    suspend fun writeFully(buffer: ByteArray, offset: Int, length: Int)

    suspend fun writeUtf8(text: String) {
        val bytes = text.encodeToByteArray()
        writeFully(bytes, 0, bytes.size)
    }

    suspend fun flushAndClose()
}

internal expect fun openLoopbackSocketWriter(
    socket: Socket,
    selector: SelectorManager,
    sendBufferBytes: Int,
): LoopbackSocketWriter
