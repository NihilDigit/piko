package dev.piko.desktop.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.ui.components.isVideoFile
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.download.DownloadStatus
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerScreenState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.BasicSlider
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.FlyoutPlacement
import io.github.composefluent.component.Icon
import io.github.composefluent.component.MenuFlyoutContainer
import io.github.composefluent.component.MenuFlyoutContainerScope
import io.github.composefluent.component.MenuFlyoutItem
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SliderDefaults
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.darkColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.FastForward
import io.github.composefluent.icons.regular.FullScreenMaximize
import io.github.composefluent.icons.regular.FullScreenMinimize
import io.github.composefluent.icons.regular.Gauge
import io.github.composefluent.icons.regular.Hd
import io.github.composefluent.icons.regular.List
import io.github.composefluent.icons.regular.Pause
import io.github.composefluent.icons.regular.Play
import io.github.composefluent.icons.regular.Rewind
import io.github.composefluent.icons.regular.ScaleFit
import io.github.composefluent.icons.regular.Speaker2
import io.github.composefluent.icons.regular.SpeakerMute
import io.github.nihildigit.pikpak.FileStat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AudioLevelController

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        "%02d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

private const val SEEK_STEP_MILLIS = 10_000L
private const val CONTROLS_HIDE_DELAY_MILLIS = 3_500L

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

private val ASPECT_LABELS = listOf(
    PlayerAspectRatio.Fit to "适应屏幕",
    PlayerAspectRatio.Crop to "裁剪填充",
    PlayerAspectRatio.Stretch to "拉伸全屏",
)

