package dev.piko.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.CenterClusterConfig
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotConfig
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_seek_backward_seconds
import dev.piko.ui.resources.player_seek_forward_seconds
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

private val ControlBarPlayHeight = 40.dp
private val ControlBarPlayIconSize = 24.dp
private val ControlBarInnerSideSize = 34.dp
private val ControlBarOuterSideSize = 30.dp
private val ControlBarInnerSideIconSize = 18.dp
private val ControlBarOuterSideIconSize = 16.dp
private const val ControlBarPlayingWidthRatio = 1.20f
private const val ControlBarPlayingCornerRatio = 0.32f
private val MinTouchTarget = 48.dp
private val CenterBaseHeight = 52.dp
private val CenterBaseHeightLarge = 60.dp
private val BorderlessLabelShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 6f)

/**
 * 顶/底栏上的 M3E 形变播放/暂停键（移植自 Bilby `ControlBarPlayButton`）：
 * 暂停态正圆 `primary`，播放态横向展宽圆角矩形 `primaryContainer`，空间弹簧与色彩弹簧分离驱动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ControlBarPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    showBackground: Boolean = true,
    tooltipBelow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val height = ControlBarPlayHeight
    val width by animateDpAsState(
        targetValue = if (isPlaying) height * ControlBarPlayingWidthRatio else height,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "control-bar-play-width",
    )
    val corner by animateDpAsState(
        targetValue = if (isPlaying) height * ControlBarPlayingCornerRatio else height / 2,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "control-bar-play-corner",
    )
    val colors = MaterialTheme.colorScheme
    val fixed = LocalFixedColors.current
    val isEInk = LocalAppearance.current.isEInk
    val translucent = LocalPlayerLayoutPrefs.current.translucentPlayButton
    val container by animateColorAsState(
        targetValue = when {
            !showBackground -> Color.Transparent
            isEInk -> if (!translucent && isPlaying) colors.onSurface else colors.surface
            translucent -> fixed.ScrimOnMediaStrong
            isPlaying -> colors.primaryContainer
            else -> colors.primary
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "control-bar-play-container",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !showBackground -> fixed.OnMedia
            isEInk -> if (!translucent && isPlaying) colors.surface else colors.onSurface
            translucent -> fixed.OnMedia
            isPlaying -> colors.onPrimaryContainer
            else -> colors.onPrimary
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "control-bar-play-content",
    )
    val border = when {
        !showBackground -> null
        isEInk -> BorderStroke(1.dp, colors.outline)
        translucent -> BorderStroke(1.dp, fixed.HudSpecularBorder)
        else -> null
    }
    val label = if (isPlaying) {
        tr("暂停", "Pause", "一時停止", "일시 중지", "暫停")
    } else {
        tr("播放", "Play", "再生", "재생", "播放")
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text("$label (Space)") } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                .handCursor(),
        ) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(corner),
                color = container,
                contentColor = contentColor,
                border = border,
                modifier = Modifier.size(width = width, height = height),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = label,
                        modifier = Modifier.size(ControlBarPlayIconSize),
                    )
                }
            }
        }
    }
}

/**
 * 控制条中的一体化播放控制簇（移植自 Bilby `ControlBarTransportCluster`）：
 * 当中央簇不激活（键鼠模式、关闭触控中央控件或隐藏中央播放键）时，
 * 将 `[ 外左 ] [ 内左 ] [ 播放/暂停 ] [ 内右 ] [ 外右 ]` 作为单一整体迁至控制条的 `PlayPause` 位置。
 */
