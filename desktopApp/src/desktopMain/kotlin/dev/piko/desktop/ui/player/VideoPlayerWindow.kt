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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import coil3.compose.AsyncImage
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.MacOs
import dev.piko.desktop.PikoWindow
import dev.piko.desktop.PixelAlignedContentEffect
import dev.piko.desktop.TitleBarColors
import dev.piko.desktop.TitleBarThemeEffect
import dev.piko.desktop.WindowFrame
import dev.piko.desktop.isMacOs
import dev.piko.desktop.rememberRememberedWindowState
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsFullscreen
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTopBar
import dev.piko.ui.screens.player.playlistOf
import dev.piko.ui.screens.player.siblingMedia
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
    settings: DesktopSettingsStore,
    appearance: Appearance,
    icon: Painter?,
    onClose: () -> Unit,
) {
    // 无边框全屏不改 WindowState 的 placement，窗口尺寸却铺满了屏幕，要告诉位置记忆此时别存
    var isFullscreen by remember { mutableStateOf(false) }
    // 所有播放窗口共用一份记忆，下一个窗口开在上一个关掉时的位置与大小
    val windowState = rememberRememberedWindowState(settings, "player", DpSize(1000.dp, 620.dp)) { isFullscreen }
    // 换集后标题跟着当前这集走
    var title by remember { mutableStateOf(request.fileName) }

    PikoWindow(
        onCloseRequest = onClose,
        title = "$title - Piko 播放器",
        icon = icon,
        state = windowState,
    ) {
        // 画面四周是黑的，窗口外框不随应用主题，始终用深色
        TitleBarThemeEffect(window, dark = true)
        PixelAlignedContentEffect(window)
        val fullscreen = remember(window) { WindowsFullscreen(window) }
        // 播放窗口是独立的组合树，主窗口根部的输入来源追踪管不到这里
        val pointerSource = remember { PointerSource() }
        CompositionLocalProvider(
            LocalPikoServices provides services,
            LocalPikoPlatform provides platform,
            LocalPointerSource provides pointerSource,
        ) {
            val inFullscreen = if (isMacOs) windowState.placement == WindowPlacement.Fullscreen else isFullscreen
            PikoTheme(appearance = appearance) {
                WindowFrame(
                    title = "$title - Piko 播放器",
                    icon = icon,
                    colors = PlayerTitleBarColors,
                    showTitleBar = !inFullscreen,
                    // 播放器顶栏已有标题，Windows 上不再叠一条标题栏，关窗按钮放进顶栏
                    onCloseInContent = onClose,
                ) {
                    VideoPlayerContent(
                        request = request,
                        services = services,
                        isFullscreen = inFullscreen,
                        onToggleFullscreen = {
                            if (isMacOs) {
                                // macOS 走系统的全屏空间。WindowsFullscreen 绕开的崩溃出在 Skiko 的 D3D 路径上，
                                // macOS 渲染走 Metal，不经过它；按标题栏绿灯进出全屏时 placement 同样跟着变
                                windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Fullscreen
                                }
                            } else {
                                if (fullscreen.isFullscreen) fullscreen.exit() else fullscreen.enter()
                                isFullscreen = fullscreen.isFullscreen
                            }
                        },
                        onTitleChange = { title = it },
                        onClose = onClose,
                        modifier = Modifier.trackPointerSource(pointerSource),
                    )
                }
            }
        }
    }
}

// 画面四周是黑的，标题栏与之连成一片，不随应用主题
private val PlayerTitleBarColors = TitleBarColors(container = Color.Black, content = Color.White)

@Composable
@OptIn(ExperimentalMediampApi::class)
private fun VideoPlayerContent(
    request: VideoPlayerRequest,
    services: PikoServices,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTitleChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
        // 主界面给的只有视频，字幕文件总要自己列一次目录取
        val siblings = services.driveRepository.siblingMedia(request.fileId)
        if (siblingVideos.isEmpty()) siblingVideos = siblings.videos
        state.playlist = playlistOf(siblingVideos, services.preferences, siblings.subtitles)
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
        val displayLease = when {
            !state.isPlaying -> null
            isMacOs -> MacOs.preventSleep()
            else -> WinRTSupport.acquireDisplayRequest()
        }
        onDispose { displayLease?.close() }
    }

    DisposableEffect(state) {
        onDispose { state.release() }
    }
    DisposableEffect(player) {
        onDispose { player.close() }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
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
                audioTracks = state.audioTracks,
                selectedAudioTrackId = state.selectedAudioTrackId,
                onSelectAudioTrack = state::selectAudioTrack,
                subtitleTracks = state.subtitleTracks,
                selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                onSelectSubtitleTrack = state::selectSubtitleTrack,
                volume = volume,
                seekThumbOnHoverOnly = true,
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
