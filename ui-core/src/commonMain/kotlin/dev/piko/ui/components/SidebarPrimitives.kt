package dev.piko.ui.screens.drive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveLibrary
import dev.piko.ui.components.focusIndication
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

/**
 * 资料库图标：侧边栏、个人页、命令面板、路径首段与空状态共用，选中时切换 [selectedIcon]。
 */
val DriveLibrary.icon: ImageVector
    get() = when (this) {
        DriveLibrary.RECENT -> Icons.Outlined.NewReleases
        DriveLibrary.STARRED -> Icons.Outlined.StarOutline
        DriveLibrary.HISTORY -> Icons.Outlined.PlayCircle
        DriveLibrary.TRASH -> Icons.Outlined.Delete
        DriveLibrary.DUPLICATES -> Icons.Outlined.FileCopy
    }

val DriveLibrary.selectedIcon: ImageVector
    get() = when (this) {
        DriveLibrary.RECENT -> Icons.Filled.NewReleases
        DriveLibrary.STARRED -> Icons.Filled.Star
        DriveLibrary.HISTORY -> Icons.Filled.PlayCircle
        DriveLibrary.TRASH -> Icons.Filled.Delete
        DriveLibrary.DUPLICATES -> Icons.Filled.FileCopy
    }

/** 侧边栏收起成窄轨：各行只保留图标，名称放入悬停提示，分组标题换为细线。 */
val LocalSidebarCollapsed = staticCompositionLocalOf { false }

/** 悬浮抽屉覆盖在窄轨之上时关闭底层窄轨的悬停提示，防止双层提示穿透。 */
val LocalSidebarTooltipsEnabled = staticCompositionLocalOf { true }

@Composable
fun SectionLabel(text: String) {
    if (LocalSidebarCollapsed.current) {
        HorizontalDivider(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        )
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector = icon,
    onClick: () -> Unit,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    onMiddleClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    accent: Boolean = false,
    shortcut: String? = null,
    tooltipDetail: String? = null,
    badgeCount: Int = 0,
    badgeDot: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (LocalSidebarCollapsed.current) {
        CollapsedSidebarItem(
            icon = icon,
            label = label,
            selected = selected,
            selectedIcon = selectedIcon,
            onClick = onClick,
            bold = bold,
            modifier = modifier,
            onMiddleClick = onMiddleClick,
            trailing = trailing,
            accent = accent,
            shortcut = shortcut,
            tooltipDetail = tooltipDetail,
            badgeCount = badgeCount,
            badgeDot = badgeDot,
        )
        return
    }
    val isEInk = LocalAppearance.current.isEInk
    val containerColor by animateColorAsState(
        targetValue = when {
            selected && isEInk -> Color.Black
            selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "sidebarItemBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(containerColor)
            .focusIndication(CircleShape, showPointerFocus = false)
            .then(
                if (onMiddleClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(onMiddleClick) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) onMiddleClick()
                            }
                        }
                    }
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (selected || bold) selectedIcon else icon,
            contentDescription = null,
            tint = when {
                selected && isEInk -> Color.White
                selected -> colors.onSecondaryContainer
                accent -> colors.tertiary
                else -> colors.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected && isEInk -> Color.White
                selected -> colors.onSecondaryContainer
                else -> colors.onSurface
            },
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsedSidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    onClick: () -> Unit,
    bold: Boolean,
    modifier: Modifier,
    onMiddleClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    accent: Boolean,
    shortcut: String?,
    tooltipDetail: String?,
    badgeCount: Int,
    badgeDot: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val pillShape = if (trailing != null) CollapsedExpandedPillShape else CircleShape
    val containerColor by animateColorAsState(
        targetValue = when {
            selected && isEInk -> Color.Black
            selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "railItemBg",
    )
    val pillContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .width(CollapsedPillWidth)
                .heightIn(min = CollapsedPillHeight)
                .clip(pillShape)
                .background(containerColor)
                .focusIndication(pillShape, showPointerFocus = false)
                .then(
                    if (onMiddleClick == null) Modifier else Modifier.pointerInput(onMiddleClick) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) onMiddleClick()
                            }
                        }
                    },
                )
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(
                    horizontal = 4.dp,
                    vertical = if (trailing != null) 6.dp else 0.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val iconNode: @Composable () -> Unit = {
                Icon(
                    imageVector = if (selected || bold) selectedIcon else icon,
                    contentDescription = label,
                    tint = when {
                        selected && isEInk -> Color.White
                        selected -> colors.onSecondaryContainer
                        accent -> colors.tertiary
                        else -> colors.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            if (badgeCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                        }
                    },
                ) {
                    iconNode()
                }
            } else if (badgeDot) {
                BadgedBox(badge = { Badge() }) {
                    iconNode()
                }
            } else {
                iconNode()
            }
            if (trailing != null) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) { trailing() }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (LocalSidebarTooltipsEnabled.current) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
                tooltip = {
                    PlainTooltip {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (shortcut != null) "$label ($shortcut)" else label)
                            if (!tooltipDetail.isNullOrBlank()) {
                                Text(
                                    text = tooltipDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                },
                state = rememberTooltipState(),
            ) {
                pillContent()
            }
        } else {
            pillContent()
        }
    }
}

private val CollapsedPillWidth: Dp = 52.dp
private val CollapsedPillHeight: Dp = 38.dp
private val CollapsedExpandedPillShape = RoundedCornerShape(16.dp)

val SidebarWidth: Dp = 240.dp
val SidebarRailWidth: Dp = 68.dp