@Composable
internal fun ControlBarTransportCluster(
    config: CenterClusterConfig,
    isPlaying: Boolean,
    showEpisodeSkip: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    allowSeek: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekDeltaMillis: (Long) -> Unit,
    showPlayBackground: Boolean = true,
    tooltipBelow: Boolean = false,
    previewMode: Boolean = false,
    selectedSlot: CenterSlotPosition? = null,
    isPlaySelected: Boolean = false,
    onSelectSlot: ((CenterSlotPosition) -> Unit)? = null,
    dropTargetSlot: CenterSlotPosition? = null,
    showEmptySlots: Boolean = false,
    onSlotBounds: ((CenterSlotPosition, Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        ControlBarTransportSideButton(
            slot = config.outerLeft,
            isOuter = true,
            showEpisodeSkip = showEpisodeSkip,
            canPrevious = canPrevious,
            canNext = canNext,
            allowSeek = allowSeek,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekDeltaMillis = onSeekDeltaMillis,
            tooltipBelow = tooltipBelow,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.OuterLeft,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterLeft,
            showEmpty = previewMode && showEmptySlots,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterLeft) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterLeft, rect) } },
        )
        ControlBarTransportSideButton(
            slot = config.innerLeft,
            isOuter = false,
            showEpisodeSkip = showEpisodeSkip,
            canPrevious = canPrevious,
            canNext = canNext,
            allowSeek = allowSeek,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekDeltaMillis = onSeekDeltaMillis,
            tooltipBelow = tooltipBelow,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.InnerLeft,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerLeft,
            showEmpty = previewMode && showEmptySlots,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerLeft) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerLeft, rect) } },
        )
        Box(
            modifier = if (previewMode && isPlaySelected) {
                Modifier
                    .border(2.dp, primary, CircleShape)
                    .padding(2.dp)
            } else {
                Modifier
            },
        ) {
            ControlBarPlayButton(
                isPlaying = isPlaying,
                onClick = onTogglePlayPause,
                showBackground = showPlayBackground,
                tooltipBelow = tooltipBelow,
            )
        }
        ControlBarTransportSideButton(
            slot = config.innerRight,
            isOuter = false,
            showEpisodeSkip = showEpisodeSkip,
            canPrevious = canPrevious,
            canNext = canNext,
            allowSeek = allowSeek,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekDeltaMillis = onSeekDeltaMillis,
            tooltipBelow = tooltipBelow,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.InnerRight,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerRight,
            showEmpty = previewMode && showEmptySlots,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerRight) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerRight, rect) } },
        )
        ControlBarTransportSideButton(
            slot = config.outerRight,
            isOuter = true,
            showEpisodeSkip = showEpisodeSkip,
            canPrevious = canPrevious,
            canNext = canNext,
            allowSeek = allowSeek,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekDeltaMillis = onSeekDeltaMillis,
            tooltipBelow = tooltipBelow,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.OuterRight,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterRight,
            showEmpty = previewMode && showEmptySlots,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterRight) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterRight, rect) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlBarTransportSideButton(
    slot: CenterSlotConfig,
    isOuter: Boolean,
    showEpisodeSkip: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    allowSeek: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekDeltaMillis: (Long) -> Unit,
    tooltipBelow: Boolean,
    previewMode: Boolean = false,
    selected: Boolean = false,
    dropHighlighted: Boolean = false,
    showEmpty: Boolean = false,
    onSelect: () -> Unit = {},
    onBounds: ((Rect) -> Unit)? = null,
) {
    val action = slot.action.sanitized()
    // 底栏主控组清晰尺寸层次：中央 Play/Pause 40dp > 内侧 34dp > 外侧 30dp
    val diameter = if (isOuter) ControlBarOuterSideSize else ControlBarInnerSideSize
    val iconSize = if (isOuter) ControlBarOuterSideIconSize else ControlBarInnerSideIconSize
    val fixed = LocalFixedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val boundsMod = if (previewMode && onBounds != null) {
        Modifier.onGloballyPositioned { coords ->
            if (coords.isAttached) onBounds(coords.boundsInWindow())
        }
    } else {
        Modifier
    }

    if (action == CenterSlotAction.None) {
        if (!previewMode || (!showEmpty && !selected && !dropHighlighted)) return
        val strokeColor = if (dropHighlighted || selected) primary else fixed.OnMedia.copy(alpha = 0.45f)
        val strokeWidth = if (dropHighlighted || selected) 2.dp else 1.2.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = boundsMod
                .size(diameter)
                .clip(CircleShape)
                .background(if (dropHighlighted) primary.copy(alpha = 0.28f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .handCursor(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = strokeWidth.toPx()
                val radius = (size.minDimension - strokePx) / 2f
                if (radius > 0f) {
                    drawCircle(
                        color = strokeColor,
                        radius = radius,
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                        ),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = strokeColor,
                modifier = Modifier.size((iconSize - 2.dp).coerceAtLeast(12.dp)),
            )
        }
        return
    }
    val visible = previewMode || when (action) {
        CenterSlotAction.Previous, CenterSlotAction.Next -> showEpisodeSkip && (canPrevious || canNext)
        is CenterSlotAction.SeekBackward, is CenterSlotAction.SeekForward -> allowSeek
        CenterSlotAction.None -> false
    }
    if (!visible) return

    val enabled = previewMode || when (action) {
        CenterSlotAction.Previous -> canPrevious
        CenterSlotAction.Next -> canNext
        is CenterSlotAction.SeekBackward, is CenterSlotAction.SeekForward -> true
        CenterSlotAction.None -> false
    }
    val icon = when (action) {
        CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
        CenterSlotAction.Next -> Icons.Filled.SkipNext
        is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
        is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
        CenterSlotAction.None -> return
    }
    val label = when (action) {
        CenterSlotAction.Previous -> tr("上一集", "Previous episode", "前のエピソード", "이전 에피소드", "上一集")
        CenterSlotAction.Next -> tr("下一集", "Next episode", "次のエピソード", "다음 에피소드", "下一集")
        is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_seek_backward_seconds, action.seconds)
        is CenterSlotAction.SeekForward -> stringResource(Res.string.player_seek_forward_seconds, action.seconds)
        CenterSlotAction.None -> ""
    }
    val shortcut = when (action) {
        CenterSlotAction.Previous -> "PageUp"
        CenterSlotAction.Next -> "PageDown"
        else -> null
    }
    val onClick: () -> Unit = if (previewMode) {
        onSelect
    } else {
        when (action) {
            CenterSlotAction.Previous -> onPrevious
            CenterSlotAction.Next -> onNext
            is CenterSlotAction.SeekBackward -> ({ onSeekDeltaMillis(-action.seconds * 1_000L) })
            is CenterSlotAction.SeekForward -> ({ onSeekDeltaMillis(action.seconds * 1_000L) })
            CenterSlotAction.None -> ({})
        }
    }

    val contentAlpha = if (enabled) 1f else 0.38f

    val buttonContent: @Composable () -> Unit = {
        Surface(
            shape = CircleShape,
            color = when {
                previewMode && dropHighlighted -> primary.copy(alpha = 0.35f)
                slot.showBackground -> playerContainerColor()
                else -> Color.Transparent
            },
            contentColor = fixed.OnMedia.copy(alpha = contentAlpha),
            modifier = boundsMod
                .size(diameter)
                .clip(CircleShape)
                .then(
                    if (previewMode && (dropHighlighted || selected)) {
                        Modifier.border(if (dropHighlighted) 2.5.dp else 2.dp, primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(enabled = enabled, onClick = onClick)
                .handCursor(enabled),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }

    if (previewMode) {
        buttonContent()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
            ),
            tooltip = {
                PlainTooltip {
                    Text(if (shortcut != null) "$label ($shortcut)" else label)
                }
            },
            state = rememberTooltipState(),
        ) {
            buttonContent()
        }
    }
}

/**
 * 播放器中央 5 槽位控件簇（移植自 Bilby `CenterControlCluster` 并保留 Piko 形变中央播放键）：
 * `[ 外左 ] [ 内左 ] [ 播放/暂停 ] [ 内右 ] [ 外右 ]`。
 *
 * - 触控控件展开且 `config.showCenterPlay` 为真时展示完整中央簇；
 * - 加载缓冲或瞬态中心读数（如双击暂停提示）时即使控件收起或键鼠模式也由中央播放键单独承载，
 *   此时侧槽位隐藏，保证中央读数居中且不误触。
 */
@Composable
internal fun PlayerCenterCluster(
    config: CenterClusterConfig,
    centerVisible: Boolean,
    showSideButtons: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    indicator: CenterIndicator?,
    large: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    allowSeek: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekDeltaMillis: (Long) -> Unit,
    loadingSpeedText: String? = null,
    interactive: Boolean = centerVisible,
    previewMode: Boolean = false,
    selectedSlot: CenterSlotPosition? = null,
    onSelectSlot: ((CenterSlotPosition) -> Unit)? = null,
    dropTargetSlot: CenterSlotPosition? = null,
    onSlotBounds: ((CenterSlotPosition, Rect) -> Unit)? = null,
    scaleMultiplier: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val baseHeight = (if (large) CenterBaseHeightLarge else CenterBaseHeight) * scaleMultiplier

    fun isSlotVisible(slot: CenterSlotConfig): Boolean {
        if (previewMode) return true
        if (!showSideButtons || !config.showCenterPlay || indicator != null) return false
        return when (slot.action) {
            CenterSlotAction.None -> false
            CenterSlotAction.Previous, CenterSlotAction.Next -> canPrevious || canNext
            is CenterSlotAction.SeekBackward, is CenterSlotAction.SeekForward -> allowSeek
        }
    }

    val outerLeftVisible = isSlotVisible(config.outerLeft)
    val innerLeftVisible = isSlotVisible(config.innerLeft)
    val innerRightVisible = isSlotVisible(config.innerRight)
    val outerRightVisible = isSlotVisible(config.outerRight)
    val showOuterPair = previewMode || outerLeftVisible || outerRightVisible
    val showInnerPair = previewMode || innerLeftVisible || innerRightVisible

    val outerVisualSize = maxOf(
        baseHeight * config.outerLeft.scale.ratio,
        baseHeight * config.outerRight.scale.ratio,
    ).coerceAtLeast(28.dp)
    val innerVisualSize = maxOf(
        baseHeight * config.innerLeft.scale.ratio,
        baseHeight * config.innerRight.scale.ratio,
    ).coerceAtLeast(32.dp)
    val outerSlotWidth = if (previewMode) outerVisualSize else maxOf(outerVisualSize, MinTouchTarget)
    val innerSlotWidth = if (previewMode) innerVisualSize else maxOf(innerVisualSize, MinTouchTarget)
    val gap = when {
        previewMode -> 8.dp
        showOuterPair && !large -> 8.dp
        large -> 20.dp
        else -> 14.dp
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = modifier,
    ) {
        if (showOuterPair) {
            CenterSideSlotContainer(
                slot = config.outerLeft,
                visible = outerLeftVisible,
                baseHeight = baseHeight,
                slotWidth = outerSlotWidth,
                canPrevious = canPrevious,
                canNext = canNext,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekDeltaMillis = onSeekDeltaMillis,
                previewMode = previewMode,
                selected = selectedSlot == CenterSlotPosition.OuterLeft,
                dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterLeft,
                onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterLeft) },
                onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterLeft, rect) } },
            )
        }
        if (showInnerPair) {
            CenterSideSlotContainer(
                slot = config.innerLeft,
                visible = innerLeftVisible,
                baseHeight = baseHeight,
                slotWidth = innerSlotWidth,
                canPrevious = canPrevious,
                canNext = canNext,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekDeltaMillis = onSeekDeltaMillis,
                previewMode = previewMode,
                selected = selectedSlot == CenterSlotPosition.InnerLeft,
                dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerLeft,
                onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerLeft) },
                onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerLeft, rect) } },
            )
        }

        val centerSelected = previewMode && selectedSlot == CenterSlotPosition.Center
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (centerSelected) {
                Modifier
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(3.dp)
            } else {
                Modifier
            },
        ) {
            if (previewMode && !config.showCenterPlay) {
                DashedCenterSlotPlaceholder(
                    diameter = baseHeight.coerceAtLeast(40.dp),
                    selected = centerSelected,
                    dropHighlighted = false,
                    onClick = { onSelectSlot?.invoke(CenterSlotPosition.Center) },
                )
            } else {
                val centerBoxModifier = if (scaleMultiplier != 1f) {
                    Modifier
                        .size(68.dp * scaleMultiplier, 56.dp * scaleMultiplier)
                        .graphicsLayer {
                            scaleX = scaleMultiplier
                            scaleY = scaleMultiplier
                        }
                } else {
                    Modifier
                }
                Box(modifier = centerBoxModifier, contentAlignment = Alignment.Center) {
                    PlayerCenterControls(
                        visible = previewMode || centerVisible,
                        interactive = previewMode || interactive,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        indicator = indicator,
                        loadingSpeedText = loadingSpeedText,
                        onPlayPause = {
                            if (previewMode) {
                                onSelectSlot?.invoke(CenterSlotPosition.Center)
                            } else {
                                onPlayPause()
                            }
                        },
                    )
                }
            }
        }

        if (showInnerPair) {
            CenterSideSlotContainer(
                slot = config.innerRight,
                visible = innerRightVisible,
                baseHeight = baseHeight,
                slotWidth = innerSlotWidth,
                canPrevious = canPrevious,
                canNext = canNext,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekDeltaMillis = onSeekDeltaMillis,
                previewMode = previewMode,
                selected = selectedSlot == CenterSlotPosition.InnerRight,
                dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerRight,
                onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerRight) },
                onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerRight, rect) } },
            )
        }
        if (showOuterPair) {
            CenterSideSlotContainer(
                slot = config.outerRight,
                visible = outerRightVisible,
                baseHeight = baseHeight,
                slotWidth = outerSlotWidth,
                canPrevious = canPrevious,
                canNext = canNext,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekDeltaMillis = onSeekDeltaMillis,
                previewMode = previewMode,
                selected = selectedSlot == CenterSlotPosition.OuterRight,
                dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterRight,
                onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterRight) },
                onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterRight, rect) } },
            )
        }
    }
}