/**
 * 独立的 Fluent 风格视频播放窗口，对齐 Android 播放器（PlayerTopBar / PlayerBottomBar）：
 * 顶栏（关闭、标题、画面比例、清晰度、倍速、同目录列表）、底部双轨进度条、
 * 断点续播、真音量、全屏切换。mpv 后端没有倍速特性时倍速入口自动隐藏。
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
        // 播放器永远深色：跟 Android 一样罩黑渐变，浅色主题下 SubtleButton 的
        // 深色字在黑色视频上会直接隐形，所以这里不跟随应用主题。
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

    val audioFeature = remember(player) { player.features[AudioLevelController.Key] }
    val volumeLevel by (audioFeature?.volume ?: flowOf(1f)).collectAsState(1f)
    val isMuted by (audioFeature?.isMute ?: flowOf(false)).collectAsState(false)

    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var touchFeedbackText by remember { mutableStateOf<String?>(null) }
    var showPlaylist by remember { mutableStateOf(false) }

    val positionMillis = state.positionMillis
    val durationMillis = state.durationMillis
    val playbackSpeed = state.playbackSpeed
    val errorMessage = if (state.isImage) "桌面播放器暂只支持视频" else state.errorMessage

    fun pokeControls() {
        areControlsVisible = true
        lastInteractionTime = System.currentTimeMillis()
    }

    fun switchFile(target: FileStat) {
        showPlaylist = false
        state.switchTo(target.id, target.name)
    }

    // 播放防锁屏：正在播才持有，暂停/关窗自动释放。
    DisposableEffect(state.isPlaying) {
        val displayLease = if (state.isPlaying) WinRTSupport.acquireDisplayRequest() else null
        onDispose {
            displayLease?.close()
        }
    }

    // 换源之类的一次性提示借用手势提示气泡
    LaunchedEffect(state) {
        state.messages.collect { touchFeedbackText = it }
    }

    // 控件自动休眠计时器
    LaunchedEffect(areControlsVisible, lastInteractionTime, state.isPlaying) {
        if (areControlsVisible && state.isPlaying) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            areControlsVisible = false
        }
    }

    // 手势提示渐隐计时器
    LaunchedEffect(touchFeedbackText) {
        if (touchFeedbackText != null) {
            delay(1200)
            touchFeedbackText = null
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
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            // 鼠标一动就写时间戳会每帧重组：只在控件藏着时唤醒，
                            // 时间戳节流到 500ms，避免自动休眠计时器被反复重启。
                            val now = System.currentTimeMillis()
                            if (!areControlsVisible) {
                                areControlsVisible = true
                                lastInteractionTime = now
                            } else if (now - lastInteractionTime > 500L) {
                                lastInteractionTime = now
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // 双击：中间 30% 播停（与 Android 同），两侧快退/快进 10 秒。
                detectTapGestures(
                    onTap = {
                        areControlsVisible = !areControlsVisible
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDoubleTap = { offset ->
                        val fraction = offset.x / size.width.toFloat()
                        when {
                            fraction < 0.35f -> {
                                state.seekBy(-SEEK_STEP_MILLIS)
                                touchFeedbackText = "快退 10 秒"
                            }
                            fraction > 0.65f -> {
                                state.seekBy(SEEK_STEP_MILLIS)
                                touchFeedbackText = "快进 10 秒"
                            }
                            else -> {
                                touchFeedbackText = if (state.isPlaying) "暂停" else "播放"
                                state.togglePlayPause()
                            }
                        }
                        pokeControls()
                    },
                )
            },
    ) {
        // 视频渲染底图
        MediampPlayerSurface(player, Modifier.fillMaxSize())

        // 缓冲转圈
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ProgressRing(size = ProgressRingSize.Large)
            }
        }

        // 错误提示 + 重试
        errorMessage?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = err,
                        color = Color.White,
                        style = FluentTheme.typography.subtitle,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = state::retry) {
                            Text("重试")
                        }
                        Button(onClick = onClose) {
                            Text("关闭窗口")
                        }
                    }
                }
            }
        }

        // 触控手势提示气泡 (居中 HUD)
        touchFeedbackText?.let { feedback ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = feedback,
                    color = Color.White,
                    style = FluentTheme.typography.subtitle,
                )
            }
        }

        // 续播提示，附带「从头播放」
        state.resumedFromMillis?.let { resumedFrom ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = if (areControlsVisible) 120.dp else 36.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { state.restartFromBeginning() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "已恢复至 ${formatDuration(resumedFrom)} · 从头播放",
                    color = Color.White,
                    style = FluentTheme.typography.caption,
                )
            }
        }

        // 浮动控件 (顶部栏与底部控制栏)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = state.title,
                    isLocalPlayback = state.isLocalPlayback,
                    aspectRatioMode = state.aspectRatio,
                    qualityOptions = state.qualityOptions,
                    currentQuality = state.currentQuality,
                    showSpeedEntry = playbackSpeed != null,
                    showPlaylistEntry = playlist.size > 1,
                    playbackSpeed = playbackSpeed ?: 1f,
                    onPlaylistClick = { showPlaylist = true },
                    onClose = onClose,
                    onAspectRatioChange = state::setAspectRatio,
                    onQualityChange = state::selectQuality,
                    onSpeedChange = state::setSpeed,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // 屏幕两侧大触控前进/后退按钮 (平板/触屏优化)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable {
                                state.seekBy(-SEEK_STEP_MILLIS)
                                touchFeedbackText = "-10 秒"
                                pokeControls()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Regular.Rewind,
                            contentDescription = "快退 10 秒",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // 居中大触控播放/暂停按钮
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.85f))
                            .clickable {
                                state.togglePlayPause()
                                pokeControls()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Regular.Pause else Icons.Regular.Play,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable {
                                state.seekBy(SEEK_STEP_MILLIS)
                                touchFeedbackText = "+10 秒"
                                pokeControls()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Regular.FastForward,
                            contentDescription = "快进 10 秒",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                PlayerBottomBar(
                    isPlaying = state.isPlaying,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    bufferedFraction = if (durationMillis > 0L) {
                        (state.bufferedPositionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    playbackSpeed = playbackSpeed ?: 1f,
                    speedSupported = playbackSpeed != null,
                    volumeFraction = (volumeLevel / (audioFeature?.maxVolume?.takeIf { it > 0f } ?: 1f))
                        .coerceIn(0f, 1f),
                    showVolume = audioFeature != null,
                    isMuted = isMuted,
                    isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onPlayPause = {
                        state.togglePlayPause()
                        pokeControls()
                    },
                    onSeekTo = {
                        state.seekTo(it)
                        pokeControls()
                    },
                    onSpeedChange = state::setSpeed,
                    onToggleFullscreen = {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                        pokeControls()
                    },
                    onVolumeChange = { fraction ->
                        val max = audioFeature?.maxVolume?.takeIf { it > 0f } ?: 1f
                        audioFeature?.setVolume(fraction * max)
                        if (fraction > 0f && isMuted) audioFeature?.setMute(false)
                        pokeControls()
                    },
                    onToggleMute = {
                        audioFeature?.setMute(!isMuted)
                        pokeControls()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // 同目录视频列表
        if (showPlaylist) {
            ContentDialog(
                title = "同目录视频 · ${playlist.size}",
                visible = true,
                primaryButtonText = "关闭",
                onButtonClick = { showPlaylist = false },
                content = {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(playlist, key = { it.id }) { video ->
                            val isCurrent = video.id == state.fileId
                            // 对话框是浅色底，文字必须用主题色，写死白色会隐形。
                            val rowColor = if (isCurrent) {
                                FluentTheme.colors.fillAccent.default
                            } else {
                                FluentTheme.colors.text.text.primary
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { switchFile(video) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Regular.Play,
                                    contentDescription = null,
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
    }
}

/**
 * 播放器顶部控制栏：关闭、标题、画面比例、清晰度、倍速、同目录列表。
 *
 * 标题占满剩余宽度（fill=true），按钮组自然贴右——Android 曾经在这里用
 * weight(1f, fill=false) + Spacer(weight) 把按钮顶到了中间，已修。
 */
