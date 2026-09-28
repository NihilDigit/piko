package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
    // Window 每次重组都从这个 CompositionLocal 重新取处理器，只能在它外面提供
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides PikoWindowExceptionHandlerFactory) {
        // 显卡设备失效后整个窗口丢掉重建，新窗口建新设备，见 GpuDeviceWatch。窗口里 remember 的状态随之重置，
        // 位置与大小在 state 里，不受影响
        key(GpuDeviceWatch.generation) {
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
 */
@OptIn(ExperimentalComposeUiApi::class)
private object PikoWindowExceptionHandlerFactory : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { throwable ->
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
