package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.unit.dp
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
 * 只认副键按下，只消费这一下：条目自己的单击、长按与悬停照常工作。
 */
@Composable
fun ContextMenuArea(
    actions: () -> List<SheetAction>,
    modifier: Modifier = Modifier,
    /** 为 false 时右键不弹菜单，例如多选时：那时的操作针对选中的全部条目，不是这一行。 */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var height by remember { mutableStateOf(0) }
    Box(
        modifier = modifier
            .onSizeChanged { height = it.height }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        // 嵌套时里层先收到（Main 阶段由内向外）：条目的菜单弹出后标为已消费，外面网格空白处的那一层就不再弹
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                            menuAt = change.position
                            change.consume()
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
 * 危险操作单独成组放在最后，与操作面板的分组一致；其余按 [SheetAction.group] 分组，组间一道细线。
 * 用 DropdownMenuPopup 自己摆，不走 [PikoDropdownMenu]，为的是菜单项形状与行高自己定。
 *
 * 代价是桌面端 DropdownMenu 额外做的两件事 DropdownMenuPopup 没有：超出窗口时滚动，这里补上；
 * 方向键在菜单项间移动焦点，补不了，Popup 的按键回调没有暴露出来。右键菜单本由鼠标唤出，Tab 仍可切换焦点。
 */
@Composable
private fun ActionMenu(actions: List<SheetAction>, offset: DpOffset, onDismiss: () -> Unit) {
    val (regular, destructive) = actions.partition { !it.destructive }
    val groups = (regular.groupBy { it.group }.values + listOf(destructive)).filter { it.isNotEmpty() }
    DropdownMenuPopup(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.verticalScroll(rememberScrollState()),
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below, offset),
    ) {
        // 一个容器，组与组之间一道细线。各组各带容器、之间留缝（M3E 竖向菜单的分组）在四五组时像一摞碎块
        val items = groups.flatten()
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                group.forEach { action ->
                    ActionMenuItem(action = action, shape = menuItemShape(items.indexOf(action), items.size), onDismiss = onDismiss)
                }
            }
        }
    }
}

private val MenuItemHeight = 36.dp

/**
 * 右键菜单只由鼠标唤出，行高照桌面菜单压到 [MenuItemHeight]、图标 20dp。库的默认是 48dp 行高、24dp 图标，
 * 按手指的触控区给的，鼠标点起来一项占一大截，五六项就拉得很长。
 */
@Composable
private fun ActionMenuItem(action: SheetAction, shape: Shape, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        action.destructive -> colors.error
        action.checked == true -> colors.primary
        else -> colors.onSurfaceVariant
    }
    DropdownMenuItem(
        text = { Text(action.label, style = MaterialTheme.typography.bodyMedium) },
        shape = shape,
        modifier = Modifier.height(MenuItemHeight),
        contentPadding = PaddingValues(horizontal = 12.dp),
        leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (action.checked == true) {
            { Icon(Icons.Outlined.Check, contentDescription = "当前", tint = colors.primary, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
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
