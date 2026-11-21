package dev.piko.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.piko.shared.media.storyboard.StoryboardIdrDecoder
import dev.piko.shared.media.storyboard.TsVideoCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端持久化单一 HEVC `MediaCodec` 雪碧图 IDR 解码器：
 * - 单视频生命周期内仅初始化一次 `MediaCodec`，每帧经 `flush()` 直接送入 `VPS/SPS/PPS + Annex B IDR`；
 * - 兼容 8-bit `YUV_420_888` 与 10-bit `YCBCR_P010`（读取 16-bit 小端样本高字节 MSB），
 *   从 `getOutputImage()` 按实际 `rowStride` / `pixelStride` 子采样转码为 `<= 384px` 紧凑 JPEG。
 */
internal class AndroidStoryboardIdrDecoder : StoryboardIdrDecoder {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var configuredCodec: TsVideoCodec? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredCsdHash = 0
    private var configuredMaxInputBytes = 0
    private var closed = false

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
        synchronized(lock) {
            if (closed) return@withContext null
            try {
                val safeWidth = if (width > 0) width.coerceAtLeast(64) else DEFAULT_DECODE_WIDTH
                val safeHeight = if (height > 0) height.coerceAtLeast(64) else DEFAULT_DECODE_HEIGHT
                decodeSynchronized(
                    csdAnnexB = csdAnnexB,
                    idrAccessUnitAnnexB = idrAccessUnitAnnexB,
                    videoCodec = codec,
                    width = safeWidth,
                    height = safeHeight,
                    maxWidthPx = maxWidthPx,
                    jpegQuality = jpegQuality,
                )
            } catch (_: MediaCodec.CodecException) {
                // 解码器已进入错误态：立即释放，下次按需重建健康实例，避免后续全部槽位连续失败
                releaseCodecLocked()
                null
            } catch (_: IllegalStateException) {
                releaseCodecLocked()
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun decodeSynchronized(
        csdAnnexB: ByteArray,
        idrAccessUnitAnnexB: ByteArray,
        videoCodec: TsVideoCodec,
        width: Int,
        height: Int,
        maxWidthPx: Int,
        jpegQuality: Int,
    ): ByteArray? {
        val payload = if (csdAnnexB.isNotEmpty() && !startsWithParameterSet(idrAccessUnitAnnexB, videoCodec)) {
            ByteArray(csdAnnexB.size + idrAccessUnitAnnexB.size).also { out ->
                csdAnnexB.copyInto(out, 0)
                idrAccessUnitAnnexB.copyInto(out, csdAnnexB.size)
            }
        } else {
            idrAccessUnitAnnexB
        }

        var activeCodec = ensureCodec(videoCodec, csdAnnexB, width, height, payload.size) ?: return null
        activeCodec.flush()

        var inputIndex = activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return null
        var inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return null
        if (inputBuffer.capacity() < payload.size) {
            releaseCodecLocked()
            activeCodec = ensureCodec(videoCodec, csdAnnexB, width, height, payload.size * 2) ?: return null
            inputIndex = activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex < 0) return null
            inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return null
            if (inputBuffer.capacity() < payload.size) return null
        }
        inputBuffer.clear()
        inputBuffer.put(payload)
        activeCodec.queueInputBuffer(
            inputIndex,
            0,
            payload.size,
            0L,
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
        )

        // 送入 EOS 标记促使硬件解码器立即吐出这一帧而不等待后续参考帧；若此时输入队列暂满则在输出轮询中重试入队
        var eosQueued = false
        val eosIndex = activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (eosIndex >= 0) {
            activeCodec.queueInputBuffer(eosIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            eosQueued = true
        }

        val info = MediaCodec.BufferInfo()
        repeat(MAX_OUTPUT_POLLS) {
            if (!eosQueued) {
                val retryEos = activeCodec.dequeueInputBuffer(0L)
                if (retryEos >= 0) {
                    activeCodec.queueInputBuffer(retryEos, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            val outIndex = activeCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    val hasFrame = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    val jpeg = if (hasFrame) {
                        val image = runCatching { activeCodec.getOutputImage(outIndex) }.getOrNull()
                        try {
                            image?.let { yuvImageToScaledJpeg(it, maxWidthPx, jpegQuality) }
                        } finally {
                            runCatching { image?.close() }
                        }
                    } else {
                        null
                    }
                    activeCodec.releaseOutputBuffer(outIndex, false)
                    if (jpeg != null) return jpeg
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return null
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            }
        }
        return null
    }

    private fun ensureCodec(
        videoCodec: TsVideoCodec,
        csdAnnexB: ByteArray,
        width: Int,
        height: Int,
        requiredInputBytes: Int,
    ): MediaCodec? {
        val csdHash = csdAnnexB.contentHashCode()
        val maxInputBytes = maxOf(DEFAULT_MAX_INPUT_BYTES, requiredInputBytes)
        val existing = codec
        if (
            existing != null &&
            configuredCodec == videoCodec &&
            configuredWidth == width &&
            configuredHeight == height &&
            configuredCsdHash == csdHash &&
            configuredMaxInputBytes >= requiredInputBytes
        ) {
            return existing
        }
        releaseCodecLocked()
        return configureNewCodec(videoCodec, csdAnnexB, width, height, csdHash, maxInputBytes, explicitFlexibleColor = true)
            ?: configureNewCodec(videoCodec, csdAnnexB, width, height, csdHash, maxInputBytes, explicitFlexibleColor = false)
    }

    private fun configureNewCodec(
        videoCodec: TsVideoCodec,
        csdAnnexB: ByteArray,
        width: Int,
        height: Int,
        csdHash: Int,
        maxInputBytes: Int,
        explicitFlexibleColor: Boolean,
    ): MediaCodec? {
        val mime = when (videoCodec) {
            TsVideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TsVideoCodec.AVC -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val created = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
            ?: return null
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputBytes)
            if (explicitFlexibleColor) {
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            }
            if (csdAnnexB.isNotEmpty()) {
                when (videoCodec) {
                    TsVideoCodec.HEVC -> format.setByteBuffer("csd-0", ByteBuffer.wrap(csdAnnexB))
                    TsVideoCodec.AVC -> {
                        val (sps, pps) = splitAvcCsd(csdAnnexB)
                        if (sps != null && pps != null) {
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        } else {
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(csdAnnexB))
                        }
                    }
                }
            }
            created.configure(format, null, null, 0)
            created.start()
            codec = created
            configuredCodec = videoCodec
            configuredWidth = width
            configuredHeight = height
            configuredCsdHash = csdHash
            configuredMaxInputBytes = maxInputBytes
            created
        } catch (_: Throwable) {
            runCatching { created.release() }
            null
        }
    }

    private fun splitAvcCsd(csdAnnexB: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val starts = ArrayList<Int>(4)
        var i = 0
        while (i + 4 <= csdAnnexB.size) {
            if (csdAnnexB[i] == 0.toByte() && csdAnnexB[i + 1] == 0.toByte() && csdAnnexB[i + 2] == 0.toByte() && csdAnnexB[i + 3] == 1.toByte()) {
                starts += i
                i += 4
            } else {
                i++
            }
        }
        for (idx in starts.indices) {
            val start = starts[idx]
            val end = if (idx + 1 < starts.size) starts[idx + 1] else csdAnnexB.size
            if (end - start <= 4) continue
            val nalType = csdAnnexB[start + 4].toInt() and 0x1F
            if (nalType == 7 && sps == null) sps = csdAnnexB.copyOfRange(start, end)
            if (nalType == 8 && pps == null) pps = csdAnnexB.copyOfRange(start, end)
        }
        return sps to pps
    }

    private fun yuvImageToScaledJpeg(image: Image, maxWidthPx: Int, jpegQuality: Int): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        if (srcW <= 0 || srcH <= 0) return null

        val targetMax = maxWidthPx.coerceIn(64, 480).toFloat()
        val scale = minOf(1f, targetMax / maxOf(srcW, srcH))
        val outW = ((srcW * scale).roundToInt() and 1.inv()).coerceAtLeast(2)
        val outH = ((srcH * scale).roundToInt() and 1.inv()).coerceAtLeast(2)

        // YCBCR_P010 (0x36) 使用 16-bit 小端存储，有效 10-bit 位于高 10 位（bits 15..6），offset + 1 即高 8 位 MSB
        val msbOffset = if (image.format == IMAGE_FORMAT_YCBCR_P010) 1 else 0
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        val pixels = IntArray(outW * outH)
        for (dy in 0 until outH) {
            val sy = crop.top + (dy * srcH) / outH
            val chromaY = sy ushr 1
            val yRowOffset = sy * yRowStride
            val uRowOffset = chromaY * uRowStride
            val vRowOffset = chromaY * vRowStride
            val dstRow = dy * outW
            for (dx in 0 until outW) {
                val sx = crop.left + (dx * srcW) / outW
                val chromaX = sx ushr 1
                val yIdx = yRowOffset + sx * yPixStride + msbOffset
                val uIdx = uRowOffset + chromaX * uPixStride + msbOffset
                val vIdx = vRowOffset + chromaX * vPixStride + msbOffset
                if (yIdx >= yLimit || uIdx >= uLimit || vIdx >= vLimit) return null

                val yVal = (yBuf.get(yIdx).toInt() and 0xFF) - 16
                val uVal = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(vIdx).toInt() and 0xFF) - 128

                val y1192 = 1192 * yVal.coerceAtLeast(0)
                val r = ((y1192 + 1634 * vVal) shr 10).coerceIn(0, 255)
                val g = ((y1192 - 833 * vVal - 400 * uVal) shr 10).coerceIn(0, 255)
                val b = ((y1192 + 2066 * uVal) shr 10).coerceIn(0, 255)
                pixels[dstRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        return try {
            val out = ByteArrayOutputStream(4096)
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun startsWithParameterSet(annexB: ByteArray, videoCodec: TsVideoCodec): Boolean {
        val off = when {
            annexB.size >= 5 && annexB[0] == 0.toByte() && annexB[1] == 0.toByte() && annexB[2] == 0.toByte() && annexB[3] == 1.toByte() -> 4
            annexB.size >= 4 && annexB[0] == 0.toByte() && annexB[1] == 0.toByte() && annexB[2] == 1.toByte() -> 3
            else -> return false
        }
        return when (videoCodec) {
            TsVideoCodec.HEVC -> ((annexB[off].toInt() ushr 1) and 0x3F) == 32
            TsVideoCodec.AVC -> (annexB[off].toInt() and 0x1F) == 7
        }
    }

    private fun releaseCodecLocked() {
        val existing = codec
        codec = null
        configuredCodec = null
        configuredWidth = 0
        configuredHeight = 0
        configuredCsdHash = 0
        configuredMaxInputBytes = 0
        if (existing != null) {
            runCatching { existing.stop() }
            runCatching { existing.release() }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            releaseCodecLocked()
        }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 25_000L
        private const val MAX_OUTPUT_POLLS = 24
        private const val DEFAULT_DECODE_WIDTH = 1920
        private const val DEFAULT_DECODE_HEIGHT = 1080
        private const val DEFAULT_MAX_INPUT_BYTES = 2 * 1024 * 1024
        private const val IMAGE_FORMAT_YCBCR_P010 = 0x36

        fun decodeEncodedImage(bytes: ByteArray): ImageBitmap? {
            if (bytes.isEmpty()) return null
            return runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
}
