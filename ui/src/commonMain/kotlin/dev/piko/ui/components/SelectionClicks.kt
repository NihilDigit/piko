package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
    /** 条目里自己接点击的控件登记的范围，按在这里的一下不截，见 [OwnClicks]。 */
    ownClicks: OwnClicks? = null,
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val toggle by rememberUpdatedState(onToggle)
    val extend by rememberUpdatedState(onExtend)
    val doubleClick by rememberUpdatedState(onDoubleClick)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(shortcut, ownClicks) {
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
                        if (ownClicks?.contains(origin + change.position) == true) continue
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

/**
 * 条目里自己接点击的控件（详情按钮）的范围，按窗口坐标记。[selectionClicks] 在 Initial 阶段截走条目上的单击，
 * Initial 由外向内传，里面的按钮收到时松开已被消费，点击判为取消：条目上的三点按钮用鼠标从来点不动。
 * 按钮经 [ownsClicks] 登记到所在条目，那一下就交给它。
 */
class OwnClicks {
    private val areas = mutableMapOf<Any, Rect>()

    internal fun put(key: Any, bounds: Rect) {
        areas[key] = bounds
    }

    internal fun remove(key: Any) {
        areas.remove(key)
    }

    fun contains(point: Offset): Boolean = areas.values.any { it.contains(point) }
}

/** 所在条目的 [OwnClicks]，由条目提供；不在条目里时为 null，[ownsClicks] 什么也不做。 */
val LocalOwnClicks = staticCompositionLocalOf<OwnClicks?> { null }

/** 把这个控件登记为自己接点击，见 [OwnClicks]。 */
@Composable
fun Modifier.ownsClicks(): Modifier {
    val owner = LocalOwnClicks.current ?: return this
    val key = remember { Any() }
    DisposableEffect(owner) { onDispose { owner.remove(key) } }
    return onGloballyPositioned { owner.put(key, it.boundsInRoot()) }
}
