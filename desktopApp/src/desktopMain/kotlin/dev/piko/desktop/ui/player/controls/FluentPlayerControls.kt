package dev.piko.desktop.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.animation.FluentDuration
import io.github.composefluent.animation.FluentEasing
import io.github.composefluent.background.Layer
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.ErrorCircle
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay

/**
 * Windows 播放器的完整控件层，叠在视频画面之上，外观取 Fluent 2 与 Windows 媒体传输控件。
 *
 * 无状态于播放器：只接收下面这组基础类型的值与回调，不引用任何播放器对象。
 * Android 的 MobilePlayerControls 接收同一组值与回调，两端可由同一个 shared state holder 驱动。
 * 这里自己持有的只有纯界面状态：控件显隐与闲置计时、指针隐藏、菜单开合、拖动中、
 * 键盘与滚轮操作的提示、续播提示计时。
 *
 * 交互约定沿用 Windows 播放器：单击画面播放/暂停，双击切换全屏；鼠标移动唤出控件，
 * 闲置后隐藏控件与指针；滚轮调音量。快捷键：空格播放/暂停，左右方向键后退/前进，
 * 上下方向键调音量，F 切换全屏，Esc 退出全屏，M 静音。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FluentPlayerControls(
    // 播放器数据契约（与 Android 一致）
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
    // 平台附加项
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
    var openFlyouts by remember { mutableIntStateOf(0) }
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

    val holdControls = !isPlaying || pointerOverChrome || openFlyouts > 0 || isScrubbing || errorMessage != null
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
    val chromeEnter = fadeIn(tween(FluentDuration.ShortDuration, easing = FluentEasing.FadeInFadeOutEasing))
    val chromeExit = fadeOut(tween(FluentDuration.ShortDuration, easing = FluentEasing.FadeInFadeOutEasing))

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
                            PointerEventType.Exit -> if (isPlaying && openFlyouts == 0) controlsVisible = false
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
            ProgressRing(size = ProgressRingSize.Large, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = feedbackVisible,
            enter = fadeIn(tween(FluentDuration.QuickDuration, easing = FluentEasing.FadeInFadeOutEasing)),
            exit = fadeOut(tween(FluentDuration.ShortDuration, easing = FluentEasing.FadeInFadeOutEasing)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            FeedbackBadge(feedbackText)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = chromeEnter,
            exit = chromeExit,
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
                InfoBar(
                    title = { Text("从 ${formatDuration(resumedFromMillis)} 继续播放") },
                    message = {},
                    severity = InfoBarSeverity.Informational,
                    action = {
                        Button(onClick = {
                            onRestartFromBeginning()
                            showResumeTip = false
                        }) { Text("从头播放") }
                    },
                    closeAction = { InfoBarDefaults.CloseActionButton(onClick = { showResumeTip = false }) },
                    modifier = Modifier.widthIn(max = 480.dp).align(Alignment.Start).trackHover { pointerOverChrome = it },
                )
            }

            AnimatedVisibility(visible = controlsVisible, enter = chromeEnter, exit = chromeExit) {
                FluentTransportBar(
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
                    onFlyoutOpenChange = { open -> openFlyouts += if (open) 1 else -1 },
                    modifier = Modifier.fillMaxWidth().trackHover { pointerOverChrome = it },
                )
            }
        }

        errorMessage?.let { message ->
            ErrorOverlay(message = message, onRetry = onRetry, onClose = onClose)
        }
    }
}

/**
 * 顶部标题条。窗口模式下系统标题栏还在，关闭键只在全屏时出现。
 */
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
                style = FluentTheme.typography.subtitle,
                // 标题条不在 Layer 里，没有内容色可继承，要显式给出
                color = FluentTheme.colors.text.text.primary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (isLocalPlayback) {
                Text(
                    text = "本地文件",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
        if (showClose) {
            ControlButton(icon = Icons.Regular.Dismiss, tooltip = "关闭", onClick = onClose)
        }
    }
}

/** 键盘与滚轮操作的即时反馈。控件栏可能是收起的，没有它这些操作就没有任何可见结果。 */
@Composable
private fun FeedbackBadge(text: String) {
    Layer(
        shape = RoundedCornerShape(8.dp),
        color = FluentTheme.colors.background.acrylic.default,
        border = androidx.compose.foundation.BorderStroke(1.dp, FluentTheme.colors.stroke.surface.flyout),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = text,
            style = FluentTheme.typography.subtitle,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/**
 * 播放失败：smoke 遮罩压暗画面，中间一张卡片给出原因、重试与关闭。
 * smoke 是 Fluent 表达「下层暂不可操作」的材质，正对这里的语义。
 */
@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background.smoke.default)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Layer(
            shape = RoundedCornerShape(8.dp),
            color = FluentTheme.colors.background.solid.base,
            border = androidx.compose.foundation.BorderStroke(1.dp, FluentTheme.colors.stroke.surface.flyout),
            modifier = Modifier.widthIn(max = 420.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Regular.ErrorCircle,
                        contentDescription = null,
                        tint = FluentTheme.colors.system.critical,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("无法播放", style = FluentTheme.typography.subtitle)
                }
                Spacer(Modifier.height(8.dp))
                Text(message, style = FluentTheme.typography.body, color = FluentTheme.colors.text.text.secondary)
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    AccentButton(onClick = onRetry) { Text("重试") }
                    Button(onClick = onClose) { Text("关闭") }
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

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_500L
private const val FEEDBACK_DURATION_MILLIS = 900L
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val VOLUME_STEP = 0.05f
