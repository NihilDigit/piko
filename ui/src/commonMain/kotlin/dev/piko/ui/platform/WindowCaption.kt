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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.DisposableEffect
import dev.piko.ui.components.isReleaseConsumed

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
     * 登记一块标题栏以下、仍算标题栏的区域（窗口坐标），[bounds] 为 null 时撤掉。开了标签时地址栏那一行在标签栏下面，
     * 不在顶上那一行里，见 [captionGestures]。
     */
    fun setHoldArea(key: Any, bounds: Rect?)

    /** [position]（窗口坐标）是否落在 [setHoldArea] 登记的区域里。 */
    fun inHoldArea(position: Offset): Boolean

    /**
     * 鼠标左键正按着时调用：把这一次按下交给系统，当作按在标题栏上，之后的拖动、贴靠与拖离最大化都由系统处理。
     * 系统接走后内容收不到原本的松开，窗口过程事后补发一次，见 [captionGestures]。
     */
    fun beginWindowDrag()

    /** 最大化与还原之间切换，与双击系统标题栏相同。 */
    fun toggleMaximize()
}

/**
 * 把所在元素登记成标题栏的一部分：长按拖动窗口、空白处双击最大化，与顶上那一行相同。只给顶上那一行以外的行用
 * （开了标签时的地址栏那一行），顶上那一行本来就算。
 */
@Composable
fun Modifier.windowHoldArea(): Modifier {
    val key = remember { Any() }
    val caption = LocalWindowCaption.current ?: return this
    DisposableEffect(caption) { onDispose { caption.setHoldArea(key, null) } }
    return onGloballyPositioned { caption.setHoldArea(key, it.boundsInWindow()) }
}

/**
 * 标题栏并进内容时，挂在窗口内容的根上，让自绘的标题栏整条都像系统标题栏：
 * - 长按：按住不动 [LongPressMs] 就交给系统拖动窗口，按在能点的控件上也一样。标题栏里的控件一多（地址栏几乎占满），
 *   登记成拖动区的空白就不好找。按住期间挪出了阈值就放弃，那是别的拖动（选文字、拖放）。交给系统后补发的松开
 *   在 Initial 阶段吃掉，控件看到的是已消费的松开，不当作一次单击。
 * - 双击：两次松开都没被控件接走（Final 阶段仍未消费）才算点在空白上，切换最大化。登记成拖动区的空白由系统
 *   直接答 HTCAPTION，事件到不了这里，双击本来就由系统处理；这里管的是标题文字这类没登记、也不可点的地方。
 *
 * 标题栏的范围是窗口顶上 [stripHeight] 高的一条，加上 [WindowCaption.setHoldArea] 登记的区域。只认鼠标左键。
 */
fun Modifier.captionGestures(caption: WindowCaption, stripHeight: Dp): Modifier = pointerInput(caption, stripHeight) {
    var lastClick: Pair<Long, Offset>? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        if (down.position.y >= stripHeight.toPx() && !caption.inHoldArea(down.position)) {
            lastClick = null
            return@awaitEachGesture
        }
        var release: PointerInputChange? = null
        // 到时之前松开或挪动了就有值，按住不动到时是 null
        val endedEarly = withTimeoutOrNull(LongPressMs) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (!change.pressed) {
                    release = change
                    break
                }
            }
            true
        }
        if (endedEarly == null) {
            lastClick = null
            caption.beginWindowDrag()
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
            return@awaitEachGesture
        }
        val up = release
        if (up == null || isReleaseConsumed(down.id)) {
            lastClick = null
            return@awaitEachGesture
        }
        val previous = lastClick
        val isDouble = previous != null &&
            up.uptimeMillis - previous.first <= viewConfiguration.doubleTapTimeoutMillis &&
            (up.position - previous.second).getDistance() <= viewConfiguration.touchSlop
        if (isDouble) {
            lastClick = null
            caption.toggleMaximize()
        } else {
            lastClick = up.uptimeMillis to up.position
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
