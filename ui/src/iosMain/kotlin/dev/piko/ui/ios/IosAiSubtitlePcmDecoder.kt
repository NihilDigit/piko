@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import dev.piko.shared.media.RandomAccessMediaSource
import dev.piko.shared.media.player.AiSubtitlePcmDecoder
import dev.piko.shared.media.player.AiSubtitlePcmFrame
import dev.piko.shared.media.player.AiSubtitlePcmReader
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/**
 * 供 Swift `AudioToolbox` 解码器回调的随机读数据源适配器。
 * 定义在 `:ui` 模块以便通过 `PikoKit` 静态框架直接导出给 `iosApp/Piko/IosAiSubtitleDecoder.swift`。
 */
class IosRandomAccessByteSource internal constructor(
    private val source: RandomAccessMediaSource,
) {
    val size: Long get() = source.size

    fun readAt(position: Long, maxBytes: Int): NSData? {
        if (maxBytes <= 0 || position < 0L || position >= source.size) return null
        val buf = ByteArray(maxBytes)
        val n = runBlocking { source.readAt(position, buf, 0, maxBytes) }
        if (n <= 0) return null
        return buf.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = n.toULong())
        }
    }
}

/**
 * Swift 侧原生 PCM 帧返回值：携带帧起始时间戳（毫秒）与 16 kHz 单声道 `pcm_s16le` 字节。
 */
class IosNativePcmFrame(
    val positionMs: Long,
    val data: NSData,
)

/**
 * Swift 侧原生音轨解码读取器接口，由 `IosAiSubtitleDecoder.swift` 实现。
 */
interface IosNativePcmReader {
    fun seekTo(positionMs: Long)
    fun readFrame(): IosNativePcmFrame?
    fun close()
}

/**
 * Swift 侧原生 AI 字幕解码器工厂接口，在 `PikoApp.swift` 启动时注册到 [IosAiSubtitleBridge]。
 */
interface IosNativePcmDecoderDelegate {
    fun open(source: IosRandomAccessByteSource, audioStreamIndex: Int): IosNativePcmReader?
}

/**
 * Kotlin 与 Swift 间的 AI 字幕解码器注册桥。
 */
object IosAiSubtitleBridge {
    @Volatile
    private var delegate: IosNativePcmDecoderDelegate? = null

    val isAvailable: Boolean get() = delegate != null

    fun registerDecoder(decoder: IosNativePcmDecoderDelegate) {
        delegate = decoder
    }

    internal fun currentDelegate(): IosNativePcmDecoderDelegate? = delegate
}

/**
 * iOS 端 AI 字幕音频解码器：委托 Swift 侧 `AudioToolbox` 原生解码器从 [RandomAccessMediaSource]
 * 输出 16 kHz 单声道 `pcm_s16le` 帧。
 */
internal class IosAiSubtitlePcmDecoder : AiSubtitlePcmDecoder {
    override suspend fun open(
        source: RandomAccessMediaSource,
        audioStreamIndex: Int,
    ): AiSubtitlePcmReader = withContext(Dispatchers.IO) {
        val delegate = IosAiSubtitleBridge.currentDelegate()
            ?: error("iOS native AI subtitle decoder not registered")
        val adapter = IosRandomAccessByteSource(source)
        val native = delegate.open(adapter, audioStreamIndex)
            ?: error("Unable to open audio stream for AI subtitles on iOS")
        IosBridgedPcmReader(native)
    }
}

private class IosBridgedPcmReader(
    private val native: IosNativePcmReader,
) : AiSubtitlePcmReader {
    private val mutex = Mutex()
    private var closed = false

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                native.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
    }

    override suspend fun readFrame(): AiSubtitlePcmFrame? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val frame = native.readFrame() ?: return@withLock null
            val len = frame.data.length.toInt()
            if (len <= 0) return@withLock null
            val bytes = ByteArray(len)
            val src = frame.data.bytes ?: return@withLock null
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), src, frame.data.length)
            }
            AiSubtitlePcmFrame(positionMs = frame.positionMs, pcm = bytes)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}
