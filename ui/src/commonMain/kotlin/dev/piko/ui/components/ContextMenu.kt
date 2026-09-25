package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset

/**
 * 在指针处弹出的右键菜单，包住一个条目。菜单项与该条目的操作面板相同，只是换成桌面上
 * 更顺手的呈现：不必先点「更多」再在底部面板里找。触屏上没有副键，这一层不起作用。
 *
 * 只认副键按下，不消费事件：条目自己的单击、长按与悬停照常工作。
 */
@Composable
fun ContextMenuArea(
    actions: () -> List<SheetAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var height by remember { mutableStateOf(0) }
    Box(
        modifier = modifier
            .onSizeChanged { height = it.height }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            menuAt = event.changes.first().position
                        }
                    }
                }
            },
    ) {
        content()
        val position = menuAt
        if (position != null) {
            // 菜单以锚点的左下角为原点，纵向偏移要减去条目高度才落在指针处
            val offset = with(LocalDensity.current) { DpOffset(position.x.toDp(), (position.y - height).toDp()) }
            ActionMenu(actions = actions(), offset = offset, onDismiss = { menuAt = null })
        }
    }
}

/**
 * 危险操作单独成组，与操作面板的分组一致。M3E 竖向菜单的分组是各带容器、之间留缝的几块，
 * 不是一个容器里插分隔线，所以这里不走 [PikoDropdownMenu]（它只有一个容器），而用
 * DropdownMenuPopup 摆放各组；菜单项的形状按组内位置算。
 *
 * 代价是桌面端 DropdownMenu 额外做的两件事 DropdownMenuPopup 没有：超出窗口时滚动，这里补上；
 * 方向键在菜单项间移动焦点，补不了，Popup 的按键回调没有暴露出来。右键菜单本由鼠标唤出，Tab 仍可切换焦点。
 */
@Composable
private fun ActionMenu(actions: List<SheetAction>, offset: DpOffset, onDismiss: () -> Unit) {
    val (regular, destructive) = actions.partition { !it.destructive }
    val groups = listOf(regular, destructive).filter { it.isNotEmpty() }
    DropdownMenuPopup(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.verticalScroll(rememberScrollState()),
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below, offset),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) Spacer(modifier = Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                group.forEachIndexed { index, action ->
                    ActionMenuItem(action = action, shape = menuItemShape(index, group.size), onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ActionMenuItem(action: SheetAction, shape: Shape, onDismiss: () -> Unit) {
    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(action.label) },
        shape = shape,
        leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint) },
        colors = if (action.destructive) {
            MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
        } else {
            MenuDefaults.itemColors()
        },
        onClick = {
            onDismiss()
            action.onClick()
        },
    )
}
