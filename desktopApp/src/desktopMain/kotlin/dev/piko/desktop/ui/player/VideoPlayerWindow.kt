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
import dev.piko.data.auth.PlayerGestureDefaults
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import coil3.compose.AsyncImage
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.MacOs
import dev.piko.desktop.PikoWindow
import dev.piko.desktop.PixelAlignedContentEffect
import dev.piko.desktop.TitleBarColors
import dev.piko.desktop.TitleBarThemeEffect
import dev.piko.desktop.WindowFrame
import dev.piko.desktop.LinuxDesktop
import dev.piko.desktop.isLinux
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
import dev.piko.desktop.AwtDialogs
import dev.piko.ui.screens.player.rememberPlayerFileActions
import kotlinx.coroutines.launch
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.PlayerTopBar
import dev.piko.ui.screens.player.playlistOf
import dev.piko.ui.screens.player.siblingMedia
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.shared.media.player.PLAYER_SUBTITLE_EXTENSIONS
import dev.piko.shared.media.player.isPlayerSubtitleName
import io.github.nihildigit.pikpak.FileStat
import androidx.compose.ui.awt.ComposeWindow
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.runBlocking
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
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
    // 窗口眼下是否随画面横竖对调着。只记真正对调过的次数：最大化与全屏时转画面不动窗口，
    // 按画面的旋转角算就与窗口对不上
    var windowTurned by remember { mutableStateOf(false) }
    // 所有播放窗口共用一份记忆，下一个窗口开在上一个关掉时的位置与大小。新窗口的画面不带旋转，
    // 所以存的是对调前的尺寸，否则转过一次之后每个新窗口都是竖的
    val windowState = rememberRememberedWindowState(
        settings = settings,
        name = "player",
        defaultSize = DpSize(1000.dp, 620.dp),
        isBorderlessFullscreen = { isFullscreen },
        sizeToSave = { size -> if (windowTurned) DpSize(size.height, size.width) else size },
    )
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
            // 只有 Windows 自己铺满屏幕（WindowsFullscreen），别处用系统的全屏
            val usesSystemFullscreen = !WinRTSupport.isWindows
            val inFullscreen = if (usesSystemFullscreen) windowState.placement == WindowPlacement.Fullscreen else isFullscreen
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
                        settings = settings,
                        window = window,
                        isFullscreen = inFullscreen,
                        onToggleFullscreen = {
                            if (usesSystemFullscreen) {
                                // macOS 走系统的全屏空间，Linux 经 _NET_WM_STATE_FULLSCREEN 交给窗口管理器。
                                // WindowsFullscreen 绕开的崩溃出在 Skiko 的 D3D 路径上，macOS 走 Metal、Linux 走 OpenGL，
                                // 都不经过它；按标题栏绿灯进出全屏时 placement 同样跟着变
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
                        onQuarterTurn = {
                            if (!inFullscreen && turnWindow(window, windowState)) windowTurned = !windowTurned
                        },
                        onClose = onClose,
                        modifier = Modifier.trackPointerSource(pointerSource),
                    )
                }
            }
        }
    }
}

/**
 * 画面转了 90 度之后把窗口也横竖对调，绕窗口中心转：只转画面的话，竖过来的片子缩在横窗口正中，两边全是黑的。
 * 对调后超出屏幕可用区域的，按比例缩到放得下，再挪回屏幕里。最大化与全屏时不动，那时窗口大小由系统定。
 *
 * WindowState 的尺寸与 AWT 的屏幕坐标都是逻辑像素，同一个单位，直接比较。
 * 返回窗口是否真的对调了。
 */
private fun turnWindow(window: Window, windowState: WindowState): Boolean {
    if (windowState.placement != WindowPlacement.Floating) return false
    val config = window.graphicsConfiguration ?: return false
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val screen = config.bounds
    val usableLeft = screen.x + insets.left
    val usableTop = screen.y + insets.top
    val usableWidth = (screen.width - insets.left - insets.right).toFloat()
    val usableHeight = (screen.height - insets.top - insets.bottom).toFloat()

    val old = windowState.size
    val fit = minOf(1f, usableWidth / old.height.value, usableHeight / old.width.value)
    val width = old.height.value * fit
    val height = old.width.value * fit
    val centerX = window.x + window.width / 2f
    val centerY = window.y + window.height / 2f
    val x = (centerX - width / 2).coerceIn(usableLeft.toFloat(), maxOf(usableLeft.toFloat(), usableLeft + usableWidth - width))
    val y = (centerY - height / 2).coerceIn(usableTop.toFloat(), maxOf(usableTop.toFloat(), usableTop + usableHeight - height))
    windowState.size = DpSize(width.dp, height.dp)
    windowState.position = WindowPosition(x.dp, y.dp)
    return true
}

// 画面四周是黑的，标题栏与之连成一片，不随应用主题
private val PlayerTitleBarColors = TitleBarColors(container = Color.Black, content = Color.White)

