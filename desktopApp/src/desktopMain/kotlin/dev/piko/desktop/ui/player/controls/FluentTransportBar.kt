@file:OptIn(ExperimentalFoundationApi::class, ExperimentalFluentApi::class)

package dev.piko.desktop.ui.player.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import io.github.composefluent.ExperimentalFluentApi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.BasicSlider
import io.github.composefluent.component.FlyoutPlacement
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ListItemSelectionType
import io.github.composefluent.component.MenuFlyoutContainer
import io.github.composefluent.component.MenuFlyoutItem
import io.github.composefluent.component.Slider
import io.github.composefluent.component.SliderDefaults
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TooltipBox
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Pause
import io.github.composefluent.icons.filled.Play
import io.github.composefluent.icons.regular.FullScreenMaximize
import io.github.composefluent.icons.regular.FullScreenMinimize
import io.github.composefluent.icons.regular.Gauge
import io.github.composefluent.icons.regular.Hd
import io.github.composefluent.icons.regular.List
import io.github.composefluent.icons.regular.ScaleFit
import io.github.composefluent.icons.regular.SkipBack10
import io.github.composefluent.icons.regular.SkipForward10
import io.github.composefluent.icons.regular.Speaker0
import io.github.composefluent.icons.regular.Speaker1
import io.github.composefluent.icons.regular.SpeakerMute
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 底部传输控制栏，布局取 Windows 媒体传输控件（MediaTransportControls）的双行形态：
 * 上行是时间与进度条，下行左侧音量、中间播放三键、右侧倍速/清晰度/比例/列表/全屏。
 *
 * 容器是浮在画面上的 Layer：Fluent 的大尺寸容器用 8px 圆角，底色取 acrylic 的色值
 * 而不做实时模糊，视频每帧都在变，模糊背景的开销换不来可读性。
 */
@Composable
internal fun FluentTransportBar(
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
    onFlyoutOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubMillis by remember { mutableStateOf<Long?>(null) }

    Layer(
        modifier = modifier.widthIn(max = TRANSPORT_BAR_MAX_WIDTH),
        shape = RoundedCornerShape(8.dp),
        color = FluentTheme.colors.background.acrylic.default,
        border = androidx.compose.foundation.BorderStroke(1.dp, FluentTheme.colors.stroke.surface.flyout),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(formatDuration(scrubMillis ?: positionMillis))
                FluentSeekBar(
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

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(48.dp)) {
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
                        icon = Icons.Regular.SkipBack10,
                        tooltip = "后退 ${SEEK_STEP_MILLIS / 1000} 秒 (←)",
                        onClick = { onSeekBy(-SEEK_STEP_MILLIS) },
                    )
                    TooltipBox(tooltip = { Text(if (isPlaying) "暂停 (空格)" else "播放 (空格)") }) {
                        AccentButton(
                            onClick = onPlayPause,
                            iconOnly = true,
                            modifier = Modifier.size(PLAY_BUTTON_SIZE),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.Play,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    ControlButton(
                        icon = Icons.Regular.SkipForward10,
                        tooltip = "前进 ${SEEK_STEP_MILLIS / 1000} 秒 (→)",
                        onClick = { onSeekBy(SEEK_STEP_MILLIS) },
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playbackSpeed != null) {
                        SelectionFlyoutButton(
                            icon = Icons.Regular.Gauge,
                            tooltip = "播放速度",
                            label = formatSpeed(playbackSpeed),
                            options = SPEED_PRESETS,
                            isSelected = { abs(it - playbackSpeed) < SPEED_MATCH_TOLERANCE },
                            optionLabel = ::formatSpeed,
                            onSelect = onSpeedChange,
                            onOpenChange = onFlyoutOpenChange,
                        )
                    }
                    if (qualityOptions.isNotEmpty()) {
                        SelectionFlyoutButton(
                            icon = Icons.Regular.Hd,
                            tooltip = "清晰度",
                            label = null,
                            options = qualityOptions,
                            isSelected = { it == currentQuality },
                            optionLabel = { it },
                            onSelect = onQualityChange,
                            onOpenChange = onFlyoutOpenChange,
                        )
                    }
                    if (aspectRatio != null) {
                        SelectionFlyoutButton(
                            icon = Icons.Regular.ScaleFit,
                            tooltip = "画面比例",
                            label = null,
                            options = PlayerAspectRatio.entries,
                            isSelected = { it == aspectRatio },
                            optionLabel = { it.label },
                            onSelect = onAspectRatioChange,
                            onOpenChange = onFlyoutOpenChange,
                        )
                    }
                    if (showPlaylistEntry) {
                        ControlButton(icon = Icons.Regular.List, tooltip = "同目录视频", onClick = onPlaylistClick)
                    }
                    ControlButton(
                        icon = if (isFullscreen) Icons.Regular.FullScreenMinimize else Icons.Regular.FullScreenMaximize,
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
        style = FluentTheme.typography.caption,
        color = FluentTheme.colors.text.text.secondary,
        modifier = Modifier.widthIn(min = 40.dp),
    )
}

/**
 * 进度条：Fluent 滑块的几何（4px 胶囊轨道、20px 带描边的圆形拇指）加一段缓冲轨，
 * 鼠标悬停时在指针处预览时间。
 *
 * 拖动期间只预览，松手才 seek：网络流每次 seek 都要重开 range 请求。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FluentSeekBar(
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
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        // BasicSlider 两端各留出半个带描边的拇指，指针位置换算成进度时要扣掉
        val insetPx = with(density) { (THUMB_SIZE_WITH_BORDER / 2).toPx() }

        BasicSlider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                currentOnScrub((it * durationMillis).toLong())
            },
            onValueChangeFinished = { finished ->
                val millis = (finished * durationMillis).toLong()
                pendingSeekMillis = millis
                onSeek(millis)
                dragFraction = null
                currentOnScrub(null)
            },
            enabled = enabled,
            rail = {
                // rail 槽位按滑块整体高度给约束，4px 的轨道要自己在里面居中
                Box(Modifier.fillMaxWidth().height(SEEK_BAR_HEIGHT), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SEEK_TRACK_HEIGHT)
                            .clip(RoundedCornerShape(50))
                            .background(FluentTheme.colors.controlStrong.default.copy(alpha = RAIL_ALPHA)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(bufferedFraction)
                                .background(FluentTheme.colors.text.text.tertiary),
                        )
                    }
                }
            },
            track = { state ->
                SliderDefaults.Track(state = state)
            },
            thumb = { state -> SliderDefaults.Thumb(state = state) },
            modifier = Modifier.fillMaxWidth(),
        )

        val previewX = dragFraction?.let { insetPx + it * (widthPx - 2 * insetPx) } ?: hoverX
        if (previewX != null && enabled) {
            val previewFraction = ((previewX - insetPx) / (widthPx - 2 * insetPx)).coerceIn(0f, 1f)
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
    Layer(
        shape = RoundedCornerShape(4.dp),
        color = FluentTheme.colors.background.acrylic.default,
        border = androidx.compose.foundation.BorderStroke(1.dp, FluentTheme.colors.stroke.surface.flyout),
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
            val x = (centerX - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
            layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
        },
    ) {
        Text(
            text = text,
            style = FluentTheme.typography.caption,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 音量：静音切换按钮加水平滑块。
 *
 * 图标只分无声与有声两档：fluent-icons v0.1.0 的 Speaker2（regular 与 filled 都是）
 * 路径数据有误，渲染出来是一道折线，不能用。
 */
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
            isMuted -> Icons.Regular.SpeakerMute
            volume <= 0f -> Icons.Regular.Speaker0
            else -> Icons.Regular.Speaker1
        },
        tooltip = if (isMuted) "取消静音 (M)" else "静音 (M)",
        onClick = onToggleMute,
    )
    if (!showSlider) return
    Slider(
        value = shown,
        onValueChange = onVolumeChange,
        tooltipContent = { Text("音量 ${(it.value * 100).roundToInt()}") },
        modifier = Modifier
            .width(VOLUME_SLIDER_WIDTH)
            .semantics { contentDescription = "音量" },
    )
}

