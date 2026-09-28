package dev.piko.ui.screens.drive

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
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
    val atRoot = onFilesTab && currentStack.size == 1
    val myPacks = state.myPacks
    SectionLabel("快速访问")
    val root = PikoDriveRepository.ROOT_BREADCRUMB
    ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tab, "在新标签页打开", { state.openRootInNewTab() })) }) {
        SidebarItem(
            icon = Icons.Outlined.Cloud,
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

@Composable
internal fun SectionLabel(text: String) {
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
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    /** 中键点它：文件夹在后台的新标签里打开。 */
    onMiddleClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
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

internal val SidebarWidth: Dp = 240.dp
