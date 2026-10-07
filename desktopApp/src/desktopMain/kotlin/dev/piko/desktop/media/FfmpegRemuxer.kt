package dev.piko.desktop.media

import dev.piko.desktop.media.FfmpegLayout.AVERROR_EIO
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EOF
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EXIT
import dev.piko.desktop.media.FfmpegLayout.AVIO_BUFFER
import dev.piko.desktop.media.FfmpegLayout.AVIO_FLAG_WRITE
import dev.piko.desktop.media.FfmpegLayout.AVIO_SIZE
import dev.piko.desktop.media.FfmpegLayout.AVMEDIA_TYPE_AUDIO
import dev.piko.desktop.media.FfmpegLayout.AVMEDIA_TYPE_VIDEO
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FLAG_BACKWARD
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FORCE
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_SIZE
import dev.piko.desktop.media.FfmpegLayout.AV_CODEC_ID_HEVC
import dev.piko.desktop.media.FfmpegLayout.AV_DISPOSITION_ATTACHED_PIC
import dev.piko.desktop.media.FfmpegLayout.AV_NOPTS_VALUE
import dev.piko.desktop.media.FfmpegLayout.AV_PKT_FLAG_KEY
import dev.piko.desktop.media.FfmpegLayout.AV_ROUND_NEAR_INF_PASS_MINMAX
import dev.piko.desktop.media.FfmpegLayout.FF_COMPLIANCE_NORMAL
import dev.piko.desktop.media.FfmpegLayout.FORMAT_NB_STREAMS
import dev.piko.desktop.media.FfmpegLayout.FORMAT_OFORMAT
import dev.piko.desktop.media.FfmpegLayout.FORMAT_PB
import dev.piko.desktop.media.FfmpegLayout.FORMAT_SIZE
import dev.piko.desktop.media.FfmpegLayout.FORMAT_START_TIME
import dev.piko.desktop.media.FfmpegLayout.FORMAT_STREAMS
import dev.piko.desktop.media.FfmpegLayout.PACKET_DTS
import dev.piko.desktop.media.FfmpegLayout.PACKET_DURATION
import dev.piko.desktop.media.FfmpegLayout.PACKET_FLAGS
import dev.piko.desktop.media.FfmpegLayout.PACKET_POS
import dev.piko.desktop.media.FfmpegLayout.PACKET_PTS
import dev.piko.desktop.media.FfmpegLayout.PACKET_SIZE
import dev.piko.desktop.media.FfmpegLayout.PACKET_STREAM_INDEX
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_ID
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_TAG
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_TYPE
import dev.piko.desktop.media.FfmpegLayout.PAR_SIZE
import dev.piko.desktop.media.FfmpegLayout.STREAM_CODECPAR
import dev.piko.desktop.media.FfmpegLayout.STREAM_DISPOSITION
import dev.piko.desktop.media.FfmpegLayout.STREAM_METADATA
import dev.piko.desktop.media.FfmpegLayout.STREAM_SIZE
import dev.piko.desktop.media.FfmpegLayout.STREAM_TIME_BASE
import dev.piko.desktop.media.FfmpegLayout.TAG_HVC1
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** 截取的一段，毫秒，相对片头，即播放器上显示的时间。 */
internal data class ClipRange(val startMillis: Long, val endMillis: Long)

/**
 * 流复制的转封装：从 FFmpeg 能读的任意容器取出音视频包，原样写进 MP4，不解码、不编码。
 *
 * 源经自定义 AVIO 读取，回调接到 [RandomAccessMediaSource]，即 SDK 的 handle 与稀疏缓存：读过的块留在盘上，
 * 中断后再来不重下，连接预算也归 SDK 管。否决过两条路：交给 FFmpeg 的 http 协议直读本机代理或直链，读位置与重试
 * 都不归我们管，读过的字节不进缓存；先把整个源文件下到本地再切，截一分钟要先下完几 GB。
 *
 * 全部调用在一个线程上阻塞完成（arena 是 confined 的），由调用方放到 IO 线程上。
 */
internal class FfmpegRemuxer(private val ffmpeg: Ffmpeg) {

