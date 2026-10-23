package dev.piko.ui.screens.transfers

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 行的多选状态，交给 FileListItem：[active] 时各行画复选框、轻点即勾选（触屏的多选），[onLongClick] 长按进多选。
 */
internal class RowSelection(
    val active: Boolean,
    val selected: Boolean,
    val onToggle: () -> Unit,
    val onLongClick: (() -> Unit)?,
) {
    companion object {
        val None = RowSelection(active = false, selected = false, onToggle = {}, onLongClick = null)
    }
}

/**
 * 条目的鼠标点选，照网盘页与资源管理器：单击选中或打开详情，主修饰键加选，Shift 连选，双击执行主操作。
 * 只截鼠标：触屏的轻点与长按照旧交给条目自己（轻点执行；长按在移动端进多选，在桌面弹右键菜单），触屏上没有修饰键可言。
 *
 * 修饰键点选在按下时立即消费；普通单击在松开时判定（与 [dev.piko.ui.components.selectionClicks] 一致）：
 * 按下不截走、也不立即触发 [onSelect]，这样在条目上按住鼠标左键拖动拉框多选（marqueeSelection）时，
 * 不会在按下瞬间误触详情面板，松开时若已发生框选拖动则消费掉松开事件，不再触发条目的单击。
 *
 * 行尾 [trailingPassThrough] 宽的一段不截：那里是行自己的按钮（更多、暂停这些），在 Initial 阶段截下的话
 * 按钮先于子元素被拦住，永远点不到。
 */
@Composable
internal fun Modifier.transferClicks(
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onExtend: () -> Unit,
    onOpen: () -> Unit,
    trailingPassThrough: Dp,
    leadingPassThrough: Dp = 0.dp,
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val select by rememberUpdatedState(onSelect)
    val toggle by rememberUpdatedState(onToggle)
    val extend by rememberUpdatedState(onExtend)
    val open by rememberUpdatedState(onOpen)
    return pointerInput(shortcut, trailingPassThrough, leadingPassThrough) {
        var pressed: PointerId? = null
        var pressedAt = Offset.Zero
        var dragged = false
        var lastClickAt = 0L
        var lastClickPosition = Offset.Zero
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Press -> {
                        pressed = null
                        dragged = false
                        if (!event.buttons.isPrimaryPressed) continue
                        // 行宽随窗口变，每次按下时现量
                        if (change.position.x >= size.width - trailingPassThrough.toPx()) continue
                        val modifiers = event.keyboardModifiers
                        val action = when {
                            modifiers.isShiftPressed -> extend
                            shortcut.isPressed(modifiers) -> toggle
                            else -> null
                        }
                        if (action != null) {
                            event.changes.forEach { it.consume() }
                            lastClickAt = 0L
                            action()
                        } else if (change.position.x >= leadingPassThrough.toPx()) {
                            pressed = change.id
                            pressedAt = change.position
                        }
                    }
                    PointerEventType.Move -> {
                        if (change.id == pressed && (change.isConsumed || (change.position - pressedAt).getDistance() > ItemMarqueeSlopDp.dp.toPx())) {
                            dragged = true
                        }
                    }
                    PointerEventType.Release -> {
                        if (change.id != pressed) continue
                        val wasDragged = dragged || change.isConsumed || (change.position - pressedAt).getDistance() > ItemMarqueeSlopDp.dp.toPx()
                        pressed = null
                        dragged = false
                        change.consume()
                        if (wasDragged) {
                            lastClickAt = 0L
                            continue
                        }
                        val now = change.uptimeMillis
                        val isDouble = now - lastClickAt <= viewConfiguration.doubleTapTimeoutMillis &&
                            (change.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop
                        if (isDouble) {
                            // 清零：连点三下是一次双击加一次单击
                            lastClickAt = 0L
                            open()
                        } else {
                            lastClickAt = now
                            lastClickPosition = change.position
                            select()
                        }
                    }
                }
            }
        }
    }
}

private const val ItemMarqueeSlopDp = 4

/** 剩余时间，按眼下的速度估：「3 分 20 秒」「1 小时 5 分」。速度为 0 或剩余未知时不给，免得报出无穷大。 */
internal fun remainingTime(remainingBytes: Long?, speed: Long): String? {
    if (remainingBytes == null || remainingBytes <= 0 || speed <= 0) return null
    val seconds = remainingBytes / speed
    return when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} 秒"
        seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        seconds < 86_400 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
        else -> "超过一天"
    }
}

/** 行尾按钮所占的宽度：一个快捷按钮加更多，各 48dp；transferClicks 在这一段不截点击。 */
internal val NarrowRowTrailingWidth = 96.dp
