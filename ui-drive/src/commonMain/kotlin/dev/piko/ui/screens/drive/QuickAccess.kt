package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.LaunchedEffect
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.AddToPhotos
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.fileDropTarget
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.ui.components.focusIndication
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
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

/**
 * 大窗口侧边栏里的快速访问，资源管理器导航窗格的同名一组：常驻的网盘根目录与 My Pack，其后是用户固定的文件夹
 * （文件夹右键「固定到快速访问」加进来）。点一下跳进网盘里的那个文件夹：这里只给出路径，换栈与切到网盘页由 [onOpen] 一起做，
 * 它可能先要问一声（窄窗口离开查找重复），取消时两样都不该发生。
 * 侧边栏只亮一处：人在其中某一项里时亮它，否则亮「文件」，见 [highlights]。
 */
@Composable
fun ColumnScope.QuickAccessSection(
    state: QuickAccessState,
    pinned: List<PikoPathBreadcrumb>,
    currentStack: List<PikoPathBreadcrumb>,
    onFilesTab: Boolean,
    onOpen: (List<PikoPathBreadcrumb>) -> Unit,
) {
    LaunchedEffect(state) { state.watchMyPacks() }
    val currentId = currentStack.lastOrNull()?.id.takeIf { onFilesTab }
    // 库也只有一级，但它在「库」那一组里亮，不算在根目录
    val atRoot = onFilesTab && currentStack.singleOrNull()?.id?.isEmpty() == true
    val myPacks = state.myPacks
    SectionLabel(tr("快速访问", "Quick access", "クイックアクセス", "빠른 액세스", "快速存取"))
    val root = PikoDriveRepository.ROOT_BREADCRUMB
    ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.AddToPhotos, tr("在新标签页打开", "Open in new tab", "新しいタブで開く", "새 탭에서 열기", "在新分頁開啟"), { state.openRootInNewTab() })) }) {
        SidebarItem(
            icon = Icons.Outlined.Home,
            selectedIcon = Icons.Filled.Home,
            label = root.localizedName,
            // 拖到这里就是移回根目录
            modifier = Modifier.fileDropTarget("quick:root", root),
            selected = atRoot,
            onClick = { onOpen(listOf(root)) },
            onMiddleClick = { state.openRootInNewTab() },
        )
    }
    if (myPacks != null) {
        ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.AddToPhotos, tr("在新标签页打开", "Open in new tab", "新しいタブで開く", "새 탭에서 열기", "在新分頁開啟"), { state.openMyPacksInNewTab(myPacks) })) }) {
            SidebarItem(
                icon = Icons.Outlined.CloudDownload,
                selectedIcon = Icons.Filled.CloudDownload,
                label = myPacks.localizedName,
                modifier = Modifier.fileDropTarget("quick:packs", myPacks),
                selected = myPacks.id == currentId,
                // My Pack 就在根目录下，路径不必再查
                onClick = { onOpen(listOf(root, myPacks)) },
                onMiddleClick = { state.openMyPacksInNewTab(myPacks) },
            )
        }
    }
    // 固定过 My Pack 的，它已常驻在上面，不再列一次
    for (folder in pinned.filterNot { it.id == myPacks?.id }) {
        val isLocal = LocalLocation.isLocal(folder.id)
        // 右键与网盘里的文件夹同一种说法，再加这一栏自己的「取消固定」
        ContextMenuArea(actions = {
            listOf(
                SheetAction(Icons.Outlined.AddToPhotos, tr("在新标签页打开", "Open in new tab", "新しいタブで開く", "새 탭에서 열기", "在新分頁開啟"), { state.openInNewTab(folder) }),
                SheetAction(Icons.Outlined.PushPin, tr("从快速访问取消固定", "Unpin from quick access", "クイックアクセスから外す", "빠른 액세스에서 고정 해제", "從快速存取取消釘選"), { state.unpin(folder) }),
            )
        }) {
            SidebarItem(
                icon = if (isLocal) Icons.Outlined.Storage else Icons.Outlined.Folder,
                selectedIcon = if (isLocal) Icons.Filled.Storage else Icons.Filled.Folder,
                label = folder.localizedName,
                modifier = if (isLocal) Modifier else Modifier.fileDropTarget("pinned:${folder.id}", folder),
                selected = folder.id == currentId,
                onClick = { state.locate(folder, onOpen) },
                onMiddleClick = { state.openInNewTab(folder) },
            )
        }
    }
}

/** 眼前的文件夹是不是快速访问里的一项（根目录、My Pack 或固定的）：是的话侧边栏亮它，不亮「文件」。 */
fun QuickAccessState.highlights(pinned: List<PikoPathBreadcrumb>, stack: List<PikoPathBreadcrumb>): Boolean {
    if (stack.size == 1) return true
    val id = stack.lastOrNull()?.id ?: return false
    return id == myPacks?.id || pinned.any { it.id == id }
}
