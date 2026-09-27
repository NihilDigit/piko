package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 文件管理器的点选：按着主修饰键（Ctrl，mac 上是 ⌘）点一项是加选或取消这一项，按着 Shift 是
 * 从上次点选的那一项选到这里。触屏没有修饰键，这一层不起作用，长按进入多选照旧。
 *
 * 在 Initial 阶段截下按下并消费掉：条目自己的单击只认未被消费的按下，于是这一下不会同时打开它。
 */
@Composable
fun Modifier.selectionClicks(onToggle: () -> Unit, onExtend: () -> Unit): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val toggle by rememberUpdatedState(onToggle)
    val extend by rememberUpdatedState(onExtend)
    return pointerInput(shortcut) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press || !event.buttons.isPrimaryPressed) continue
                val modifiers = event.keyboardModifiers
                val action = when {
                    modifiers.isShiftPressed -> extend
                    shortcut.isPressed(modifiers) -> toggle
                    else -> continue
                }
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}
