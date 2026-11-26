package dev.piko.desktop.media

import dev.piko.desktop.media.FfmpegLayout.AVERROR_EOF
import dev.piko.desktop.media.FfmpegLayout.AVIO_BUFFER
import dev.piko.desktop.media.FfmpegLayout.AVIO_SIZE
import dev.piko.desktop.media.FfmpegLayout.AVMEDIA_TYPE_AUDIO
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FLAG_BACKWARD
import dev.piko.desktop.media.FfmpegLayout.AV_NOPTS_VALUE
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_DBL
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_DBLP
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_FLT
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_FLTP
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_S16
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_S16P
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_S32
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_S32P
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_U8
import dev.piko.desktop.media.FfmpegLayout.AV_SAMPLE_FMT_U8P
import dev.piko.desktop.media.FfmpegLayout.FORMAT_NB_STREAMS
import dev.piko.desktop.media.FfmpegLayout.FORMAT_PB
import dev.piko.desktop.media.FfmpegLayout.FORMAT_SIZE
import dev.piko.desktop.media.FfmpegLayout.FORMAT_START_TIME
import dev.piko.desktop.media.FfmpegLayout.FORMAT_STREAMS
import dev.piko.desktop.media.FfmpegLayout.FRAME_DATA
import dev.piko.desktop.media.FfmpegLayout.FRAME_EXTENDED_DATA
import dev.piko.desktop.media.FfmpegLayout.FRAME_FORMAT
import dev.piko.desktop.media.FfmpegLayout.FRAME_NB_SAMPLES
import dev.piko.desktop.media.FfmpegLayout.FRAME_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.PACKET_DTS
import dev.piko.desktop.media.FfmpegLayout.PACKET_PTS
import dev.piko.desktop.media.FfmpegLayout.PACKET_SIZE
import dev.piko.desktop.media.FfmpegLayout.PACKET_STREAM_INDEX
import dev.piko.desktop.media.FfmpegLayout.PAR_AUDIO_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.PAR_CH_LAYOUT_NB_CHANNELS
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_ID
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_TYPE
import dev.piko.desktop.media.FfmpegLayout.PAR_SAMPLE_RATE
import dev.piko.desktop.media.FfmpegLayout.STREAM_CODECPAR
import dev.piko.desktop.media.FfmpegLayout.STREAM_SIZE
import dev.piko.desktop.media.FfmpegLayout.STREAM_TIME_BASE
import dev.piko.shared.media.RandomAccessMediaSource
import dev.piko.shared.media.player.AiSubtitlePcmDecoder
import dev.piko.shared.media.player.AiSubtitlePcmFrame
import dev.piko.shared.media.player.AiSubtitlePcmReader
import dev.piko.shared.media.player.AiSubtitlePcmResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * 桌面端 AI 字幕音频解码器：复用随包 FFmpeg 8（FFM 直调）与自定义 [AvioReader]，
 * 直接从 [RandomAccessMediaSource] 解码音轨并重采样为 16 kHz 单声道 `pcm_s16le` 帧。
 */
internal class DesktopAiSubtitlePcmDecoder(
    private val ffmpegProvider: () -> Ffmpeg = Ffmpeg::bundled,
) : AiSubtitlePcmDecoder {

    override suspend fun open(
        source: RandomAccessMediaSource,
        audioStreamIndex: Int,
    ): AiSubtitlePcmReader = withContext(Dispatchers.IO) {
        val ffmpeg = ffmpegProvider()
        DesktopAiSubtitlePcmReader(ffmpeg, source, audioStreamIndex)
    }
}

