package dev.piko.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.player.controls.FluentPlayerControls
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.PlayerScreenState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.Text
import io.github.composefluent.darkColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Play
import io.github.nihildigit.pikpak.FileStat
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AudioLevelController

/**
 * 独立的 Windows 视频播放窗口。取流、续播与重连在共用的 [PlayerScreenState]，
 * 控件是无状态的 [FluentPlayerControls]；这里只负责窗口、画面表面、音量与全屏这些平台胶水。
 */
@Composable
@OptIn(ExperimentalMediampApi::class)
fun VideoPlayerWindow(
    file: FileStat,
    mediaRepository: PikoMediaRepository,
    themeColors: io.github.composefluent.Colors,
    onClose: () -> Unit,
    downloadCoordinator: PikoDownloadCoordinator? = null,
    playlist: List<FileStat> = emptyList(),
) {
    val windowState = rememberWindowState(width = 1000.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        title = "${file.name} - Piko 播放器",
        state = windowState,
    ) {
        // 播放器永远深色：控件叠在视频画面上，跟随浅色主题时深色文字在画面上不可读
        val playerColors = remember(themeColors) { darkColors(themeColors.fillAccent.default) }
        FluentTheme(colors = playerColors) {
            VideoPlayerContent(
                file = file,
                mediaRepository = mediaRepository,
                windowState = windowState,
                downloadCoordinator = downloadCoordinator,
                playlist = playlist,
                onClose = onClose,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMediampApi::class)
private fun VideoPlayerContent(
    file: FileStat,
    mediaRepository: PikoMediaRepository,
    windowState: WindowState,
    downloadCoordinator: PikoDownloadCoordinator?,
    playlist: List<FileStat>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    val backend = remember(player) { MediampPlaybackBackend(player, scope) }
    val state = remember(file.id) {
        PlayerScreenState(
            repository = mediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = file.id,
            initialFileName = file.name,
            // 本地副本按文件长度验完整性，需要对应的 FileStat，从播放列表里取
            resolveLocalPath = { fileId, _ ->
                (playlist + file).find { it.id == fileId }
                    ?.let { downloadCoordinator?.findCompletedLocalPath(it) }
                    ?.takeIf { File(it).exists() }
            },
        )
    }

    // 音量走 mpv 自身的软件音量：桌面端没有系统媒体音量那一路可借
    val audioFeature = remember(player) { player.features[AudioLevelController.Key] }
    val volumeLevel by (audioFeature?.volume ?: flowOf(1f)).collectAsState(1f)
    val isMuted by (audioFeature?.isMute ?: flowOf(false)).collectAsState(false)
    val maxVolume = audioFeature?.maxVolume?.takeIf { it > 0f } ?: 1f

    var showPlaylist by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageCount by remember { mutableIntStateOf(0) }

    val isFullscreen = windowState.placement == WindowPlacement.Fullscreen

    // 播放防锁屏：正在播才持有，暂停/关窗自动释放。
    DisposableEffect(state.isPlaying) {
        val displayLease = if (state.isPlaying) WinRTSupport.acquireDisplayRequest() else null
        onDispose {
            displayLease?.close()
        }
    }

    // 换源之类的一次性提示用 InfoBar 呈现
    LaunchedEffect(state) {
        state.messages.collect {
            message = it
            messageCount += 1
        }
    }
    LaunchedEffect(messageCount) {
        if (message != null) {
            delay(MESSAGE_DURATION_MILLIS)
            message = null
        }
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }
    DisposableEffect(player) {
        onDispose { player.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MediampPlayerSurface(player, Modifier.fillMaxSize())

        FluentPlayerControls(
            title = state.title,
            isLocalPlayback = state.isLocalPlayback,
            isPlaying = state.isPlaying,
            isLoading = state.isLoading,
            positionMillis = state.positionMillis,
            durationMillis = state.durationMillis,
            bufferedPositionMillis = state.bufferedPositionMillis,
            playbackSpeed = state.playbackSpeed,
            aspectRatio = state.aspectRatio,
            qualityOptions = state.qualityOptions,
            currentQuality = state.currentQuality,
            errorMessage = if (state.isImage) "桌面播放器暂只支持视频" else state.errorMessage,
            resumedFromMillis = state.resumedFromMillis,
            onPlayPause = state::togglePlayPause,
            onSeek = state::seekTo,
            onSpeedChange = state::setSpeed,
            onAspectRatioChange = state::setAspectRatio,
            onQualityChange = state::selectQuality,
            onRetry = state::retry,
            onRestartFromBeginning = state::restartFromBeginning,
            volume = if (audioFeature != null) (volumeLevel / maxVolume).coerceIn(0f, 1f) else null,
            isMuted = isMuted,
            isFullscreen = isFullscreen,
            onVolumeChange = { fraction ->
                audioFeature?.setVolume(fraction * maxVolume)
                if (fraction > 0f && isMuted) audioFeature?.setMute(false)
            },
            onToggleMute = { audioFeature?.setMute(!isMuted) },
            onToggleFullscreen = {
                windowState.placement = if (isFullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            },
            onClose = onClose,
            showPlaylistEntry = playlist.size > 1,
            onPlaylistClick = { showPlaylist = true },
        )

        message?.let {
            InfoBar(
                title = { Text(it) },
                message = {},
                severity = InfoBarSeverity.Informational,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .widthIn(max = 480.dp),
            )
        }

        if (showPlaylist) {
            PlaylistDialog(
                playlist = playlist,
                currentFileId = state.fileId,
                onSelect = { target ->
                    showPlaylist = false
                    state.switchTo(target.id, target.name)
                },
                onDismiss = { showPlaylist = false },
            )
        }
    }
}

/**
 * 同目录视频列表。当前这条用强调色标出。
 */
@Composable
private fun PlaylistDialog(
    playlist: List<FileStat>,
    currentFileId: String,
    onSelect: (FileStat) -> Unit,
    onDismiss: () -> Unit,
) {
    ContentDialog(
        title = "同目录视频",
        visible = true,
        primaryButtonText = "关闭",
        onButtonClick = { onDismiss() },
        content = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(playlist, key = { it.id }) { video ->
                    val isCurrent = video.id == currentFileId
                    // 文字取主题色而不写死：对话框底色跟随主题，写死的颜色换了主题就可能隐形。
                    val rowColor = if (isCurrent) {
                        FluentTheme.colors.fillAccent.default
                    } else {
                        FluentTheme.colors.text.text.primary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { if (!isCurrent) onSelect(video) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Regular.Play,
                            contentDescription = if (isCurrent) "正在播放" else null,
                            tint = if (isCurrent) {
                                FluentTheme.colors.fillAccent.default
                            } else {
                                FluentTheme.colors.text.text.secondary
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = video.name,
                            style = FluentTheme.typography.body,
                            color = rowColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
}

private const val MESSAGE_DURATION_MILLIS = 4_000L
