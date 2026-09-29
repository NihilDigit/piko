package dev.piko.ui.platform

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 标题栏并进内容时（照 Chrome）由桌面端提供：不再有单独的一条标题栏，窗口按钮由贴着窗口右上角的那一行
 * 画在自己末尾，拖动窗口靠内容里登记的空白处（windowDragArea）。没有这种标题栏时为 null。
 *
 * 按钮不浮在窗口角上：各行高度不同（标签栏、地址栏那一行、各页顶栏、侧栏栏名），浮着的按钮只能贴顶，
 * 与哪一行的图标都对不齐。
 */
interface WindowCaption {
    /**
     * 界面声明自己放得下窗口按钮与拖动区，在组合里待多久就算多久。没有谁声明时窗口仍画单独的标题栏：
     * 登录页这类没有侧边栏的界面上找不到能拖的空白，按钮也没有哪一行来画。
     */
    @Composable
    fun Host()

    /** 三个窗口按钮。画在哪里由调用方决定，位置自己报给窗口过程。 */
    @Composable
    fun Buttons()

    /** 登记一块能拖动窗口的区域（窗口坐标），[bounds] 为 null 时撤掉。同一个 [key] 后登记的替换先登记的。 */
    fun setDragArea(key: Any, bounds: Rect?)

    /**
     * 鼠标左键正按着时调用：把这一次按下交给系统，当作按在标题栏上，之后的拖动、贴靠与拖离最大化都由系统处理。
     * 系统接走后内容收不到原本的松开，窗口过程事后补发一次，见 [dragWindowOnLongPress]。
     */
    fun beginWindowDrag()
}

/**
 * 按住不动一会儿就改成拖动窗口，给本身能点的控件用（地址栏）：点一下照常，按住等 [LongPressMs] 再动是拖窗口。
 * 地址栏几乎占满顶栏，只剩两头的空白能拖，窗口不好抓。
 *
 * 只认鼠标左键；按住期间挪出了阈值就放弃，那是别的拖动（选文字、拖放）。交给系统后补发的松开在 Initial 阶段吃掉，
 * 里面的可点击项看到的是已消费的松开，不当作一次单击。
 */
@Composable
fun Modifier.dragWindowOnLongPress(): Modifier {
    val caption = LocalWindowCaption.current ?: return this
    return pointerInput(caption) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            // 到时之前松开或挪动了就有值，按住不动到时是 null
            val endedEarly = withTimeoutOrNull(LongPressMs) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (endedEarly != null) return@awaitEachGesture
            caption.beginWindowDrag()
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
        }
    }
}

private const val LongPressMs = 400L

val LocalWindowCaption = compositionLocalOf<WindowCaption?> { null }

/**
 * 一行内容是否要画窗口按钮：挂上 [modifier] 的那一行若从窗口顶上开始、右沿贴着窗口右沿，[buttons] 不为 null，
 * 放在这一行的末尾。
 *
 * 按位置判断而不是由各页声明：同一页在有没有右侧面板时，贴着右上角的是不同的行，只有布局知道。
 * 按钮画在这一行里面，这一行自己的边界不随之变化，判断不会来回翻。
 */
class CaptionSlot(val modifier: Modifier, val buttons: (@Composable () -> Unit)?)

@Composable
fun rememberCaptionSlot(): CaptionSlot {
    val caption = LocalWindowCaption.current ?: return NoCaptionSlot
    val windowWidth = LocalWindowInfo.current.containerSize.width
    var touches by remember { mutableStateOf(false) }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        // 容一个像素：边界按浮点算，贴边的行可能差零点几
        touches = bounds.right >= windowWidth - 1f && bounds.top <= 1f
    }
    return CaptionSlot(
        modifier,
        if (touches) {
            {
                // 按钮前面明摆着留一段空白给拖动：行里的控件一多，别处的空白就不好找，Chrome 标签后面也留着这一截。
                // 高度写死与按钮同高，不用 fillMaxHeight：侧栏栏名那一行在竖排的列里没有定高，填满高度会把它撑成整列高，
                // 栏里的内容被挤没（实测）
                Spacer(Modifier.width(CaptionDragGap).height(CaptionDragHeight).windowDragArea())
                caption.Buttons()
            }
        } else {
            null
        },
    )
}

private val NoCaptionSlot = CaptionSlot(Modifier, null)

private val CaptionDragGap = 48.dp
private val CaptionDragHeight = 40.dp
