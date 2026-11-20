package dev.piko.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable

/**
 * 连体按钮组里第 [index] 个（共 [count] 个）的 Material 3 Expressive 形变形状：
 * 复用 `ButtonGroupDefaults.connected*ButtonShapes()`，在按下与选中时呈现外圆内收的物理弹性形变。
 */
@Composable
fun connectedToggleShapes(index: Int, count: Int): ToggleButtonShapes = when {
    count <= 1 -> ToggleButtonShapes(
        shape = CircleShape,
        pressedShape = MaterialTheme.shapes.medium,
        checkedShape = CircleShape,
    )
    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}