@Composable
private fun PlayerTopBar(
    title: String,
    isLocalPlayback: Boolean,
    aspectRatioMode: PlayerAspectRatio?,
    qualityOptions: List<String>,
    currentQuality: String?,
    showSpeedEntry: Boolean,
    showPlaylistEntry: Boolean,
    playbackSpeed: Float,
    onPlaylistClick: () -> Unit,
    onClose: () -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onQualityChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SubtleButton(onClick = onClose) {
                Icon(Icons.Regular.Dismiss, contentDescription = "关闭", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = FluentTheme.typography.subtitle,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isLocalPlayback) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "本地播放",
                        style = FluentTheme.typography.caption,
                        color = Color.White,
                    )
                }
            }

            if (aspectRatioMode != null) {
                MenuFlyoutContainer(
                    flyout = {
                        ASPECT_LABELS.forEach { (mode, label) ->
                            MenuFlyoutItem(
                                onClick = {
                                    isFlyoutVisible = false
                                    onAspectRatioChange(mode)
                                },
                                text = { Text(if (mode == aspectRatioMode) "$label ·" else label) },
                            )
                        }
                    },
                    placement = FlyoutPlacement.BottomAlignedEnd,
                    adaptivePlacement = true,
                    content = {
                        SubtleButton(onClick = { isFlyoutVisible = !isFlyoutVisible }) {
                            Icon(Icons.Regular.ScaleFit, contentDescription = "画面比例", modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }

            if (qualityOptions.isNotEmpty()) {
                MenuFlyoutContainer(
                    flyout = {
                        qualityOptions.forEach { option ->
                            MenuFlyoutItem(
                                onClick = {
                                    isFlyoutVisible = false
                                    onQualityChange(option)
                                },
                                text = { Text(if (option == currentQuality) "$option ·" else option) },
                            )
                        }
                    },
                    placement = FlyoutPlacement.BottomAlignedEnd,
                    adaptivePlacement = true,
                    content = {
                        SubtleButton(onClick = { isFlyoutVisible = !isFlyoutVisible }) {
                            Icon(Icons.Regular.Hd, contentDescription = "清晰度", modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }

            if (showSpeedEntry) {
                MenuFlyoutContainer(
                    flyout = {
                        SpeedMenuItems(playbackSpeed) {
                            isFlyoutVisible = false
                            onSpeedChange(it)
                        }
                    },
                    placement = FlyoutPlacement.BottomAlignedEnd,
                    adaptivePlacement = true,
                    content = {
                        SubtleButton(onClick = { isFlyoutVisible = !isFlyoutVisible }) {
                            Icon(Icons.Regular.Gauge, contentDescription = "倍速", modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }

            if (showPlaylistEntry) {
                SubtleButton(onClick = onPlaylistClick) {
                    Icon(Icons.Regular.List, contentDescription = "同目录视频", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * 播放器底部控制栏：播放/快退快进、时间、倍速回显、音量、全屏切换、双轨进度条。
 */
@Composable
private fun PlayerBottomBar(
    isPlaying: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedFraction: Float,
    playbackSpeed: Float,
    speedSupported: Boolean,
    volumeFraction: Float,
    showVolume: Boolean,
    isMuted: Boolean,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosFraction by remember { mutableFloatStateOf(0f) }

    val progressFraction = if (durationMillis > 0L) {
        (positionMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.5f),
                        Color.Black.copy(alpha = 0.9f),
                    ),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                // Android 同款：大播放键 + 快退/快进 + 双色时间，进度条另起一行。
                SubtleButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Regular.Pause else Icons.Regular.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(26.dp),
                    )
                }
                SubtleButton(
                    onClick = { onSeekTo((positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L)) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Regular.Rewind,
                        contentDescription = "后退 10 秒",
                        modifier = Modifier.size(22.dp),
                    )
                }
                SubtleButton(
                    onClick = {
                        val limit = durationMillis.coerceAtLeast(0L)
                        onSeekTo((positionMillis + SEEK_STEP_MILLIS).coerceAtMost(limit))
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Regular.FastForward,
                        contentDescription = "前进 10 秒",
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(Modifier.width(6.dp))

                Text(
                    text = formatDuration(positionMillis),
                    style = FluentTheme.typography.body,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = " / ${formatDuration(durationMillis)}",
                    style = FluentTheme.typography.body,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (speedSupported) {
                    MenuFlyoutContainer(
                        flyout = {
                            SpeedMenuItems(playbackSpeed) {
                                isFlyoutVisible = false
                                onSpeedChange(it)
                            }
                        },
                        placement = FlyoutPlacement.TopAlignedEnd,
                        adaptivePlacement = true,
                        content = {
                            // Android 的半透明白胶囊，回显当前倍速。
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .clickable { isFlyoutVisible = !isFlyoutVisible }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2fx", playbackSpeed),
                                    style = FluentTheme.typography.caption,
                                    color = Color.White,
                                )
                            }
                        },
                    )
                }

                if (showVolume) {
                    SubtleButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Regular.SpeakerMute else Icons.Regular.Speaker2,
                            contentDescription = "静音切换",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Box(modifier = Modifier.width(100.dp)) {
                        Slider(
                            value = if (isMuted) 0f else volumeFraction,
                            onValueChange = onVolumeChange,
                            tooltipContent = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                SubtleButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isFullscreen) {
                            Icons.Regular.FullScreenMinimize
                        } else {
                            Icons.Regular.FullScreenMaximize
                        },
                        contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 双轨进度条：缓冲一轨、播放一轨。Slider 单轨画不出缓冲，
        // 自绘轨道拿不到拖拽手感，所以轨道自绘 + BasicSlider 只留 thumb。
        DualTrackScrubber(
            playedFraction = if (isSeeking) seekPosFraction else progressFraction,
            bufferedFraction = bufferedFraction,
            enabled = durationMillis > 0,
            timeLabel = formatDuration(
                if (isSeeking) (seekPosFraction * durationMillis).toLong() else positionMillis,
            ),
            onSeekFraction = {
                isSeeking = true
                seekPosFraction = it
            },
            onSeekFinished = { fraction ->
                if (durationMillis > 0L) {
                    onSeekTo((fraction * durationMillis).toLong())
                }
                isSeeking = false
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 倍速预设菜单项，顶栏仪表盘按钮和底栏倍速回显共用同一份。 */
@Composable
private fun MenuFlyoutContainerScope.SpeedMenuItems(
    playbackSpeed: Float,
    onPick: (Float) -> Unit,
) {
    SPEED_PRESETS.forEach { preset ->
        MenuFlyoutItem(
            onClick = { onPick(preset) },
            text = {
                Text(
                    String.format(Locale.US, "%.2f", preset) + "x" +
                        if (preset == playbackSpeed) " ·" else "",
                )
            },
        )
    }
}

@Composable
private fun DualTrackScrubber(
    playedFraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    timeLabel: String,
    onSeekFraction: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicSlider(
            value = playedFraction,
            onValueChange = onSeekFraction,
            onValueChangeFinished = onSeekFinished,
            enabled = enabled,
            rail = {
                Box(Modifier.fillMaxWidth().height(28.dp))
            },
            track = { state ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                            .background(Color.White.copy(alpha = 0.45f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(state.value.coerceIn(0f, 1f))
                            .background(FluentTheme.colors.fillAccent.default),
                    )
                }
            },
            thumb = { state ->
                SliderDefaults.Thumb(
                    state = state,
                    label = {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.8f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = timeLabel,
                                style = FluentTheme.typography.caption,
                                color = Color.White,
                            )
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
