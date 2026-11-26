package dev.piko.shared.media.player

import dev.piko.shared.log.PikoLog

/**
 * JVM/Android 共享的 Silero VAD v5 权重加载器：从类路径一次性解码并缓存共享权重实例。
 */
object JvmSileroVadLoader {
    private const val RESOURCE_PATH = "dev/piko/shared/media/player/silero_vad_v5_16k.fp16"

    private val cachedWeights: SileroVadWeights? by lazy {
        runCatching {
            val stream = JvmSileroVadLoader::class.java.classLoader?.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing bundled Silero VAD weights at $RESOURCE_PATH")
            stream.use { SileroVadWeights.fromFp16(it.readBytes()) }
        }.onFailure { err ->
            PikoLog.w("AiSubtitle", "Failed to load bundled Silero VAD weights: ${err.message}")
        }.getOrNull()
    }

    fun create(): SileroVad? = cachedWeights?.newDetector()
}
