package dev.piko.desktop.winrt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberWindowState
import dev.piko.desktop.TitleBarColors
import dev.piko.desktop.WindowFrame
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * 端到端地验证触摸链路：WindowFrame 装上 WindowsCaption，它的窗口过程把 WM_POINTER 交给 windows-touch，
 * 再注入 Compose。输入是操作系统级的（InjectTouchInput 与 Robot 的 SendInput），中间任何一环没接上，
 * Windows 就把触摸降级成鼠标，Compose 看到的是 Mouse，单指也只剩一个指针。
 * 会在本机弹出窗口、移动真实的鼠标；没有交互式桌面或注入不可用时跳过。
 */
class WindowsTouchInjectionTest {
    @Test
    fun `a tap reaches compose as a touch press and release`() = withTouchWindow { window ->
        assumeTrue("本会话不能注入触摸", TouchInjector.available)
        val (x, y) = window.contentCenter()
        TouchInjector.frame(TouchContact(1, x, y, TouchPhase.DOWN))
        Thread.sleep(40)
        TouchInjector.frame(TouchContact(1, x, y, TouchPhase.UP))

        val press = window.awaitEvent { it.type == PointerEventType.Press }
        assertEquals(listOf(PointerType.Touch), press.pointers.map { it.type }, window.describe())
        val release = window.awaitEvent { it.type == PointerEventType.Release }
        assertEquals(PointerType.Touch, release.pointers.single().type, window.describe())
        assertFalse(release.pointers.single().pressed, window.describe())
    }

    @Test
    fun `a pinch reaches compose with both fingers in one event`() = withTouchWindow { window ->
        assumeTrue("本会话不能注入触摸", TouchInjector.available)
        val (cx, cy) = window.contentCenter()
        fun fingers(half: Int, phase: TouchPhase) = arrayOf(
            TouchContact(1, cx - half, cy, phase),
            TouchContact(2, cx + half, cy, phase),
        )
        TouchInjector.frame(*fingers(30, TouchPhase.DOWN))
        for (half in 40..120 step 10) {
            Thread.sleep(16)
            TouchInjector.frame(*fingers(half, TouchPhase.UPDATE))
        }
        Thread.sleep(16)
        TouchInjector.frame(*fingers(120, TouchPhase.UP))

        window.awaitEvent { event -> event.pointers.count { it.pressed && it.type == PointerType.Touch } == 2 }
        // 最后一次抬起之后还有手指按着，下一次手势就从卡住的状态开始
        val lastRelease = window.awaitEvent { it.type == PointerEventType.Release && it.pointers.none { p -> p.pressed } }
        assertTrue(lastRelease.pointers.all { it.type == PointerType.Touch }, window.describe())
    }

    @Test
    fun `a mouse click still arrives as a mouse pointer`() = withTouchWindow { window ->
        // Robot 的坐标是 AWT 的逻辑坐标，不是物理像素
        val location = window.composeWindow.locationOnScreen
        val size = window.composeWindow.size
        Robot().apply {
            autoDelay = 20
            mouseMove(location.x + size.width / 2, location.y + size.height / 2)
            mousePress(InputEvent.BUTTON1_DOWN_MASK)
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }

        val press = window.awaitEvent { it.type == PointerEventType.Press }
        assertEquals(listOf(PointerType.Mouse), press.pointers.map { it.type }, window.describe())
        window.awaitEvent { it.type == PointerEventType.Release }
    }
}

internal class RecordedPointer(val id: Long, val type: PointerType, val pressed: Boolean)

internal class RecordedEvent(val type: PointerEventType, val pointers: List<RecordedPointer>)

internal class TouchTestWindow(val composeWindow: ComposeWindow, private val events: List<RecordedEvent>) {
    val hwnd: Long get() = composeWindow.windowHandle

    /** 窗口中心向下挪一点，避开顶上自绘的标题栏；那里答 HTCAPTION，触摸会被系统当成拖窗口。 */
    fun contentCenter(): Pair<Int, Int> {
        val rect = NativeUser32.windowRect(hwnd)
        return (rect.left + rect.right) / 2 to (rect.top + rect.bottom) / 2 + 40
    }

    fun awaitEvent(timeoutMillis: Long = 5_000, predicate: (RecordedEvent) -> Boolean): RecordedEvent {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull(predicate)?.let { return it }
            Thread.sleep(20)
        }
        fail("${timeoutMillis}ms 内没有等到符合条件的指针事件；${describe()}")
    }

    fun describe(): String = "收到 " + events.joinToString { event ->
        "${event.type}${event.pointers.map { "${it.id}:${it.type}:${if (it.pressed) "down" else "up"}" }}"
    }
}

/**
 * 照 Main.kt 的主窗口搭：Compose 的 Window 里套 WindowFrame，所以装上的是真实的 WindowsCaption，
 * 而不是测试自己挂的窗口过程。
 */
private fun withTouchWindow(block: (TouchTestWindow) -> Unit) {
    assumeTrue("只在 Windows 上跑", WinRTSupport.isWindows)
    assumeTrue("需要交互式桌面", !GraphicsEnvironment.isHeadless())

    val events = CopyOnWriteArrayList<RecordedEvent>()
    val shown = CompletableFuture<ComposeWindow>()
    val open = mutableStateOf(true)
    val app = thread(name = "touch-test-application", isDaemon = true) {
        runCatching {
            runBlocking {
                awaitApplication {
                    if (open.value) {
                        Window(
                            onCloseRequest = { open.value = false },
                            title = "WindowsTouchInjectionTest",
                            state = rememberWindowState(position = WindowPosition(80.dp, 80.dp), size = DpSize(640.dp, 480.dp)),
                        ) {
                            WindowFrame(title = "WindowsTouchInjectionTest", icon = null, colors = TitleBarColors(Color.White, Color.Black)) {
                                Box(Modifier.fillMaxSize().background(Color.White).pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            events += RecordedEvent(event.type, event.changes.map { RecordedPointer(it.id.value, it.type, it.pressed) })
                                        }
                                    }
                                })
                            }
                            LaunchedEffect(Unit) {
                                withFrameNanos { }
                                shown.complete(window)
                            }
                        }
                    }
                }
            }
        }.onFailure { shown.completeExceptionally(it) }
    }
    try {
        val window = TouchTestWindow(shown.get(15, TimeUnit.SECONDS), events)
        // 首帧之后再等一会儿：桥在第一条指针消息时才取 scene，此前画布的尺寸与位置还可能在变
        Thread.sleep(500)
        NativeUser32.forceForeground(window.hwnd)
        Thread.sleep(300)
        assumeTrue("窗口拿不到前台，注入的输入会落到别的窗口", NativeUser32.foregroundWindow() == window.hwnd)
        events.clear()
        block(window)
    } finally {
        EventQueue.invokeAndWait { open.value = false }
        app.join(10_000)
    }
}
