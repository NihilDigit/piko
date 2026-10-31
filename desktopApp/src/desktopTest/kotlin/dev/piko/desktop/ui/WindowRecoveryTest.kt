package dev.piko.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.launchApplication
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.DesktopWindowFaultCoordinator
import dev.piko.desktop.PikoWindow
import dev.piko.desktop.desktopWindowFaultCoordinator
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowRecoveryTest {
    private class Fixture {
        var now = 0L
        var fatalCloses = 0
        val pending = ArrayDeque<() -> Unit>()
        val coordinator = DesktopWindowFaultCoordinator({ now }, { pending.addLast(it) }, {})
        fun report(error: Throwable = IllegalStateException()) =
            coordinator.report(error, isUiThread = true, onFatalClose = { fatalCloses++ })
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
    }

    @Test
    fun `same throwable at window and default handler is counted once`() {
        val f = Fixture()
        val error = IllegalArgumentException()
        assertTrue(f.report(error))
        assertTrue(f.report(error))
        assertEquals(1, f.pending.size)
        assertEquals(0, f.coordinator.generation)
        f.drain()
        assertEquals(1, f.coordinator.generation)
        f.report()
        f.drain()
        assertEquals(2, f.coordinator.generation)
        assertEquals(0, f.fatalCloses)
    }

    @Test
    fun `first two failures rebuild and third closes fatally`() {
        val f = Fixture()
        repeat(2) { f.report(); f.drain(); assertEquals(it + 1, f.coordinator.generation) }
        f.report()
        f.drain()
        assertEquals(2, f.coordinator.generation)
        assertEquals(1, f.fatalCloses)
    }

    @Test
    fun `sliding window expires and permits recovery again`() {
        val f = Fixture()
        f.report()
        f.drain()
        f.now = 1_000L
        f.report()
        f.drain()
        assertEquals(2, f.coordinator.generation)
        assertEquals(0, f.fatalCloses)

        // 15 秒窗口过期后，最早一次恢复滑出窗口，仍存活的窗口可继续获得自愈额度
        f.now = 15_000L
        f.report()
        f.drain()
        assertEquals(3, f.coordinator.generation)
        assertEquals(0, f.fatalCloses)
    }

    @Test
    fun `linkage and virtual machine errors never rebuild`() {
        for (error in listOf(NoClassDefFoundError(), OutOfMemoryError(), IllegalStateException(NoClassDefFoundError()))) {
            val f = Fixture()
            f.report(error)
            f.drain()
            assertEquals(0, f.coordinator.generation)
            assertEquals(1, f.fatalCloses)
        }
    }

    @Test
    fun `disposed layer race always rebuilds and background errors are ignored`() {
        val f = Fixture()
        repeat(4) {
            f.report(IllegalArgumentException("RootNodeOwner is already disposed"))
            f.drain()
        }
        assertEquals(4, f.coordinator.generation)
        assertEquals(0, f.fatalCloses)
        assertFalse(f.coordinator.report(IllegalStateException(), isUiThread = false, onFatalClose = { f.fatalCloses++ }))
        assertTrue(f.pending.isEmpty())
    }

    @Test
    fun `same generation bursts coalesce and fatal terminal state blocks reentrant modals or late close`() {
        val f = Fixture()
        val scope = f.coordinator.newWindowScope()
        // 同一代次在队列尚未排空前连续爆发多个不同异常：只消耗 1 次恢复额度、只重建 1 次
        repeat(3) {
            f.coordinator.report(IllegalStateException("burst-$it"), isUiThread = true, scope = scope, onFatalClose = { f.fatalCloses++ })
        }
        assertEquals(1, f.pending.size)
        f.drain()
        assertEquals(1, scope.generation)
        assertEquals(0, f.fatalCloses)

        // 第二代恢复一次（满 2 次配额）
        f.coordinator.report(IllegalStateException("second-gen"), isUiThread = true, scope = scope, onFatalClose = { f.fatalCloses++ })
        f.drain()
        assertEquals(2, scope.generation)

        // 第三代进入 fatal：在队列排空前及排空期间（模拟模态框嵌套事件循环）继续上报异常，fatal 至多执行 1 次且不再重建
        var reentrantReported = false
        val onFatal = {
            f.fatalCloses++
            if (!reentrantReported) {
                reentrantReported = true
                f.coordinator.report(IllegalStateException("during-modal"), isUiThread = true, scope = scope, onFatalClose = { f.fatalCloses++ })
            }
        }
        f.coordinator.report(IllegalStateException("fatal-1"), isUiThread = true, scope = scope, onFatalClose = onFatal)
        f.coordinator.report(IllegalStateException("fatal-2"), isUiThread = true, scope = scope, onFatalClose = onFatal)
        f.drain()
        assertEquals(1, f.fatalCloses)
        assertEquals(2, scope.generation)

        // 窗口已正常关闭（markClosed）后，此前排在队列中的恢复或 fatal 回调不再执行
        val closingScope = f.coordinator.newWindowScope()
        f.coordinator.report(NoClassDefFoundError("fatal-before-close"), isUiThread = true, scope = closingScope, onFatalClose = { f.fatalCloses++ })
        closingScope.markClosed()
        f.drain()
        assertEquals(1, f.fatalCloses)
    }

    @org.junit.Test(timeout = 20_000L)
    fun `real PikoWindow rebuilds only the crashed window while keeping sibling windows intact`() {
        desktopWindowFaultCoordinator.resetForTest()
        val initialGlobalGen = desktopWindowFaultCoordinator.generation
        val firstRenderedA = CountDownLatch(1)
        val firstRenderedB = CountDownLatch(1)
        val rebuiltRenderedA = CountDownLatch(1)
        val postRecoveryFrameDrawnA = CountDownLatch(1)
        val postRecoveryFrameDrawnB = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)
        val firstWindowARef = AtomicReference<Window?>(null)
        val secondWindowARef = AtomicReference<Window?>(null)
        val firstWindowBRef = AtomicReference<Window?>(null)
        val latestWindowBRef = AtomicReference<Window?>(null)
        val siblingLocalStateValue = AtomicInteger(0)
        val siblingRecomposedCount = AtomicInteger(0)
        val throwOnce = AtomicBoolean(false)
        val fatalCount = AtomicInteger(0)
        var triggerStep by mutableIntStateOf(0)
        var requestExit: (() -> Unit)? = null

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            appScope.launchApplication {
                requestExit = ::exitApplication
                val stateA = rememberWindowState(position = WindowPosition(120.dp, 120.dp), size = DpSize(300.dp, 200.dp))
                val stateB = rememberWindowState(position = WindowPosition(450.dp, 120.dp), size = DpSize(300.dp, 200.dp))

                // 窗口 A：注入测量崩溃，验证局部重建
                PikoWindow(
                    onCloseRequest = ::exitApplication,
                    onFatalClose = { fatalCount.incrementAndGet(); exitApplication() },
                    state = stateA,
                    title = "Piko Recovery Window A",
                    icon = null,
                    visible = true,
                ) {
                    val step = triggerStep
                    val currentWindow = window
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .drawBehind {
                                if (firstWindowARef.compareAndSet(null, currentWindow)) {
                                    firstRenderedA.countDown()
                                } else if (currentWindow !== firstWindowARef.get()) {
                                    secondWindowARef.compareAndSet(null, currentWindow)
                                    rebuiltRenderedA.countDown()
                                    if (step >= 2) postRecoveryFrameDrawnA.countDown()
                                }
                            },
                    ) {
                        Layout(content = {}) { _, constraints ->
                            if (step == 1 && throwOnce.compareAndSet(true, false)) {
                                throw IllegalArgumentException("synthetic measure crash in window A")
                            }
                            layout(constraints.minWidth, constraints.minHeight) {}
                        }
                    }
                }

                // 窗口 B（模拟正在播放视频的独立播放器子窗口）：内部 remember 状态与窗口实例在 A 重建期间必须保持原样
                PikoWindow(
                    onCloseRequest = ::exitApplication,
                    onFatalClose = { fatalCount.incrementAndGet(); exitApplication() },
                    state = stateB,
                    title = "Piko Sibling Window B",
                    icon = null,
                    visible = true,
                ) {
                    val step = triggerStep
                    val currentWindow = window
                    val localEpisodeToken = remember {
                        siblingRecomposedCount.incrementAndGet()
                        77
                    }
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .drawBehind {
                                firstWindowBRef.compareAndSet(null, currentWindow)
                                latestWindowBRef.set(currentWindow)
                                siblingLocalStateValue.set(localEpisodeToken + step)
                                firstRenderedB.countDown()
                                if (step >= 2) postRecoveryFrameDrawnB.countDown()
                            },
                    )
                }
            }.invokeOnCompletion { closedLatch.countDown() }

            assertTrue(firstRenderedA.await(10, TimeUnit.SECONDS), "窗口 A 未完成首帧绘制")
            assertTrue(firstRenderedB.await(10, TimeUnit.SECONDS), "窗口 B 未完成首帧绘制")

            throwOnce.set(true)
            javax.swing.SwingUtilities.invokeLater { triggerStep = 1 }

            assertTrue(rebuiltRenderedA.await(10, TimeUnit.SECONDS), "故障后未自动重建窗口 A 并恢复出帧")
            val firstWindowA = assertNotNull(firstWindowARef.get())
            val secondWindowA = assertNotNull(secondWindowARef.get())
            assertNotSame(firstWindowA, secondWindowA, "故障窗口 A 应已重建为新实例")
            assertEquals(initialGlobalGen, desktopWindowFaultCoordinator.generation, "已知所属窗口的故障不应触发全局重建")

            javax.swing.SwingUtilities.invokeLater { triggerStep = 2 }
            assertTrue(postRecoveryFrameDrawnA.await(5, TimeUnit.SECONDS), "重建后的窗口 A 未能继续渲染后续帧")
            assertTrue(postRecoveryFrameDrawnB.await(5, TimeUnit.SECONDS), "相邻窗口 B 未能继续渲染后续帧")
            assertFalse(firstWindowA.isDisplayable, "旧故障窗口 A 应当已被销毁")
            assertSame(firstWindowBRef.get(), latestWindowBRef.get(), "相邻正常窗口 B 不应被牵连销毁重建")
            assertEquals(1, siblingRecomposedCount.get(), "相邻正常窗口 B 的内部 remember 状态不应被重置")
            assertEquals(79, siblingLocalStateValue.get())
            assertEquals(0, fatalCount.get())
        } finally {
            requestExit?.let { exit -> javax.swing.SwingUtilities.invokeLater(exit) }
            closedLatch.await(5, TimeUnit.SECONDS)
            desktopWindowFaultCoordinator.resetForTest()
            appScope.cancel()
        }
    }
}
