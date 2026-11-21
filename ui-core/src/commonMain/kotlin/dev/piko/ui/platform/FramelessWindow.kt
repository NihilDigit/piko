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

    /** 登记一块交互控件排除区（窗口坐标）：命中此处一律答 HTCLIENT，优先于任何 [updateDragArea]。 */
    fun updateExclusion(key: Any, bounds: Rect?) = Unit

    /** 模态弹层打开期间临时清空拖动区命中，弹层离开组合时自动恢复。 */
    @Composable
    fun ModalBlocker() = Unit

    fun close()

    /** 窗口是否置顶。读的是 Compose 状态，切换后按钮跟着重组。 */
    val isAlwaysOnTop: Boolean

    fun setAlwaysOnTop(onTop: Boolean)
}

/** 桌面端的播放窗口、看图窗口与信息流窗口提供；Android 与带标题栏的窗口为 null。 */
val LocalFramelessWindow = staticCompositionLocalOf<FramelessWindow?> { null }

/**
 * 把所在元素报成窗口拖动区，双击最大化、右键出系统菜单。元素离开组合时一并撤销：控件栏收起后那块画面要回到
 * 点一下暂停，而不是按住就拖走窗口。只给没有控件的空白处用：落在这里的点击到不了内容。
 *
 * 两种窗口都认：播放与看图/信息流的无边框窗口（[LocalFramelessWindow]）与标题栏并进内容的主窗口（[LocalWindowCaption]），
 * 都可以有好几块，按元素区分。
 */
@Composable
fun Modifier.windowDragArea(enabled: Boolean = true): Modifier {
    val key = remember { Any() }
    val caption = LocalWindowCaption.current
    if (caption != null) {
        DisposableEffect(caption, enabled) {
            if (!enabled) caption.setDragArea(key, null)
            onDispose { caption.setDragArea(key, null) }
        }
        return if (enabled) {
            onGloballyPositioned { coordinates ->
                caption.setDragArea(key, if (coordinates.isAttached) coordinates.boundsInWindow() else null)
            }
        } else {
            this
        }
    }
    val window = LocalFramelessWindow.current ?: return this
    DisposableEffect(window, enabled) {
        if (!enabled) window.updateDragArea(key, null)
        onDispose { window.updateDragArea(key, null) }
    }
    return if (enabled) {
        onGloballyPositioned { coordinates ->
            window.updateDragArea(key, if (coordinates.isAttached) coordinates.boundsInWindow() else null)
        }
    } else {
        this
    }
}

/**
 * 将交互控件标记为标题栏非客户区排除区（CaptionExclusion）。
 * 在 Windows 紧凑标题栏主窗口或无边框独立媒体窗口中，凡落在排除区内的指针命中测试均优先返回 HTCLIENT，
 * 确保顶栏返回键、搜索按钮与右侧操作按钮绝不被相邻或底层的 HTCAPTION 拖动区吞噬。
 */
@Composable
fun Modifier.captionExclusion(enabled: Boolean = true): Modifier {
    val key = remember { Any() }
    val caption = LocalWindowCaption.current
    if (caption != null) {
        DisposableEffect(caption, enabled) {
            if (!enabled) caption.setExclusion(key, null)
            onDispose { caption.setExclusion(key, null) }
        }
        return if (enabled) {
            onGloballyPositioned { coordinates ->
                caption.setExclusion(key, if (coordinates.isAttached) coordinates.boundsInWindow() else null)
            }
        } else {
            this
        }
    }
    val window = LocalFramelessWindow.current ?: return this
    DisposableEffect(window, enabled) {
        if (!enabled) window.updateExclusion(key, null)
        onDispose { window.updateExclusion(key, null) }
    }
    return if (enabled) {
        onGloballyPositioned { coordinates ->
            window.updateExclusion(key, if (coordinates.isAttached) coordinates.boundsInWindow() else null)
        }
    } else {
        this
    }
}

/**
 * 声明当前存在顶层模态浮层（对话框、全屏弹层、侧边面板、菜单或遮罩）：
 * 组合期间自动挂起底层的 HTCAPTION 拖动区与并进内容的窗口按钮命中区，避免模态层顶部交互被 Win32 非客户区劫持。
 */
@Composable
fun ModalCaptionBlocker(enabled: Boolean = true) {
    if (!enabled) return
    LocalWindowCaption.current?.ModalBlocker()
    LocalFramelessWindow.current?.ModalBlocker()
}
