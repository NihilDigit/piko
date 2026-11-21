package dev.piko.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_maximize
import dev.piko.ui.resources.player_minimize
import dev.piko.ui.resources.player_restore
import dev.piko.ui.theme.LocalFixedColors

/**
 * 桌面独立播放/看图窗口交给顶栏的窗口控制动作：最小化、最大化/还原、置顶与关闭。
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
fun MediaWindowControlsCluster(
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

/** 浮在媒体画面上的分组半透明高光描边胶囊。 */
@Composable
fun PlayerPillCluster(
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
fun ClusterIconButton(
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
fun PlayerCloseWindowButton(
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
fun playerContainerColor(): Color =
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerIconButton(
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

fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

val TopScrim = listOf(
    Color(0x8C000000),
    Color(0x38000000),
    Color.Transparent,
)
val BottomScrim = listOf(
    Color.Transparent,
    Color(0x42000000),
    Color(0x9E000000),
)
val PlayerTextShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 6f)
private val PlayerCloseHoverRed = Color(0xFFC42B1C)

private const val CONTAINER_ALPHA = 0.78f
const val DISABLED_CONTENT_ALPHA = 0.38f