private class DesktopAiSubtitlePcmReader(
    private val ffmpeg: Ffmpeg,
    source: RandomAccessMediaSource,
    preferredAudioOrdinal: Int,
) : AiSubtitlePcmReader {

    private val mutex = Mutex()
    private val arena = Arena.ofShared()
    private val readerJob = SupervisorJob()
    private val avioReader = AvioReader(source, readerJob)
    private val avioRef: MemorySegment
    private val inputRef: MemorySegment
    private val input: MemorySegment
    private val codecCtxRef: MemorySegment
    private val codecCtx: MemorySegment
    private val packetRef: MemorySegment
    private val packet: MemorySegment
    private val frameRef: MemorySegment
    private val frame: MemorySegment

    private val audioStreamIndex: Int
    private val timeBaseNum: Int
    private val timeBaseDen: Int
    private val containerStartUs: Long
    private val sampleRate: Int
    private val channels: Int

    private val resampler = AiSubtitlePcmResampler()
    private val pendingFrames = ArrayDeque<AiSubtitlePcmFrame>()
    private var monoScratch = FloatArray(4096)
    private var currentPtsMs = 0L
    private var dropBeforeMs = 0L
    private var eofReached = false
    private var closed = false

    init {
        var success = false
        avioRef = arena.allocate(ADDRESS)
        inputRef = arena.allocate(ADDRESS)
        codecCtxRef = arena.allocate(ADDRESS)
        packetRef = arena.allocate(ADDRESS)
        frameRef = arena.allocate(ADDRESS)
        try {
            avioRef.set(ADDRESS, 0, avioReader.open(ffmpeg, arena))
            val ctx = (ffmpeg.allocContext.invokeWithArguments() as MemorySegment).reinterpret(FORMAT_SIZE)
            if (ctx == MemorySegment.NULL) throw FfmpegException("Out of memory: avformat_alloc_context")
            ctx.set(ADDRESS, FORMAT_PB, avioRef.get(ADDRESS, 0))
            inputRef.set(ADDRESS, 0, ctx)
            avioReader.guarded {
                ffmpeg.check(
                    ffmpeg.openInput.invokeWithArguments(inputRef, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int,
                    "Open audio input",
                )
            }
            input = inputRef.get(ADDRESS, 0).reinterpret(FORMAT_SIZE)
            avioReader.guarded {
                ffmpeg.check(
                    ffmpeg.findStreamInfo.invokeWithArguments(input, MemorySegment.NULL) as Int,
                    "Find stream info",
                )
            }
            val startRaw = input.get(JAVA_LONG, FORMAT_START_TIME)
            containerStartUs = if (startRaw == AV_NOPTS_VALUE) 0L else startRaw

            val streamCount = input.get(JAVA_INT, FORMAT_NB_STREAMS)
            val streamsPtr = input.get(ADDRESS, FORMAT_STREAMS).reinterpret(streamCount.toLong() * ADDRESS.byteSize())
            val audioStreams = ArrayList<Pair<Int, MemorySegment>>()
            for (i in 0 until streamCount) {
                val st = streamsPtr.getAtIndex(ADDRESS, i.toLong()).reinterpret(STREAM_SIZE)
                val cp = st.get(ADDRESS, STREAM_CODECPAR).reinterpret(PAR_AUDIO_PREFIX_SIZE)
                if (cp.get(JAVA_INT, PAR_CODEC_TYPE) == AVMEDIA_TYPE_AUDIO) {
                    audioStreams += i to st
                }
            }
            if (audioStreams.isEmpty()) throw FfmpegException("No audio stream found")
            val chosen = if (preferredAudioOrdinal in audioStreams.indices) {
                audioStreams[preferredAudioOrdinal]
            } else {
                audioStreams.first()
            }
            audioStreamIndex = chosen.first
            val chosenStream = chosen.second
            timeBaseNum = chosenStream.get(JAVA_INT, STREAM_TIME_BASE).coerceAtLeast(1)
            timeBaseDen = chosenStream.get(JAVA_INT, STREAM_TIME_BASE + 4).coerceAtLeast(1)

            val codecpar = chosenStream.get(ADDRESS, STREAM_CODECPAR).reinterpret(PAR_AUDIO_PREFIX_SIZE)
            val codecId = codecpar.get(JAVA_INT, PAR_CODEC_ID)
            sampleRate = codecpar.get(JAVA_INT, PAR_SAMPLE_RATE).takeIf { it in 8_000..384_000 } ?: 48_000
            channels = codecpar.get(JAVA_INT, PAR_CH_LAYOUT_NB_CHANNELS).takeIf { it in 1..16 } ?: 2

            val decoder = ffmpeg.decoderFind.invokeWithArguments(codecId) as MemorySegment
            if (decoder == MemorySegment.NULL) throw FfmpegException("Audio decoder not found for codecId=$codecId")
            codecCtx = ffmpeg.decoderAlloc.invokeWithArguments(decoder) as MemorySegment
            if (codecCtx == MemorySegment.NULL) throw FfmpegException("Out of memory: avcodec_alloc_context3")
            codecCtxRef.set(ADDRESS, 0, codecCtx)
            ffmpeg.check(
                ffmpeg.parametersToContext.invokeWithArguments(codecCtx, codecpar) as Int,
                "Copy audio codec parameters",
            )
            ffmpeg.check(
                ffmpeg.decoderOpen.invokeWithArguments(codecCtx, decoder, MemorySegment.NULL) as Int,
                "Open audio decoder",
            )

            packet = (ffmpeg.packetAlloc.invokeWithArguments() as MemorySegment).reinterpret(PACKET_SIZE)
            if (packet == MemorySegment.NULL) throw FfmpegException("Out of memory: av_packet_alloc")
            packetRef.set(ADDRESS, 0, packet)

            frame = (ffmpeg.frameAlloc.invokeWithArguments() as MemorySegment).reinterpret(FRAME_PREFIX_SIZE)
            if (frame == MemorySegment.NULL) throw FfmpegException("Out of memory: av_frame_alloc")
            frameRef.set(ADDRESS, 0, frame)
            success = true
        } finally {
            if (!success) closeNative()
        }
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            pendingFrames.clear()
            resampler.reset()
            eofReached = false
            val targetUs = containerStartUs + positionMs.coerceAtLeast(0L) * 1_000L
            val ts = (targetUs.toDouble() / 1_000_000.0 * timeBaseDen.toDouble() / timeBaseNum.toDouble()).toLong()
            val seekCode = avioReader.guarded {
                val primary = ffmpeg.seekFrame.invokeWithArguments(input, audioStreamIndex, ts, AVSEEK_FLAG_BACKWARD) as Int
                if (primary < 0) {
                    ffmpeg.seekFrame.invokeWithArguments(input, -1, targetUs, AVSEEK_FLAG_BACKWARD) as Int
                } else {
                    primary
                }
            }
            if (seekCode < 0) {
                dev.piko.shared.log.PikoLog.w("AiSubtitle", "FFmpeg seekFrame failed: code=$seekCode, targetMs=$positionMs")
            }
            ffmpeg.flushBuffers.invokeWithArguments(codecCtx)
            currentPtsMs = positionMs.coerceAtLeast(0L)
            dropBeforeMs = currentPtsMs
        }
    }

    override suspend fun readFrame(): AiSubtitlePcmFrame? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            while (true) {
                while (pendingFrames.isEmpty()) {
                    if (eofReached) return@withLock null
                    val readCode = avioReader.guarded {
                        ffmpeg.readFrame.invokeWithArguments(input, packet) as Int
                    }
                    if (readCode == AVERROR_EOF) {
                        eofReached = true
                        ffmpeg.sendPacket.invokeWithArguments(codecCtx, MemorySegment.NULL)
                        drainDecodedFrames()
                        resampler.flush(pendingFrames)
                        break
                    }
                    if (readCode < 0) {
                        dev.piko.shared.log.PikoLog.w("AiSubtitle", "FFmpeg readFrame ended with error: code=$readCode, currentPtsMs=$currentPtsMs")
                        eofReached = true
                        resampler.flush(pendingFrames)
                        break
                    }
                    try {
                        val pktStream = packet.get(JAVA_INT, PACKET_STREAM_INDEX)
                        if (pktStream != audioStreamIndex) continue
                        val rawPts = packet.get(JAVA_LONG, PACKET_PTS).takeIf { it != AV_NOPTS_VALUE }
                            ?: packet.get(JAVA_LONG, PACKET_DTS).takeIf { it != AV_NOPTS_VALUE }
                        if (rawPts != null) {
                            val ptsUs = (rawPts.toDouble() * timeBaseNum.toDouble() / timeBaseDen.toDouble() * 1_000_000.0).toLong()
                            currentPtsMs = ((ptsUs - containerStartUs) / 1_000L).coerceAtLeast(0L)
                        }
                        val sendCode = ffmpeg.sendPacket.invokeWithArguments(codecCtx, packet) as Int
                        if (sendCode >= 0) {
                            drainDecodedFrames()
                        }
                    } finally {
                        ffmpeg.packetUnref.invokeWithArguments(packet)
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

    private fun drainDecodedFrames() {
        while (true) {
            val ret = ffmpeg.receiveFrame.invokeWithArguments(codecCtx, frame) as Int
            if (ret < 0) break
            try {
                val nbSamples = frame.get(JAVA_INT, FRAME_NB_SAMPLES)
                val format = frame.get(JAVA_INT, FRAME_FORMAT)
                if (nbSamples <= 0) continue
                if (monoScratch.size < nbSamples) {
                    monoScratch = FloatArray(nbSamples)
                }
                if (downmixToMonoFloat(frame, format, nbSamples, channels, monoScratch)) {
                    resampler.pushMonoFloat(
                        monoSamples = monoScratch,
                        sampleCount = nbSamples,
                        inputSampleRate = sampleRate,
                        ptsMs = currentPtsMs,
                        outFrames = pendingFrames,
                    )
                    currentPtsMs += (nbSamples * 1000L) / sampleRate
                }
            } finally {
                ffmpeg.frameUnref.invokeWithArguments(frame)
            }
        }
    }

    override fun close() {
        readerJob.cancel()
        closeNative()
    }

    private fun closeNative() {
        if (closed) return
        closed = true
        runCatching {
            if (frameRef.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.frameFree.invokeWithArguments(frameRef)
        }
        runCatching {
            if (packetRef.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.packetFree.invokeWithArguments(packetRef)
        }
        runCatching {
            if (codecCtxRef.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.decoderFree.invokeWithArguments(codecCtxRef)
        }
        runCatching {
            if (inputRef.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.closeInput.invokeWithArguments(inputRef)
        }
        runCatching {
            val avioPtr = avioRef.get(ADDRESS, 0)
            if (avioPtr != MemorySegment.NULL) {
                val avio = avioPtr.reinterpret(AVIO_SIZE)
                ffmpeg.freep.invokeWithArguments(avio.asSlice(AVIO_BUFFER))
                ffmpeg.avioContextFree.invokeWithArguments(avioRef)
            }
        }
        runCatching { arena.close() }
    }
}

private fun downmixToMonoFloat(
    frame: MemorySegment,
    format: Int,
    nbSamples: Int,
    channels: Int,
    out: FloatArray,
): Boolean {
    val ch = channels.coerceAtLeast(1)
    val invCh = 1f / ch.toFloat()
    return when (format) {
        AV_SAMPLE_FMT_FLTP -> {
            val planes = readPlanes(frame, ch, nbSamples.toLong() * 4L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                for (c in 0 until ch) sum += planes[c].getAtIndex(JAVA_FLOAT, i.toLong())
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_S16P -> {
            val planes = readPlanes(frame, ch, nbSamples.toLong() * 2L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                for (c in 0 until ch) sum += planes[c].getAtIndex(JAVA_SHORT, i.toLong()).toFloat() / 32768f
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_S32P -> {
            val planes = readPlanes(frame, ch, nbSamples.toLong() * 4L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                for (c in 0 until ch) sum += (planes[c].getAtIndex(JAVA_INT, i.toLong()).toDouble() / 2147483648.0).toFloat()
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_DBLP -> {
            val planes = readPlanes(frame, ch, nbSamples.toLong() * 8L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0.0
                for (c in 0 until ch) sum += planes[c].getAtIndex(JAVA_DOUBLE, i.toLong())
                out[i] = (sum * invCh).toFloat()
            }
            true
        }
        AV_SAMPLE_FMT_U8P -> {
            val planes = readPlanes(frame, ch, nbSamples.toLong()) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                for (c in 0 until ch) sum += ((planes[c].getAtIndex(JAVA_BYTE, i.toLong()).toInt() and 0xFF) - 128) / 128f
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_FLT -> {
            val buf = frame.get(ADDRESS, FRAME_DATA).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(nbSamples.toLong() * ch * 4L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                val base = i.toLong() * ch
                for (c in 0 until ch) sum += buf.getAtIndex(JAVA_FLOAT, base + c)
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_S16 -> {
            val buf = frame.get(ADDRESS, FRAME_DATA).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(nbSamples.toLong() * ch * 2L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                val base = i.toLong() * ch
                for (c in 0 until ch) sum += buf.getAtIndex(JAVA_SHORT, base + c).toFloat() / 32768f
                out[i] = sum * invCh
            }
            true
        }
        AV_SAMPLE_FMT_S32 -> {
            val buf = frame.get(ADDRESS, FRAME_DATA).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(nbSamples.toLong() * ch * 4L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0.0
                val base = i.toLong() * ch
                for (c in 0 until ch) sum += buf.getAtIndex(JAVA_INT, base + c).toDouble() / 2147483648.0
                out[i] = (sum * invCh).toFloat()
            }
            true
        }
        AV_SAMPLE_FMT_DBL -> {
            val buf = frame.get(ADDRESS, FRAME_DATA).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(nbSamples.toLong() * ch * 8L) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0.0
                val base = i.toLong() * ch
                for (c in 0 until ch) sum += buf.getAtIndex(JAVA_DOUBLE, base + c)
                out[i] = (sum * invCh).toFloat()
            }
            true
        }
        AV_SAMPLE_FMT_U8 -> {
            val buf = frame.get(ADDRESS, FRAME_DATA).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(nbSamples.toLong() * ch) ?: return false
            for (i in 0 until nbSamples) {
                var sum = 0f
                val base = i.toLong() * ch
                for (c in 0 until ch) sum += ((buf.getAtIndex(JAVA_BYTE, base + c).toInt() and 0xFF) - 128) / 128f
                out[i] = sum * invCh
            }
            true
        }
        else -> false
    }
}

private fun readPlanes(frame: MemorySegment, channels: Int, planeBytes: Long): Array<MemorySegment>? {
    val ext = frame.get(ADDRESS, FRAME_EXTENDED_DATA)
    val table = if (ext != MemorySegment.NULL) {
        ext.reinterpret(channels.toLong() * ADDRESS.byteSize())
    } else {
        frame.asSlice(FRAME_DATA, minOf(channels, 8).toLong() * ADDRESS.byteSize())
    }
    return Array(channels) { c ->
        val ptr = table.getAtIndex(ADDRESS, c.toLong())
        if (ptr == MemorySegment.NULL) return null
        ptr.reinterpret(planeBytes)
    }
}
