package dev.piko.desktop.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.VolumeMute
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.PlayerAspectRatio
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 桌面播放器的控件层，叠在视频画面之上，Material 3 Expressive 外观，由外层包一层深色主题。
 *
 * 无状态于播放器：只接收基础类型的值与回调，数据全部来自 PlayerScreenState，参数与 Android 的
 * MobilePlayerControls 同名。这里自己持有的只有纯界面状态：控件显隐与闲置计时、指针隐藏、
 * 菜单开合、拖动中、键盘与滚轮操作的提示、续播提示计时。
 *
 * 交互沿用桌面播放器的习惯：单击画面播放/暂停，双击切换全屏；鼠标移动唤出控件，
 * 闲置后隐藏控件与指针；滚轮调音量。快捷键：空格播放/暂停，左右方向键后退/前进，
 * 上下方向键调音量，F 切换全屏，Esc 退出全屏，M 静音。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesktopPlayerControls(
    title: String,
    isLocalPlayback: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    aspectRatio: PlayerAspectRatio?,
    qualityOptions: List<String>,
    currentQuality: String?,
    errorMessage: String?,
    resumedFromMillis: Long?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onQualityChange: (String) -> Unit,
    onRetry: () -> Unit,
    onRestartFromBeginning: () -> Unit,
    volume: Float?,
    isMuted: Boolean,
    isFullscreen: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showPlaylistEntry: Boolean = false,
    onPlaylistClick: () -> Unit = {},
) {
    val currentPosition by rememberUpdatedState(positionMillis)
    val currentVolume by rememberUpdatedState(volume)

    var controlsVisible by remember { mutableStateOf(true) }
    // 每次指针移动或操作控件时加一，闲置计时从头开始
    var activityCount by remember { mutableIntStateOf(0) }
    var pointerOverChrome by remember { mutableStateOf(false) }
    var openMenus by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    // 文字与可见性分开存：淡出动画期间文字还要留着
    var feedbackText by remember { mutableStateOf("") }
    var feedbackVisible by remember { mutableStateOf(false) }
    var feedbackCount by remember { mutableIntStateOf(0) }
    var showResumeTip by remember(resumedFromMillis) { mutableStateOf(resumedFromMillis != null) }
    val focusRequester = remember { FocusRequester() }

    fun wake() {
        controlsVisible = true
        activityCount += 1
    }

    fun showFeedback(text: String) {
        feedbackText = text
        feedbackVisible = true
        feedbackCount += 1
    }

    fun seekBy(deltaMillis: Long) {
        val limit = durationMillis.coerceAtLeast(0L)
        onSeek((currentPosition + deltaMillis).coerceIn(0L, limit))
        wake()
    }

    fun changeVolumeBy(delta: Float) {
        val base = currentVolume ?: return
        val next = ((if (isMuted) 0f else base) + delta).coerceIn(0f, 1f)
        if (isMuted && next > 0f) onToggleMute()
        onVolumeChange(next)
        showFeedback("音量 ${(next * 100).toInt()}")
    }

    val holdControls = !isPlaying || pointerOverChrome || openMenus > 0 || isScrubbing || errorMessage != null
    LaunchedEffect(controlsVisible, holdControls, activityCount) {
        if (controlsVisible && !holdControls) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) controlsVisible = true
    }
    LaunchedEffect(feedbackCount) {
        if (feedbackVisible) {
            delay(FEEDBACK_DURATION_MILLIS)
            feedbackVisible = false
        }
    }
    LaunchedEffect(showResumeTip) {
        if (showResumeTip) {
            delay(RESUME_TIP_DURATION_MILLIS)
            showResumeTip = false
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 闲置时连指针一起藏起来，全屏看片时指针停在画面中央很碍眼
    val hideCursor = !controlsVisible && isPlaying

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 按键在根节点先行拦截：焦点落在某个按钮上时，空格不该变成「点一下那个按钮」
                when (event.key) {
                    Key.Spacebar -> onPlayPause()
                    Key.DirectionLeft -> {
                        seekBy(-SEEK_STEP_MILLIS)
                        showFeedback("-${SEEK_STEP_MILLIS / 1000} 秒")
                    }
                    Key.DirectionRight -> {
                        seekBy(SEEK_STEP_MILLIS)
                        showFeedback("+${SEEK_STEP_MILLIS / 1000} 秒")
                    }
                    Key.DirectionUp -> changeVolumeBy(VOLUME_STEP)
                    Key.DirectionDown -> changeVolumeBy(-VOLUME_STEP)
                    Key.F -> onToggleFullscreen()
                    Key.M -> if (volume != null) onToggleMute()
                    Key.Escape -> if (isFullscreen) onToggleFullscreen() else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // Initial 阶段观察，不消费：任何位置的移动都算活动，包括在按钮与菜单之上
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Press -> wake()
                            // 指针离开窗口时立刻收起，不等计时
                            PointerEventType.Exit -> if (isPlaying && openMenus == 0) controlsVisible = false
                        }
                    }
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val scrollY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (scrollY != 0f) changeVolumeBy(if (scrollY < 0f) VOLUME_STEP else -VOLUME_STEP)
            }
            .pointerHoverIcon(if (hideCursor) BlankPointerIcon else PointerIcon.Default),
    ) {
        // 画面点击层在控件之下：控件栏自己有指针输入，点在栏上不会穿透到这里
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onPlayPause() },
                        onDoubleTap = { onToggleFullscreen() },
                    )
                },
        )

        if (isLoading && errorMessage == null) {
            LoadingIndicator(modifier = Modifier.align(Alignment.Center).size(64.dp))
        }

        AnimatedVisibility(
            visible = feedbackVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            FeedbackBadge(feedbackText)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TitleStrip(
                title = title,
                isLocalPlayback = isLocalPlayback,
                showClose = isFullscreen,
                onClose = onClose,
                modifier = Modifier.trackHover { pointerOverChrome = it },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (resumedFromMillis != null && showResumeTip) {
                ResumeTip(
                    resumedFromMillis = resumedFromMillis,
                    onRestart = {
                        onRestartFromBeginning()
                        showResumeTip = false
                    },
                    onDismiss = { showResumeTip = false },
                    modifier = Modifier.align(Alignment.Start).trackHover { pointerOverChrome = it },
                )
            }

            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                TransportBar(
                    isPlaying = isPlaying,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    bufferedPositionMillis = bufferedPositionMillis,
                    playbackSpeed = playbackSpeed,
                    aspectRatio = aspectRatio,
                    qualityOptions = qualityOptions,
                    currentQuality = currentQuality,
                    volume = volume,
                    isMuted = isMuted,
                    isFullscreen = isFullscreen,
                    showPlaylistEntry = showPlaylistEntry,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSeekBy = ::seekBy,
                    onSpeedChange = onSpeedChange,
                    onAspectRatioChange = onAspectRatioChange,
                    onQualityChange = onQualityChange,
                    onVolumeChange = onVolumeChange,
                    onToggleMute = onToggleMute,
                    onToggleFullscreen = onToggleFullscreen,
                    onPlaylistClick = onPlaylistClick,
                    onScrubbingChange = { isScrubbing = it },
                    onMenuOpenChange = { open -> openMenus += if (open) 1 else -1 },
                    modifier = Modifier.fillMaxWidth().trackHover { pointerOverChrome = it },
                )
            }
        }

        errorMessage?.let { message ->
            ErrorOverlay(message = message, onRetry = onRetry, onClose = onClose)
        }
    }
}

