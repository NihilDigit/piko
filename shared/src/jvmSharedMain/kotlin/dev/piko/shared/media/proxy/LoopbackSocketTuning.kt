package dev.piko.shared.media.proxy

import io.ktor.network.selector.SelectInterest
import io.ktor.network.selector.Selectable
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

internal actual fun openLoopbackSocketWriter(
    socket: Socket,
    selector: SelectorManager,
    sendBufferBytes: Int,
): LoopbackSocketWriter {
    val selectable = socket as? Selectable
    val channel = selectable?.channel as? SocketChannel
    if (selectable != null && channel != null) {
        runCatching { channel.setOption(StandardSocketOptions.SO_SNDBUF, sendBufferBytes) }
        selectable.interestOp(SelectInterest.WRITE, false)
        return NioLoopbackSocketWriter(selectable, channel, selector)
    }
    val fallback = socket.openWriteChannel(autoFlush = false)
    return object : LoopbackSocketWriter {
        override suspend fun writeFully(buffer: ByteArray, offset: Int, length: Int) {
            fallback.writeFully(buffer, offset, offset + length)
            fallback.flush()
        }

        override suspend fun flushAndClose() {
            fallback.flushAndClose()
        }
    }
}

private class NioLoopbackSocketWriter(
    private val selectable: Selectable,
    private val channel: SocketChannel,
    private val selector: SelectorManager,
) : LoopbackSocketWriter {
    override suspend fun writeFully(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val bb = ByteBuffer.wrap(buffer, offset, length)
        while (bb.hasRemaining()) {
            currentCoroutineContext().ensureActive()
            while (bb.hasRemaining()) {
                val written = channel.write(bb)
                if (written <= 0) break
            }
            if (!bb.hasRemaining()) break
            selectable.interestOp(SelectInterest.WRITE, true)
            try {
                selector.select(selectable, SelectInterest.WRITE)
            } finally {
                selectable.interestOp(SelectInterest.WRITE, false)
            }
        }
    }

    override suspend fun flushAndClose() {
        runCatching { channel.shutdownOutput() }
    }
}
