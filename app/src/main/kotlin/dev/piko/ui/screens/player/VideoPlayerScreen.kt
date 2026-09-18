package dev.piko.ui.screens.player

import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.round
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.piko.PikoApplication
import dev.piko.data.repository.PlayableMediaInfo
import dev.piko.media.PikoStreamSession
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.theme.FixedColors
import dev.piko.ui.theme.LocalFixedColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    fileId: String,
    fileName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val orientationController = rememberOrientationController()
    val windowBrightness = rememberWindowBrightness()
    val mediaVolume = rememberMediaVolume(context)
    val mediaRepo = PikoApplication.instance.mediaRepository
    val scope = rememberCoroutineScope()

    var mediaInfo by remember { mutableStateOf<PlayableMediaInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // 历史播放位置记忆与恢复
    var resumedPosition by remember { mutableLongStateOf(0L) }
    var pendingSeekPosition by remember { mutableLongStateOf(0L) }
    var showResumeTip by remember { mutableStateOf(false) }

    // 控制条可见性
    var controlsVisible by remember { mutableStateOf(true) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showResizeMenu by remember { mutableStateOf(false) }
    var isLongPressingSpeed by remember { mutableStateOf(false) }

    // 手势状态
    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var initialTouchPoint by remember { mutableStateOf(Offset.Zero) }
    var initialGestureValue by remember { mutableFloatStateOf(0f) }
    var initialGesturePosition by remember { mutableLongStateOf(0L) }

    // 双击快进快退反馈
    var doubleTapFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // isRight to label

    var currentStreamSession by remember { mutableStateOf<PikoStreamSession?>(null) }

    val prepareVideo = { resolution: String? ->
        isLoading = true
        scope.launch {
            currentStreamSession?.close()
            currentStreamSession = null

            val result = mediaRepo.createStreamSession(fileId, resolution, concurrency = 8)
            isLoading = false
            result.onSuccess { (info, session) ->
                mediaInfo = info
                currentStreamSession = session
                val mediaSource = DefaultMediaSourceFactory(session.dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(info.currentUrl))
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
            }.onFailure {
                // 回退到单连接默认取流保障播放
                val fallback = mediaRepo.prepareMedia(fileId, resolution)
                fallback.onSuccess { info ->
                    mediaInfo = info
                    val mediaItem = MediaItem.fromUri(info.currentUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                }
            }
        }
    }

    // 初始化载入并恢复上次播放进度
    LaunchedEffect(fileId) {
        val saved = mediaRepo.getPlaybackPosition(fileId)
        if (saved > 3000L) {
            resumedPosition = saved
            pendingSeekPosition = saved
        }
        prepareVideo(null)
    }

    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                    if (pendingSeekPosition > 0L && totalDuration > pendingSeekPosition) {
                        exoPlayer.seekTo(pendingSeekPosition)
                        showResumeTip = true
                        pendingSeekPosition = 0L
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        while (isActive) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            // 周期性持久化播放进度
            if (isPlaying && currentPosition > 2000L) {
                if (totalDuration > 0 && currentPosition >= totalDuration - 10000L) {
                    mediaRepo.savePlaybackPosition(fileId, 0L)
                } else {
                    mediaRepo.savePlaybackPosition(fileId, currentPosition)
                }
            }
            delay(400)
        }
    }

    // 恢复提示自动消失
    LaunchedEffect(showResumeTip) {
        if (showResumeTip) {
            delay(5000)
            showResumeTip = false
        }
    }

    // 控制栏 4.5 秒自动隐藏
    LaunchedEffect(controlsVisible, isPlaying, isLocked) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(4500)
            controlsVisible = false
        }
    }

    // 退出时保存最终进度并释放播放器
    DisposableEffect(fileId) {
        onDispose {
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (dur > 0 && pos >= dur - 10000L) {
                PikoApplication.instance.appScope.launch {
                    mediaRepo.savePlaybackPosition(fileId, 0L)
                }
            } else if (pos > 1500L) {
                PikoApplication.instance.appScope.launch {
                    mediaRepo.savePlaybackPosition(fileId, pos)
                }
            }
            exoPlayer.release()
            currentStreamSession?.close()
        }
    }

    // 屏幕常亮与旋转沉浸式系统栏监听
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(isLandscape) {
        if (isLandscape) {
            orientationController.hideSystemBars()
        } else {
            orientationController.showSystemBars()
        }
    }

    BackHandler {
        if (isLandscape) {
            orientationController.setPortrait()
        } else {
            onBackClick()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ExoPlayer 核心视频视图
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 全功能手势响应层 (对齐 Animeko 架构: 单击显隐, 双击10秒, 长按2x加速, 横划Seek, 竖划亮度/音量)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                        return@pointerInput
                    }

                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            val isRight = offset.x > size.width / 2
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isRight) {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(totalDuration))
                                doubleTapFeedback = Pair(true, "+10秒")
                            } else {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                                doubleTapFeedback = Pair(false, "-10秒")
                            }
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isLongPressingSpeed = true
                            exoPlayer.setPlaybackSpeed(2.0f)
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (isLongPressingSpeed) {
                                isLongPressingSpeed = false
                                exoPlayer.setPlaybackSpeed(playbackSpeed)
                            }
                        },
                    )
                }
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            initialTouchPoint = offset
                            activeGesture = null
                        },
                        onDragEnd = {
                            (activeGesture as? PlayerGesture.Seek)?.let { seek ->
                                val target = (seek.startPositionMillis + seek.deltaMillis)
                                    .coerceIn(0L, totalDuration.coerceAtLeast(1L))
                                exoPlayer.seekTo(target)
                            }
                            activeGesture = null
                        },
                        onDragCancel = {
                            activeGesture = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val totalDx = change.position.x - initialTouchPoint.x
                            val totalDy = change.position.y - initialTouchPoint.y

                            if (activeGesture == null) {
                                if (abs(totalDx) > 24 && abs(totalDx) > abs(totalDy)) {
                                    initialGesturePosition = exoPlayer.currentPosition
                                    activeGesture = PlayerGesture.Seek(initialGesturePosition, 0L)
                                } else if (abs(totalDy) > 24 && abs(totalDy) > abs(totalDx)) {
                                    val isLeftSide = initialTouchPoint.x < size.width / 2
                                    if (isLeftSide) {
                                        initialGestureValue = windowBrightness.current()
                                        activeGesture = PlayerGesture.Adjust(VerticalAdjust.Brightness, initialGestureValue)
                                    } else {
                                        initialGestureValue = mediaVolume.current()
                                        activeGesture = PlayerGesture.Adjust(VerticalAdjust.Volume, initialGestureValue)
                                    }
                                }
                            }

                            when (val gesture = activeGesture) {
                                is PlayerGesture.Seek -> {
                                    val delta = (totalDx * 120).toLong()
                                    activeGesture = gesture.copy(deltaMillis = delta)
                                }
                                is PlayerGesture.Adjust -> {
                                    val deltaFraction = -totalDy / (size.height * 0.75f)
                                    val newFraction = (initialGestureValue + deltaFraction).coerceIn(0f, 1f)
                                    if (gesture.kind == VerticalAdjust.Brightness) {
                                        windowBrightness.set(newFraction)
                                    } else {
                                        mediaVolume.set(newFraction)
                                    }
                                    activeGesture = gesture.copy(fraction = newFraction)
                                }
                                null -> Unit
                            }
                        },
                    )
                },
        )

        // 双击快进/快退浮动反馈
        LaunchedEffect(doubleTapFeedback) {
            if (doubleTapFeedback != null) {
                delay(650)
                doubleTapFeedback = null
            }
        }
        doubleTapFeedback?.let { (isRight, text) ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp),
                contentAlignment = if (isRight) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isRight) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = text, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // 浮动手势 HUD (音量 / 亮度 / Seek 滑动进度条)
        activeGesture?.let { gesture ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier.padding(16.dp),
                ) {
                    when (gesture) {
                        is PlayerGesture.Adjust -> {
                            Row(
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (gesture.kind == VerticalAdjust.Brightness) {
                                        Icons.Filled.BrightnessMedium
                                    } else if (gesture.fraction <= 0.02f) {
                                        Icons.Filled.VolumeMute
                                    } else {
                                        Icons.Filled.VolumeUp
                                    },
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp),
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.width(130.dp)) {
                                    Text(
                                        text = "${(gesture.fraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { gesture.fraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.White.copy(alpha = 0.25f),
                                    )
                                }
                            }
                        }
                        is PlayerGesture.Seek -> {
                            val target = (gesture.startPositionMillis + gesture.deltaMillis)
                                .coerceIn(0L, totalDuration.coerceAtLeast(1L))
                            val deltaSec = (gesture.deltaMillis / 1000).toInt()
                            val prefix = if (deltaSec >= 0) "+$deltaSec" else "$deltaSec"

                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "${formatTime(target)} / ${formatTime(totalDuration)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "[$prefix 秒]",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (deltaSec >= 0) LocalFixedColors.current.InstantMatchGreen else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 长按 2.0x 极速播放胶囊提示
        AnimatedVisibility(
            visible = isLongPressingSpeed,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isLandscape) 24.dp else 52.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.75f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "2.0X 极速播放中",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 恢复播放位置提示胶囊（对齐 Animeko 与现代播放器体验）
        AnimatedVisibility(
            visible = showResumeTip,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (isLandscape) 40.dp else 20.dp,
                    bottom = if (controlsVisible) (if (isLandscape) 100.dp else 120.dp) else (if (isLandscape) 28.dp else 36.dp),
                ),
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已为您恢复至上次进度 ${formatTime(resumedPosition)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "从头播放",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            exoPlayer.seekTo(0L)
                            showResumeTip = false
                        },
                    )
                }
            }
        }

        if (isLoading) {
            FullScreenLoading()
        }

        // 屏幕锁手势控制（浮动于左边缘）
        AnimatedVisibility(
            visible = controlsVisible || isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (isLandscape) 36.dp else 16.dp),
        ) {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isLocked = !isLocked
                    if (isLocked) controlsVisible = false
                },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = "Lock controls",
                        tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // 核心控制层（Animeko 风格渐变蒙层，未呼出时 100% 纯净）
        AnimatedVisibility(
            visible = controlsVisible && !isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 顶部控制栏 (带柔和渐变)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .padding(
                            horizontal = if (isLandscape) 36.dp else 16.dp,
                            vertical = if (isLandscape) 12.dp else 28.dp,
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            orientationController.resetOrientation()
                            onBackClick()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // 画面比例选择
                        Box {
                            IconButton(onClick = { showResizeMenu = true }) {
                                Icon(
                                    imageVector = Icons.Filled.AspectRatio,
                                    contentDescription = "Aspect Ratio",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showResizeMenu,
                                onDismissRequest = { showResizeMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("适应屏幕 (Fit)") },
                                    onClick = {
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        showResizeMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("裁剪填充 (Zoom)") },
                                    onClick = {
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        showResizeMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("拉伸全屏 (Fill)") },
                                    onClick = {
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        showResizeMenu = false
                                    },
                                )
                            }
                        }

                        // 清晰度选择
                        mediaInfo?.let { info ->
                            Box {
                                IconButton(onClick = { showQualityMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.HighQuality,
                                        contentDescription = "Quality",
                                        tint = Color.White,
                                    )
                                }
                                DropdownMenu(
                                    expanded = showQualityMenu,
                                    onDismissRequest = { showQualityMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("原画 (Original)") },
                                        onClick = {
                                            showQualityMenu = false
                                            prepareVideo("Original")
                                        },
                                    )
                                    info.availableVariants.forEach { variant ->
                                        DropdownMenuItem(
                                            text = { Text(variant.mediaName) },
                                            onClick = {
                                                showQualityMenu = false
                                                prepareVideo(variant.mediaName)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // 倍速调节对话框入口
                        IconButton(onClick = { showSpeedDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = "Speed",
                                tint = Color.White,
                            )
                        }
                    }
                }

                // 底部控制栏 (带柔和渐变与双轨进度条)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Black.copy(alpha = 0.9f),
                                ),
                            ),
                        )
                        .padding(
                            horizontal = if (isLandscape) 36.dp else 20.dp,
                            vertical = if (isLandscape) 14.dp else 20.dp,
                        ),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 进度条上方工具项：播放/暂停、快进快退、时间、全屏切换
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0L))
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Replay10,
                                        contentDescription = "Rewind 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(totalDuration))
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Forward10,
                                        contentDescription = "Forward 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Text(
                                    text = formatTime(currentPosition),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = " / ${formatTime(totalDuration)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 倍速调节入口胶囊 (带 .01 实时回显，点击呼出 0.5~3.5 无步进调节器)
                                Surface(
                                    onClick = { showSpeedDialog = true },
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.18f),
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.2fx", playbackSpeed),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        orientationController.toggleOrientation(isLandscape)
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                        contentDescription = "Toggle Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Animeko 风格双轨缓存与播放进度条 (Dual-Track Scrubber)
                        VideoDualTrackScrubber(
                            currentPosition = currentPosition,
                            bufferedPosition = bufferedPosition,
                            totalDuration = totalDuration,
                            onSeek = { target ->
                                exoPlayer.seekTo(target)
                            },
                        )
                    }
                }
            }
        }

        // 0.5 ~ 3.5 无步进 Slider (.01 精度) 播放倍速调节浮层
        if (showSpeedDialog) {
            Dialog(
                onDismissRequest = { showSpeedDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (isLandscape) 0.52f else 0.88f)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1C1B1F).copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(22.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "播放倍速",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(
                                onClick = {
                                    playbackSpeed = 1.0f
                                    exoPlayer.setPlaybackSpeed(1.0f)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "重置 1.0x",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 大数字实时回显 (.01 精度)
                        Text(
                            text = String.format(Locale.US, "%.2fx", playbackSpeed),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 0.5 ~ 3.5 无步进 Slider (.01 精度)
                        Slider(
                            value = playbackSpeed,
                            onValueChange = { raw ->
                                val rounded = round(raw * 100f) / 100f
                                playbackSpeed = rounded
                                exoPlayer.setPlaybackSpeed(rounded)
                            },
                            valueRange = 0.5f..3.5f,
                            steps = 0,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("0.5x", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                            Text("2.0x", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                            Text("3.5x", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 常用倍速快捷选择胶囊
                        val presetSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            presetSpeeds.forEach { speed ->
                                val isSelected = abs(playbackSpeed - speed) < 0.005f
                                Surface(
                                    onClick = {
                                        playbackSpeed = speed
                                        exoPlayer.setPlaybackSpeed(speed)
                                    },
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "${speed}x",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { showSpeedDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                        ) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 具有已缓冲 (Buffered) 轨道与已播放 (Played) 轨道的精致 Scrubber
 */
@Composable
private fun VideoDualTrackScrubber(
    currentPosition: Long,
    bufferedPosition: Long,
    totalDuration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    val activePos = if (isDragging) dragPosition else currentPosition
    val playedFraction = if (totalDuration > 0) (activePos.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (totalDuration > 0) (bufferedPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 底层自定义绘制轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            // 缓冲轨道 (半透白)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction)
                    .background(Color.White.copy(alpha = 0.45f)),
            )
            // 播放进度轨道 (主题色高亮)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(playedFraction)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        // 顶层透明手势 Slider，提供标准的无缝拖拽手感
        Slider(
            value = playedFraction,
            onValueChange = { frac ->
                isDragging = true
                dragPosition = (frac * totalDuration).toLong()
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragPosition)
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
