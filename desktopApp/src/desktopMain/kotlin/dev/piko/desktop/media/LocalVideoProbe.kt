package dev.piko.desktop.media

import dev.piko.shared.data.LocalMediaMetadata
import dev.piko.desktop.media.FfmpegLayout.FORMAT_SIZE
import dev.piko.desktop.media.FfmpegLayout.FORMAT_DURATION
import dev.piko.desktop.media.FfmpegLayout.STREAM_SIZE
import dev.piko.desktop.media.FfmpegLayout.PAR_VIDEO_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.PAR_WIDTH
import dev.piko.desktop.media.FfmpegLayout.PAR_HEIGHT
import dev.piko.desktop.media.FfmpegLayout.PAR_CODEC_ID
import dev.piko.desktop.media.FfmpegLayout.PAR_SIDE_DATA
import dev.piko.desktop.media.FfmpegLayout.PAR_NB_SIDE_DATA
import dev.piko.desktop.media.FfmpegLayout.FRAME_LINESIZE
import dev.piko.desktop.media.FfmpegLayout.FRAME_WIDTH
import dev.piko.desktop.media.FfmpegLayout.FRAME_HEIGHT
import dev.piko.desktop.media.FfmpegLayout.FRAME_FORMAT
import dev.piko.desktop.media.FfmpegLayout.FRAME_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_BYTES
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_SIZE
import dev.piko.desktop.media.FfmpegLayout.SIDE_DATA_PREFIX_SIZE
import dev.piko.desktop.media.FfmpegLayout.AV_PKT_DATA_DISPLAYMATRIX
import dev.piko.desktop.media.FfmpegLayout.AV_PIX_FMT_RGB24
import dev.piko.desktop.media.FfmpegLayout.SWS_BILINEAR
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EXIT
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EOF
import dev.piko.desktop.media.FfmpegLayout.AVERROR_EIO
import dev.piko.desktop.media.FfmpegLayout.AVIO_BUFFER
import dev.piko.desktop.media.FfmpegLayout.AVIO_SIZE
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_SIZE
import dev.piko.desktop.media.FfmpegLayout.AVSEEK_FORCE
import dev.piko.desktop.media.FfmpegLayout.FORMAT_PB
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** 使用随包 FFmpeg 读取本地文件，不启动外部进程，也不建立网络或回环代理。 */
internal fun probeLocalVideo(source: File, output: File, ff: Ffmpeg = Ffmpeg.bundled(), timeoutMs: Long = 4_000L, cancelled: () -> Boolean = { false }): LocalMediaMetadata =
    try {
        FileChannel.open(source.toPath(), StandardOpenOption.READ).use { probeLocalVideo(it, output, ff, timeoutMs, cancelled) }
    } catch (_: Exception) { throw FfmpegException("本地媒体探测失败") }

