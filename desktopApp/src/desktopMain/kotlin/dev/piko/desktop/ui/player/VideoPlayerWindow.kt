package dev.piko.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTopBar
import dev.piko.ui.screens.player.playlistOf
import dev.piko.ui.screens.player.siblingVideos
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer

/**
 * 独立的视频播放窗口。取流、续播与重连在共用的 [PlayerScreenState]，控件是与 Android 共用的
 * [MobilePlayerControls]；这里只负责窗口、画面表面、窗口全屏、防锁屏与播放器音量这些平台胶水。
 *
 * 独立窗口不在主窗口的 PikoApp 之下，主题与 [LocalPikoServices]、[LocalPikoPlatform] 要自己再提供一遍：
 * 选集缩略图的防窥模糊要问平台支不支持。
 */
@Composable
fun VideoPlayerWindow(
    request: VideoPlayerRequest,
    services: PikoServices,
    platform: PikoPlatform,
    appearance: Appearance,
    icon: Painter?,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(width = 1000.dp, height = 620.dp)
    // 换集后标题跟着当前这集走
    var title by remember { mutableStateOf(request.fileName) }

    Window(
        onCloseRequest = onClose,
        title = "$title - Piko 播放器",
        icon = icon,
        state = windowState,
    ) {
        CompositionLocalProvider(
            LocalPikoServices provides services,
            LocalPikoPlatform provides platform,
        ) {
            PikoTheme(appearance = appearance) {
                VideoPlayerContent(
                    request = request,
                    services = services,
                    isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onToggleFullscreen = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    },
                    onTitleChange = { title = it },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMediampApi::class)
private fun VideoPlayerContent(
    request: VideoPlayerRequest,
    services: PikoServices,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTitleChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    val backend = remember(player) { MediampPlaybackBackend(player, scope) }
    val downloads = services.downloadManager
    // 主界面给的同目录视频；从传输页打开时为空，进来后再按父目录取
    var siblingVideos by remember(request) { mutableStateOf(request.playlist) }
    val state = remember(request) {
        PlayerScreenState(
            repository = services.mediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = request.fileId,
            initialFileName = request.fileName,
            initialLocalPath = request.localPath,
            // 本地副本按文件长度验完整性，需要对应的 FileStat，从同目录列表里取
            resolveLocalPath = { fileId, hint ->
                hint?.takeIf { File(it).exists() }
                    ?: siblingVideos.find { it.id == fileId }
                        ?.let { downloads.findCompletedLocalPath(it) }
                        ?.takeIf { File(it).exists() }
            },
        )
    }
    val volume = remember(backend) { backend.volume?.let { BackendVolume(backend) } }
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsState(initial = true)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(request) {
        if (siblingVideos.isEmpty()) siblingVideos = services.driveRepository.siblingVideos(request.fileId)
        state.playlist = playlistOf(siblingVideos)
    }

    // 与 Android 相同：同目录元数据到了之后再验一次磁盘，下好的片子从当前位置换到本地文件
    LaunchedEffect(state.fileId, siblingVideos) {
        if (state.isLocalPlayback) return@LaunchedEffect
        val stat = siblingVideos.find { it.id == state.fileId } ?: return@LaunchedEffect
        downloads.findCompletedLocalPath(stat)?.takeIf { File(it).exists() }?.let(state::useLocalCopy)
    }

    LaunchedEffect(state.title) { onTitleChange(state.title) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 播放防锁屏：正在播才持有，暂停与关窗时释放
    DisposableEffect(state.isPlaying) {
        val displayLease = if (state.isPlaying) WinRTSupport.acquireDisplayRequest() else null
        onDispose { displayLease?.close() }
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }
    DisposableEffect(player) {
        onDispose { player.close() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.isImage) {
            AsyncImage(
                model = state.mediaInfo?.currentUrl,
                contentDescription = state.title,
                modifier = Modifier.fillMaxSize(),
            )
            PlayerTheme {
                PlayerTopBar(
                    title = state.title,
                    episodeLabel = null,
                    isLocalPlayback = state.isLocalPlayback,
                    onBackClick = onClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        } else {
            MediampPlayerSurface(backend.player, Modifier.fillMaxSize())

            MobilePlayerControls(
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
                errorMessage = state.errorMessage,
                resumedFromMillis = state.resumedFromMillis,
                onPlayPause = state::togglePlayPause,
                onSeek = state::seekTo,
                onSpeedChange = state::setSpeed,
                onAspectRatioChange = state::setAspectRatio,
                onQualityChange = state::selectQuality,
                onRetry = state::retry,
                onRestartFromBeginning = state::restartFromBeginning,
                onBack = onClose,
                onToggleFullscreen = onToggleFullscreen,
                isFullscreen = isFullscreen,
                isLandscapeVideo = state.isLandscapeVideo,
                playlist = state.playlist,
                currentFileId = state.fileId,
                hasPrevious = state.previousEntry != null,
                hasNext = state.nextEntry != null,
                onPrevious = state::playPrevious,
                onNext = state::playNext,
                onSelectEntry = state::playEntry,
                hideEpisodeThumbnails = isSpoilerBlurEnabled,
                volume = volume,
                showLockToggle = false,
                idleCursor = BlankPointerIcon,
                snackbarHost = { SnackbarHost(snackbarHostState) },
            )
        }
    }
}

/** 桌面没有系统媒体音量可借，音量手势与方向键调的是 mpv 自身的音量。 */
private class BackendVolume(private val backend: PlaybackBackend) : PlayerLevelControl {
    override fun current(): Float = backend.volume ?: 1f

    override fun set(fraction: Float): Float {
        backend.setVolume(fraction)
        return fraction
    }
}

// 控件收起后连指针一起藏起来，全屏看片时指针停在画面中央很碍眼
private val BlankPointerIcon = PointerIcon(
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "piko-blank",
    ),
)
