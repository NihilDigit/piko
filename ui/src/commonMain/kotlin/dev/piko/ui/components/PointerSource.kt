package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 最近一次输入是否为可以「拉」的一类（手指、触控笔）。交互约定按输入设备区分，不按平台或窗口宽度：
 * 触屏笔记本上的手指应当能下拉刷新，接了鼠标的平板上的滚轮则不应当。
 *
 * 只在类别切换时写状态，否则每个指针事件都触发一次重组。尚未见过任何指针时按触摸算，
 * 那是纯触屏设备的初始状态。
 */
class PointerSource {
    var isTouchLike: Boolean by mutableStateOf(true)
        private set

    internal fun observe(type: PointerType) {
        val touchLike = type == PointerType.Touch || type == PointerType.Stylus || type == PointerType.Unknown
        if (touchLike != isTouchLike) isTouchLike = touchLike
    }
}

val LocalPointerSource = staticCompositionLocalOf { PointerSource() }

/**
 * 挂在窗口根部，在 Initial 阶段只看不消费。进出事件不计：手指操作时它们也可能以鼠标类型到达，
 * 会把来源记错。
 *
 * 弹出层（ModalBottomSheet、Dialog）的事件不经过根部，里面的输入要另挂一次，见 [wheelStaysInSheet]。
 */
fun Modifier.trackPointerSource(source: PointerSource): Modifier = pointerInput(source) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Exit) continue
            event.changes.firstOrNull()?.let { source.observe(it.type) }
        }
    }
}

/**
 * 挂在 ModalBottomSheet 的内容根部：列表滚到头后剩余的滚轮位移不再交给 sheet。
 * sheet 把嵌套滚动的剩余量当作拖动自己，滚轮没有松手这一步，sheet 不会吸附到任何一档，
 * 表现为向上滚过头时 sheet 被一格一格拖下去，停在半路。手指照旧交上去：滚到顶再下拉就是关闭。
 */
@Composable
fun Modifier.wheelStaysInSheet(): Modifier {
    val pointers = LocalPointerSource.current
    val connection = remember(pointers) { WheelStaysInSheet(pointers) }
    return trackPointerSource(pointers).nestedScroll(connection)
}

private class WheelStaysInSheet(private val pointers: PointerSource) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && !pointers.isTouchLike) available else Offset.Zero
}
