package dev.piko.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.AUTO_QUALITY
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.ui.format.formatDurationMillis
import dev.piko.ui.format.formatPlaybackSpeed
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_maximize
import dev.piko.ui.resources.player_minimize
import dev.piko.ui.resources.player_restore
import dev.piko.ui.resources.text_local_file
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * 桌面独立播放窗口交给顶栏的窗口控制动作：最小化、最大化/还原、置顶与关闭。
 * Windows 无边框窗口显示整组控制；macOS / Linux 复用系统窗口键，仅保留置顶并去掉重复返回键。
 */
@Immutable
data class PlayerWindowActions(
    val isMaximized: Boolean = false,
    val isFullscreen: Boolean = false,
    val isAlwaysOnTop: Boolean = false,
    val showWindowButtons: Boolean = true,
    val topChromePadding: Dp = 0.dp,
    val onMinimize: (() -> Unit)? = null,
    val onToggleMaximize: (() -> Unit)? = null,
    val onToggleAlwaysOnTop: (() -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
)

@Composable
internal fun MediaWindowControlsCluster(
    windowActions: PlayerWindowActions?,
    effectivePin: (() -> Unit)? = windowActions?.onToggleAlwaysOnTop,
    effectiveOnTop: Boolean = windowActions?.isAlwaysOnTop == true,
    showWindowButtons: Boolean = windowActions?.showWindowButtons == true,
    effectiveClose: (() -> Unit)? = windowActions?.onClose,
    closeLabel: String = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
    closeShortcut: String? = LocalPikoPlatform.current.shortcutModifier.label("W"),
) {
    PlayerPillCluster {
        if (effectivePin != null) {
            ClusterIconButton(
                icon = if (effectiveOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (effectiveOnTop) tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂") else tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂"),
                onClick = effectivePin,
                tooltipBelow = true,
                shortcut = "T",
                tint = if (effectiveOnTop) MaterialTheme.colorScheme.primary else LocalFixedColors.current.OnMedia,
            )
        }
        if (showWindowButtons) {
            windowActions?.onMinimize?.let { onMinimize ->
                ClusterIconButton(
                    icon = Icons.Filled.Remove,
                    label = stringResource(Res.string.player_minimize),
                    onClick = onMinimize,
                    tooltipBelow = true,
                    iconSize = 18.dp,
                )
            }
            windowActions?.onToggleMaximize?.let { onToggleMaximize ->
                val restored = windowActions.isMaximized || windowActions.isFullscreen
                ClusterIconButton(
                    icon = if (restored) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                    label = stringResource(if (restored) Res.string.player_restore else Res.string.player_maximize),
                    onClick = onToggleMaximize,
                    tooltipBelow = true,
                    iconSize = 16.dp,
                )
            }
            if (effectiveClose != null) {
                PlayerCloseWindowButton(
                    label = closeLabel,
                    shortcut = closeShortcut,
                    onClick = effectiveClose,
                )
            }
        }
    }
}

/** 浮在视频画面上的分组半透明高光描边胶囊：在纯白或亮画面上仍保持清晰的边缘对比度。 */
@Composable
internal fun PlayerPillCluster(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = playerContainerColor(),
        contentColor = LocalFixedColors.current.OnMedia,
        border = BorderStroke(1.dp, LocalFixedColors.current.HudSpecularBorder),
        modifier = modifier.captionExclusion(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ClusterIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    tint: Color = LocalFixedColors.current.OnMedia,
    tooltipBelow: Boolean = false,
    shortcut: String? = null,
    suppressTooltip: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(
        targetValue = if (pressed && enabled) 10.dp else 18.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "cluster-btn-corner",
    )
    val bg by animateColorAsState(
        targetValue = if ((hovered || pressed) && enabled) Color.White.copy(alpha = if (pressed) 0.22f else 0.14f) else Color.Transparent,
        animationSpec = motion.fastEffectsSpec(),
        label = "cluster-btn-bg",
    )
    val shape = RoundedCornerShape(corner)
    val buttonContent: @Composable () -> Unit = {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(bg)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = label,
                    onClick = onClick,
                )
                .handCursor(enabled),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else tint.copy(alpha = DISABLED_CONTENT_ALPHA),
                modifier = Modifier.size(iconSize),
            )
        }
    }
    if (suppressTooltip) {
        Box(modifier = modifier) { buttonContent() }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
            ),
            tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
            state = rememberTooltipState(),
            modifier = modifier,
        ) {
            buttonContent()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerCloseWindowButton(
    label: String,
    shortcut: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(
        targetValue = if (pressed) 10.dp else 18.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "close-btn-corner",
    )
    val bg by animateColorAsState(
        targetValue = if (hovered || pressed) PlayerCloseHoverRed else Color.Transparent,
        animationSpec = motion.fastEffectsSpec(),
        label = "close-btn-bg",
    )
    val shape = RoundedCornerShape(corner)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(bg)
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = label,
                    onClick = onClick,
                )
                .handCursor(),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = label,
                tint = if (hovered || pressed) Color.White else LocalFixedColors.current.OnMedia,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun LocalPlaybackBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = stringResource(Res.string.text_local_file),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun PlayerTimeReadout(
    positionMillisProvider: () -> Long,
    scrubPositionMillis: Long?,
    previewPositionMillis: Long?,
    durationMillis: Long,
) {
    val latestPositionProvider by rememberUpdatedState(positionMillisProvider)
    val latestScrub by rememberUpdatedState(scrubPositionMillis)
    val latestPreview by rememberUpdatedState(previewPositionMillis)
    val displayedSeconds by remember {
        derivedStateOf {
            val millis = latestScrub ?: latestPreview ?: latestPositionProvider()
            millis.coerceAtLeast(0L) / 1_000L
        }
    }
    val onMedia = LocalFixedColors.current.OnMedia
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(displayedSeconds * 1_000L),
            style = TimeTextStyle().copy(shadow = PlayerTextShadow),
            color = onMedia,
            maxLines = 1,
        )
        Text(
            text = " / ${formatTime(durationMillis)}",
            style = TimeTextStyle().copy(shadow = PlayerTextShadow),
            color = onMedia.copy(alpha = 0.72f),
            maxLines = 1,
        )
    }
}

