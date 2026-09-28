package dev.piko.ui.screens.drive

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.LaunchedEffect
import dev.piko.shared.data.PikoDriveRepository
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.Tab
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.fileDropTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.QuickAccessState

/**
 * 大窗口侧边栏里的快速访问，资源管理器导航窗格的同名一组：常驻的网盘根目录与 My Pack，其后是用户固定的文件夹
 * （文件夹右键「固定到快速访问」加进来）。点一下跳进网盘里的那个文件夹，[onOpened] 由调用方切到网盘页。
 * 侧边栏只亮一处：人在其中某一项里时亮它，否则亮「文件」，见 [highlights]。
 */
@Composable
internal fun ColumnScope.QuickAccessSection(
    state: QuickAccessState,
    pinned: List<PikoPathBreadcrumb>,
    currentStack: List<PikoPathBreadcrumb>,
    onFilesTab: Boolean,
    onOpened: () -> Unit,
) {
    LaunchedEffect(state) { state.watchMyPacks() }
    val currentId = currentStack.lastOrNull()?.id.takeIf { onFilesTab }
    // 库也只有一级，但它在「库」那一组里亮，不算在根目录
    val atRoot = onFilesTab && currentStack.singleOrNull()?.id?.isEmpty() == true
    val myPacks = state.myPacks
    SectionLabel("快速访问")
    val root = PikoDriveRepository.ROOT_BREADCRUMB
    ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tab, "在新标签页打开", { state.openRootInNewTab() })) }) {
        SidebarItem(
            icon = Icons.Outlined.Cloud,
            selectedIcon = Icons.Filled.Cloud,
            label = root.name,
            // 拖到这里就是移回根目录
            modifier = Modifier.fileDropTarget("quick:root", root),
            selected = atRoot,
            onClick = {
                state.openRoot()
                onOpened()
            },
            onMiddleClick = { state.openRootInNewTab() },
        )
    }
    if (myPacks != null) {
        ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tab, "在新标签页打开", { state.openMyPacksInNewTab(myPacks) })) }) {
            SidebarItem(
                icon = Icons.Outlined.CloudDownload,
                selectedIcon = Icons.Filled.CloudDownload,
                label = myPacks.name,
                modifier = Modifier.fileDropTarget("quick:packs", myPacks),
                selected = myPacks.id == currentId,
                onClick = {
                    state.openMyPacks(myPacks)
                    onOpened()
                },
                onMiddleClick = { state.openMyPacksInNewTab(myPacks) },
            )
        }
    }
    // 固定过 My Pack 的，它已常驻在上面，不再列一次
    for (folder in pinned.filterNot { it.id == myPacks?.id }) {
        // 右键与网盘里的文件夹同一种说法，再加这一栏自己的「取消固定」
        ContextMenuArea(actions = {
            listOf(
                SheetAction(Icons.Outlined.Tab, "在新标签页打开", { state.openInNewTab(folder) }),
                SheetAction(Icons.Outlined.PushPin, "从快速访问取消固定", { state.unpin(folder) }),
            )
        }) {
            SidebarItem(
                icon = Icons.Outlined.Folder,
                selectedIcon = Icons.Filled.Folder,
                label = folder.name,
                modifier = Modifier.fileDropTarget("pinned:${folder.id}", folder),
                selected = folder.id == currentId,
                onClick = {
                    state.open(folder)
                    onOpened()
                },
                onMiddleClick = { state.openInNewTab(folder) },
            )
        }
    }
}

/** 眼前的文件夹是不是快速访问里的一项（根目录、My Pack 或固定的）：是的话侧边栏亮它，不亮「文件」。 */
internal fun QuickAccessState.highlights(pinned: List<PikoPathBreadcrumb>, stack: List<PikoPathBreadcrumb>): Boolean {
    if (stack.size == 1) return true
    val id = stack.lastOrNull()?.id ?: return false
    return id == myPacks?.id || pinned.any { it.id == id }
}

/**
 * 侧边栏收起成窄轨：各行只剩图标，名字放进悬停提示，分组的小标题换成一道细线。
 * 用 CompositionLocal 而不是一层层传参：侧边栏里画行的地方分散在几个文件里，收起与否对它们是同一件事。
 */
internal val LocalSidebarCollapsed = staticCompositionLocalOf { false }

@Composable
internal fun SectionLabel(text: String) {
    if (LocalSidebarCollapsed.current) {
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp),
    )
}

/**
 * 侧边栏的一行：比导航抽屉的 56dp 矮，照桌面文件管理器的密度，一屏能多列几项。
 * 平时是描边图标 [icon]，亮起时换成实心的 [selectedIcon]，照 M3 导航项的做法：只靠底色，选中与悬停不好分。
 */
@Composable
internal fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector = icon,
    onClick: () -> Unit,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    /** 中键点它：文件夹在后台的新标签里打开。 */
    onMiddleClick: (() -> Unit)? = null,
    /** 行尾的读数，比如传输的实时速度；null 时不占位。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 没亮起时图标用强调色，表示这一项有个设置正生效（传输的蜗牛模式）。 */
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (LocalSidebarCollapsed.current) {
        CollapsedSidebarItem(icon, label, selected, selectedIcon, onClick, bold, modifier, onMiddleClick, trailing, accent)
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
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
            // 加粗是「这一页开着、但亮的是快速访问里的某一项」：页确实开着，图标照样实心
            imageVector = if (selected || bold) selectedIcon else icon,
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
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 窄轨里的一项：M3 navigation rail 的样子，图标居中、底色是一块胶囊，名字在悬停提示里；
 * 行尾的读数（传输的速度）写在图标下面一行小字，与 rail 项的标签同一个位置。
 */
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
) {
    val colors = MaterialTheme.colorScheme
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
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
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (selected || bold) selectedIcon else icon,
                    contentDescription = label,
                    tint = when {
                        selected -> colors.onSecondaryContainer
                        accent -> colors.tertiary
                        else -> colors.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            if (trailing != null) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) { trailing() }
            }
        }
    }
}

internal val SidebarWidth: Dp = 240.dp

/** 收起后的窄轨宽度，M3 navigation rail 的 80dp。 */
internal val SidebarRailWidth: Dp = 80.dp
