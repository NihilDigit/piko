package dev.piko.desktop

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import java.lang.ref.WeakReference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Compose 的 [Window]，界面出错时按单窗口隔离自愈或销毁，见 [DesktopWindowFaultCoordinator]。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PikoWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
    title: String,
    icon: Painter?,
    visible: Boolean = true,
    onFatalClose: () -> Unit = onCloseRequest,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val currentFatalClose by rememberUpdatedState(onFatalClose)
    val windowScope = remember { desktopWindowFaultCoordinator.newWindowScope() }
    DisposableEffect(windowScope) {
        onDispose { windowScope.markClosed() }
    }
    val handlerFactory = remember(windowScope) {
        PikoWindowExceptionHandlerFactory(
            scope = windowScope,
            onFatalClose = { currentFatalClose() },
        )
    }
    // Window 每次重组都从这个 CompositionLocal 重新取处理器，只能在它外面提供
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides handlerFactory) {
        // 显卡设备失效或未归属 EDT 异常走全局代次；已知所属窗口的故障仅递增该窗口自己的 windowScope.generation，
        // 避免主窗口异常把正在连播的独立播放器窗口重置回初始集，反之亦然。
        key(GpuDeviceWatch.generation, desktopWindowFaultCoordinator.generation, windowScope.generation) {
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
 * 单个窗口（或全局未归属通道）的故障恢复与终止状态机：
 * - 同一代次最多排队一次恢复，防止同帧连发多条异常重复消耗恢复配额；
 * - 一旦进入 [Phase.CLOSING] 终态（或正常关闭进入 [Phase.CLOSED]），后续异常、模态提示框嵌套事件循环期间的新异常、
 *   以及此前排在队列中的旧恢复回调全部失效，确保错误框与 `onFatalClose` 至多执行一次。
 */
internal class WindowFaultScope internal constructor(
    private val coordinator: DesktopWindowFaultCoordinator,
) {
    private enum class Phase { ACTIVE, CLOSING, CLOSED }

    var generation by mutableIntStateOf(0)
        private set
    private var phase = Phase.ACTIVE
    private var queuedGeneration = -1
    private val recoveries = ArrayDeque<Long>()

    internal fun reset() {
        phase = Phase.ACTIVE
        queuedGeneration = -1
        recoveries.clear()
    }

    fun markClosed() = coordinator.withLock {
        phase = Phase.CLOSED
        queuedGeneration = -1
    }

    internal fun handleLocked(
        throwable: Throwable,
        now: Long,
        onFatalClose: () -> Unit,
    ) {
        if (phase != Phase.ACTIVE) return
        while (recoveries.isNotEmpty() && now - recoveries.first() >= 15_000L) recoveries.removeFirst()
        val unrecoverable = generateSequence(throwable) { it.cause }.take(32)
            .any { it is VirtualMachineError || it is LinkageError }
        val currentGen = generation
        if (!unrecoverable && throwable.isDisposedLayerRace()) {
            coordinator.log(throwable)
            if (queuedGeneration == currentGen) return
            queuedGeneration = currentGen
            coordinator.dispatch {
                coordinator.withLock {
                    if (phase == Phase.ACTIVE && generation == currentGen) {
                        queuedGeneration = -1
                        generation++
                    }
                }
            }
        } else if (!unrecoverable && throwable is RuntimeException && (queuedGeneration == currentGen || recoveries.size < 2)) {
            coordinator.log(throwable)
            if (queuedGeneration == currentGen) return
            recoveries.addLast(now)
            queuedGeneration = currentGen
            coordinator.dispatch {
                coordinator.withLock {
                    if (phase == Phase.ACTIVE && generation == currentGen) {
                        queuedGeneration = -1
                        generation++
                    }
                }
            }
        } else {
            phase = Phase.CLOSING
            queuedGeneration = -1
            PikoLog.e("Window", "界面故障无法自动恢复，关闭故障窗口", throwable)
            coordinator.dispatch {
                val shouldRun = coordinator.withLock {
                    if (phase == Phase.CLOSED) false
                    else {
                        phase = Phase.CLOSED
                        true
                    }
                }
                if (shouldRun) onFatalClose()
            }
        }
    }
}

/** 双入口共享异常引用去重；单窗口故障按 [WindowFaultScope] 独立限流，未归属 EDT 故障走兜底全局作用域。 */
internal class DesktopWindowFaultCoordinator(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    internal val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    internal val log: (Throwable) -> Unit = {
        PikoLog.e("Window", "界面渲染或事件异常，自动重建窗口恢复出帧", it)
    },
) {
    private val handled = ArrayDeque<WeakReference<Throwable>>()
    private val globalScope = WindowFaultScope(this)

    val generation: Int get() = globalScope.generation

    fun newWindowScope(): WindowFaultScope = WindowFaultScope(this)

    @Synchronized
    internal fun <T> withLock(block: () -> T): T = block()

    @Synchronized
    internal fun resetForTest() {
        handled.clear()
        globalScope.reset()
    }

    @Synchronized
    fun report(
        throwable: Throwable,
        isUiThread: Boolean,
        onFatalClose: () -> Unit,
        scope: WindowFaultScope = globalScope,
    ): Boolean {
        if (!isUiThread && !throwable.isDisposedLayerRace()) return false
        if (handled.any { it.get() === throwable }) return true
        handled.addLast(WeakReference(throwable))
        while (handled.size > 64) handled.removeFirst()
        scope.handleLocked(throwable, nowMillis(), onFatalClose)
        return true
    }
}

internal val desktopWindowFaultCoordinator = DesktopWindowFaultCoordinator()

/** 不再投递 WINDOW_CLOSING，避免主窗口关闭策略把故障窗口隐藏至托盘。 */
@OptIn(ExperimentalComposeUiApi::class)
private class PikoWindowExceptionHandlerFactory(
    private val scope: WindowFaultScope,
    private val onFatalClose: () -> Unit,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { throwable ->
        desktopWindowFaultCoordinator.report(
            throwable,
            isUiThread = true,
            scope = scope,
            onFatalClose = { showWindowFault(window, onFatalClose) },
        )
    }
}

internal fun showWindowFault(window: java.awt.Window?, onFatalClose: () -> Unit) {
    try {
            JOptionPane.showMessageDialog(
                window?.takeIf { it.isDisplayable },
                tr("Piko 发生错误，当前窗口即将关闭。\n反馈问题时，可在「设置」→「关于」→「导出日志」中导出日志并附上。", "Piko encountered an error and this window must close.\nWhen reporting the issue, attach logs exported from Settings → About → Export logs.", "エラーが発生したため、このウィンドウを閉じます。\n問題を報告する際は、「設定」→「このアプリについて」→「ログをエクスポート」からログを添付してください。", "Piko에 오류가 발생하여 이 창을 닫아야 합니다.\n문제를 제보할 때 '설정' → '정보' → '로그 내보내기'에서 내보낸 로그를 첨부해 주세요.", "Piko 發生錯誤，目前視窗即將關閉。\n回報問題時，可於「設定」→「關於」→「匯出日誌」中匯出日誌並一併附上。"),
                "Piko",
                JOptionPane.ERROR_MESSAGE,
            )
    } finally {
        // 即使原生提示框失败也不能保留停止出帧的窗口。
        try { window?.dispose() } finally { onFatalClose() }
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
