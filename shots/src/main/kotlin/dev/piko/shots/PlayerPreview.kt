package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.rememberPlayerFileActions
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode

/** 播放器控件的一种画面。没有播放后端，取流阶段与面板由这几项直接给出。 */
data class PlayerShot(
    val playing: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val resumedFromMillis: Long? = null,
    val fullscreen: Boolean = false,
    /** 竖屏窗口里横向视频另有「全屏播放」浮钮。 */
    val landscapeVideo: Boolean = true,
)

private class FixedLevel(private var value: Float) : PlayerLevelControl {
    override fun current() = value
    override fun set(fraction: Float): Float = fraction.coerceIn(0f, 1f).also { value = it }
}

/**
 * 仅渲染播放器控件，不创建播放后端。数据照真实播放器喂齐：同分区的 12 集、两条音轨与字幕、详细信息、
 * 文件操作（分享与画质下载对话框按 [fileIdOf] 取到的网盘文件弹出）。
 */
@Composable
internal fun PlayerPreview(env: ShotEnv, mode: ThemeMode, shot: PlayerShot, platform: PikoPlatform) {
    val pointers = remember { PointerSource() }
    CompositionLocalProvider(LocalPikoServices provides env.services, LocalPikoPlatform provides platform,
        LocalPointerSource provides pointers) {
        PikoTheme(Appearance(mode = mode)) {
            var playing by remember { mutableStateOf(shot.playing) }
            var position by remember { mutableStateOf(60_000L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var rotation by remember { mutableIntStateOf(0) }
            var subtitle by remember { mutableStateOf<String?>("s1") }
            val episodes = remember { playlist(env) }
            val current = episodes.first()
            val actions = rememberPlayerFileActions(current.fileId, isLocalPlayback = false) {}
            Box(Modifier.fillMaxSize().background(Color.Black).trackPointerSource(pointers)) {
                MobilePlayerControls(
                    title = current.name, isLocalPlayback = false,
                    isPlaying = playing, isLoading = shot.loading, positionMillis = position,
                    durationMillis = 1_440_000, bufferedPositionMillis = 120_000,
                    playbackSpeed = speed, aspectRatio = PlayerAspectRatio.Fit,
                    qualityOptions = listOf("原画", "1080P", "720P"), currentQuality = "原画",
                    errorMessage = shot.error, resumedFromMillis = shot.resumedFromMillis,
                    onPlayPause = { playing = !playing }, onSeek = { position = it }, onSpeedChange = { speed = it },
                    onAspectRatioChange = {}, onQualityChange = {}, onRetry = {}, onRestartFromBeginning = {},
                    onBack = {}, onToggleFullscreen = {},
                    isFullscreen = shot.fullscreen,
                    isLandscapeVideo = shot.landscapeVideo,
                    playlist = episodes, currentFileId = current.fileId, hasPrevious = false, hasNext = true,
                    audioTracks = listOf(MediaTrack("a1", "日语", "jpn"), MediaTrack("a2", "评论音轨", "jpn")),
                    selectedAudioTrackId = "a1",
                    subtitleTracks = listOf(MediaTrack("s1", "简日双语", "chi"), MediaTrack("s2", "繁日双语", "chi")),
                    selectedSubtitleTrackId = subtitle, onSelectSubtitleTrack = { subtitle = it?.id },
                    // 桌面没有可调的屏幕亮度
                    brightness = if (platform.formFactor == FormFactor.Mobile) remember { FixedLevel(0.6f) } else null,
                    volume = remember { FixedLevel(0.7f) },
                    rotationDegrees = rotation, onRotationChange = { rotation = it },
                    onPickLocalSubtitle = {}, onPickDriveSubtitle = {},
                    fileActions = actions,
                    playbackStats = {
                        listOf(
                            PlaybackStatsSection("视频", listOf("编码" to "HEVC Main 10", "分辨率" to "1920×1080", "帧率" to "23.976")),
                            PlaybackStatsSection("网络", listOf("来源" to "原画直链", "缓冲" to "58 秒", "速度" to "12.4 MB/s")),
                        )
                    },
                )
            }
        }
    }
}

/** 选集：「动画」里的 12 集，ID 取自假服务端，网盘字幕面板与文件操作才找得到文件。 */
private fun playlist(env: ShotEnv): List<PlaylistEntry> {
    return (1..12).map { ep ->
        val name = "[SweetSub] Frieren - %02d [WebRip 1080p HEVC-10bit AAC][CHS_JPN].mkv".format(ep)
        PlaylistEntry(fileId = env.server.node(name).id, name = name, label = "%02d".format(ep), sectionKey = "main", sectionLabel = "正片")
    }
}
