package dev.piko.ui.screens.player

internal const val ANDROID_HWDEC_VALUE = "mediacodec,mediacodec-copy"
internal const val ANDROID_HWDEC_CODECS = "h264,hevc,vp8,vp9,av1"

/**
 * Android 端老旧 AVI/MPEG-4 等容器与编解码器的自动软解回退策略。
 *
 * 1. 用户显式关闭硬解时始终保持 `hwdec=no`；
 * 2. 已知在 Android MediaCodec 上高发黑屏/花屏/失败的老旧容器（`.avi`、`.divx`、`.xvid`、`.wmv`、`.rmvb` 等）
 *    在打开时直接按文件级启用 `hwdec=no`，且不影响下一部 H.264/HEVC/AV1/VP9 视频恢复硬解；
 * 3. 若打开无扩展名 URI 或特殊封装时 MediaCodec 硬解运行期出错或未出帧即异常结束，自动无缝切 `hwdec=no` 重试一次。
 */
internal class AndroidHwdecFallbackState(
    private val userHardwareDecoding: Boolean,
) {
    @Volatile
    var forcedSoftwareForCurrentFile: Boolean = !userHardwareDecoding
        private set

    @Volatile
    var proactiveReason: String? = null
        private set

    @Volatile
    private var sawHardwareDecodeError: Boolean = false

    @Volatile
    private var reactiveFallbackTriggered: Boolean = false

    @Volatile
    private var fileLoadedObserved: Boolean = false

    @Volatile
    private var readyObserved: Boolean = false

    fun effectiveHwdec(): String =
        if (!userHardwareDecoding || forcedSoftwareForCurrentFile) "no" else ANDROID_HWDEC_VALUE

    fun onOpen(targetPathOrUrl: String): String {
        sawHardwareDecodeError = false
        reactiveFallbackTriggered = false
        fileLoadedObserved = false
        readyObserved = false
        if (!userHardwareDecoding) {
            forcedSoftwareForCurrentFile = true
            proactiveReason = null
            return "no"
        }
        val ext = extractContainerExtension(targetPathOrUrl)
        return if (isLegacySoftwareDecodeContainer(ext)) {
            forcedSoftwareForCurrentFile = true
            proactiveReason = "legacy_container:.$ext"
            "no"
        } else {
            forcedSoftwareForCurrentFile = false
            proactiveReason = null
            ANDROID_HWDEC_VALUE
        }
    }

    fun onLogMessage(prefix: String, isWarningOrError: Boolean, text: String) {
        if (!userHardwareDecoding || forcedSoftwareForCurrentFile || !isWarningOrError) return
        if (isHardwareDecodeErrorLog(prefix, text)) {
            sawHardwareDecodeError = true
        }
    }

    fun onFileLoaded() {
        fileLoadedObserved = true
    }

    fun onPlaybackReady() {
        readyObserved = true
    }

    fun shouldSwitchToSoftwareOnCodecLoaded(codec: String?, hwdecCurrent: String?): Boolean {
        if (!userHardwareDecoding || forcedSoftwareForCurrentFile) return false
        val activeHw = hwdecCurrent?.trim()?.lowercase().orEmpty()
        if (activeHw.isEmpty() || activeHw == "no" || activeHw == "none") return false
        if (!isLegacySoftwareDecodeCodec(codec)) return false
        forcedSoftwareForCurrentFile = true
        reactiveFallbackTriggered = true
        return true
    }

    fun shouldRetryWithSoftwareOnEndFileError(headless: Boolean, audioOnly: Boolean): Boolean {
        if (!userHardwareDecoding || forcedSoftwareForCurrentFile || reactiveFallbackTriggered) return false
        if (headless || audioOnly) return false
        // 仅当观测到明确硬解错误，或文件已完成解复用（FILE_LOADED）但在出帧前异常结束时才触发软解重试；
        // 避免文件打开前的纯网络/HTTP/解复用失败被误判为硬解错误并触发多余重开。
        val likelyHwdecFailure = sawHardwareDecodeError || (fileLoadedObserved && !readyObserved)
        if (!likelyHwdecFailure) return false
        forcedSoftwareForCurrentFile = true
        reactiveFallbackTriggered = true
        return true
    }

    companion object {
        private val LEGACY_CONTAINERS = setOf(
            "avi",
            "divx",
            "xvid",
            "wmv",
            "asf",
            "rm",
            "rmvb",
            "flv",
        )

        private val LEGACY_CODECS = setOf(
            "mpeg4",
            "msmpeg4",
            "msmpeg4v1",
            "msmpeg4v2",
            "msmpeg4v3",
            "wmv1",
            "wmv2",
            "wmv3",
            "vc1",
            "rv10",
            "rv20",
            "rv30",
            "rv40",
            "mpeg1video",
            "mpeg2video",
            "cinepak",
            "indeo3",
            "indeo4",
            "indeo5",
            "flv1",
            "mjpeg",
        )

        fun extractContainerExtension(targetPathOrUrl: String): String? {
            if (targetPathOrUrl.isBlank()) return null
            val withoutFragment = targetPathOrUrl.substringBefore('#')
            val withoutQuery = withoutFragment.substringBefore('?')
            val lastSlash = maxOf(withoutQuery.lastIndexOf('/'), withoutQuery.lastIndexOf('\\'))
            val lastSegment = if (lastSlash >= 0) withoutQuery.substring(lastSlash + 1) else withoutQuery
            val dot = lastSegment.lastIndexOf('.')
            if (dot <= 0 || dot >= lastSegment.lastIndex) return null
            val ext = lastSegment.substring(dot + 1).lowercase()
            return ext.takeIf { it.length in 1..6 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }
        }

        fun isLegacySoftwareDecodeContainer(extension: String?): Boolean =
            extension != null && extension.lowercase() in LEGACY_CONTAINERS

        fun isLegacySoftwareDecodeCodec(codec: String?): Boolean {
            val normalized = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            if (normalized in LEGACY_CODECS) return true
            return LEGACY_CODECS.any { normalized.startsWith("$it ") || normalized.startsWith("$it(") || normalized.contains("_$it") }
        }

        fun isHardwareDecodeErrorLog(prefix: String, text: String): Boolean {
            val p = prefix.lowercase()
            val t = text.lowercase()
            if ("mediacodec" in p || "mediacodec" in t || "amediacodec" in t) return true
            if ("hwdec" in p || "hwdec" in t || "hwaccel" in t) return true
            if ("omx." in t || "c2." in t) return true
            if (p == "vd" || p.startsWith("ffmpeg")) {
                if ("failed to open" in t ||
                    "failed to initialize" in t ||
                    "could not open codec" in t ||
                    "error while decoding" in t ||
                    "cannot open video decoder" in t ||
                    "hardware decoding" in t
                ) {
                    return true
                }
            }
            return false
        }
    }
}