@Composable
private fun CenterSideSlotContainer(
    slot: CenterSlotConfig,
    visible: Boolean,
    baseHeight: Dp,
    slotWidth: Dp,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekDeltaMillis: (Long) -> Unit,
    previewMode: Boolean,
    selected: Boolean,
    dropHighlighted: Boolean = false,
    onSelect: () -> Unit,
    onBounds: ((Rect) -> Unit)? = null,
) {
    val visualDiameter = (baseHeight * slot.scale.ratio).coerceAtLeast(28.dp)
    if (!visible) {
        Box(modifier = Modifier.width(slotWidth))
        return
    }
    val boundsMod = if (previewMode && onBounds != null) {
        Modifier.onGloballyPositioned { coords ->
            if (coords.isAttached) onBounds(coords.boundsInWindow())
        }
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = boundsMod
            .width(slotWidth)
            .then(
                if (previewMode) Modifier else Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
            ),
    ) {
        if (slot.action == CenterSlotAction.None) {
            if (previewMode) {
                DashedCenterSlotPlaceholder(
                    diameter = visualDiameter,
                    selected = selected,
                    dropHighlighted = dropHighlighted,
                    onClick = onSelect,
                )
            }
            return
        }

        val enabled = previewMode || when (slot.action) {
            CenterSlotAction.Previous -> canPrevious
            CenterSlotAction.Next -> canNext
            is CenterSlotAction.SeekBackward, is CenterSlotAction.SeekForward -> true
            CenterSlotAction.None -> false
        }
        val contentAlpha = if (enabled) 1f else 0.38f
        val icon = when (slot.action) {
            CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
            CenterSlotAction.Next -> Icons.Filled.SkipNext
            is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
            is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
            CenterSlotAction.None -> Icons.Filled.Add
        }
        val contentDesc = when (val a = slot.action) {
            CenterSlotAction.Previous -> tr("上一集", "Previous episode", "前のエピソード", "이전 에피소드", "上一集")
            CenterSlotAction.Next -> tr("下一集", "Next episode", "次のエピソード", "다음 에피소드", "下一集")
            is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_seek_backward_seconds, a.seconds)
            is CenterSlotAction.SeekForward -> stringResource(Res.string.player_seek_forward_seconds, a.seconds)
            CenterSlotAction.None -> null
        }
        val secondsBadge = slot.action.seekSecondsOrNull()
        val iconSize = (visualDiameter * (if (secondsBadge != null) 0.42f else 0.52f)).coerceIn(14.dp, 28.dp)
        val fixed = LocalFixedColors.current
        val primary = MaterialTheme.colorScheme.primary

        Surface(
            onClick = {
                if (previewMode) {
                    onSelect()
                } else {
                    when (val a = slot.action) {
                        CenterSlotAction.Previous -> onPrevious()
                        CenterSlotAction.Next -> onNext()
                        is CenterSlotAction.SeekBackward -> onSeekDeltaMillis(-a.seconds * 1_000L)
                        is CenterSlotAction.SeekForward -> onSeekDeltaMillis(a.seconds * 1_000L)
                        CenterSlotAction.None -> Unit
                    }
                }
            },
            enabled = enabled,
            shape = CircleShape,
            color = when {
                previewMode && dropHighlighted -> primary.copy(alpha = 0.35f)
                slot.showBackground -> playerContainerColor()
                else -> Color.Transparent
            },
            contentColor = fixed.OnMedia.copy(alpha = contentAlpha),
            modifier = Modifier
                .size(visualDiameter)
                .then(
                    if (previewMode && (dropHighlighted || selected)) {
                        Modifier.border(if (dropHighlighted) 2.5.dp else 2.dp, primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .handCursor(enabled),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (secondsBadge != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = contentDesc,
                            modifier = Modifier.size(iconSize),
                        )
                        Text(
                            text = "${secondsBadge}s",
                            style = if (slot.showBackground) {
                                MaterialTheme.typography.labelSmall.tabular
                            } else {
                                MaterialTheme.typography.labelSmall.tabular.copy(shadow = BorderlessLabelShadow)
                            },
                            maxLines = 1,
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDesc,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun DashedCenterSlotPlaceholder(
    diameter: Dp,
    selected: Boolean,
    dropHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val fixed = LocalFixedColors.current
    val primary = MaterialTheme.colorScheme.primary
    val strokeColor = if (dropHighlighted || selected) {
        primary
    } else {
        fixed.OnMedia.copy(alpha = 0.42f)
    }
    val strokeWidth = if (dropHighlighted) 2.5.dp else if (selected) 2.dp else 1.5.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(if (dropHighlighted) primary.copy(alpha = 0.28f) else Color.Transparent)
            .clickable(onClick = onClick)
            .handCursor(),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            drawCircle(
                color = strokeColor,
                radius = (size.minDimension - strokePx) / 2f,
                style = Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                ),
            )
        }
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = strokeColor,
            modifier = Modifier.size((diameter * 0.42f).coerceIn(14.dp, 22.dp)),
        )
    }
}
