package dev.piko.shared.media

import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.runBlocking
import org.openani.mediamp.io.BufferedSeekableInput
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import kotlin.coroutines.CoroutineContext

/**
 * Exposes one PikPak range reader to both MediaMP backends.
 *
 * Media3's DataSource only models the Android backend, so keeping that adapter
 * in app/ would force desktop to fall back to a plain URL and lose the reader's
 * range scheduling. MediaMP's SeekableInput contract is the platform-neutral
 * boundary. The buffer also absorbs the small, frequent reads made by MKV
 * demuxers before they discover the file's seek table.
 */
@OptIn(ExperimentalMediampApi::class)
class PikoSeekableMediaData(
    override val uri: String,
    private val reader: PikPakStreamReader,
    private val handle: PikPakFileHandle? = null,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) : SeekableInputMediaData {
    private var closed = false

    override val options: List<String> = emptyList()
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY

    override fun fileLength(): Long = reader.size

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
        check(!closed) { "Media data is closed" }
        return PikoBufferedInput(reader, bufferSize)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
        runCatching { handle?.close() }
    }

    private class PikoBufferedInput(
        private val reader: PikPakStreamReader,
        bufferSize: Int,
    ) : BufferedSeekableInput(bufferSize) {
        override val size: Long get() = reader.size

        override fun fillBuffer() {
            val start = position
            val end = (start + buf.size).coerceAtMost(size)
            fillBufferRange(start, end)
        }

        override fun readFileToBuffer(fileOffset: Long, bufferOffset: Int, length: Int): Int {
            if (length == 0) return 0
            runBlocking {
                if (reader.position != fileOffset) {
                    reader.seekTo(fileOffset)
                }

                var filled = 0
                while (filled < length) {
                    val read = reader.read(buf, bufferOffset + filled, length - filled)
                    if (read <= 0) {
                        error("PikPak reader ended at ${fileOffset + filled}, expected $length bytes")
                    }
                    filled += read
                }
            }
            return length
        }

        override fun close() {
            super.close()
            // The MediaData owns the reader; this input only owns its buffer.
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 512 * 1024
    }
}
