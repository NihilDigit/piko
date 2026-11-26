package dev.piko.ui.screens.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.piko.shared.media.RandomAccessMediaSource
import dev.piko.shared.media.player.AiSubtitlePcmDecoder
import dev.piko.shared.media.player.AiSubtitlePcmFrame
import dev.piko.shared.media.player.AiSubtitlePcmReader
import dev.piko.shared.media.player.AiSubtitlePcmResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Android 端 AI 字幕音频解码器：通过系统 [MediaExtractor] + [MediaDataSource] + [MediaCodec]
 * 直接从 [RandomAccessMediaSource] 解码音轨，并交由共享 [AiSubtitlePcmResampler] 输出 16 kHz 单声道 `pcm_s16le`。
 */
internal class AndroidAiSubtitlePcmDecoder : AiSubtitlePcmDecoder {
    override suspend fun open(
        source: RandomAccessMediaSource,
        audioStreamIndex: Int,
    ): AiSubtitlePcmReader = withContext(Dispatchers.IO) {
        AndroidAiSubtitlePcmReader(source, audioStreamIndex)
    }
}

private class AndroidAiSubtitlePcmReader(
    source: RandomAccessMediaSource,
    preferredAudioOrdinal: Int,
) : AiSubtitlePcmReader {

    private val mutex = Mutex()
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val resampler = AiSubtitlePcmResampler()
    private val pendingFrames = ArrayDeque<AiSubtitlePcmFrame>()

    private var sampleRate: Int
    private var channels: Int
    private var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
    private var monoScratch = FloatArray(4096)
    private var dropBeforeMs = 0L
    private var inputEof = false
    private var outputEof = false
    private var closed = false

    init {
        var openedCodec: MediaCodec? = null
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= source.size) return -1
                    return runBlocking { source.readAt(position, buffer, offset, size) }
                }

                override fun getSize(): Long = source.size

                override fun close() = Unit
            })
            val audioTracks = ArrayList<Pair<Int, MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTracks += i to fmt
                }
            }
            if (audioTracks.isEmpty()) error("No audio track found")
            val (trackIdx, format) = if (preferredAudioOrdinal in audioTracks.indices) {
                audioTracks[preferredAudioOrdinal]
            } else {
                audioTracks.first()
            }
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME")
            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48_000
            channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            openedCodec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            codec = openedCodec
        } catch (t: Throwable) {
            runCatching { openedCodec?.stop() }
            runCatching { openedCodec?.release() }
            runCatching { extractor.release() }
            throw t
        }
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            pendingFrames.clear()
            resampler.reset()
            inputEof = false
            outputEof = false
            extractor.seekTo(positionMs.coerceAtLeast(0L) * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            dropBeforeMs = positionMs.coerceAtLeast(0L)
        }
    }

    override suspend fun readFrame(): AiSubtitlePcmFrame? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            while (true) {
                while (pendingFrames.isEmpty() && !outputEof) {
                    if (!inputEof) {
                        val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEof = true
                            } else {
                                val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIdx >= 0 -> {
                            try {
                                if (bufferInfo.size > 0) {
                                    val outBuf = codec.getOutputBuffer(outIdx)
                                    if (outBuf != null) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        val slice = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN)
                                        val ch = channels.coerceAtLeast(1)
                                        val invCh = 1f / ch.toFloat()
                                        val isFloat = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
                                        val bytesPerFrame = ch * (if (isFloat) 4 else 2)
                                        val frameCount = bufferInfo.size / bytesPerFrame
                                        if (frameCount > 0) {
                                            if (monoScratch.size < frameCount) monoScratch = FloatArray(frameCount)
                                            if (isFloat) {
                                                val fb = slice.asFloatBuffer()
                                                for (i in 0 until frameCount) {
                                                    var sum = 0f
                                                    for (c in 0 until ch) sum += fb.get()
                                                    monoScratch[i] = sum * invCh
                                                }
                                            } else {
                                                val sb = slice.asShortBuffer()
                                                for (i in 0 until frameCount) {
                                                    var sum = 0f
                                                    for (c in 0 until ch) sum += sb.get().toFloat() / 32768f
                                                    monoScratch[i] = sum * invCh
                                                }
                                            }
                                            resampler.pushMonoFloat(
                                                monoSamples = monoScratch,
                                                sampleCount = frameCount,
                                                inputSampleRate = sampleRate,
                                                ptsMs = (bufferInfo.presentationTimeUs / 1_000L).coerceAtLeast(0L),
                                                outFrames = pendingFrames,
                                            )
                                        }
                                    }
                                }
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputEof = true
                                    resampler.flush(pendingFrames)
                                }
                            } finally {
                                codec.releaseOutputBuffer(outIdx, false)
                            }
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = codec.outputFormat
                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
                val next = pendingFrames.removeFirstOrNull() ?: return@withLock null
                if (dropBeforeMs > 0L && next.positionMs + 100L < dropBeforeMs) {
                    continue
                }
                dropBeforeMs = 0L
                return@withLock next
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
