package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
            // DropdownMenu 以锚点的左下角为原点，纵向偏移要减去条目高度才落在指针处
            val offset = with(LocalDensity.current) { DpOffset(position.x.toDp(), (position.y - height).toDp()) }
            ActionMenu(actions = actions(), offset = offset, onDismiss = { menuAt = null })
        }
    }
}

@Composable
private fun ActionMenu(actions: List<SheetAction>, offset: DpOffset, onDismiss: () -> Unit) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = offset) {
        actions.forEachIndexed { index, action ->
            // 危险操作前加分隔，与面板里单独成组的做法一致
            if (action.destructive && index > 0) HorizontalDivider()
            val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            DropdownMenuItem(
                text = { Text(action.label) },
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
    }
}
