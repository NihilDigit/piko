package dev.piko.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    /** [bounds] 为 null 表示这块区域已不在界面上。 */
    fun updateDragArea(bounds: Rect?)

    fun close()

    /** 窗口是否置顶。读的是 Compose 状态，切换后按钮跟着重组。 */
    val isAlwaysOnTop: Boolean

    fun setAlwaysOnTop(onTop: Boolean)
}

/** 桌面端的播放窗口提供；Android 与带标题栏的窗口为 null。 */
val LocalFramelessWindow = staticCompositionLocalOf<FramelessWindow?> { null }

/**
 * 把所在元素报成窗口拖动区。元素离开组合时一并撤销：控件栏收起后那块画面要回到点一下暂停，
 * 而不是按住就拖走窗口。
 */
@Composable
fun Modifier.windowDragArea(): Modifier {
    val window = LocalFramelessWindow.current ?: return this
    DisposableEffect(window) { onDispose { window.updateDragArea(null) } }
    return onGloballyPositioned { window.updateDragArea(it.boundsInWindow()) }
}
