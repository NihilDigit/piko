package dev.piko.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EIO
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EOF
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EXIT
import dev.piko.desktop.media.FfmpegLayout.AVIO_BUFFER
import dev.piko.desktop.media.FfmpegLayout.AVIO_SIZE
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FLAG_BACKWARD
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FORCE
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_SIZE
import dev.piko.desktop.media.FfmpegLayout.AV_CODEC_ID_H264
import dev.piko.desktop.media.FfmpegLayout.AV_CODEC_ID_HEVC
import dev.piko.desktop.media.FfmpegLayout.AV_PIX_FMT_RGB24
import dev.piko.desktop.media.FfmpegLayout.AV_PKT_DATA_DISPLAYMATRIX
import dev.piko.desktop.media.FfmpegLayout.FORMAT_NB_STREAMS
import dev.piko.desktop.media.FfmpegLayout.FORMAT_PB
import dev.piko.desktop.media.FfmpegLayout.FORMAT_SIZE
import dev.piko.desktop.media.FfmpegLayout.FORMAT_STREAMS
import dev.piko.desktop.media.FfmpegLayout.FRAME_FORMAT
import dev.piko.desktop.media.FfmpegLayout.FRAME_HEIGHT
import dev.piko.desktop.media.FfmpegLayout.FRAME_LINESIZE
import dev.piko.desktop.media.FfmpegLayout.FRAME_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.FRAME_WIDTH
import dev.piko.desktop.media.FfmpegLayout.PACKET_DATA
import dev.piko.desktop.media.FfmpegLayout.PACKET_SIZE
import dev.piko.desktop.media.FfmpegLayout.PACKET_STREAM_INDEX
import dev.piko.shared.media.storyboard.StoryboardIdrDecoder
import dev.piko.shared.media.storyboard.TsVideoCodec
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_ID
import dev.piko.desktop.media.FfmpegLayout.PAR_NB_SIDE_DATA
import dev.piko.desktop.media.FfmpegLayout.PAR_SIDE_DATA
import dev.piko.desktop.media.FfmpegLayout.PAR_VIDEO_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_BYTES
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_SIZE
import dev.piko.desktop.media.FfmpegLayout.STREAM_CODECPAR
import dev.piko.desktop.media.FfmpegLayout.STREAM_SIZE
import dev.piko.desktop.media.FfmpegLayout.SWS_BILINEAR
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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * 桌面端轻量预览帧解码器：直接复用随包 FFmpeg 8.0.1（FFM 零外部进程），
 * 支持从内存 MPEG-TS 关键帧分块或本地视频文件指定时刻解码单张缩略图并直写 Skia [ImageBitmap]。
 * 与 [LocalVideoProbe] 一样绑定标准 `Function3::invoke` 回调，Release ProGuard 裁剪无需额外 keep 规则。
 */
internal object PreviewFrameDecoder {
    private val concurrency = Semaphore(2)
    private const val DECODE_TIMEOUT_MS = 1_500L
    private const val MAX_PACKETS = 240

    /**
     * 创建复用单一持久化 FFmpeg 8 `AVCodecContext`（HEVC）的雪碧图 IDR 解码器：
     * 全程跳过 `avformat_open_input` / `av_seek_frame`，直接 `avcodec_send_packet` -> `avcodec_receive_frame`
     * -> 复用 `SwsContext` 缩放到 `<= 384x216` -> Skia JPEG，单帧耗时约 2–6ms。
     */
    fun createPersistentHevcDecoder(): StoryboardIdrDecoder? = runCatching {
        PersistentHevcIdrDecoder(Ffmpeg.bundled())
    }.getOrNull()

