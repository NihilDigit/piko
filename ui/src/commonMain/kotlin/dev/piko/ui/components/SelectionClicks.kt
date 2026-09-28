package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 文件管理器的点选，资源管理器与 Finder 一致：
 * - 按着主修饰键（Ctrl，mac 上是 ⌘）点一项是加选或取消这一项，按着 Shift 是从上次点选的那一项选到这里。
 * - [onDoubleClick] 不为 null 时，鼠标单击只是选中（条目取得焦点，由外层处理），双击才调它打开。
 *
 * 触屏不经这一层：没有修饰键，轻点照旧打开，长按照旧进入多选。
 *
 * 修饰键点选在 Initial 阶段截下按下并消费掉，条目自己的单击只认未被消费的按下，于是这一下不会同时打开它。
 * 普通单击截的是松开而不是按下：拖放（fileDragSource）要看到未被消费的按下才起拖，按下截走就拖不动了。
 * 松开被消费后，条目自己的点击判定为取消，不会打开。
 */
@Composable
fun Modifier.selectionClicks(
    onToggle: () -> Unit,
    onExtend: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val toggle by rememberUpdatedState(onToggle)
    val extend by rememberUpdatedState(onExtend)
    val doubleClick by rememberUpdatedState(onDoubleClick)
    return pointerInput(shortcut) {
        var pressed: PointerId? = null
        var pressedAt = Offset.Zero
        var lastClickAt = 0L
        var lastClickPosition = Offset.Zero
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> {
                        pressed = null
                        if (!event.buttons.isPrimaryPressed) continue
                        val modifiers = event.keyboardModifiers
                        val action = when {
                            modifiers.isShiftPressed -> extend
                            shortcut.isPressed(modifiers) -> toggle
                            else -> null
                        }
                        if (action != null) {
                            event.changes.forEach { it.consume() }
                            action()
                        } else if (change.type == PointerType.Mouse && doubleClick != null) {
                            pressed = change.id
                            pressedAt = change.position
                        }
                    }
                    PointerEventType.Release -> {
                        if (change.id != pressed) continue
                        pressed = null
                        val open = doubleClick ?: continue
                        // 挪过了就是拖动或框选，不算一次点击
                        if (change.isConsumed || (change.position - pressedAt).getDistance() > viewConfiguration.touchSlop) continue
                        change.consume()
                        val now = change.uptimeMillis
                        val isDouble = now - lastClickAt <= viewConfiguration.doubleTapTimeoutMillis &&
                            (change.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop
                        if (isDouble) {
                            // 清零：连点三下是一次双击加一次单击，不是两次双击
                            lastClickAt = 0L
                            open()
                        } else {
                            lastClickAt = now
                            lastClickPosition = change.position
                        }
                    }
                }
            }
        }
    }
}
