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
import androidx.compose.runtime.mutableIntStateOf
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
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PikoSeekableMediaData
import dev.piko.shared.media.bestTranscodeName
import dev.piko.shared.media.originNeedsTranscode
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.errorOrNull
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.playUri
import org.openani.mediamp.togglePlayWhenReady

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
private const val RESUME_THRESHOLD_MILLIS = 3_000L
private const val NEAR_END_MILLIS = 10_000L
private const val MIN_PERSIST_MILLIS = 1_500L
private const val CONTROLS_HIDE_DELAY_MILLIS = 3_500L

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

private val ASPECT_LABELS = listOf(
    AspectRatioMode.FIT to "适应屏幕",
    AspectRatioMode.CROP to "裁剪填充",
    AspectRatioMode.STRETCH to "拉伸全屏",
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
    val playerState by player.state.collectAsState()
    val positionMillis by player.currentPositionMillis.collectAsState()
    val mediaProperties by player.mediaProperties.collectAsState()

    // 播哪个文件由状态决定：同目录列表可以在播放器里直接换片，不退回网盘。
    var currentFileId by remember(file.id) { mutableStateOf(file.id) }
    var currentFileName by remember(file.id) { mutableStateOf(file.name) }
    // 切片元数据（拿长度验本地完整性用）：列表内切换时跟着换。
    var currentFileStat by remember(file.id) { mutableStateOf(file) }

    val bufferingFeature = remember(player) { player.features[Buffering.Key] }
    val speedFeature = remember(player) { player.features[PlaybackSpeed.Key] }
    val aspectRatioFeature = remember(player) { player.features[VideoAspectRatio.Key] }
    val audioFeature = remember(player) { player.features[AudioLevelController.Key] }
    val bufferedPercentage by remember(bufferingFeature) {
        bufferingFeature?.bufferedPercentage ?: flowOf(0)
    }.collectAsState(0)
    val aspectRatioMode by (aspectRatioFeature?.mode ?: flowOf(AspectRatioMode.FIT))
        .collectAsState(AspectRatioMode.FIT)
    val playbackSpeed by remember(speedFeature) {
        speedFeature?.valueFlow ?: flowOf(1f)
    }.collectAsState(1f)
    val volumeLevel by (audioFeature?.volume ?: flowOf(1f)).collectAsState(1f)
    val isMuted by (audioFeature?.isMute ?: flowOf(false)).collectAsState(false)

    var mediaData by remember { mutableStateOf<PikoSeekableMediaData?>(null) }
    var mediaInfo by remember { mutableStateOf<PlayableMediaInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(true) }
    var requestedQuality by remember { mutableStateOf<String?>(null) }
    var activeQuality by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    var pendingStartMillis by remember { mutableStateOf<Long?>(null) }
    var resumedPositionMillis by remember { mutableLongStateOf(0L) }
    var showResumeTip by remember { mutableStateOf(false) }
    var isLocalPlayback by remember { mutableStateOf(false) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var touchFeedbackText by remember { mutableStateOf<String?>(null) }
    var showPlaylist by remember { mutableStateOf(false) }

    val durationMillis = mediaProperties?.durationMillis
        ?: mediaInfo?.durationSeconds?.times(1000L)
        ?: 0L
    val qualityOptions = mediaInfo?.availableVariants
        ?.map { it.mediaName.ifBlank { it.resolutionName } }
        .orEmpty().filter { it.isNotBlank() }
    val currentQuality = activeQuality ?: mediaInfo?.currentResolution

    fun pokeControls() {
        areControlsVisible = true
        lastInteractionTime = System.currentTimeMillis()
    }

    fun switchFile(target: FileStat) {
        if (target.id == currentFileId) return
        showPlaylist = false
        mediaData?.close()
        mediaData = null
        mediaInfo = null
        currentFileId = target.id
        currentFileName = target.name
        currentFileStat = target
        requestedQuality = null
        activeQuality = null
        pendingStartMillis = null
        retryToken += 1
    }

    // 播放防锁屏：有数据且正在播才持有，暂停/关窗自动释放。
    DisposableEffect(mediaData, playerState.playWhenReady) {
        val displayLease = if (mediaData != null && playerState.playWhenReady) {
            WinRTSupport.acquireDisplayRequest()
        } else null

        onDispose {
            displayLease?.close()
        }
    }

    // 取流：本地下完的直接播文件，否则走并发 range reader；wmv 这类本机解不开的
    // 容器自动换转码流（只看服务端元数据，不认扩展名）。
    LaunchedEffect(currentFileId, requestedQuality, retryToken) {
        isPreparing = true
        errorMessage = null
        showResumeTip = false
        player.stopPlayback()
        try {
            val startMillis = pendingStartMillis
                ?: mediaRepository.getPlaybackPosition(currentFileId)
                    .takeIf { it > RESUME_THRESHOLD_MILLIS }
                ?: 0L
            pendingStartMillis = null

            val localFile = downloadCoordinator?.findCompletedLocalPath(currentFileStat)
                ?.let(::File)?.takeIf { it.exists() }
            isLocalPlayback = localFile != null
            if (localFile != null) {
                player.playUri(localFile.toURI().toString(), startPositionMillis = startMillis)
                isPreparing = false
            } else {
                val info = mediaRepository.prepareMedia(currentFileId, requestedQuality).getOrThrow()
                mediaInfo = info
                if (info.kind != PlayableMediaKind.Video) {
                    errorMessage = "桌面播放器暂只支持视频"
                    isPreparing = false
                } else {
                    val autoQuality = if (requestedQuality == null && info.originNeedsTranscode()) {
                        info.bestTranscodeName()
                    } else {
                        null
                    }
                    val playedInfo = if (autoQuality != null) {
                        mediaRepository.prepareMedia(currentFileId, autoQuality).getOrThrow()
                            .also { mediaInfo = it }
                    } else {
                        info
                    }
                    val quality = requestedQuality ?: autoQuality
                    activeQuality = quality
                    val dataResult = mediaRepository.createMediaData(currentFileId, quality)
                    if (dataResult.isSuccess) {
                        mediaData?.close()
                        mediaData = dataResult.getOrThrow().second
                        player.setMediaData(
                            mediaData!!,
                            playWhenReady = true,
                            startPositionMillis = startMillis,
                        )
                    } else {
                        // range reader 打不开的，退回直链。
                        player.playUri(playedInfo.currentUrl, startPositionMillis = startMillis)
                    }
                    isPreparing = false
                }
            }

            if (startMillis > RESUME_THRESHOLD_MILLIS) {
                resumedPositionMillis = startMillis
                showResumeTip = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "无法加载视频媒体流"
            isPreparing = false
        }
    }

    // 断点续播：5 秒写一次，退出补一次；放到最后十秒记 0，下次从头播。
    LaunchedEffect(player, currentFileId) {
        suspend fun persistProgress() {
            val position = player.currentPositionMillis.value
            val duration = player.mediaProperties.value?.durationMillis ?: 0L
            if (duration > 0L && position >= duration - NEAR_END_MILLIS) {
                mediaRepository.savePlaybackPosition(currentFileId, 0L)
            } else if (position > MIN_PERSIST_MILLIS) {
                mediaRepository.savePlaybackPosition(currentFileId, position)
            }
        }

        try {
            while (isActive) {
                delay(5_000)
                persistProgress()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 持久化失败不能掐掉播放。
        } finally {
            withContext(NonCancellable) {
                runCatching { persistProgress() }
            }
        }
    }

    LaunchedEffect(showResumeTip) {
        if (showResumeTip) {
            delay(5_000)
            showResumeTip = false
        }
    }

    // 控件自动休眠计时器
    LaunchedEffect(areControlsVisible, lastInteractionTime, playerState.playWhenReady) {
        if (areControlsVisible && playerState.playWhenReady) {
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

    DisposableEffect(player) {
        onDispose {
            mediaData?.close()
            player.close()
        }
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
                                player.seekTo((positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L))
                                touchFeedbackText = "快退 10 秒"
                            }
                            fraction > 0.65f -> {
                                player.seekTo(positionMillis + SEEK_STEP_MILLIS)
                                touchFeedbackText = "快进 10 秒"
                            }
                            else -> {
                                player.togglePlayWhenReady()
                                touchFeedbackText = if (playerState.playWhenReady) "暂停" else "播放"
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
        if (isPreparing || playerState.isLoadingOrBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ProgressRing(size = ProgressRingSize.Large)
            }
        }

        // 错误提示 + 重试
        errorMessage?.let { err ->
            val decodeError = playerState.errorOrNull?.message
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
                        text = decodeError ?: err,
                        color = Color.White,
                        style = FluentTheme.typography.subtitle,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                errorMessage = null
                                retryToken += 1
                            },
                        ) {
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
        if (showResumeTip) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = if (areControlsVisible) 120.dp else 36.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable {
                        player.seekTo(0L)
                        showResumeTip = false
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "已恢复至 ${formatDuration(resumedPositionMillis)} · 从头播放",
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
                    title = currentFileName,
                    isLocalPlayback = isLocalPlayback,
                    aspectRatioMode = aspectRatioMode.takeIf { aspectRatioFeature != null },
                    qualityOptions = qualityOptions,
                    currentQuality = currentQuality,
                    showSpeedEntry = speedFeature != null,
                    showPlaylistEntry = playlist.size > 1,
                    playbackSpeed = playbackSpeed,
                    onPlaylistClick = { showPlaylist = true },
                    onClose = onClose,
                    onAspectRatioChange = { aspectRatioFeature?.setMode(it) },
                    onQualityChange = { quality ->
                        pendingStartMillis = positionMillis
                        requestedQuality = quality
                    },
                    onSpeedChange = { speedFeature?.set(it) },
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
                                player.seekTo((positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L))
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
                                player.togglePlayWhenReady()
                                pokeControls()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playerState.playWhenReady) Icons.Regular.Pause else Icons.Regular.Play,
                            contentDescription = if (playerState.playWhenReady) "暂停" else "播放",
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
                                player.seekTo(positionMillis + SEEK_STEP_MILLIS)
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
                    isPlaying = playerState.playWhenReady,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    bufferedFraction = bufferedPercentage / 100f,
                    playbackSpeed = playbackSpeed,
                    speedSupported = speedFeature != null,
                    volumeFraction = (volumeLevel / (audioFeature?.maxVolume?.takeIf { it > 0f } ?: 1f))
                        .coerceIn(0f, 1f),
                    showVolume = audioFeature != null,
                    isMuted = isMuted,
                    isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onPlayPause = {
                        player.togglePlayWhenReady()
                        pokeControls()
                    },
                    onSeekTo = {
                        player.seekTo(it)
                        pokeControls()
                    },
                    onSpeedChange = { speedFeature?.set(it) },
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
                            val isCurrent = video.id == currentFileId
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
    aspectRatioMode: AspectRatioMode?,
    qualityOptions: List<String>,
    currentQuality: String?,
    showSpeedEntry: Boolean,
    showPlaylistEntry: Boolean,
    playbackSpeed: Float,
    onPlaylistClick: () -> Unit,
    onClose: () -> Unit,
    onAspectRatioChange: (AspectRatioMode) -> Unit,
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
