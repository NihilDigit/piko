package dev.piko.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * 连体按钮组里第 [index] 个（共 [count] 个）的形状，按下与选中时不变。
 *
 * 不用库里的 connectedLeadingButtonShapes 这一族：它们在按下与选中时用弹簧动画在几种形状间过渡，
 * 内侧的小圆角冲过头会算出负值，CornerBasedShape 当场抛异常，整个窗口崩掉（2026-09-28 切换信息流时实测）。
 * 形状跟着按压变是 Expressive 的点缀，丢了它换不崩，值得。
 */
@Composable
fun connectedToggleShapes(index: Int, count: Int): ToggleButtonShapes {
    // 取库里那一族平时（未按下、未选中）的形状，外观与原来一致，只是不再变
    val shape: Shape = when {
        count == 1 -> CircleShape
        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes().shape
        index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes().shape
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes().shape
    }
    return ToggleButtonShapes(shape = shape, pressedShape = shape, checkedShape = shape)
}
