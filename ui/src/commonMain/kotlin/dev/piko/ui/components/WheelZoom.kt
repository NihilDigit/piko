package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.piko.ui.platform.LocalPikoPlatform
import kotlin.math.abs
import kotlin.math.sign

/**
 * 按住主修饰键（Ctrl，mac 上是 ⌘）滚动滚轮时逐档缩放，照资源管理器与 Finder。[onZoom] 的参数为 true 是放大（向上滚）。
 *
 * 在 Initial 阶段截下并消费，里面的列表就不跟着滚。滚轮一格的位移是 1，高精度滚轮与触控板一次只给零点几，
 * 攒满一格才走一档，换方向时攒着的作废。Windows 把精确式触控板的捏合当作按着 Ctrl 的滚轮发给不认手势的程序，
 * 因此捏合在 Windows 上也走这里；macOS 的捏合是放大手势，AWT 不交给 Compose，那里没有。
 */
@Composable
fun Modifier.zoomOnWheel(onZoom: (larger: Boolean) -> Unit): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val zoom by rememberUpdatedState(onZoom)
    return pointerInput(shortcut) {
        var pending = 0f
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                if (!shortcut.isPressed(event.keyboardModifiers)) {
                    pending = 0f
                    continue
                }
                event.changes.forEach { it.consume() }
                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                if (delta == 0f) continue
                if (sign(delta) != sign(pending)) pending = 0f
                pending += delta
                if (abs(pending) >= 1f) {
                    zoom(pending < 0f)
                    pending = 0f
                }
            }
        }
    }
}