/**
 * 桌面底栏的音量：静音键加一条常驻的可展开横向滑块，键与滑块上的滚轮都调音量（±5%）。
 * 常驻挂载保证桌面触屏与键盘 Tab 可达，同时悬停或拖动时平滑展开提供更精细的调节行程。
 */
@Composable
internal fun PlayerVolumeControl(
    volume: Float,
    showSlider: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    showBackground: Boolean = true,
    tooltipBelow: Boolean = false,
    suppressTooltip: Boolean = false,
) {
    val currentVolume by rememberUpdatedState(volume)
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val muted = volume <= 0f
    val containerHover = remember { MutableInteractionSource() }
    val containerHovered by containerHover.collectIsHoveredAsState()
    var sliderDragging by remember { mutableStateOf(false) }
    val expanded = containerHovered || sliderDragging

    val scrollModifier = Modifier
        .hoverable(containerHover)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val dy = change.scrollDelta.y
                    if (dy == 0f) continue
                    val step = if (dy < 0f) VOLUME_KEY_STEP else -VOLUME_KEY_STEP
                    currentOnVolumeChange((currentVolume + step).coerceIn(0f, 1f))
                    change.consume()
                }
            }
        }

    val content: @Composable RowScope.() -> Unit = {
        ClusterIconButton(
            icon = when {
                muted -> Icons.AutoMirrored.Filled.VolumeOff
                volume < VOLUME_LOW_THRESHOLD -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            label = if (muted) tr("取消静音", "Unmute", "ミュートを解除", "음소거 해제", "取消靜音") else tr("静音", "Mute", "ミュート", "음소거", "靜音"),
            onClick = onToggleMute,
            tooltipBelow = tooltipBelow,
            shortcut = "M",
            suppressTooltip = suppressTooltip,
        )
        if (showSlider) {
            PlayerVolumeSlider(
                volume = volume,
                expanded = expanded,
                onVolumeChange = onVolumeChange,
                onDraggingChange = { dragging ->
                    sliderDragging = dragging
                    onDraggingChange(dragging)
                },
                suppressBubble = suppressTooltip,
            )
            Spacer(Modifier.width(6.dp))
        }
    }

    if (showBackground) {
        PlayerPillCluster(modifier = scrollModifier, content = content)
    } else {
        Row(
            modifier = scrollModifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 音量滑块，外观同进度条（细轨道、靠近变粗），理由见 [PlayerSeekBar]。与进度条不同，拖动途中即时生效：
 * 改音量没有重开请求的代价，要边拖边听。悬停或拖动时在手柄上方显示百分比。
 */
@Composable
private fun PlayerVolumeSlider(
    volume: Float,
    expanded: Boolean,
    onVolumeChange: (Float) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    suppressBubble: Boolean = false,
) {
    var dragging by remember { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val inactive = scheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA)
    val trackColors = SeekTrackColors(active = scheme.primary, inactive = inactive, buffered = inactive)
    val motion = MaterialTheme.motionScheme
    val engaged = dragging || hovered || focused
    val sliderWidth by animateDpAsState(if (expanded || focused) VolumeSliderExpandedWidth else VolumeSliderWidth, motion.fastSpatialSpec())
    val thickness by animateDpAsState(if (engaged) SeekTrackEngagedThickness else SeekTrackThickness, motion.fastSpatialSpec())
    val thumbRadius by animateDpAsState(if (dragging) SeekThumbDraggingRadius else SeekThumbRadius, motion.fastSpatialSpec())
    val focusRingColor = scheme.secondary
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val percent = (volume * 100).roundToInt()

    Box(
        Modifier
            .width(sliderWidth)
            .height(36.dp)
            .handCursor()
            .hoverable(hoverInteraction)
            .pointerInput(suppressBubble) {
                if (suppressBubble) return@pointerInput
                val inset = SeekThumbDraggingRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    currentOnDraggingChange(true)
                    try {
                        currentOnVolumeChange(seekFractionAt(down.position.x, size.width.toFloat(), inset))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            currentOnVolumeChange(seekFractionAt(change.position.x, size.width.toFloat(), inset))
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        currentOnDraggingChange(false)
                    }
                }
            }
            .drawBehind {
                drawSeekTrack(
                    fraction = volume,
                    bufferedFraction = 0f,
                    colors = trackColors,
                    thickness = thickness.toPx(),
                    thumbRadius = thumbRadius.toPx(),
                    inset = SeekThumbDraggingRadius.toPx(),
                    focusRing = if (focused) focusRingColor else null,
                )
            }
            .focusable(enabled = !suppressBubble, interactionSource = focusInteraction)
            .semantics {
                contentDescription = tr("音量", "Volume", "音量", "볼륨", "音量")
                stateDescription = "$percent%"
                progressBarRangeInfo = ProgressBarRangeInfo(volume, 0f..1f)
                setProgress { target ->
                    currentOnVolumeChange(target.coerceIn(0f, 1f))
                    true
                }
            },
    ) {
        if (!suppressBubble && (dragging || hovered)) {
            val density = LocalDensity.current
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (dragging) scheme.primary else LocalFixedColors.current.ScrimOnMediaStrong,
                contentColor = if (dragging) scheme.onPrimary else LocalFixedColors.current.OnMedia,
                border = if (dragging) null else BorderStroke(1.dp, LocalFixedColors.current.HudSpecularBorder),
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints())
                    val inset = with(density) { SeekThumbDraggingRadius.toPx() }
                    val anchor = inset + volume * (constraints.maxWidth - 2 * inset)
                    val drop = with(density) { VolumeBubbleDrop.roundToPx() }
                    layout(0, 0) { placeable.place((anchor - placeable.width / 2f).roundToInt(), -placeable.height + drop) }
                },
            ) {
                Text(
                    text = if (dragging) "$percent%" else tr("音量 $percent% (↑ / ↓)", "Volume $percent% (↑ / ↓)", "音量 $percent% (↑ / ↓)", "볼륨 $percent% (↑ / ↓)", "音量 $percent% (↑ / ↓)"),
                    style = TimeTextStyle(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private val VolumeSliderWidth = 76.dp
private val VolumeSliderExpandedWidth = 104.dp
private val VolumeBubbleDrop = 6.dp
private const val VOLUME_LOW_THRESHOLD = 0.5f

internal val String.baseQualityLabel: String
    get() = when (this) {
        AUTO_QUALITY -> tr("自动", "Auto", "自動", "자동", "自動")
        ORIGINAL_QUALITY -> tr("原画", "Original", "オリジナル", "원본", "原畫")
        else -> this
    }

internal val String.qualityDisplayLabel: String
    @Composable get() = if (this == AUTO_QUALITY) {
        val resolved = LocalAutoResolvedQuality.current
        if (resolved != null) "${AUTO_QUALITY.baseQualityLabel} (${resolved.baseQualityLabel})" else AUTO_QUALITY.baseQualityLabel
    } else {
        baseQualityLabel
    }

/** 时间码用等宽数字：比例数字随秒数跳动，右侧按钮会跟着左右抖。 */
@Composable
private fun TimeTextStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.tabular

/**
 * 底栏的文字胶囊按钮：半透明高光描边容器，等宽数字防抖动，按压时有形变；
 * 对应单段面板展开时切换为 `primaryContainer` 选中态（与 Bilby `ControlButton` 对齐）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    tooltip: String? = null,
    tooltipBelow: Boolean = false,
    showBackground: Boolean = true,
) {
    val fixed = LocalFixedColors.current
    val colors = MaterialTheme.colorScheme
    val textStyle = if (showBackground || selected) {
        MaterialTheme.typography.labelLarge.tabular
    } else {
        MaterialTheme.typography.labelLarge.tabular.copy(shadow = PlayerTextShadow)
    }
    val button: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = when {
                    selected && showBackground -> colors.primaryContainer
                    showBackground -> playerContainerColor()
                    else -> Color.Transparent
                },
                contentColor = when {
                    selected && showBackground -> colors.onPrimaryContainer
                    selected -> colors.primary
                    else -> fixed.OnMedia
                },
            ),
            border = if (showBackground && !selected) BorderStroke(1.dp, fixed.HudSpecularBorder) else null,
            contentPadding = ButtonDefaults.ExtraSmallContentPadding,
            modifier = modifier.heightIn(min = 36.dp).captionExclusion().handCursor(),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize))
                Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
            }
            Text(text, style = textStyle, maxLines = 1)
        }
    }
    if (tooltip != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
            ),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            button()
        }
    } else {
        button()
    }
}

/** 浮在视频上的控件容器色。半透明高光底色：既保证亮画面上的对比度，又不整块挡住画面。 */
@Composable
internal fun playerContainerColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CONTAINER_ALPHA)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun playerIconButtonColors(
    selected: Boolean = false,
    showBackground: Boolean = true,
): IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        showBackground -> playerContainerColor()
        else -> Color.Transparent
    },
    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LocalFixedColors.current.OnMedia,
    disabledContainerColor = if (showBackground) playerContainerColor().copy(alpha = CONTAINER_ALPHA / 2) else Color.Transparent,
    disabledContentColor = LocalFixedColors.current.OnMedia.copy(alpha = DISABLED_CONTENT_ALPHA),
)

/**
 * 带长按提示与可选半透明高光描边容器的图标按钮。
 *
 * 控件栏全是纯图标按钮，提示是这类按钮在触屏上唯一的文字说明；
 * 触控目标由 IconButton 自带的最小交互尺寸保证不低于 48dp。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    showBackground: Boolean = true,
    containerSize: DpSize? = null,
    iconSize: Dp = 24.dp,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    tooltipBelow: Boolean = false,
    // 只写进提示，不进 contentDescription，与 TooltipIconButton 相同
    shortcut: String? = null,
    suppressTooltip: Boolean = false,
) {
    val buttonContent: @Composable () -> Unit = {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = shapes,
            colors = playerIconButtonColors(selected = selected, showBackground = showBackground),
            enabled = enabled,
            modifier = (if (containerSize != null) Modifier.size(containerSize) else Modifier)
                .captionExclusion()
                .handCursor(enabled),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
            )
        }
    }
    if (suppressTooltip) {
        Box(modifier = modifier) { buttonContent() }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
            ),
            tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
            state = rememberTooltipState(),
            modifier = modifier,
        ) {
            buttonContent()
        }
    }
}

/**
 * 鼠标悬停在可点的控件上时换成手型。Compose 的按钮默认不换，浮在画面上的半透明按钮
 * 与画面本身难以分辨，指针变化是鼠标用户判断「这里能点」的依据。触屏上没有影响。
 */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

internal val TopScrim = listOf(
    Color(0x8C000000),
    Color(0x38000000),
    Color.Transparent,
)
internal val BottomScrim = listOf(
    Color.Transparent,
    Color(0x42000000),
    Color(0x9E000000),
)
internal val PlayerTextShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 6f)
private val PlayerCloseHoverRed = Color(0xFFC42B1C)

private const val CONTAINER_ALPHA = 0.78f
internal const val DISABLED_CONTENT_ALPHA = 0.38f

// 公开给 app 模块里的控件测试用。播放器里的实际值来自设置，这是信息流与测试用的默认值
const val SEEK_STEP_MILLIS = PlayerGestureDefaults.SEEK_STEP_SECONDS * 1000L
internal const val MIN_SPEED = 0.5f
internal const val MAX_SPEED = 3.5f
const val LONG_PRESS_BOOST_SPEED = PlayerGestureDefaults.BOOST_SPEED

internal fun formatSpeed(speed: Float): String = formatSpeedPreset(speed) + "x"

// 播放键上的读数用乘号：字号大，字母 x 与数字挤在一起像是一个词
internal fun formatSpeedMultiplier(speed: Float): String = formatSpeedPreset(speed) + "×"

// 按两位小数取整后去掉末尾的 0：1.00 显示为 1，1.50 显示为 1.5
internal fun formatSpeedPreset(speed: Float): String = formatPlaybackSpeed(speed)

internal fun formatTime(millis: Long): String = formatDurationMillis(millis)
