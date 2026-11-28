@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.media.player

import dev.piko.shared.log.PikoLog
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS 端 Silero VAD v5 权重加载器：从应用 Bundle 中一次性解码并缓存共享权重实例。
 * 权重文件由 `iosApp/project.yml` 引用仓库单一真源 `silero_vad_v5_16k.fp16` 打入 `Piko.app`。
 */
object IosSileroVadLoader {
    private const val RESOURCE_NAME = "silero_vad_v5_16k"
    private const val RESOURCE_EXT = "fp16"

    private val cachedWeights: SileroVadWeights? by lazy {
        runCatching {
            val path = NSBundle.mainBundle.pathForResource(RESOURCE_NAME, ofType = RESOURCE_EXT)
                ?: error("Missing bundled Silero VAD weights ($RESOURCE_NAME.$RESOURCE_EXT)")
            val data = NSData.dataWithContentsOfFile(path)
                ?: error("Unable to read bundled Silero VAD weights at $path")
            val size = data.length.toInt()
            if (size <= 0) error("Empty bundled Silero VAD weights at $path")
            val bytes = ByteArray(size)
            val src = data.bytes ?: error("Null NSData buffer for Silero VAD weights")
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), src, data.length)
            }
            SileroVadWeights.fromFp16(bytes)
        }.onFailure { err ->
            PikoLog.w("AiSubtitle", "Failed to load bundled iOS Silero VAD weights: ${err.message}")
        }.getOrNull()
    }

    fun create(): SileroVad? = cachedWeights?.newDetector()
}
