package dev.piko.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 没有标题栏的桌面窗口（播放窗口）交给内容的两件事：哪块区域兼做拖动窗口，以及关窗。
 * 窗口过程按拖动区答「标题栏」，拖动、双击最大化与贴靠交给系统。
 */
interface FramelessWindow {
    /**
     * 登记一块拖动区，[key] 区分各块，[bounds] 为 null 表示这块已不在界面上。可以有好几块：
     * 按钮夹在中间的顶栏，按钮两边的空白都要能拖。
     */
    fun updateDragArea(key: Any, bounds: Rect?)

    fun close()

    /** 窗口是否置顶。读的是 Compose 状态，切换后按钮跟着重组。 */
    val isAlwaysOnTop: Boolean

    fun setAlwaysOnTop(onTop: Boolean)
}

/** 桌面端的播放窗口与随机片段窗口提供；Android 与带标题栏的窗口为 null。 */
val LocalFramelessWindow = staticCompositionLocalOf<FramelessWindow?> { null }

/**
 * 把所在元素报成窗口拖动区，双击最大化、右键出系统菜单。元素离开组合时一并撤销：控件栏收起后那块画面要回到
 * 点一下暂停，而不是按住就拖走窗口。只给没有控件的空白处用：落在这里的点击到不了内容。
 *
 * 两种窗口都认：播放与信息流的无边框窗口（[LocalFramelessWindow]）与标题栏并进内容的主窗口（[LocalWindowCaption]），
 * 都可以有好几块，按元素区分。
 */
@Composable
fun Modifier.windowDragArea(): Modifier {
    val key = remember { Any() }
    val caption = LocalWindowCaption.current
    if (caption != null) {
        DisposableEffect(caption) { onDispose { caption.setDragArea(key, null) } }
        return onGloballyPositioned { caption.setDragArea(key, it.boundsInWindow()) }
    }
    val window = LocalFramelessWindow.current ?: return this
    DisposableEffect(window) { onDispose { window.updateDragArea(key, null) } }
    return onGloballyPositioned { window.updateDragArea(key, it.boundsInWindow()) }
}
