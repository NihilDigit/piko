package dev.piko.ui.platform

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
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
}

val LocalWindowCaption = compositionLocalOf<WindowCaption?> { null }

/**
 * 正拖着窗口边框改尺寸。这期间按尺寸换形态的判断停在拖动前，松手再换，由桌面端提供（Windows 的 WindowFrame）。
 * LocalWindowInfo 的尺寸在那里已经换成松手后的，这一项给自己量内容区宽度的地方（PikoMainScaffold 的 panelFits）用。
 */
val LocalWindowResizing = compositionLocalOf { false }

/**
 * 一行内容是否要画窗口按钮：挂上 [modifier] 的那一行若从窗口顶上开始、右沿贴着窗口右沿，[buttons] 不为 null，
 * 放在这一行的末尾。
 *
 * 按位置判断而不是由各页声明：同一页在有没有右侧面板时，贴着右上角的是不同的行，只有布局知道。
 * 按钮画在这一行里面，这一行自己的边界不随之变化，判断不会来回翻。
 */
class CaptionSlot(
    val modifier: Modifier,
    val buttons: (@Composable () -> Unit)?,
    /** 这一行从窗口顶上开始，属于并进内容的标题栏：行里的空白应当登记成拖动区。 */
    val atTop: Boolean = false,
)

/**
 * [topInset] 是这一行离窗口顶边允许的间距：没有标签的页，岛的上沿与侧边栏岛对齐、离窗口顶边留一道外框色，
 * 页眉那一行仍算贴着顶，窗口按钮画在它末尾（见 PikoScaffold 的 IslandScaffold）。
 */
@Composable
fun rememberCaptionSlot(topInset: Dp = 0.dp): CaptionSlot {
    val caption = LocalWindowCaption.current ?: return NoCaptionSlot
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val insetPx = with(LocalDensity.current) { topInset.toPx() }
    var touches by remember { mutableStateOf(false) }
    var atTop by remember { mutableStateOf(false) }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        // 容一个像素：边界按浮点算，贴边的行可能差零点几
        atTop = bounds.top <= insetPx + 1f
        touches = bounds.right >= windowWidth - 1f && atTop
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
        atTop,
    )
}

private val NoCaptionSlot = CaptionSlot(Modifier, null)

private val CaptionDragGap = 48.dp
private val CaptionDragHeight = 40.dp