    /** 将紧凑 JPEG/WebP 字节直接解码为 Compose [ImageBitmap]（用于磁盘雪碧图命中 `< 0.3ms` 渲染）。 */
    fun decodeEncodedImage(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            SkiaImage.makeFromEncoded(bytes).use { image ->
                Bitmap.makeFromImage(image).apply { setImmutable() }.asComposeImageBitmap()
            }
        }.getOrNull()
    }

    suspend fun decodeFromBytes(bytes: ByteArray, maxWidthPx: Int = 320): ImageBitmap? {
        if (bytes.size < 376) return null
        return concurrency.withPermit {
            val activeContext = coroutineContext
            withContext(Dispatchers.IO) {
                runCatching {
                    val ff = Ffmpeg.bundled()
                    decodeInternal(
                        ff = ff,
                        openAvio = { arena, interrupted -> bytesAvio(bytes, ff, arena, interrupted) },
                        seekMillis = null,
                        probeBytes = min(bytes.size, 256 * 1024).toString(),
                        analyzeUs = "1200000",
                        maxWidthPx = maxWidthPx,
                        cancelled = { !activeContext.isActive },
                    )
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
            }
        }
    }

    suspend fun decodeFromLocalFile(path: String, positionMillis: Long, maxWidthPx: Int = 320): ImageBitmap? {
        val file = File(path)
        if (!file.isFile) return null
        return concurrency.withPermit {
            val activeContext = coroutineContext
            withContext(Dispatchers.IO) {
                runCatching {
                    val ff = Ffmpeg.bundled()
                    FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                        decodeInternal(
                            ff = ff,
                            openAvio = { arena, interrupted -> channelAvio(channel, ff, arena, interrupted) },
                            seekMillis = positionMillis.coerceAtLeast(0L),
                            probeBytes = "2097152",
                            analyzeUs = "1500000",
                            maxWidthPx = maxWidthPx,
                            cancelled = { !activeContext.isActive },
                        )
                    }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }
            }
        }
    }

    private fun decodeInternal(
        ff: Ffmpeg,
        openAvio: (Arena, () -> Boolean) -> MemorySegment,
        seekMillis: Long?,
        probeBytes: String,
        analyzeUs: String,
        maxWidthPx: Int,
        cancelled: () -> Boolean,
    ): ImageBitmap? = Arena.ofConfined().use { arena ->
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DECODE_TIMEOUT_MS)
        fun interrupted() = cancelled() || System.nanoTime() > deadlineNanos
        if (interrupted()) return@use null

        val avioRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val openOptionsRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val inputRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val decoderRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val frameRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val packetRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
        val codecOptionsRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }

        try {
            avioRef.set(ADDRESS, 0, openAvio(arena, ::interrupted))
            val context = ff.allocContext.invokeWithArguments() as MemorySegment
            if (context == MemorySegment.NULL) return@use null
            context.reinterpret(FORMAT_SIZE).set(ADDRESS, FORMAT_PB, avioRef.get(ADDRESS, 0))
            inputRef.set(ADDRESS, 0, context)
            for ((k, v) in mapOf("protocol_whitelist" to "", "probesize" to probeBytes, "analyzeduration" to analyzeUs)) {
                ff.dictSet.invokeWithArguments(openOptionsRef, arena.allocateFrom(k), arena.allocateFrom(v), 0)
            }
            if ((ff.openInput.invokeWithArguments(inputRef, MemorySegment.NULL, MemorySegment.NULL, openOptionsRef) as Int) < 0) {
                return@use null
            }
            if (interrupted()) return@use null
            val input = inputRef.get(ADDRESS, 0).reinterpret(FORMAT_SIZE)
            if ((ff.findStreamInfo.invokeWithArguments(input, MemorySegment.NULL) as Int) < 0) return@use null
            if (interrupted()) return@use null

            val index = ff.findBestStream.invokeWithArguments(input, 0, -1, -1, MemorySegment.NULL, 0) as Int
            if (index < 0) return@use null
            val count = input.get(JAVA_INT, FORMAT_NB_STREAMS)
            if (index >= count) return@use null
            val streams = input.get(ADDRESS, FORMAT_STREAMS).reinterpret(count * 8L)
            val stream = streams.getAtIndex(ADDRESS, index.toLong()).reinterpret(STREAM_SIZE)
            val parameters = stream.get(ADDRESS, STREAM_CODECPAR).reinterpret(PAR_VIDEO_PREFIX_SIZE)

            val sideData = ff.sideDataGet.invokeWithArguments(
                parameters.get(ADDRESS, PAR_SIDE_DATA),
                parameters.get(JAVA_INT, PAR_NB_SIDE_DATA),
                AV_PKT_DATA_DISPLAYMATRIX,
            ) as MemorySegment
            val angle = if (sideData == MemorySegment.NULL) {
                0
            } else {
                val side = sideData.reinterpret(SIDE_DATA_PREFIX_SIZE)
                val matrix = side.get(ADDRESS, SIDE_DATA_BYTES)
                if (side.get(JAVA_LONG, SIDE_DATA_SIZE) >= 36L && matrix != MemorySegment.NULL) {
                    val degrees = -(ff.displayRotation.invokeWithArguments(matrix) as Double)
                    if (degrees.isFinite()) ((degrees.roundToInt() % 360) + 360) % 360 else 0
                } else {
                    0
                }
            }

            val codec = ff.decoderFind.invokeWithArguments(parameters.get(JAVA_INT, PAR_CODEC_ID)) as MemorySegment
            if (codec == MemorySegment.NULL) return@use null
            val decoder = ff.decoderAlloc.invokeWithArguments(codec) as MemorySegment
            if (decoder == MemorySegment.NULL) return@use null
            decoderRef.set(ADDRESS, 0, decoder)
            if ((ff.parametersToContext.invokeWithArguments(decoder, parameters) as Int) < 0) return@use null
            ff.dictSet.invokeWithArguments(codecOptionsRef, arena.allocateFrom("threads"), arena.allocateFrom("2"), 0)
            if ((ff.decoderOpen.invokeWithArguments(decoder, codec, codecOptionsRef) as Int) < 0) return@use null

            val frame = (ff.frameAlloc.invokeWithArguments() as MemorySegment).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(FRAME_PREFIX_SIZE) ?: return@use null
            frameRef.set(ADDRESS, 0, frame)
            val packet = (ff.packetAlloc.invokeWithArguments() as MemorySegment).takeIf { it != MemorySegment.NULL }
                ?.reinterpret(PACKET_SIZE) ?: return@use null
            packetRef.set(ADDRESS, 0, packet)

            if (seekMillis != null && seekMillis > 0L) {
                ff.seekFrame.invokeWithArguments(input, -1, seekMillis * 1_000L, AVSEEK_FLAG_BACKWARD)
            }

            for (attempt in 0 until MAX_PACKETS) {
                if (interrupted()) return@use null
                val r = ff.readFrame.invokeWithArguments(input, packet) as Int
                if (r < 0) break
                try {
                    if (packet.get(JAVA_INT, PACKET_STREAM_INDEX) != index) continue
                    if ((ff.sendPacket.invokeWithArguments(decoder, packet) as Int) < 0) continue
                    if ((ff.receiveFrame.invokeWithArguments(decoder, frame) as Int) == 0) {
                        return@use frameToImageBitmap(ff, frame, arena, maxWidthPx, angle)
                    }
                } finally {
                    ff.packetUnref.invokeWithArguments(packet)
                }
            }
            // Flush decoder in case the first IDR frame was buffered for reordering
            if (!interrupted() && (ff.sendPacket.invokeWithArguments(decoder, MemorySegment.NULL) as Int) >= 0) {
                if ((ff.receiveFrame.invokeWithArguments(decoder, frame) as Int) == 0) {
                    return@use frameToImageBitmap(ff, frame, arena, maxWidthPx, angle)
                }
            }
            null
        } finally {
            ff.dictFree.invokeWithArguments(openOptionsRef)
            ff.dictFree.invokeWithArguments(codecOptionsRef)
            if (packetRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.packetFree.invokeWithArguments(packetRef)
            if (frameRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.frameFree.invokeWithArguments(frameRef)
            if (decoderRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.decoderFree.invokeWithArguments(decoderRef)
            if (inputRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.closeInput.invokeWithArguments(inputRef)
            if (avioRef.get(ADDRESS, 0) != MemorySegment.NULL) {
                val avio = avioRef.get(ADDRESS, 0).reinterpret(AVIO_SIZE)
                ff.freep.invokeWithArguments(avio.asSlice(AVIO_BUFFER))
                ff.avioContextFree.invokeWithArguments(avioRef)
            }
        }
    }

    private fun bytesAvio(bytes: ByteArray, ff: Ffmpeg, arena: Arena, interrupted: () -> Boolean): MemorySegment {
        var pos = 0
        val read: (MemorySegment, MemorySegment, Int) -> Int = { _, buffer, length ->
            try {
                if (interrupted()) {
                    AVERROR_EXIT
                } else if (pos >= bytes.size) {
                    AVERROR_EOF
                } else {
                    val count = min(length, bytes.size - pos)
                    buffer.reinterpret(count.toLong()).asByteBuffer().put(bytes, pos, count)
                    pos += count
                    count
                }
            } catch (_: Throwable) {
                AVERROR_EIO
            }
        }
        val seek: (MemorySegment, Long, Int) -> Long = { _, offset, whence ->
            try {
                if (interrupted()) {
                    AVERROR_EXIT.toLong()
                } else {
                    when (whence and AVSEEK_FORCE.inv()) {
                        AVSEEK_SIZE -> bytes.size.toLong()
                        0 -> {
                            if (offset < 0L || offset > bytes.size.toLong()) AVERROR_EIO.toLong()
                            else {
                                pos = offset.toInt()
                                pos.toLong()
                            }
                        }
                        1 -> {
                            val target = Math.addExact(pos.toLong(), offset)
                            if (target < 0L || target > bytes.size.toLong()) AVERROR_EIO.toLong()
                            else {
                                pos = target.toInt()
                                pos.toLong()
                            }
                        }
                        2 -> {
                            val target = Math.addExact(bytes.size.toLong(), offset)
                            if (target < 0L || target > bytes.size.toLong()) AVERROR_EIO.toLong()
                            else {
                                pos = target.toInt()
                                pos.toLong()
                            }
                        }
                        else -> AVERROR_EIO.toLong()
                    }
                }
            } catch (_: Throwable) {
                AVERROR_EIO.toLong()
            }
        }
        return allocAvio(ff, arena, read, seek, bufferSize = 64 * 1024)
    }

    private fun channelAvio(channel: FileChannel, ff: Ffmpeg, arena: Arena, interrupted: () -> Boolean): MemorySegment {
        val read: (MemorySegment, MemorySegment, Int) -> Int = { _, buffer, length ->
            try {
                if (interrupted()) {
                    AVERROR_EXIT
                } else {
                    val bb: ByteBuffer = buffer.reinterpret(length.toLong()).asByteBuffer()
                    channel.read(bb).let { if (it <= 0) AVERROR_EOF else it }
                }
            } catch (_: Throwable) {
                AVERROR_EIO
            }
        }
        val seek: (MemorySegment, Long, Int) -> Long = { _, offset, whence ->
            try {
                if (interrupted()) {
                    AVERROR_EXIT.toLong()
                } else {
                    when (whence and AVSEEK_FORCE.inv()) {
                        AVSEEK_SIZE -> channel.size()
                        0 -> channel.position(offset).position()
                        1 -> channel.position(Math.addExact(channel.position(), offset)).position()
                        2 -> channel.position(Math.addExact(channel.size(), offset)).position()
                        else -> AVERROR_EIO.toLong()
                    }
                }
            } catch (_: Throwable) {
                AVERROR_EIO.toLong()
            }
        }
        return allocAvio(ff, arena, read, seek, bufferSize = 128 * 1024)
    }

    private fun allocAvio(
        ff: Ffmpeg,
        arena: Arena,
        read: (MemorySegment, MemorySegment, Int) -> Int,
        seek: (MemorySegment, Long, Int) -> Long,
        bufferSize: Int,
    ): MemorySegment {
        val invoke = MethodHandles.lookup().findVirtual(
            Function3::class.java,
            "invoke",
            MethodType.methodType(Any::class.java, Any::class.java, Any::class.java, Any::class.java),
        )
        val linker = Linker.nativeLinker()
        val readStub = linker.upcallStub(
            invoke.bindTo(read).asType(
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                    Int::class.javaPrimitiveType,
                ),
            ),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
            arena,
        )
        val seekStub = linker.upcallStub(
            invoke.bindTo(seek).asType(
                MethodType.methodType(
                    Long::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            ),
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT),
            arena,
        )
        val bufferRef = arena.allocate(ADDRESS)
        bufferRef.set(ADDRESS, 0, ff.malloc.invokeWithArguments(bufferSize.toLong()) as MemorySegment)
        check(bufferRef.get(ADDRESS, 0) != MemorySegment.NULL)
        try {
            val avio = ff.avioAllocContext.invokeWithArguments(
                bufferRef.get(ADDRESS, 0),
                bufferSize,
                0,
                MemorySegment.NULL,
                readStub,
                MemorySegment.NULL,
                seekStub,
            ) as MemorySegment
            check(avio != MemorySegment.NULL)
            bufferRef.set(ADDRESS, 0, MemorySegment.NULL)
            return avio
        } finally {
            ff.freep.invokeWithArguments(bufferRef)
        }
    }

    private fun frameToImageBitmap(
        ff: Ffmpeg,
        frame: MemorySegment,
        arena: Arena,
        maxWidthPx: Int,
        angle: Int,
    ): ImageBitmap? {
        val width = frame.get(JAVA_INT, FRAME_WIDTH)
        val height = frame.get(JAVA_INT, FRAME_HEIGHT)
        val format = frame.get(JAVA_INT, FRAME_FORMAT)
        if (width !in 1..32768 || height !in 1..32768) return null
        val targetMax = maxWidthPx.coerceIn(64, 640).toDouble()
        val scale = minOf(1.0, targetMax / maxOf(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(2)
        val h = (height * scale).roundToInt().coerceAtLeast(2)
        val scaler = ff.scaleContext.invokeWithArguments(
            width, height, format, w, h, AV_PIX_FMT_RGB24, SWS_BILINEAR,
            MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
        ) as MemorySegment
        if (scaler == MemorySegment.NULL) return null
        try {
            val stride = ((w * 3 + 31) / 32) * 32
            val pixels = arena.allocate(stride * h.toLong() + 64, 32)
            val pointers = arena.allocate(ADDRESS, 8).apply { set(ADDRESS, 0, pixels) }
            val strides = arena.allocate(JAVA_INT, 8).apply { set(JAVA_INT, 0, stride) }
            if ((ff.scaleFrame.invokeWithArguments(scaler, frame, frame.asSlice(FRAME_LINESIZE), 0, height, pointers, strides) as Int) < 0) {
                return null
            }
            val swap = angle == 90 || angle == 270
            val outW = if (swap) h else w
            val outH = if (swap) w else h
            val bgra = ByteArray(outW * outH * 4)
            for (y in 0 until h) {
                val rowOff = y.toLong() * stride
                for (x in 0 until w) {
                    val src = rowOff + x * 3L
                    val r = pixels.get(JAVA_BYTE, src)
                    val g = pixels.get(JAVA_BYTE, src + 1L)
                    val b = pixels.get(JAVA_BYTE, src + 2L)
                    val dx: Int
                    val dy: Int
                    when (angle) {
                        90 -> {
                            dx = h - 1 - y
                            dy = x
                        }
                        180 -> {
                            dx = w - 1 - x
                            dy = h - 1 - y
                        }
                        270 -> {
                            dx = y
                            dy = w - 1 - x
                        }
                        else -> {
                            dx = x
                            dy = y
                        }
                    }
                    val dst = (dy * outW + dx) * 4
                    bgra[dst] = b
                    bgra[dst + 1] = g
                    bgra[dst + 2] = r
                    bgra[dst + 3] = 0xFF.toByte()
                }
            }
            val skia = Bitmap()
            val info = ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            if (!skia.installPixels(info, bgra, outW * 4)) return null
            skia.setImmutable()
            return skia.asComposeImageBitmap()
        } finally {
            ff.scaleFree.invokeWithArguments(scaler)
        }
    }
}

/**
 * 复用单一 FFmpeg 8（HEVC / H.264）`AVCodecContext` + `AVFrame` + `AVPacket` + `SwsContext` 的持久化单帧 IDR 解码器。
 * 经 `Arena.ofShared()` 配合对象互斥锁，支持在协程 IO 线程池上跨线程安全调用并在 `close()` 时确定性释放全部原生资源。
 */
private class PersistentHevcIdrDecoder(
    private val ff: Ffmpeg,
) : StoryboardIdrDecoder {
    private val nativeLock = Any()
    private val arena = Arena.ofShared()
    private val decoderRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    private val frameRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    private val packetRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    private var openCodec: TsVideoCodec? = null
    private var scaler: MemorySegment = MemorySegment.NULL
    private var scalerSrcW = 0
    private var scalerSrcH = 0
    private var scalerSrcFmt = -1
    private var scalerDstW = 0
    private var scalerDstH = 0
    private var closed = false

    init {
        try {
            val frame = (ff.frameAlloc.invokeWithArguments() as MemorySegment)
                .takeIf { it != MemorySegment.NULL }
                ?.reinterpret(FRAME_PREFIX_SIZE)
                ?: error("Failed to allocate AVFrame")
            frameRef.set(ADDRESS, 0, frame)
            val packet = (ff.packetAlloc.invokeWithArguments() as MemorySegment)
                .takeIf { it != MemorySegment.NULL }
                ?.reinterpret(PACKET_SIZE)
                ?: error("Failed to allocate AVPacket")
            packetRef.set(ADDRESS, 0, packet)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    private fun ensureDecoderLocked(targetCodec: TsVideoCodec): MemorySegment {
        val current = decoderRef.get(ADDRESS, 0)
        if (current != MemorySegment.NULL && openCodec == targetCodec) return current
        if (current != MemorySegment.NULL) {
            ff.decoderFree.invokeWithArguments(decoderRef)
            decoderRef.set(ADDRESS, 0, MemorySegment.NULL)
            openCodec = null
        }
        val codecId = when (targetCodec) {
            TsVideoCodec.HEVC -> AV_CODEC_ID_HEVC
            TsVideoCodec.AVC -> AV_CODEC_ID_H264
        }
        val codec = ff.decoderFind.invokeWithArguments(codecId) as MemorySegment
        check(codec != MemorySegment.NULL) { "FFmpeg decoder not found for $targetCodec" }
        val decoder = ff.decoderAlloc.invokeWithArguments(codec) as MemorySegment
        check(decoder != MemorySegment.NULL) { "Failed to allocate AVCodecContext for $targetCodec" }
        decoderRef.set(ADDRESS, 0, decoder)
        Arena.ofConfined().use { initArena ->
            val codecOptionsRef = initArena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
            try {
                // 单帧 IDR/CRA 缩略图无需帧级多线程：显式单线程避免在 ARM64 / 软解播放时拉起多线程引发 CPU 抖动与音画不同步，
                // 并启用 +showall 确保独立的 HEVC CRA (NALU 21) 在 flush 后干净解码输出。
                ff.dictSet.invokeWithArguments(codecOptionsRef, initArena.allocateFrom("threads"), initArena.allocateFrom("1"), 0)
                ff.dictSet.invokeWithArguments(codecOptionsRef, initArena.allocateFrom("flags2"), initArena.allocateFrom("+showall"), 0)
                check((ff.decoderOpen.invokeWithArguments(decoder, codec, codecOptionsRef) as Int) >= 0) {
                    "Failed to open persistent $targetCodec decoder"
                }
            } catch (t: Throwable) {
                ff.decoderFree.invokeWithArguments(decoderRef)
                decoderRef.set(ADDRESS, 0, MemorySegment.NULL)
                throw t
            } finally {
                ff.dictFree.invokeWithArguments(codecOptionsRef)
            }
        }
        openCodec = targetCodec
        return decoder
    }

    override suspend fun decodeToJpeg(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        width: Int,
        height: Int,
        maxWidthPx: Int,
        jpegQuality: Int,
    ): ByteArray? = decodeToJpeg(
        csdAnnexB = csdAnnexB,
        idrAccessUnitAnnexB = idrAccessUnitAnnexB,
        width = width,
        height = height,
        codec = TsVideoCodec.HEVC,
        maxWidthPx = maxWidthPx,
        jpegQuality = jpegQuality,
    )

    override suspend fun decodeToJpeg(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        width: Int,
        height: Int,
        codec: TsVideoCodec,
        maxWidthPx: Int,
        jpegQuality: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (idrAccessUnitAnnexB.isEmpty()) return@withContext null
        synchronized(nativeLock) {
            if (closed) return@withContext null
            runCatching {
                decodeSynchronized(csdAnnexB, idrAccessUnitAnnexB, codec, maxWidthPx, jpegQuality)
            }.getOrNull()
        }
    }

    private fun decodeSynchronized(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        codec: TsVideoCodec,
        maxWidthPx: Int,
        jpegQuality: Int,
    ): ByteArray? {
        val decoder = ensureDecoderLocked(codec)
        val frame = frameRef.get(ADDRESS, 0).reinterpret(FRAME_PREFIX_SIZE)
        val packet = packetRef.get(ADDRESS, 0).reinterpret(PACKET_SIZE)
        if (decoder == MemorySegment.NULL) return null

        val payload = if (csdAnnexB.isNotEmpty() && !startsWithParameterSet(idrAccessUnitAnnexB, codec)) {
            ByteArray(csdAnnexB.size + idrAccessUnitAnnexB.size).also { out ->
                csdAnnexB.copyInto(out, 0)
                idrAccessUnitAnnexB.copyInto(out, csdAnnexB.size)
            }
        } else {
            idrAccessUnitAnnexB
        }

        // 清空上一帧参考缓存，确保随机顺序的单帧 IDR 直接输出不积压重排队列
        ff.flushBuffers.invokeWithArguments(decoder)
        if ((ff.packetNew.invokeWithArguments(packet, payload.size) as Int) < 0) return null
        try {
            val dataPtr = packet.get(ADDRESS, PACKET_DATA)
            if (dataPtr == MemorySegment.NULL) return null
            val dst = dataPtr.reinterpret(payload.size.toLong())
            MemorySegment.copy(payload, 0, dst, JAVA_BYTE, 0, payload.size)
            if ((ff.sendPacket.invokeWithArguments(decoder, packet) as Int) < 0) return null
        } finally {
            ff.packetUnref.invokeWithArguments(packet)
        }

        var gotFrame = (ff.receiveFrame.invokeWithArguments(decoder, frame) as Int) == 0
        if (!gotFrame) {
            if ((ff.sendPacket.invokeWithArguments(decoder, MemorySegment.NULL) as Int) >= 0) {
                gotFrame = (ff.receiveFrame.invokeWithArguments(decoder, frame) as Int) == 0
            }
        }
        if (!gotFrame) return null
        return try {
            encodeFrameToJpeg(frame, maxWidthPx, jpegQuality)
        } finally {
            ff.frameUnref.invokeWithArguments(frame)
        }
    }

    private fun encodeFrameToJpeg(frame: MemorySegment, maxWidthPx: Int, jpegQuality: Int): ByteArray? {
        val srcW = frame.get(JAVA_INT, FRAME_WIDTH)
        val srcH = frame.get(JAVA_INT, FRAME_HEIGHT)
        val srcFmt = frame.get(JAVA_INT, FRAME_FORMAT)
        if (srcW !in 1..8192 || srcH !in 1..8192) return null

        val targetMax = maxWidthPx.coerceIn(64, 480).toDouble()
        val scale = minOf(1.0, targetMax / maxOf(srcW, srcH))
        val outW = ((srcW * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
        val outH = ((srcH * scale).roundToInt() and 1.inv()).coerceAtLeast(2)

        if (scaler == MemorySegment.NULL ||
            scalerSrcW != srcW || scalerSrcH != srcH || scalerSrcFmt != srcFmt ||
            scalerDstW != outW || scalerDstH != outH
        ) {
            if (scaler != MemorySegment.NULL) {
                ff.scaleFree.invokeWithArguments(scaler)
                scaler = MemorySegment.NULL
            }
            scaler = ff.scaleContext.invokeWithArguments(
                srcW, srcH, srcFmt, outW, outH, AV_PIX_FMT_RGB24, SWS_BILINEAR,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            if (scaler == MemorySegment.NULL) return null
            scalerSrcW = srcW
            scalerSrcH = srcH
            scalerSrcFmt = srcFmt
            scalerDstW = outW
            scalerDstH = outH
        }

        val stride = ((outW * 3 + 31) / 32) * 32
        Arena.ofConfined().use { scratch ->
            val pixels = scratch.allocate(stride * outH.toLong() + 64L, 32L)
            val pointers = scratch.allocate(ADDRESS, 8L).apply { set(ADDRESS, 0, pixels) }
            val strides = scratch.allocate(JAVA_INT, 8L).apply { set(JAVA_INT, 0, stride) }
            if ((ff.scaleFrame.invokeWithArguments(scaler, frame, frame.asSlice(FRAME_LINESIZE), 0, srcH, pointers, strides) as Int) < 0) {
                return null
            }
            val bgra = ByteArray(outW * outH * 4)
            for (y in 0 until outH) {
                val rowOff = y.toLong() * stride
                val dstRow = y * outW * 4
                for (x in 0 until outW) {
                    val src = rowOff + x * 3L
                    val dst = dstRow + x * 4
                    bgra[dst] = pixels.get(JAVA_BYTE, src + 2L)
                    bgra[dst + 1] = pixels.get(JAVA_BYTE, src + 1L)
                    bgra[dst + 2] = pixels.get(JAVA_BYTE, src)
                    bgra[dst + 3] = 0xFF.toByte()
                }
            }
            val info = ImageInfo(outW, outH, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            return SkiaImage.makeRaster(info, bgra, outW * 4).use { img ->
                img.encodeToData(EncodedImageFormat.JPEG, jpegQuality.coerceIn(40, 95))?.use { it.bytes }
            }
        }
    }

    private fun startsWithParameterSet(annexB: ByteArray, codec: TsVideoCodec): Boolean {
        val off = when {
            annexB.size >= 5 && annexB[0] == 0.toByte() && annexB[1] == 0.toByte() && annexB[2] == 0.toByte() && annexB[3] == 1.toByte() -> 4
            annexB.size >= 4 && annexB[0] == 0.toByte() && annexB[1] == 0.toByte() && annexB[2] == 1.toByte() -> 3
            else -> return false
        }
        return when (codec) {
            TsVideoCodec.HEVC -> ((annexB[off].toInt() ushr 1) and 0x3F) == 32 // HEVC VPS
            TsVideoCodec.AVC -> (annexB[off].toInt() and 0x1F) == 7 // AVC SPS
        }
    }

    override fun close() {
        synchronized(nativeLock) {
            if (closed) return
            closed = true
            runCatching {
                if (scaler != MemorySegment.NULL) {
                    ff.scaleFree.invokeWithArguments(scaler)
                    scaler = MemorySegment.NULL
                }
                if (packetRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.packetFree.invokeWithArguments(packetRef)
                if (frameRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.frameFree.invokeWithArguments(frameRef)
                if (decoderRef.get(ADDRESS, 0) != MemorySegment.NULL) ff.decoderFree.invokeWithArguments(decoderRef)
            }
            runCatching { arena.close() }
        }
    }
}
