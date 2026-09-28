package dev.piko.ui.screens.transfers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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
 * 条目的鼠标点选，照网盘页与资源管理器：单击选中这一项，主修饰键加选，Shift 连选，双击执行主操作。
 * 只截鼠标：触屏的轻点与长按照旧交给条目自己（轻点执行、长按进多选），触屏上没有修饰键可言。
 * 在 Initial 阶段截下并消费，条目自己的点击就不会同时触发。
 *
 * 不用组件库的 selectionClicks：它的普通单击什么也不做（网盘页靠条目取得焦点来「选中」），
 * 这里的选中要落进 TransfersState 的选中集，页头才能据此换成批量操作。
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
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    val select by rememberUpdatedState(onSelect)
    val toggle by rememberUpdatedState(onToggle)
    val extend by rememberUpdatedState(onExtend)
    val open by rememberUpdatedState(onOpen)
    return pointerInput(shortcut, trailingPassThrough) {
        var lastClickAt = 0L
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (event.type != PointerEventType.Press || change.type != PointerType.Mouse || !event.buttons.isPrimaryPressed) continue
                // 行宽随窗口变，每次按下时现量
                if (change.position.x >= size.width - trailingPassThrough.toPx()) continue
                event.changes.forEach { it.consume() }
                val modifiers = event.keyboardModifiers
                when {
                    modifiers.isShiftPressed -> extend()
                    shortcut.isPressed(modifiers) -> toggle()
                    change.uptimeMillis - lastClickAt <= viewConfiguration.doubleTapTimeoutMillis -> {
                        // 清零：连点三下是一次双击加一次单击
                        lastClickAt = 0L
                        open()
                    }
                    else -> {
                        lastClickAt = change.uptimeMillis
                        select()
                    }
                }
            }
        }
    }
}

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