    /**
     * 把 [source] 转封装成 MP4 写到 [destination]（直接写这个路径，改名由调用方负责）。
     * [range] 为 null 时整段复制；否则从起点之前最近的视频关键帧复制到终点，时间戳从 0 起。
     * HEVC 写成 hvc1，moov 前置。[job] 取消后在下一次读包或读源时停下，抛 [CancellationException]。
     */
    fun remux(source: RandomAccessMediaSource, destination: File, range: ClipRange?, job: Job, onProgress: (Float) -> Unit) {
        Arena.ofConfined().use { arena ->
            val reader = AvioReader(source, job)
            val avioRef = arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, reader.open(ffmpeg, arena)) }
            val inputRef = arena.allocate(ADDRESS)
            try {
                val context = (ffmpeg.allocContext.invokeWithArguments() as MemorySegment).reinterpret(FORMAT_SIZE)
                if (context == MemorySegment.NULL) throw FfmpegException("内存不足：avformat_alloc_context")
                context.set(ADDRESS, FORMAT_PB, avioRef.get(ADDRESS, 0))
                inputRef.set(ADDRESS, 0, context)
                // 失败时它自己释放 context 并把 inputRef 置空，finally 里据此判断
                reader.guarded { ffmpeg.check(ffmpeg.openInput.invokeWithArguments(inputRef, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as Int, "识别源文件格式") }
                val input = inputRef.get(ADDRESS, 0).reinterpret(FORMAT_SIZE)
                reader.guarded { ffmpeg.check(ffmpeg.findStreamInfo.invokeWithArguments(input, MemorySegment.NULL) as Int, "读取流信息") }
                Session(arena, reader, input, destination, onProgress).run(range)
            } finally {
                if (inputRef.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.closeInput.invokeWithArguments(inputRef)
                // 自定义 AVIO 由我们释放；缓冲区可能已被 FFmpeg 换过，按上下文里现在的那个释放
                val avio = avioRef.get(ADDRESS, 0).reinterpret(AVIO_SIZE)
                ffmpeg.freep.invokeWithArguments(avio.asSlice(AVIO_BUFFER))
                ffmpeg.avioContextFree.invokeWithArguments(avioRef)
            }
        }
    }

    private class SourceStream(val index: Int, val stream: MemorySegment) {
        val codecpar: MemorySegment = stream.get(ADDRESS, STREAM_CODECPAR).reinterpret(PAR_SIZE)
        val type: Int = codecpar.get(JAVA_INT, PAR_CODEC_TYPE)
        val codecId: Int = codecpar.get(JAVA_INT, PAR_CODEC_ID)
        val timeBaseNum: Int = stream.get(JAVA_INT, STREAM_TIME_BASE)
        val timeBaseDen: Int = stream.get(JAVA_INT, STREAM_TIME_BASE + 4)
        val attachedPicture: Boolean = stream.get(JAVA_INT, STREAM_DISPOSITION) and AV_DISPOSITION_ATTACHED_PIC != 0
    }

