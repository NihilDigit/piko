package dev.piko.ui.screens.drive

import dev.piko.ui.components.fileDropTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.QuickAccessState

/**
 * 大窗口侧边栏里的快捷访问：星标文件夹与最近去过的文件夹，资源管理器的导航窗格、Finder 的边栏。
 * 点一下跳进网盘里的那个文件夹，[onOpened] 由调用方切到网盘页。侧边栏只亮一处：人在某个星标文件夹里时亮它，
 * 否则亮「文件」，见 [highlightsStarred]。
 */
@Composable
internal fun ColumnScope.QuickAccessSections(
    state: QuickAccessState,
    currentStack: List<PikoPathBreadcrumb>,
    onFilesTab: Boolean,
    onOpened: () -> Unit,
) {
    val recent by state.recentFolders.collectAsStateWithLifecycle()
    LaunchedEffect(state) { state.watchStarred() }
    val currentId = currentStack.lastOrNull()?.id.takeIf { onFilesTab }
    if (state.starredFolders.isNotEmpty()) {
        SectionLabel("星标")
        for (folder in state.starredFolders) {
            SidebarItem(
                icon = Icons.Outlined.Star,
                label = folder.name,
                modifier = Modifier.fileDropTarget("starred:${folder.id}", PikoPathBreadcrumb(folder.id, folder.name)),
                selected = folder.id == currentId,
                onClick = {
                    state.openStarred(folder)
                    onOpened()
                },
            )
        }
    }
    // 眼前这个不列：「最近」是还能回哪儿去，所在之处已经在顶栏的路径上
    val others = recent.filterNot { it.last().id == currentId }
    if (others.isNotEmpty()) {
        SectionLabel("最近")
        for (stack in others) {
            val folder = stack.last()
            SidebarItem(
                icon = Icons.Outlined.History,
                label = folder.name,
                modifier = Modifier.fileDropTarget("recent:${folder.id}", folder),
                // 同名的文件夹多得是（「SPs」「字幕」），悬停时给出整条路径
                tooltip = stack.joinToString(" › ") { it.name },
                selected = false,
                onClick = {
                    state.openRecent(stack)
                    onOpened()
                },
            )
        }
    }
}

/** 眼前的文件夹是不是星标里的一项：是的话侧边栏亮它，不亮「文件」。 */
internal fun QuickAccessState.highlightsStarred(stack: List<PikoPathBreadcrumb>): Boolean {
    val id = stack.lastOrNull()?.id ?: return false
    return starredFolders.any { it.id == id }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}

/** 侧边栏的一行：比导航抽屉的 56dp 矮，照桌面文件管理器的密度，一屏能多列几项。 */
@Composable
internal fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tooltip: String? = null,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val row: @Composable () -> Unit = {
        Row(
            modifier = modifier
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
                fontWeight = if (bold) FontWeight.Bold else null,
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

internal val SidebarWidth: Dp = 240.dp
