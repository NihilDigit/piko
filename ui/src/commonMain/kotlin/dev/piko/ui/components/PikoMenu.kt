package dev.piko.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 的竖向菜单：圆角容器与 standard 配色。全应用的弹出菜单都走这里，
 * 菜单项的形状用 [menuItemShape] 按位置取。
 *
 * 菜单要挂在触发它的按钮上，不挂在整行上：以整行为锚时菜单从行的左下角弹出，离手指半屏远。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = MenuDefaults.shape,
        containerColor = MenuDefaults.containerColor,
        content = content,
    )
}

/**
 * 第 [index] 项（共 [count] 项）的形状。基线菜单的项是一条通栏矩形，按下时的状态层也是方的；
 * M3E 的项自带形状，首项上圆下直、末项反之、中间两头直。只有一项时取独立形状，两项时不能
 * 都取独立形状：两块圆角贴在一起会在中缝挤出一道空隙。菜单项数会变的地方（按文件类型增减动作）
 * 要按实际项数算，不能写死。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun menuItemShape(index: Int, count: Int): Shape = when {
    count <= 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == count - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}
