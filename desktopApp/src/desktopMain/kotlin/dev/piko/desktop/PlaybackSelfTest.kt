package dev.piko.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.player.MediampPlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.mpv.MpvMediampPlayer

/**
 * 装好的包里真开一个窗口放本机视频：mpv 从安装包的资源目录加载、画面表面接上窗口的渲染上下文、播放往前走。
 * Linux 上画面经 GLX 与 Skiko 共享纹理，这一段 JVM 单测里没有窗口测不到（MediampProxyPlaybackSmokeTest 因此跳过）。
 *
 * 播放越过 [PASS_POSITION_MILLIS] 即通过；报错、[TIMEOUT_MILLIS] 内没走到都算失败。
 * [holdMillis] 是通过之后窗口再留多久，给外面截图看画面是不是黑的。
 */
internal fun playbackSelfTest(file: File, holdMillis: Long, report: (String) -> Unit): Boolean {
    if (!file.isFile) {
        report("no such file: $file")
        return false
    }
    System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") }
        ?.takeIf { it.resolve(System.mapLibraryName("mediampv")).isFile }
        ?.let { MpvMediampPlayer.prepareLibraries(it.absolutePath, false) }
    var passed = false
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Piko 播放自检",
            state = rememberWindowState(size = DpSize(640.dp, 400.dp)),
        ) {
            val scope = rememberCoroutineScope()
            val player = rememberMediampPlayer()
            val backend = remember(player) { MediampPlaybackBackend(player, scope) }
            MediampPlayerSurface(player, Modifier.fillMaxSize().background(Color.Black))
            LaunchedEffect(backend) {
                var failure: String? = null
                scope.launch {
                    val error = backend.events.first { it is PlaybackBackendEvent.Error } as PlaybackBackendEvent.Error
                    failure = error.detail
                }
                // open 也算在限时里：画面表面接不上渲染上下文时（Skiko 退到软件渲染）它一直不返回
                val reached = withTimeoutOrNull(TIMEOUT_MILLIS) {
                    runCatching {
                        backend.open(PlaybackTarget.LocalFile(file.absolutePath), startMillis = 0L, playWhenReady = true, subtitles = emptyList())
                    }.onFailure { failure = it.toString() }
                    while (backend.positionMillis < PASS_POSITION_MILLIS && failure == null) delay(50)
                    failure == null
                } ?: false
                report("position=${backend.positionMillis} duration=${backend.durationMillis} error=$failure")
                passed = reached
                if (passed) delay(holdMillis)
                player.close()
                exitApplication()
            }
        }
    }
    return passed
}

private const val PASS_POSITION_MILLIS = 1_500L
private const val TIMEOUT_MILLIS = 30_000L