/** 顶部标题条。窗口模式下系统标题栏还在，关闭键只在全屏时出现。 */
@Composable
private fun TitleStrip(
    title: String,
    isLocalPlayback: Boolean,
    showClose: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (isLocalPlayback) {
                Text(
                    text = "本地文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        if (showClose) {
            ControlButton(icon = Icons.Outlined.Close, tooltip = "关闭", onClick = onClose)
        }
    }
}

/**
 * 底部传输控制栏，两行：上行是时间与进度条，下行左侧音量、中间播放三键、右侧倍速、
 * 清晰度、比例、列表与全屏。容器取半透明的 surfaceContainer 而不做实时模糊：
 * 视频每帧都在变，模糊背景的开销换不来可读性。
 */
@Composable
private fun TransportBar(
    isPlaying: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    aspectRatio: PlayerAspectRatio?,
    qualityOptions: List<String>,
    currentQuality: String?,
    volume: Float?,
    isMuted: Boolean,
    isFullscreen: Boolean,
    showPlaylistEntry: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onQualityChange: (String) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPlaylistClick: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubMillis by remember { mutableStateOf<Long?>(null) }

    Surface(
        modifier = modifier.widthIn(max = TRANSPORT_BAR_MAX_WIDTH),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(formatDuration(scrubMillis ?: positionMillis))
                SeekBar(
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    bufferedPositionMillis = bufferedPositionMillis,
                    onSeek = onSeek,
                    onScrub = { target ->
                        val wasScrubbing = scrubMillis != null
                        scrubMillis = target
                        if (wasScrubbing != (target != null)) onScrubbingChange(target != null)
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                TimeLabel(formatDuration(durationMillis))
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                // 窄窗口下三组按钮会互相挤压，先收起音量滑块，只留静音键；滚轮与方向键仍可调音量
                val compact = maxWidth < COMPACT_BAR_WIDTH
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (volume != null) {
                        VolumeControl(
                            volume = volume,
                            isMuted = isMuted,
                            showSlider = !compact,
                            onVolumeChange = onVolumeChange,
                            onToggleMute = onToggleMute,
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(
                        icon = Icons.Outlined.Replay10,
                        tooltip = "后退 ${SEEK_STEP_MILLIS / 1000} 秒 (←)",
                        onClick = { onSeekBy(-SEEK_STEP_MILLIS) },
                    )
                    WithTooltip(if (isPlaying) "暂停 (空格)" else "播放 (空格)") {
                        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                            )
                        }
                    }
                    ControlButton(
                        icon = Icons.Outlined.Forward10,
                        tooltip = "前进 ${SEEK_STEP_MILLIS / 1000} 秒 (→)",
                        onClick = { onSeekBy(SEEK_STEP_MILLIS) },
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playbackSpeed != null) {
                        SelectionMenuButton(
                            icon = Icons.Outlined.Speed,
                            tooltip = "播放速度",
                            label = formatSpeed(playbackSpeed),
                            options = SPEED_PRESETS,
                            isSelected = { abs(it - playbackSpeed) < SPEED_MATCH_TOLERANCE },
                            optionLabel = ::formatSpeed,
                            onSelect = onSpeedChange,
                            onOpenChange = onMenuOpenChange,
                        )
                    }
                    if (qualityOptions.isNotEmpty()) {
                        SelectionMenuButton(
                            icon = Icons.Outlined.HighQuality,
                            tooltip = "清晰度",
                            label = null,
                            options = qualityOptions,
                            isSelected = { it == currentQuality },
                            optionLabel = { it },
                            onSelect = onQualityChange,
                            onOpenChange = onMenuOpenChange,
                        )
                    }
                    if (aspectRatio != null) {
                        SelectionMenuButton(
                            icon = Icons.Outlined.AspectRatio,
                            tooltip = "画面比例",
                            label = null,
                            options = PlayerAspectRatio.entries,
                            isSelected = { it == aspectRatio },
                            optionLabel = { it.label },
                            onSelect = onAspectRatioChange,
                            onOpenChange = onMenuOpenChange,
                        )
                    }
                    if (showPlaylistEntry) {
                        ControlButton(
                            icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            tooltip = "同目录视频",
                            onClick = onPlaylistClick,
                        )
                    }
                    ControlButton(
                        icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        tooltip = if (isFullscreen) "退出全屏 (F)" else "全屏 (F)",
                        onClick = onToggleFullscreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

/**
 * 进度条：M3 滑块加一段缓冲轨，鼠标悬停时在指针处预览时间。
 * 拖动期间只预览，松手才 seek：网络流每次 seek 都要重开 range 请求。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // 松手到新位置回报之间，拇指停在目标处，不回跳到旧位置
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val currentOnScrub by rememberUpdatedState(onScrub)

    LaunchedEffect(pendingSeekMillis, positionMillis) {
        val pending = pendingSeekMillis ?: return@LaunchedEffect
        if (abs(positionMillis - pending) < SEEK_SETTLE_TOLERANCE_MILLIS) {
            pendingSeekMillis = null
        } else {
            delay(SEEK_SETTLE_TIMEOUT_MILLIS)
            pendingSeekMillis = null
        }
    }

    val enabled = durationMillis > 0
    fun fractionOf(millis: Long): Float =
        if (enabled) (millis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

    val fraction = dragFraction ?: fractionOf(pendingSeekMillis ?: positionMillis)
    val bufferedFraction = fractionOf(bufferedPositionMillis)
    val positionText = formatDuration((fraction * durationMillis).toLong())
    val durationText = formatDuration(durationMillis)
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier = modifier
            .height(SEEK_BAR_HEIGHT)
            .onPointerEvent(PointerEventType.Move) { event -> hoverX = event.changes.first().position.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .semantics {
                contentDescription = "播放进度"
                stateDescription = "$positionText / $durationText"
            },
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        // 缓冲轨画在滑块之下，滑块自己的未播放段设为透明，露出它
        Box(
            Modifier
                .fillMaxWidth()
                .height(SEEK_TRACK_HEIGHT)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(colors.onSurface.copy(alpha = 0.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(bufferedFraction)
                    .height(SEEK_TRACK_HEIGHT)
                    .background(colors.onSurface.copy(alpha = 0.35f)),
            )
        }
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                currentOnScrub((it * durationMillis).toLong())
            },
            onValueChangeFinished = {
                val millis = ((dragFraction ?: fraction) * durationMillis).toLong()
                pendingSeekMillis = millis
                onSeek(millis)
                dragFraction = null
                currentOnScrub(null)
            },
            enabled = enabled,
            colors = androidx.compose.material3.SliderDefaults.colors(
                inactiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        val previewX = dragFraction?.let { it * widthPx } ?: hoverX
        if (previewX != null && enabled) {
            val previewFraction = (previewX / widthPx).coerceIn(0f, 1f)
            SeekPreviewLabel(
                text = formatDuration((previewFraction * durationMillis).toLong()),
                centerX = previewX,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** 进度条上方的时间预览。零尺寸布局，贴在指针正上方，不占进度条的高度。 */
@Composable
private fun SeekPreviewLabel(text: String, centerX: Float, modifier: Modifier = Modifier) {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
            val x = (centerX - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
            layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun VolumeControl(
    volume: Float,
    isMuted: Boolean,
    showSlider: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val shown = if (isMuted) 0f else volume
    ControlButton(
        icon = when {
            isMuted -> Icons.AutoMirrored.Outlined.VolumeOff
            volume <= 0f -> Icons.AutoMirrored.Outlined.VolumeMute
            else -> Icons.AutoMirrored.Outlined.VolumeUp
        },
        tooltip = if (isMuted) "取消静音 (M)" else "静音 (M)",
        onClick = onToggleMute,
    )
    if (!showSlider) return
    Slider(
        value = shown,
        onValueChange = onVolumeChange,
        modifier = Modifier
            .width(VOLUME_SLIDER_WIDTH)
            .semantics { contentDescription = "音量" },
    )
}

/** 悬停提示。纯图标按钮靠它给出文字标签，快捷键写在括号里，这是它唯一能被发现的地方。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    label: String? = null,
) {
    WithTooltip(tooltip) {
        if (label == null) {
            IconButton(onClick = onClick) { Icon(icon, contentDescription = tooltip) }
        } else {
            TextButton(onClick = onClick) {
                Icon(icon, contentDescription = tooltip, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label)
            }
        }
    }
}

/**
 * 单选菜单入口，选中项前面打勾。菜单开合回报给外层：外层据此在菜单打开期间暂停
 * 控件栏的自动隐藏，否则菜单会随控件栏一起消失。
 */
@Composable
private fun <T> SelectionMenuButton(
    icon: ImageVector,
    tooltip: String,
    label: String?,
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    if (expanded) {
        DisposableEffect(Unit) {
            currentOnOpenChange(true)
            onDispose { currentOnOpenChange(false) }
        }
    }
    Box {
        ControlButton(icon = icon, tooltip = tooltip, label = label, onClick = { expanded = !expanded })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val selected = isSelected(option)
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Outlined.Check, contentDescription = "已选中")
                        else Spacer(Modifier.size(24.dp))
                    },
                )
            }
        }
    }
}

/** 键盘与滚轮操作的即时反馈。控件栏可能是收起的，没有它这些操作就没有任何可见结果。 */
@Composable
private fun FeedbackBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ResumeTip(
    resumedFromMillis: Long,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.widthIn(max = 480.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("从 ${formatDuration(resumedFromMillis)} 继续播放", modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRestart) {
                Text("从头播放", color = MaterialTheme.colorScheme.inversePrimary)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭提示")
            }
        }
    }
}

/** 播放失败：遮罩压暗画面，中间一张卡片给出原因、重试与关闭。 */
@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("无法播放", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onClose) { Text("关闭") }
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

/**
 * 记录指针是否停在控件上，并让该区域成为命中目标：没有指针输入的容器不参与命中，
 * 点在控件栏空白处会穿透到下层画面，触发播放/暂停。
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.trackHover(onHoverChange: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
    .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) }
    .pointerInput(Unit) { detectTapGestures { } }

private val BlankPointerIcon = PointerIcon(
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "piko-blank",
    ),
)

internal val PlayerAspectRatio.label: String
    get() = when (this) {
        PlayerAspectRatio.Fit -> "适应窗口"
        PlayerAspectRatio.Crop -> "裁剪填充"
        PlayerAspectRatio.Stretch -> "拉伸填满"
    }

internal fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

// 按两位小数取整后去掉末尾的 0：1.00 显示为 1x，1.50 显示为 1.5x
internal fun formatSpeed(speed: Float): String =
    BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "x"

private const val SEEK_STEP_MILLIS = 10_000L
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L
private const val SPEED_MATCH_TOLERANCE = 0.005f
private const val CONTROLS_HIDE_DELAY_MILLIS = 3_500L
private const val FEEDBACK_DURATION_MILLIS = 900L
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val VOLUME_STEP = 0.05f
private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val SEEK_TRACK_HEIGHT = 4.dp
private val SEEK_BAR_HEIGHT = 40.dp
private val PLAY_BUTTON_SIZE = 48.dp
private val VOLUME_SLIDER_WIDTH = 112.dp
private val TRANSPORT_BAR_MAX_WIDTH = 960.dp
private val COMPACT_BAR_WIDTH = 680.dp
