package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.SheetAction
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode

/** 仅渲染播放器控件，不创建播放后端，也不读取真实账号。 */
@Composable
internal fun PlayerPreview(env: ShotEnv, mode: ThemeMode) {
    CompositionLocalProvider(LocalPikoServices provides env.services, LocalPikoPlatform provides env.platform,
        LocalPointerSource provides remember { PointerSource() }) {
        PikoTheme(Appearance(mode = mode)) {
            var playing by remember { mutableStateOf(true) }
            var position by remember { mutableStateOf(60_000L) }
            var speed by remember { mutableStateOf(1f) }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MobilePlayerControls(
                    title = "Frieren - 第 01 集 [1080p].mkv", isLocalPlayback = false,
                    isPlaying = playing, isLoading = false, positionMillis = position,
                    durationMillis = 1_440_000, bufferedPositionMillis = 120_000,
                    playbackSpeed = speed, aspectRatio = PlayerAspectRatio.Fit,
                    qualityOptions = listOf("原画", "1080P", "720P"), currentQuality = "原画",
                    errorMessage = null, resumedFromMillis = null,
                    onPlayPause = { playing = !playing }, onSeek = { position = it }, onSpeedChange = { speed = it },
                    onAspectRatioChange = {}, onQualityChange = {}, onRetry = {}, onRestartFromBeginning = {},
                    onBack = {}, onToggleFullscreen = {},
                    onPickLocalSubtitle = {},
                    fileActions = listOf(SheetAction(Icons.Outlined.Download, "下载", {}),
                        SheetAction(Icons.Outlined.Share, "分享", {}), SheetAction(Icons.Outlined.FolderOpen, "在文件夹中显示", {})),
                )
            }
        }
    }
}
