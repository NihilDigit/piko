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
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PikoSeekableMediaData
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.Maximize
import io.github.composefluent.icons.regular.Pause
import io.github.composefluent.icons.regular.Play
import io.github.composefluent.icons.regular.Speaker2
import io.github.composefluent.icons.regular.SpeakerMute
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.isLoadingOrBuffering
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

/**
 * 独立的 Fluent 风格视频播放窗口，支持触控手势（双击播放/暂停、快进/快退，左右滑动调音量/亮度指示，上下滑动调音量）
 * 与完整的 Fluent 触控控制栏。
 */
@Composable
@OptIn(ExperimentalMediampApi::class)
fun VideoPlayerWindow(
    file: FileStat,
    mediaRepository: PikoMediaRepository,
    themeColors: io.github.composefluent.Colors,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(width = 1000.dp, height = 620.dp)

    Window(
        onCloseRequest = onClose,
        title = "${file.name} - Piko 播放器",
        state = windowState,
    ) {
        FluentTheme(colors = themeColors) {
            VideoPlayerContent(
                file = file,
                mediaRepository = mediaRepository,
                windowState = windowState,
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
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    val playerState by player.state.collectAsState()

    var mediaData by remember { mutableStateOf<PikoSeekableMediaData?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPreparing by remember { mutableStateOf(true) }

    // 控件显示与自动隐藏逻辑 (触控或鼠标静止 3.5 秒后自动隐藏)
    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 触控手势提示指示器 (如: 音量、快进快退反馈)
    var touchFeedbackText by remember { mutableStateOf<String?>(null) }
    var touchFeedbackIcon by remember { mutableStateOf<String?>(null) }

    // 视频音量 (0f .. 1f)
    var volumeFraction by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

    // WinRT 屏幕常亮
    DisposableEffect(mediaData, playerState.playWhenReady) {
        val displayLease = if (mediaData != null && playerState.playWhenReady) {
            WinRTSupport.createDisplayRequest()
        } else null

        onDispose {
            displayLease?.close()
        }
    }

    // 资源加载
    LaunchedEffect(file.id) {
        isPreparing = true
        errorMessage = null
        scope.launch {
            mediaRepository.createMediaData(file.id)
                .onSuccess { (_, data) ->
                    mediaData = data
                    player.setMediaData(data, playWhenReady = true)
                    isPreparing = false
                }
                .onFailure { err ->
                    errorMessage = err.message ?: "无法加载视频媒体流"
                    isPreparing = false
                }
        }
    }

    // 控件自动休眠计时器
    LaunchedEffect(areControlsVisible, lastInteractionTime, playerState.playWhenReady) {
        if (areControlsVisible && playerState.playWhenReady) {
            delay(3500)
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
                // 监听鼠标移动或触摸指针唤醒控件
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            areControlsVisible = true
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // 核心触控手势：双击播放/暂停，单触显示/隐藏控制栏
                detectTapGestures(
                    onTap = {
                        areControlsVisible = !areControlsVisible
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDoubleTap = { offset ->
                        // 双击判定：左侧 1/3 快退 10s，右侧 1/3 快进 10s，中间双击 播放/暂停
                        val screenWidth = size.width
                        if (offset.x < screenWidth * 0.33f) {
                            val newPos = (player.currentPositionMillis.value - 10_000L).coerceAtLeast(0L)
                            player.seekTo(newPos)
                            touchFeedbackText = "快退 10 秒"
                            touchFeedbackIcon = "rewind"
                        } else if (offset.x > screenWidth * 0.67f) {
                            val newPos = player.currentPositionMillis.value + 10_000L
                            player.seekTo(newPos)
                            touchFeedbackText = "快进 10 秒"
                            touchFeedbackIcon = "forward"
                        } else {
                            player.togglePlayWhenReady()
                            touchFeedbackText = if (playerState.playWhenReady) "暂停" else "播放"
                            touchFeedbackIcon = if (playerState.playWhenReady) "pause" else "play"
                        }
                        areControlsVisible = true
                        lastInteractionTime = System.currentTimeMillis()
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

        // 错误提示
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
                    Button(onClick = onClose) {
                        Text("关闭窗口")
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

        // 浮动控件 (顶部栏与底部控制栏)
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 顶部标题栏 + 操作
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = file.name,
                        style = FluentTheme.typography.subtitle,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SubtleButton(
                            onClick = onClose,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Regular.Dismiss, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                                Text("关闭")
                            }
                        }
                    }
                }

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
                                val newPos = (player.currentPositionMillis.value - 10_000L).coerceAtLeast(0L)
                                player.seekTo(newPos)
                                touchFeedbackText = "-10 秒"
                                lastInteractionTime = System.currentTimeMillis()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("-10s", color = Color.White, fontSize = 14.sp)
                    }

                    // 居中大触控播放/暂停按钮
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(FluentTheme.colors.fillAccent.default.copy(alpha = 0.85f))
                            .clickable {
                                player.togglePlayWhenReady()
                                lastInteractionTime = System.currentTimeMillis()
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
                                val newPos = player.currentPositionMillis.value + 10_000L
                                player.seekTo(newPos)
                                touchFeedbackText = "+10 秒"
                                lastInteractionTime = System.currentTimeMillis()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+10s", color = Color.White, fontSize = 14.sp)
                    }
                }

                // 底部控制面板
                val currentPos by player.currentPositionMillis.collectAsState()
                val mediaProps by player.mediaProperties.collectAsState()
                val totalDuration = mediaProps?.durationMillis ?: 0L
                var isSeeking by remember { mutableStateOf(false) }
                var seekPosFraction by remember { mutableFloatStateOf(0f) }

                val progressFraction = if (totalDuration > 0L) {
                    (currentPos.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                            ),
                        )
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 进度条 Slider
                    Slider(
                        value = if (isSeeking) seekPosFraction else progressFraction,
                        onValueChange = {
                            isSeeking = true
                            seekPosFraction = it
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onValueChangeFinished = { finalVal ->
                            if (totalDuration > 0L) {
                                val targetMs = (finalVal * totalDuration).toLong()
                                player.seekTo(targetMs)
                            }
                            isSeeking = false
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 时间指示与控制项
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 播放/暂停 + 快进快退按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AccentButton(
                                onClick = {
                                    player.togglePlayWhenReady()
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (playerState.playWhenReady) Icons.Regular.Pause else Icons.Regular.Play,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(if (playerState.playWhenReady) "暂停" else "播放")
                                }
                            }

                            Button(
                                onClick = {
                                    val newPos = (player.currentPositionMillis.value - 10_000L).coerceAtLeast(0L)
                                    player.seekTo(newPos)
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                            ) {
                                Text("-10s")
                            }

                            Button(
                                onClick = {
                                    val newPos = player.currentPositionMillis.value + 10_000L
                                    player.seekTo(newPos)
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                            ) {
                                Text("+10s")
                            }

                            Text(
                                text = "${formatDuration(if (isSeeking) (seekPosFraction * totalDuration).toLong() else currentPos)} / ${formatDuration(totalDuration)}",
                                style = FluentTheme.typography.bodyStrong,
                                color = Color.White,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        // 右侧音量调节与辅助控制
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SubtleButton(
                                onClick = {
                                    isMuted = !isMuted
                                    lastInteractionTime = System.currentTimeMillis()
                                },
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Regular.SpeakerMute else Icons.Regular.Speaker2,
                                    contentDescription = "静音切换",
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Box(modifier = Modifier.width(100.dp)) {
                                Slider(
                                    value = if (isMuted) 0f else volumeFraction,
                                    onValueChange = {
                                        volumeFraction = it
                                        isMuted = false
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
