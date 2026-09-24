package dev.piko.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.player.controls.DesktopPlayerControls
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTypography
import dev.piko.ui.theme.colorScheme
import io.github.nihildigit.pikpak.FileStat
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AudioLevelController

/**
 * 独立的视频播放窗口。取流、续播与重连在共用的 [PlayerScreenState]，控件是无状态的
 * [DesktopPlayerControls]；这里只负责窗口、画面表面、音量与全屏这些平台胶水。
 */
@Composable
fun VideoPlayerWindow(
    request: VideoPlayerRequest,
    mediaRepository: PikoMediaRepository,
    downloadCoordinator: PikoDownloadCoordinator,
    appearance: Appearance,
    icon: Painter?,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(width = 1000.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        title = "${request.fileName} - Piko 播放器",
        icon = icon,
        state = windowState,
    ) {
        PlayerTheme(appearance) {
            VideoPlayerContent(
                request = request,
                mediaRepository = mediaRepository,
                windowState = windowState,
                downloadCoordinator = downloadCoordinator,
                onClose = onClose,
            )
        }
    }
}

/**
 * 播放器固定用深色：控件叠在视频画面上，跟随浅色主题时深色文字在画面上不可读。
 * 主题色仍跟随用户的选择，与 Android 播放器的 PlayerTheme 一致。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerTheme(appearance: Appearance, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = appearance.colorScheme(dark = true),
        motionScheme = MotionScheme.expressive(),
        typography = PikoTypography,
        content = content,
    )
}

@Composable
@OptIn(ExperimentalMediampApi::class)
private fun VideoPlayerContent(
    request: VideoPlayerRequest,
    mediaRepository: PikoMediaRepository,
    windowState: WindowState,
    downloadCoordinator: PikoDownloadCoordinator,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    val backend = remember(player) { MediampPlaybackBackend(player, scope) }
    val playlist = request.playlist
    val state = remember(request) {
        PlayerScreenState(
            repository = mediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = request.fileId,
            initialFileName = request.fileName,
            initialLocalPath = request.localPath,
            // 本地副本按文件长度验完整性，需要对应的 FileStat，从播放列表里取
            resolveLocalPath = { fileId, hint ->
                hint?.takeIf { File(it).exists() }
                    ?: playlist.find { it.id == fileId }
                        ?.let { downloadCoordinator.findCompletedLocalPath(it) }
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

    // 换源之类的一次性提示
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

        DesktopPlayerControls(
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
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .widthIn(max = 480.dp),
            ) { Text(it) }
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

/** 同目录视频列表。当前这条用主题色标出。 */
@Composable
private fun PlaylistDialog(
    playlist: List<FileStat>,
    currentFileId: String,
    onSelect: (FileStat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同目录视频") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(playlist, key = { it.id }) { video ->
                    val isCurrent = video.id == currentFileId
                    ListItem(
                        headlineContent = {
                            Text(video.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = if (isCurrent) "正在播放" else null,
                                tint = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                        modifier = Modifier.clickable(enabled = !isCurrent) { onSelect(video) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private const val MESSAGE_DURATION_MILLIS = 4_000L
