package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.delay
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowState
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Compose 的 [Window]，界面出错时换成 Piko 自己的提示，见 [PikoWindowExceptionHandlerFactory]。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PikoWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
    title: String,
    icon: Painter?,
    visible: Boolean = true,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    // 这一个窗口自己的重建次数，见 PikoWindowExceptionHandlerFactory
    var rebuilds by remember { mutableIntStateOf(0) }
    val handlerFactory = remember { PikoWindowExceptionHandlerFactory(onDisposedLayer = { rebuilds++ }) }
    // Window 每次重组都从这个 CompositionLocal 重新取处理器，只能在它外面提供
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides handlerFactory) {
        // 显卡设备失效后整个窗口丢掉重建，新窗口建新设备，见 GpuDeviceWatch。窗口里 remember 的状态随之重置，
        // 位置与大小在 state 里，不受影响
        key(GpuDeviceWatch.generation, rebuilds) {
            Window(onCloseRequest = onCloseRequest, state = state, visible = visible, title = title, icon = icon) {
                LaunchedEffect(window) {
                    while (true) {
                        delay(DEVICE_CHECK_INTERVAL_MS)
                        if (GpuDeviceWatch.checkLost(window)) break
                    }
                }
                content()
            }
        }
    }
}

// 画面冻住到用户去拉窗口之间，这么久足够先一步发现；一次检查是一次反射加一次虚表调用
private const val DEVICE_CHECK_INTERVAL_MS = 1_000L

/**
 * 与 Compose 默认的处理相同：关掉出错的窗口，异常照旧抛出，由进程的崩溃处理写进日志。只把提示换掉：
 * 默认的对话框只有一行英文类名，用户既看不懂，也不知道该做什么。
 *
 * 不留着窗口继续用：异常可能出在组合或布局中途，界面状态已经不可信。
 *
 * 唯一的例外是弹层在测量途中被销毁（[isDisposedLayerRace]），改为重建这个窗口（[onDisposedLayer]）。
 */
@OptIn(ExperimentalComposeUiApi::class)
private class PikoWindowExceptionHandlerFactory(
    private val onDisposedLayer: () -> Unit,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { throwable ->
        if (throwable.isDisposedLayerRace()) {
            PikoLog.w("Window", "弹层在测量途中被销毁，重建窗口", throwable)
            SwingUtilities.invokeLater(onDisposedLayer)
            return@WindowExceptionHandler
        }
        // 与默认实现一样推迟到下一轮事件：对话框是阻塞的，不能在出错的这次分发里弹
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                window.takeIf { it.isDisplayable },
                "Piko 出现错误，这个窗口需要关闭。\n反馈问题时，请在「设置」→「关于」→「导出日志」中导出日志一并附上。",
                "Piko",
                JOptionPane.ERROR_MESSAGE,
            )
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
        throw throwable
    }
}

/**
 * Compose 的桌面场景每帧先拷一份「主层加各弹层」的列表再逐个测量（CanvasLayersComposeSceneImpl.doMeasureAndLayout）。
 * 测主层时，Scaffold 的各槽位、命令栏、BoxWithConstraints 这些在测量时才组合的内容会就地重组，
 * 这时一个弹层（菜单、提示、对话框）若离开组合就当场被关闭销毁，却还在刚拷的列表里，轮到它时抛出这一个异常，
 * 出帧的协程随之结束。改窗口尺寸时的那次测量不等下一帧、立刻执行，把挂着的重组一并做掉，最容易撞上：
 * 开着命令栏的下拉菜单往下拖窗口时出过（2026-09-29）。
 *
 * 弹层的创建与关闭经 Compose 的场景上下文，那是 internal，推迟不了关闭；出帧协程已死，吞掉异常窗口就冻住。
 * 只能认出它、重建窗口，与显卡设备失效同一条路（GpuDeviceWatch）。认的是 Compose 的 require 文案，升级 Compose 时核对。
 */
private fun Throwable.isDisposedLayerRace(): Boolean =
    this is IllegalArgumentException && message == "RootNodeOwner is already disposed"
