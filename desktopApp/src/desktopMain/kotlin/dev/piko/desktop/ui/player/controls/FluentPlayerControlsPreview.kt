package dev.piko.desktop.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.composefluent.FluentTheme
import io.github.composefluent.darkColors
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

/**
 * 仅本地使用的离屏预览：把几种典型状态渲染成 PNG，用来检查布局与配色，不进发布产物的入口。
 *
 * 运行方式：取 desktopApp 的运行时类路径后执行
 * java -cp <classpath> dev.piko.desktop.ui.player.controls.FluentPlayerControlsPreviewKt <输出目录>
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "player-controls-preview").apply { mkdirs() }
    val scenarios: List<Pair<String, @Composable () -> Unit>> = listOf(
        "playing" to { Scenario() },
        "paused-local-resume" to { Scenario(isPlaying = false, isLocalPlayback = true, resumedFromMillis = 754_000L) },
        "loading" to { Scenario(isLoading = true) },
        "error" to { Scenario(errorMessage = "本机无法解码此格式，需等服务端转码完成") },
        "fullscreen-muted" to { Scenario(isFullscreen = true, isMuted = true, playbackSpeed = 1.5f) },
    )
    scenarios.forEach { (name, content) ->
        render(File(outDir, "$name.png"), 1280, 720, content)
    }
    render(File(outDir, "narrow.png"), 640, 400) { Scenario(playbackSpeed = 1.25f) }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(file: File, width: Int, height: Int, content: @Composable () -> Unit) {
    ImageComposeScene(width = width, height = height, density = Density(1f)) {
        FluentTheme(colors = darkColors()) { content() }
    }.use { scene ->
        // 渲染两帧：第一帧触发组合，第二帧让淡入与布局落定
        scene.render(0)
        val image = scene.render(1_000_000_000L)
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let(file::writeBytes)
    }
}

@Composable
private fun Scenario(
    isPlaying: Boolean = true,
    isLoading: Boolean = false,
    isLocalPlayback: Boolean = false,
    isFullscreen: Boolean = false,
    isMuted: Boolean = false,
    playbackSpeed: Float = 1f,
    errorMessage: String? = null,
    resumedFromMillis: Long? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF3A5A78), Color(0xFF1B2A33), Color(0xFF6B4E3D)))),
    ) {
        FluentPlayerControls(
            title = "[字幕组] 一部名字很长很长的连续剧 第二季 S02E07 1080p WEB-DL HEVC 10bit.mkv",
            isLocalPlayback = isLocalPlayback,
            isPlaying = isPlaying,
            isLoading = isLoading,
            positionMillis = 754_000L,
            durationMillis = 2_640_000L,
            bufferedPositionMillis = 1_020_000L,
            playbackSpeed = playbackSpeed,
            aspectRatio = PlayerAspectRatio.Fit,
            qualityOptions = listOf("Original", "1080P", "720P"),
            currentQuality = "Original",
            errorMessage = errorMessage,
            resumedFromMillis = resumedFromMillis,
            onPlayPause = {},
            onSeek = {},
            onSpeedChange = {},
            onAspectRatioChange = {},
            onQualityChange = {},
            onRetry = {},
            onRestartFromBeginning = {},
            volume = 0.6f,
            isMuted = isMuted,
            isFullscreen = isFullscreen,
            onVolumeChange = {},
            onToggleMute = {},
            onToggleFullscreen = {},
            onClose = {},
            showPlaylistEntry = true,
        )
    }
}