    private inner class Session(
        private val arena: Arena,
        private val reader: AvioReader,
        private val input: MemorySegment,
        private val destination: File,
        private val onProgress: (Float) -> Unit,
    ) {
        private val streams: List<SourceStream> = run {
            val count = input.get(JAVA_INT, FORMAT_NB_STREAMS)
            val array = input.get(ADDRESS, FORMAT_STREAMS).reinterpret(count * ADDRESS.byteSize())
            List(count) { SourceStream(it, array.getAtIndex(ADDRESS, it.toLong()).reinterpret(STREAM_SIZE)) }
        }
        private val packet: MemorySegment = (ffmpeg.packetAlloc.invokeWithArguments() as MemorySegment).reinterpret(PACKET_SIZE)
        private var reported = -1

        fun run(range: ClipRange?) {
            val packetRef = arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, packet) }
            try {
                copy(range)
            } finally {
                ffmpeg.packetFree.invokeWithArguments(packetRef)
            }
        }

        private fun copy(range: ClipRange?) {
            val outputRef = arena.allocate(ADDRESS)
            val path = arena.allocateFrom(destination.absolutePath)
            ffmpeg.check(ffmpeg.allocOutputContext.invokeWithArguments(outputRef, MemorySegment.NULL, arena.allocateFrom("mp4"), path) as Int, "建立 MP4 输出")
            val output = outputRef.get(ADDRESS, 0).reinterpret(FORMAT_SIZE)
            try {
                val selected = selectStreams(output.get(ADDRESS, FORMAT_OFORMAT))
                val outputIndex = IntArray(streams.size) { -1 }
                selected.forEachIndexed { index, stream ->
                    outputIndex[stream.index] = index
                    addOutputStream(output, stream)
                }
                val video = selected.firstOrNull { it.type == AVMEDIA_TYPE_VIDEO }
                val origin = input.get(JAVA_LONG, FORMAT_START_TIME).takeIf { it != AV_NOPTS_VALUE } ?: 0L
                val window = range?.let { clip ->
                    val target = origin + clip.startMillis * 1000
                    val start = video?.let { keyframeAtOrBefore(it, target, origin) } ?: StartPoint(target, target)
                    seek(video, start.seekFrom)
                    start.keyframe..(origin + clip.endMillis * 1000)
                }
                ffmpeg.check(ffmpeg.avioOpen.invokeWithArguments(output.asSlice(FORMAT_PB), path, AVIO_FLAG_WRITE) as Int, "创建输出文件")
                val options = arena.allocate(ADDRESS)
                ffmpeg.dictSet.invokeWithArguments(options, arena.allocateFrom("movflags"), arena.allocateFrom("+faststart"), 0)
                try {
                    ffmpeg.check(ffmpeg.writeHeader.invokeWithArguments(output, options) as Int, "写入 MP4 头")
                } finally {
                    ffmpeg.dictFree.invokeWithArguments(options)
                }
                copyPackets(output, outputIndex, video, origin, window)
                ffmpeg.check(ffmpeg.writeTrailer.invokeWithArguments(output) as Int, "写入 MP4 索引")
                report(1f)
            } finally {
                val pb = output.asSlice(FORMAT_PB)
                if (pb.get(ADDRESS, 0) != MemorySegment.NULL) ffmpeg.avioClosep.invokeWithArguments(pb)
                ffmpeg.freeContext.invokeWithArguments(output)
            }
        }

        /**
         * 视频取一条（av_find_best_stream 挑的那条，跳过封面图），音频取 MP4 装得下的全部；字幕不带，MP4 只认 mov_text，
         * 要转换而不是复制。MP4 装不下视频编码时直接失败，不出一个没有画面的文件。
         */
        private fun selectStreams(format: MemorySegment): List<SourceStream> {
            val best = ffmpeg.findBestStream.invokeWithArguments(input, AVMEDIA_TYPE_VIDEO, -1, -1, MemorySegment.NULL, 0) as Int
            // 片段与转码档下载都是视频，读不出画面就失败，不出一个只有声音的文件
            val video = streams.getOrNull(best)?.takeUnless { it.attachedPicture } ?: throw FfmpegException("源文件中没有可读的视频")
            if (!accepts(format, video)) throw FfmpegException("这种视频编码无法放进 MP4")
            val audio = streams.filter { it.type == AVMEDIA_TYPE_AUDIO && accepts(format, it) }
            return listOf(video) + audio
        }

        // 返回 0 是确定不支持，负数是 FFmpeg 不知道，照样试，写头时自然会报错
        private fun accepts(format: MemorySegment, stream: SourceStream): Boolean =
            ffmpeg.queryCodec.invokeWithArguments(format, stream.codecId, FF_COMPLIANCE_NORMAL) as Int != 0

        private fun addOutputStream(output: MemorySegment, source: SourceStream) {
            val stream = (ffmpeg.newStream.invokeWithArguments(output, MemorySegment.NULL) as MemorySegment).reinterpret(STREAM_SIZE)
            if (stream == MemorySegment.NULL) throw FfmpegException("内存不足：avformat_new_stream")
            val codecpar = stream.get(ADDRESS, STREAM_CODECPAR).reinterpret(PAR_SIZE)
            ffmpeg.check(ffmpeg.parametersCopy.invokeWithArguments(codecpar, source.codecpar) as Int, "复制编码参数")
            // 源容器的 tag 不能照搬（TS 与 MKV 的 tag 在 MP4 里无效），置 0 由 muxer 按编码挑；HEVC 例外，见 TAG_HVC1
            codecpar.set(JAVA_INT, PAR_CODEC_TAG, if (source.codecId == AV_CODEC_ID_HEVC) TAG_HVC1 else 0)
            stream.set(JAVA_INT, STREAM_TIME_BASE, source.timeBaseNum)
            stream.set(JAVA_INT, STREAM_TIME_BASE + 4, source.timeBaseDen)
            stream.set(JAVA_INT, STREAM_DISPOSITION, source.stream.get(JAVA_INT, STREAM_DISPOSITION))
            // 语言等标签：多音轨的 MKV 靠它分辨音轨
            ffmpeg.dictCopy.invokeWithArguments(stream.asSlice(STREAM_METADATA), source.stream.get(ADDRESS, STREAM_METADATA), 0)
        }

        /**
         * [target] 之前（含）最近的视频关键帧的时间，微秒。先跳到 target 往前读，没碰到关键帧就再往前多退一些：
         * 有索引的 MP4、MKV 一次就落在关键帧上，TS 没有索引，FFmpeg 按时间戳二分，落点不保证是关键帧。
         * 片头之前都没有（视频从 target 之后才开始）时取第一个关键帧。
         *
         * 一并交出这次是从哪里跳的，复制时原样再跳一次：直接跳到关键帧的时间上，TS 的落点可能已在那个关键帧的包之后，
         * 起点就顺延到下一个关键帧（实测差了整整一个 GOP）。同一个跳转落点不变，从那里读一定会再碰到它。
         */
        private fun keyframeAtOrBefore(video: SourceStream, target: Long, origin: Long): StartPoint {
            for (lookback in KEYFRAME_LOOKBACK_US) {
                val from = (target - lookback).coerceAtLeast(origin)
                seek(video, from)
                var best: Long? = null
                var firstAfter: Long? = null
                while (readPacket()) {
                    try {
                        if (packet.get(JAVA_INT, PACKET_STREAM_INDEX) != video.index) continue
                        val pts = microseconds(video, packet.get(JAVA_LONG, PACKET_PTS).orElse(packet.get(JAVA_LONG, PACKET_DTS)))
                        val dts = microseconds(video, packet.get(JAVA_LONG, PACKET_DTS).orElse(packet.get(JAVA_LONG, PACKET_PTS)))
                        if (packet.get(JAVA_INT, PACKET_FLAGS) and AV_PKT_FLAG_KEY != 0 && pts != AV_NOPTS_VALUE) {
                            if (pts <= target) best = maxOf(best ?: pts, pts) else if (firstAfter == null) firstAfter = pts
                        }
                        // 解码顺序上越过 target 之后，再来的帧显示时间都不早于它，不会有更合适的关键帧
                        if (dts != AV_NOPTS_VALUE && dts > target) break
                    } finally {
                        ffmpeg.packetUnref.invokeWithArguments(packet)
                    }
                }
                best?.let { return StartPoint(it, from) }
                if (from <= origin) return StartPoint(firstAfter ?: target, from)
            }
            return StartPoint(target, origin)
        }

        private fun seek(video: SourceStream?, microseconds: Long) {
            val (index, timestamp) = if (video == null) -1 to microseconds else video.index to rescale(microseconds, video.timeBaseDen.toLong(), video.timeBaseNum * 1_000_000L)
            reader.guarded { ffmpeg.check(ffmpeg.seekFrame.invokeWithArguments(input, index, timestamp, AVSEEK_FLAG_BACKWARD) as Int, "定位起点") }
        }

        private fun readPacket(): Boolean {
            if (!reader.job.isActive) throw CancellationException("转封装已取消")
            val code = reader.guarded { ffmpeg.readFrame.invokeWithArguments(input, packet) as Int }
            if (code == AVERROR_EOF) return false
            ffmpeg.check(code, "读取源文件")
            return true
        }

        /**
         * 片段：视频从起点关键帧开始、解码时间到终点为止（照 ffmpeg -to 的流复制：按解码时间截，显示时间越过终点的
         * 几帧是前面的帧要参考的，丢了画面会坏）；关键帧之后显示时间早于它的前导帧参考上一组画面，解不出来，丢掉。
         * 音频按显示时间截在同一区间。时间戳整体减去起点。
         */
        private fun copyPackets(output: MemorySegment, outputIndex: IntArray, video: SourceStream?, origin: Long, window: LongRange?) {
            val outputStreams = run {
                val array = output.get(ADDRESS, FORMAT_STREAMS).reinterpret(output.get(JAVA_INT, FORMAT_NB_STREAMS) * ADDRESS.byteSize())
                List(output.get(JAVA_INT, FORMAT_NB_STREAMS)) { array.getAtIndex(ADDRESS, it.toLong()).reinterpret(STREAM_SIZE) }
            }
            val start = window?.first ?: origin
            val lastDts = LongArray(outputStreams.size) { AV_NOPTS_VALUE }
            val finished = BooleanArray(outputStreams.size)
            var videoStarted = window == null || video == null
            while (window == null || !finished.all { it }) {
                if (!readPacket()) break
                try {
                    val inputIndex = packet.get(JAVA_INT, PACKET_STREAM_INDEX)
                    val target = outputIndex.getOrElse(inputIndex) { -1 }
                    if (target < 0) continue
                    val source = streams[inputIndex]
                    val rawPts = packet.get(JAVA_LONG, PACKET_PTS)
                    val rawDts = packet.get(JAVA_LONG, PACKET_DTS)
                    if (rawPts == AV_NOPTS_VALUE && rawDts == AV_NOPTS_VALUE) continue
                    val ptsUs = microseconds(source, rawPts.orElse(rawDts))
                    val dtsUs = microseconds(source, rawDts.orElse(rawPts))
                    if (window != null) {
                        if (dtsUs > window.last + END_OVERRUN_US) break
                        if (source === video) {
                            if (!videoStarted) {
                                if (packet.get(JAVA_INT, PACKET_FLAGS) and AV_PKT_FLAG_KEY == 0 || ptsUs < start) continue
                                videoStarted = true
                            }
                            if (ptsUs < start) continue
                            if (dtsUs >= window.last) {
                                finished[target] = true
                                continue
                            }
                        } else {
                            if (ptsUs < start) continue
                            if (ptsUs >= window.last) {
                                finished[target] = true
                                continue
                            }
                        }
                    }
                    val stream = outputStreams[target]
                    val num = stream.get(JAVA_INT, STREAM_TIME_BASE).toLong()
                    val den = stream.get(JAVA_INT, STREAM_TIME_BASE + 4).toLong()
                    val offset = rescale(start, source.timeBaseDen.toLong(), source.timeBaseNum * 1_000_000L)
                    fun convert(value: Long) = rescale(value, source.timeBaseNum * den, source.timeBaseDen * num)
                    var dts = convert(rawDts.orElse(rawPts) - offset)
                    var pts = convert(rawPts.orElse(rawDts) - offset)
                    // MP4 要求同一条流的解码时间严格递增；TS 偶有重复或回跳的时间戳，照 ffmpeg 的做法往后挪一格
                    if (lastDts[target] != AV_NOPTS_VALUE && dts <= lastDts[target]) dts = lastDts[target] + 1
                    if (pts < dts) pts = dts
                    lastDts[target] = dts
                    packet.set(JAVA_LONG, PACKET_PTS, pts)
                    packet.set(JAVA_LONG, PACKET_DTS, dts)
                    packet.set(JAVA_LONG, PACKET_DURATION, convert(packet.get(JAVA_LONG, PACKET_DURATION)))
                    packet.set(JAVA_LONG, PACKET_POS, -1L)
                    packet.set(JAVA_INT, PACKET_STREAM_INDEX, target)
                    // 写入后包的内容归 muxer，packet 被清空，finally 里的 unref 不再有事可做
                    ffmpeg.check(ffmpeg.interleavedWriteFrame.invokeWithArguments(output, packet) as Int, "写入 MP4")
                    report(
                        if (window == null) reader.furthest.toFloat() / reader.size.coerceAtLeast(1)
                        else (ptsUs - start).toFloat() / (window.last - start).coerceAtLeast(1),
                    )
                } finally {
                    ffmpeg.packetUnref.invokeWithArguments(packet)
                }
            }
        }

        private fun microseconds(stream: SourceStream, value: Long): Long =
            rescale(value, stream.timeBaseNum * 1_000_000L, stream.timeBaseDen.toLong())

        private fun rescale(value: Long, multiplier: Long, divisor: Long): Long =
            ffmpeg.rescaleRnd.invokeWithArguments(value, multiplier, divisor, AV_ROUND_NEAR_INF_PASS_MINMAX) as Long

        // 每多 1% 报一次：一部片几十万个包，逐包报会把任务表的状态流刷爆
        private fun report(fraction: Float) {
            val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
            if (percent > reported) {
                reported = percent
                onProgress(percent / 100f)
            }
        }
    }

    /** 片段的起点关键帧与找到它时的跳转位置，都是微秒。 */
    private class StartPoint(val keyframe: Long, val seekFrom: Long)

    private fun Long.orElse(other: Long): Long = if (this == AV_NOPTS_VALUE) other else this

    private companion object {
        /** 找起点关键帧时依次往前多退的量。转码每 5 秒一个关键帧，原画常见 2 到 10 秒，个别长片头更长。 */
        val KEYFRAME_LOOKBACK_US = longArrayOf(0L, 10_000_000L, 30_000_000L, 120_000_000L)

        /** 某条流到不了终点（例如音轨比画面短）时，读到终点之后这么远就收手，不把剩下的整片读完。 */
        const val END_OVERRUN_US = 5_000_000L
    }
}