@Composable
@OptIn(ExperimentalMediampApi::class, FlowPreview::class)
private fun VideoPlayerContent(
    request: VideoPlayerRequest,
    services: PikoServices,
    settings: DesktopSettingsStore,
    window: ComposeWindow,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onTitleChange: (String) -> Unit,
    onQuarterTurn: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    // 偏好是 StateFlow，first() 当场返回
    val hardwareDecoding = remember { runBlocking { services.preferences.hardwareDecodingFlow.first() } }
    val backend = remember(player) {
        MediampPlaybackBackend(player, scope, hardwareDecoding = hardwareDecoding).also { created ->
            // mpv 的音量在换文件时保留，只需在开窗时设一次
            settings.get(KEY_PLAYER_VOLUME).toFloatOrNull()?.let(created::setVolume)
        }
    }
    // 音量按本机记住，下一个窗口、下次启动照旧。不进设置同步：各台设备的音箱与系统音量不同。
    // 静音（音量为 0）不记：下次打开一片寂静，像是坏了
    LaunchedEffect(backend) {
        snapshotFlow { backend.volume }
            .filterNotNull()
            .filter { it > 0f }
            .debounce(VOLUME_SAVE_DEBOUNCE_MILLIS)
            .collect { settings.set(KEY_PLAYER_VOLUME, it.toString()) }
    }
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
            initialStartMillis = request.startMillis,
            // 本地副本按文件长度验完整性，需要对应的 FileStat，从同目录列表里取
            resolveLocalPath = { fileId, hint ->
                hint?.takeIf { File(it).exists() }
                    ?: siblingVideos.find { it.id == fileId }
                        ?.let { downloads.findCompletedLocalPath(it) }
                        ?.takeIf { File(it).exists() }
            },
            defaultMaxHeight = { services.preferences.playbackMaxHeightFlow.first() },
        )
    }
    val volume = remember(backend) { backend.volume?.let { BackendVolume(backend) } }
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsState(initial = true)
    val seekStepSeconds by services.preferences.playerSeekStepSecondsFlow
        .collectAsState(initial = PlayerGestureDefaults.SEEK_STEP_SECONDS)
    val longPressSpeed by services.preferences.playerBoostSpeedFlow.collectAsState(initial = PlayerGestureDefaults.BOOST_SPEED)
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

    // Linux 上 MediaMP 的画面经 GLX 与 Skiko 共享纹理，Skiko 退到软件渲染时（没有硬件 OpenGL，llvmpipe 也被它拒绝）
    // 画面一直是黑的，打开也不返回。说一句原因，免得用户对着黑屏等。渲染方式在窗口头几帧里才定下来
    if (isLinux) {
        LaunchedEffect(window) {
            delay(1_000)
            if (window.renderApi.name.startsWith("SOFTWARE")) {
                PikoLog.w("Player", "Skiko 以 ${window.renderApi} 渲染，Linux 上播放器画面出不来")
                snackbarHostState.showSnackbar("没有可用的硬件 OpenGL，无法显示视频画面。请检查显卡驱动", withDismissAction = true)
            }
        }
    }

    // 播放防锁屏：正在播才持有，暂停与关窗时释放
    DisposableEffect(state.isPlaying) {
        val displayLease = when {
            !state.isPlaying -> null
            isMacOs -> MacOs.preventSleep()
            isLinux -> LinuxDesktop.preventSleep()
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
                seekStepSeconds = seekStepSeconds,
                onSeekStepChange = { scope.launch { services.preferences.setPlayerSeekStepSeconds(it) } },
                longPressSpeed = longPressSpeed,
                onLongPressSpeedChange = { scope.launch { services.preferences.setPlayerBoostSpeed(it) } },
                playbackStats = state::stats,
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
                fileActions = rememberPlayerFileActions(state.fileId, state.isLocalPlayback) { message ->
                    scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
                },
                rotationDegrees = state.rotationDegrees.takeIf { state.supportsRotation },
                onRotationChange = { degrees ->
                    // 只在用户转的时候动窗口；换集时 mpv 自己保留旋转，窗口本就是转过的样子
                    val turned = (degrees - state.rotationDegrees) % 180 != 0
                    state.setRotation(degrees)
                    if (turned) onQuarterTurn()
                },
                onPickLocalSubtitle = fun() {
                    // 属主要在点击的当下取，launch 之后焦点可能已经变了
                    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    // 文件框开着时照常播放，可能已连播到下一集
                    val videoFileId = state.fileId
                    scope.launch {
                        val file = chooseSubtitleFile(owner) ?: return@launch
                        if (isPlayerSubtitleName(file.name)) {
                            state.addLocalSubtitle(videoFileId, file.absolutePath, file.name)
                        } else {
                            snackbarHostState.showSnackbar("不支持这种字幕格式", withDismissAction = true)
                        }
                    }
                }.takeIf { state.canAddSubtitle },
                onPickDriveSubtitle = { file: FileStat -> state.addDriveSubtitle(file.id, file.name) }
                    .takeIf { state.canAddSubtitle },
            )
        }
    }
}

/** 系统的文件框，只挑一个字幕文件，经 [AwtDialogs] 弹。取消时为 null。 */
private suspend fun chooseSubtitleFile(owner: Window?): File? = AwtDialogs.chooseFiles(
    owner = owner,
    title = "选择字幕文件",
    multiple = false,
    pattern = PLAYER_SUBTITLE_EXTENSIONS.joinToString(";") { "*.$it" },
    filter = ::isPlayerSubtitleName,
).firstOrNull()

// settings.properties 里的键，与 DesktopPikoPreferences 的 player.* 同一命名空间，只是不经偏好接口：Android 用系统音量，用不上
private const val KEY_PLAYER_VOLUME = "player.volume"
// 拖滑块、滚滚轮时连续在变，停下来再写盘
private const val VOLUME_SAVE_DEBOUNCE_MILLIS = 500L

/** 桌面没有系统媒体音量可借，音量手势、方向键与底栏滑块调的是 mpv 自身的音量。 */
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