internal fun probeLocalVideo(channel: FileChannel, output: File, ff: Ffmpeg = Ffmpeg.bundled(), timeoutMs: Long = 4_000L, cancelled: () -> Boolean = { false }): LocalMediaMetadata = Arena.ofConfined().use { arena ->
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
    fun interrupted() = cancelled() || System.nanoTime() > deadlineNanos
    fun checkActive() { if (interrupted()) throw FfmpegException("本地媒体探测已中断") }
    val avioRef = arena.allocate(ADDRESS)
    val openOptionsRef = arena.allocate(ADDRESS)
    var temporary: File? = null
    val inputRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    val decoderRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    val frameRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    val packetRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    val optionsRef = arena.allocate(ADDRESS).apply { set(ADDRESS, 0, MemorySegment.NULL) }
    try {
        checkActive()
        avioRef.set(ADDRESS, 0, localAvio(channel, ff, arena, ::interrupted))
        val context = ff.allocContext.invokeWithArguments() as MemorySegment
        check(context != MemorySegment.NULL)
        context.reinterpret(FORMAT_SIZE).set(ADDRESS, FORMAT_PB, avioRef.get(ADDRESS, 0))
        inputRef.set(ADDRESS, 0, context)
        for ((key, value) in mapOf("protocol_whitelist" to "", "probesize" to "8388608", "analyzeduration" to "3000000")) {
            ff.check(ff.dictSet.invokeWithArguments(openOptionsRef, arena.allocateFrom(key), arena.allocateFrom(value), 0) as Int, "配置本地探测")
        }
        ff.check(ff.openInput.invokeWithArguments(inputRef, MemorySegment.NULL, MemorySegment.NULL, openOptionsRef) as Int, "读取本地媒体")
        checkActive()
        val input = inputRef.get(ADDRESS, 0).reinterpret(FORMAT_SIZE)
        ff.check(ff.findStreamInfo.invokeWithArguments(input, MemorySegment.NULL) as Int, "读取媒体信息")
        checkActive()
        val index = ff.check(ff.findBestStream.invokeWithArguments(input, 0, -1, -1, MemorySegment.NULL, 0) as Int, "查找视频轨")
        val count = input.get(JAVA_INT, FfmpegLayout.FORMAT_NB_STREAMS)
        val streams = input.get(ADDRESS, FfmpegLayout.FORMAT_STREAMS).reinterpret(count * 8L)
        val stream = streams.getAtIndex(ADDRESS, index.toLong()).reinterpret(STREAM_SIZE)
        val parameters = stream.get(ADDRESS, FfmpegLayout.STREAM_CODECPAR).reinterpret(PAR_VIDEO_PREFIX_SIZE)
        val width = parameters.get(JAVA_INT, PAR_WIDTH)
        val height = parameters.get(JAVA_INT, PAR_HEIGHT)
        val durationMs = (input.get(JAVA_LONG, FORMAT_DURATION) / 1000).coerceAtLeast(0)
        val sideData = ff.sideDataGet.invokeWithArguments(parameters.get(ADDRESS, PAR_SIDE_DATA), parameters.get(JAVA_INT, PAR_NB_SIDE_DATA), AV_PKT_DATA_DISPLAYMATRIX) as MemorySegment
        val angle = if (sideData == MemorySegment.NULL) 0 else {
            val side = sideData.reinterpret(SIDE_DATA_PREFIX_SIZE)
            val matrix = side.get(ADDRESS, SIDE_DATA_BYTES)
            if (side.get(JAVA_LONG, SIDE_DATA_SIZE) >= 36L && matrix != MemorySegment.NULL) {
                val degrees = -(ff.displayRotation.invokeWithArguments(matrix) as Double)
                if (degrees.isFinite()) ((degrees.roundToInt() % 360) + 360) % 360 else 0
            } else 0
        }
        fun metadata(thumbnail: String = "") = if (angle == 90 || angle == 270) LocalMediaMetadata(height, width, durationMs, thumbnail)
            else LocalMediaMetadata(width, height, durationMs, thumbnail)
        val codec = ff.decoderFind.invokeWithArguments(parameters.get(JAVA_INT, PAR_CODEC_ID)) as MemorySegment
        if (codec == MemorySegment.NULL) return@use metadata()
        val decoder = ff.decoderAlloc.invokeWithArguments(codec) as MemorySegment
        check(decoder != MemorySegment.NULL)
        decoderRef.set(ADDRESS, 0, decoder)
        ff.check(ff.parametersToContext.invokeWithArguments(decoder, parameters) as Int, "配置视频解码")
        ff.dictSet.invokeWithArguments(optionsRef, arena.allocateFrom("threads"), arena.allocateFrom("2"), 0)
        ff.check(ff.decoderOpen.invokeWithArguments(decoder, codec, optionsRef) as Int, "打开视频解码")
        val frame = (ff.frameAlloc.invokeWithArguments() as MemorySegment).also { check(it != MemorySegment.NULL) }.reinterpret(FRAME_PREFIX_SIZE)
        frameRef.set(ADDRESS, 0, frame)
        val packet = (ff.packetAlloc.invokeWithArguments() as MemorySegment).also { check(it != MemorySegment.NULL) }.reinterpret(FfmpegLayout.PACKET_SIZE)
        packetRef.set(ADDRESS, 0, packet)
        ff.seekFrame.invokeWithArguments(input, -1, durationMs * 100L, FfmpegLayout.AVSEEK_FLAG_BACKWARD)
        var thumbnail = ""
        // 有界读取，损坏文件或无法解码的轨道不能占住后台探测车道。
        for (attempt in 0 until 2000) {
            checkActive()
            val result = ff.readFrame.invokeWithArguments(input, packet) as Int
            checkActive()
            if (result < 0) break
            try {
                if (packet.get(JAVA_INT, FfmpegLayout.PACKET_STREAM_INDEX) != index) continue
                if ((ff.sendPacket.invokeWithArguments(decoder, packet) as Int) < 0) continue
                if ((ff.receiveFrame.invokeWithArguments(decoder, frame) as Int) < 0) continue
                val image = rotateImage(frameToImage(ff, frame, arena), angle)
                val tmp = Files.createTempFile(output.parentFile.toPath(), "local-cover-", ".tmp").toFile()
                temporary = tmp
                if (ImageIO.write(image, "jpg", tmp) && tmp.length() > 0) {
                    checkActive()
                    Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    thumbnail = output.absolutePath
                }
                break
            } finally { ff.packetUnref.invokeWithArguments(packet) }
        }
        checkActive()
        metadata(thumbnail)
    } catch (_: Exception) {
        throw FfmpegException("本地媒体探测失败")
    } finally {
        temporary?.delete()
        ff.dictFree.invokeWithArguments(openOptionsRef)
        ff.dictFree.invokeWithArguments(optionsRef)
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

/** 只暴露已验证句柄；回调不允许异常跨越原生边界。 */
private fun localAvio(channel: FileChannel, ff: Ffmpeg, arena: Arena, interrupted: () -> Boolean): MemorySegment {
    val read: (MemorySegment, MemorySegment, Int) -> Int = { _, buffer, length ->
        try {
            if (interrupted()) AVERROR_EXIT
            else channel.read(buffer.reinterpret(length.toLong()).asByteBuffer()).let { if (it <= 0) AVERROR_EOF else it }
        } catch (_: Throwable) { AVERROR_EIO }
    }
    val seek: (MemorySegment, Long, Int) -> Long = { _, offset, whence ->
        try {
            if (interrupted()) AVERROR_EXIT.toLong()
            else when (whence and AVSEEK_FORCE.inv()) {
                AVSEEK_SIZE -> channel.size()
                0 -> channel.position(offset).position()
                1 -> channel.position(Math.addExact(channel.position(), offset)).position()
                2 -> channel.position(Math.addExact(channel.size(), offset)).position()
                else -> AVERROR_EIO.toLong()
            }
        } catch (_: Throwable) { AVERROR_EIO.toLong() }
    }
    // 绑定标准 Function3.invoke 而不是按名称反射私有回调，Release 裁剪无需新增 keep 规则。
    val invoke = MethodHandles.lookup().findVirtual(Function3::class.java, "invoke",
        MethodType.methodType(Any::class.java, Any::class.java, Any::class.java, Any::class.java))
    val linker = Linker.nativeLinker()
    val readStub = linker.upcallStub(invoke.bindTo(read).asType(MethodType.methodType(Int::class.javaPrimitiveType,
        MemorySegment::class.java, MemorySegment::class.java, Int::class.javaPrimitiveType)), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), arena)
    val seekStub = linker.upcallStub(invoke.bindTo(seek).asType(MethodType.methodType(Long::class.javaPrimitiveType,
        MemorySegment::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT), arena)
    val bufferRef = arena.allocate(ADDRESS)
    bufferRef.set(ADDRESS, 0, ff.malloc.invokeWithArguments(256 * 1024L) as MemorySegment)
    check(bufferRef.get(ADDRESS, 0) != MemorySegment.NULL)
    try {
        val avio = ff.avioAllocContext.invokeWithArguments(bufferRef.get(ADDRESS, 0), 256 * 1024, 0,
            MemorySegment.NULL, readStub, MemorySegment.NULL, seekStub) as MemorySegment
        check(avio != MemorySegment.NULL)
        bufferRef.set(ADDRESS, 0, MemorySegment.NULL)
        return avio
    } finally { ff.freep.invokeWithArguments(bufferRef) }
}

/** swscale 直接输出约 480px 的 RGB，支持 10bit、HDR 及非 YUV420 的源格式。 */
private fun frameToImage(ff: Ffmpeg, frame: MemorySegment, arena: Arena): BufferedImage {
    val width = frame.get(JAVA_INT, FRAME_WIDTH)
    val height = frame.get(JAVA_INT, FRAME_HEIGHT)
    val format = frame.get(JAVA_INT, FRAME_FORMAT)
    require(width in 1..32768 && height in 1..32768)
    val scale = minOf(1.0, 480.0 / maxOf(width, height))
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val scaler = ff.scaleContext.invokeWithArguments(width, height, format, w, h, AV_PIX_FMT_RGB24, SWS_BILINEAR,
        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
    check(scaler != MemorySegment.NULL)
    try {
        val stride = ((w * 3 + 31) / 32) * 32
        val pixels = arena.allocate(stride * h.toLong() + 64, 32)
        val pointers = arena.allocate(ADDRESS, 8).apply { set(ADDRESS, 0, pixels) }
        val strides = arena.allocate(JAVA_INT, 8).apply { set(JAVA_INT, 0, stride) }
        ff.check(ff.scaleFrame.invokeWithArguments(scaler, frame, frame.asSlice(FRAME_LINESIZE), 0, height, pointers, strides) as Int, "转换视频封面")
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val at = y.toLong() * stride + x * 3
            val r = pixels.get(JAVA_BYTE, at).toInt() and 255
            val g = pixels.get(JAVA_BYTE, at + 1).toInt() and 255
            val b = pixels.get(JAVA_BYTE, at + 2).toInt() and 255
            image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return image
    } finally { ff.scaleFree.invokeWithArguments(scaler) }
}

private fun rotateImage(image: BufferedImage, angle: Int): BufferedImage {
    if (angle == 0) return image
    val swap = angle == 90 || angle == 270
    val result = BufferedImage(if (swap) image.height else image.width, if (swap) image.width else image.height, BufferedImage.TYPE_INT_RGB)
    val graphics = result.createGraphics()
    try {
        graphics.translate(result.width / 2.0, result.height / 2.0)
        graphics.rotate(Math.toRadians(angle.toDouble()))
        graphics.drawImage(image, -image.width / 2, -image.height / 2, null)
    } finally { graphics.dispose() }
    return result
}
