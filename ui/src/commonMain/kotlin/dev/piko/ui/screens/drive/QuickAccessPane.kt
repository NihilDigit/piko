package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.QuickAccessState

/**
 * 宽窗口网盘页左侧的快捷栏，资源管理器的导航窗格：根目录常驻，下面是星标文件夹与最近去过的文件夹。
 * 眼前所在的那一项高亮。行比导航抽屉的 56dp 矮，照桌面文件管理器的密度，一屏能多列几项。
 */
@Composable
internal fun QuickAccessPane(
    state: QuickAccessState,
    currentStack: List<PikoPathBreadcrumb>,
    modifier: Modifier = Modifier,
) {
    val recent by state.recentFolders.collectAsStateWithLifecycle()
    val currentId = currentStack.lastOrNull()?.id
    Column(
        modifier = modifier
            .width(QuickAccessWidth)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
    ) {
        QuickAccessItem(
            icon = Icons.Outlined.Cloud,
            label = "网盘",
            selected = currentStack.size <= 1,
            onClick = state::openRoot,
        )
        if (state.starredFolders.isNotEmpty()) {
            SectionLabel("星标")
            for (folder in state.starredFolders) {
                QuickAccessItem(
                    icon = Icons.Outlined.Star,
                    label = folder.name,
                    selected = folder.id == currentId,
                    onClick = { state.openStarred(folder) },
                )
            }
        }
        if (recent.isNotEmpty()) {
            SectionLabel("最近")
            for (stack in recent) {
                val folder = stack.last()
                QuickAccessItem(
                    icon = Icons.Outlined.History,
                    label = folder.name,
                    // 同名的文件夹多得是（「SPs」「字幕」），悬停时给出整条路径
                    tooltip = stack.joinToString(" › ") { it.name },
                    selected = folder.id == currentId,
                    onClick = { state.openRecent(stack) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun QuickAccessItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tooltip: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CircleShape)
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (tooltip == null) {
        row()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) { row() }
    }
}

internal val QuickAccessWidth: Dp = 232.dp

/** 网盘页至少这么宽才放快捷栏：扣掉它之后列表仍能排两列。 */
internal val QuickAccessMinDriveWidth: Dp = 1_040.dp