/**
 * FFmpeg 读源的 AVIO 回调，按偏移转给 [RandomAccessMediaSource]。回调在 FFmpeg 调用它的那个线程上同步执行，
 * 所以这里阻塞等待挂起的读取。
 *
 * upcall 里抛出的异常会让整个 JVM 崩溃，所以一律接住：记进 [failure]，给 FFmpeg 回一个错误码，
 * 等这次原生调用返回后由 [guarded] 原样抛出（取消照样是 CancellationException）。
 * [read] 与 [seek] 经 MethodHandles 按名字取出，代码里没有直接调用，release 的 ProGuard 要 keep（proguard-rules.pro）。
 */
internal class AvioReader(private val source: RandomAccessMediaSource, val job: Job) {
    private var position = 0L
    private var chunk = ByteArray(0)
    private var failure: Throwable? = null

    val size: Long get() = source.size

    /** 读到过的最远偏移，整段转封装按它报进度。 */
    var furthest = 0L
        private set

    fun read(@Suppress("UNUSED_PARAMETER") opaque: MemorySegment, buffer: MemorySegment, length: Int): Int = try {
        when {
            !job.isActive -> fail(CancellationException("转封装已取消"))
            position >= source.size -> AVERROR_EOF
            else -> {
                if (chunk.size < length) chunk = ByteArray(length)
                // 以 job 为父协程：任务取消时正在等的这次读取一并取消
                val read = runBlocking(job) { source.readAt(position, chunk, 0, length) }
                if (read <= 0) {
                    AVERROR_EOF
                } else {
                    MemorySegment.copy(chunk, 0, buffer.reinterpret(length.toLong()), JAVA_BYTE, 0, read)
                    position += read
                    furthest = maxOf(furthest, position)
                    read
                }
            }
        }
    } catch (e: Throwable) {
        fail(e)
    }