/**
 * 图标按钮加悬停提示。Fluent 工具栏的纯图标按钮必须用提示给出文字标签，
 * 快捷键写在提示括号里，这是它唯一能被发现的地方。
 */
@Composable
internal fun ControlButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    TooltipBox(tooltip = { Text(tooltip) }, modifier = modifier) {
        SubtleButton(onClick = onClick, iconOnly = label == null) {
            Icon(icon, contentDescription = tooltip, modifier = Modifier.size(ICON_SIZE))
            if (label != null) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = FluentTheme.typography.body)
            }
        }
    }
}

/**
 * 单选菜单入口。选项用 Radio 形态的菜单项，选中项有指示点，不在文字后面拼符号。
 *
 * 菜单开合经 DisposableEffect 回报给外层：flyout 内容只在打开时存在，
 * 外层据此在菜单打开期间暂停控件栏的自动隐藏，否则菜单会随控件栏一起消失。
 */
@Composable
private fun <T> SelectionFlyoutButton(
    icon: ImageVector,
    tooltip: String,
    label: String?,
    options: List<T>,
    isSelected: (T) -> Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    MenuFlyoutContainer(
        flyout = {
            DisposableEffect(Unit) {
                currentOnOpenChange(true)
                onDispose { currentOnOpenChange(false) }
            }
            options.forEach { option ->
                MenuFlyoutItem(
                    selected = isSelected(option),
                    onSelectedChanged = {
                        isFlyoutVisible = false
                        onSelect(option)
                    },
                    text = { Text(optionLabel(option)) },
                    selectionType = ListItemSelectionType.Radio,
                )
            }
        },
        placement = FlyoutPlacement.TopAlignedEnd,
        adaptivePlacement = true,
        content = {
            ControlButton(
                icon = icon,
                tooltip = tooltip,
                label = label,
                onClick = { isFlyoutVisible = !isFlyoutVisible },
            )
        },
    )
}

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

internal const val SEEK_STEP_MILLIS = 10_000L
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L
private const val SPEED_MATCH_TOLERANCE = 0.005f
private const val RAIL_ALPHA = 0.5f
private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

// Fluent 滑块几何：拇指 20px 加 1px 描边（compose-fluent SliderKt 的 ThumbSizeWithBorder），轨道 4px
private val THUMB_SIZE_WITH_BORDER = 22.dp
private val SEEK_TRACK_HEIGHT = 4.dp
private val SEEK_BAR_HEIGHT = 32.dp
private val ICON_SIZE = 20.dp
private val PLAY_BUTTON_SIZE = 40.dp
private val VOLUME_SLIDER_WIDTH = 96.dp
private val TRANSPORT_BAR_MAX_WIDTH = 960.dp
private val COMPACT_BAR_WIDTH = 680.dp
