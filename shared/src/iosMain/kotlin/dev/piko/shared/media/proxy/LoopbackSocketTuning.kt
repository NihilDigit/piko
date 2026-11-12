package dev.piko.shared.media.proxy

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully

internal actual fun openLoopbackSocketWriter(
    socket: Socket,
    selector: SelectorManager,
    sendBufferBytes: Int,
): LoopbackSocketWriter {
    val channel = socket.openWriteChannel(autoFlush = false)
    return object : LoopbackSocketWriter {
        override suspend fun writeFully(buffer: ByteArray, offset: Int, length: Int) {
            channel.writeFully(buffer, offset, offset + length)
            channel.flush()
        }

        override suspend fun flushAndClose() {
            channel.flushAndClose()
        }
    }
}