    fun seek(@Suppress("UNUSED_PARAMETER") opaque: MemorySegment, offset: Long, whence: Int): Long = try {
        when (whence and AVSEEK_FORCE.inv()) {
            AVSEEK_SIZE -> source.size
            SEEK_SET -> offset.also { position = it }
            SEEK_CUR -> (position + offset).also { position = it }
            SEEK_END -> (source.size + offset).also { position = it }
            else -> AVERROR_EIO.toLong()
        }
    } catch (e: Throwable) {
        fail(e).toLong()
    }

    private fun fail(error: Throwable): Int {
        if (failure == null) failure = error
        return if (error is CancellationException) AVERROR_EXIT else AVERROR_EIO
    }

    /** 执行一次可能回调到这里的原生调用；回调里出过错就抛那个错，而不是 FFmpeg 转述的错误码。 */
    fun <T> guarded(call: () -> T): T {
        val result = try {
            call()
        } catch (e: FfmpegException) {
            failure?.let { throw it }
            throw e
        }
        failure?.let { throw it }
        return result
    }

    /** 建 AVIOContext。缓冲区与上下文由 [FfmpegRemuxer] 在用完后释放，回调存根随 [arena] 释放。 */
    fun open(ffmpeg: Ffmpeg, arena: Arena): MemorySegment {
        val lookup = MethodHandles.lookup()
        val readStub = Linker.nativeLinker().upcallStub(
            lookup.findVirtual(
                AvioReader::class.java, "read",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java, Int::class.javaPrimitiveType),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
            arena,
        )
        val seekStub = Linker.nativeLinker().upcallStub(
            lookup.findVirtual(
                AvioReader::class.java, "seek",
                MethodType.methodType(Long::class.javaPrimitiveType, MemorySegment::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT),
            arena,
        )
        val buffer = ffmpeg.malloc.invokeWithArguments(BUFFER_BYTES.toLong()) as MemorySegment
        if (buffer == MemorySegment.NULL) throw FfmpegException("内存不足：av_malloc")
        val context = ffmpeg.avioAllocContext.invokeWithArguments(buffer, BUFFER_BYTES, 0, MemorySegment.NULL, readStub, MemorySegment.NULL, seekStub) as MemorySegment
        if (context == MemorySegment.NULL) {
            ffmpeg.freep.invokeWithArguments(arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, buffer) })
            throw FfmpegException("内存不足：avio_alloc_context")
        }
        return context
    }

    private companion object {
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        /** 与 SDK 的块一样大，一次回调正好取一块。 */
        const val BUFFER_BYTES = 256 * 1024
    }
}
