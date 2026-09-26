package dev.piko.desktop.winrt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows 上由 Compose 绘制标题栏，同时保留系统窗口的行为。
 *
 * 做法是经 FFM 子类化窗口过程（SetWindowLongPtr(GWLP_WNDPROC)），只改两件事：
 * - WM_NCCALCSIZE：先让 AWT 与 DefWindowProc 照常算出客户区，再把顶边放回窗口顶端，
 *   只去掉标题栏那一条。左右下三边仍是系统的非客户区，即 Windows 10 起窗口外侧那圈不可见的
 *   缩放边框；WS_CAPTION 与 WS_THICKFRAME 都保留，DWM 照常画阴影、圆角与 1px 边框，贴靠、
 *   Win+方向键、Aero Shake 与最大化动画都不受影响。
 * - WM_NCHITTEST：标题栏空白处答 HTCAPTION，拖动、双击最大化与右键系统菜单交给
 *   DefWindowProc；三个按钮答 HTMINBUTTON、HTMAXBUTTON、HTCLOSE，其中 HTMAXBUTTON 是
 *   Windows 11 悬停弹出贴靠布局的前提；非最大化时顶端一条答 HTTOP，补回被并进客户区的顶边缩放。
 *
 * 否决过的做法：
 * - undecorated = true（含 Compose 的 WindowDecoration.Undecorated）：窗口没有 WS_CAPTION 与
 *   WS_THICKFRAME，系统就不再提供贴靠布局、边缘缩放、阴影与 Win+方向键，只能逐项仿写。
 * - WM_NCCALCSIZE 直接返回 0，让整个窗口矩形都成为客户区（compose-fluent-ui 的做法）：外侧那圈
 *   不可见的缩放边框也变成了客户区，要在内容里自己判定四边缩放，最大化时再自己把内容缩回屏幕内，
 *   阴影还得靠 DwmExtendFrameIntoClientArea 补。只收回顶边时这些都由系统负责。
 * - JBR 的 CustomTitleBar 与依赖它的 Jewel DecoratedWindow：只在 JetBrains Runtime 上可用，
 *   Piko 捆绑的是 Zulu JDK 25。
 *
 * Skiko 的画布是框架窗口的子 HWND，盖满整个客户区，鼠标下的 WM_NCHITTEST 先发给它。所以子窗口
 * 也要子类化：落在标题栏的非客户区部分答 HTTRANSPARENT，系统转而问下面的框架窗口。
 *
 * 三个按钮都作为非客户区处理，而不是只有最大化按钮：命中 HTMAXBUTTON 时鼠标消息发往框架窗口的
 * 非客户区，Compose 收不到悬停；三个按钮走同一条路，悬停与按下状态统一由 WM_NCMOUSEMOVE、
 * WM_NCMOUSELEAVE 与 WM_NCLBUTTONDOWN/UP 驱动，由 [hovered] 与 [pressed] 交给界面绘制。
 * 这些消息不交给 DefWindowProc：它会进入按钮的模态跟踪并画出经典样式的按钮。
 *
 * 窗口过程运行在 AWT 的工具包线程上；[updateLayout] 由界面线程写入，所以布局是整体替换的不可变快照。
 */
internal class WindowsCaption(private val window: Window) {
    enum class Button { MINIMIZE, MAXIMIZE, CLOSE }

    /** 标题栏与按钮在 Compose 根坐标系里的位置，单位为像素，即 Skiko 画布的客户区坐标。 */
    private class Layout(val bar: Rect, val buttons: Map<Button, Rect>)

    @Volatile
    private var layout: Layout? = null

    /** 鼠标所在的按钮。 */
    var hovered: Button? by mutableStateOf(null)
        private set

    /** 按下且尚未松开的按钮；松开时仍在同一按钮上才执行。 */
    var pressed: Button? by mutableStateOf(null)
        private set

    var isMaximized: Boolean by mutableStateOf(false)
        private set

    /**
     * 标题栏按系统的激活状态显示，取自 WM_NCACTIVATE，与系统标题栏变灰的时机一致。不用 AWT 的焦点：
     * 后台进程新开的窗口在自己的线程里是激活的，AWT 报告已获得焦点，系统却没有把它放到前台。
     */
    var isActive: Boolean by mutableStateOf(false)
        private set

    private val arena = Arena.ofAuto()
    private var frame: MemorySegment = MemorySegment.NULL
    private var originalFrameProc: MemorySegment = MemorySegment.NULL
    private var canvas: MemorySegment = MemorySegment.NULL

    /** 已子类化的子窗口：HWND 地址到它原来的窗口过程。 */
    private val originalChildProcs = ConcurrentHashMap<Long, MemorySegment>()
    private val point = arena.allocate(8)
    private val rect = arena.allocate(16)
    private val trackMouseEvent = arena.allocate(TRACKMOUSEEVENT_BYTES)

    private val frameProcStub: MemorySegment by lazy { upcall("frameProc") }
    private val childProcStub: MemorySegment by lazy { upcall("childProc") }

    /**
     * 窗口已有 HWND 时接管标题栏。须在窗口首次显示前调用，否则系统标题栏会先闪一下。
     * 非 Windows 或调用失败时返回 false，窗口保持系统标题栏。
     */
    fun install(): Boolean {
        if (!WinRTSupport.isWindows || frame != MemorySegment.NULL) return false
        val hwnd = Win32Window.handleOf(window) ?: return false
        return runCatching {
            frame = hwnd
            // 这里在界面线程，窗口过程在工具包线程上跑：先记下原窗口过程再替换，替换后的第一条消息就要用到它
            originalFrameProc = User32.getWindowLongPtr.invokeWithArguments(hwnd, GWLP_WNDPROC) as MemorySegment
            live += this
            User32.setWindowLongPtr.invokeWithArguments(hwnd, GWLP_WNDPROC, frameProcStub)
            hookChildren()
            isMaximized = User32.isZoomed.invokeWithArguments(hwnd) as Int != 0
            // 触发一次 WM_NCCALCSIZE，让新的客户区立即生效
            User32.setWindowPos.invokeWithArguments(
                hwnd, MemorySegment.NULL, 0, 0, 0, 0,
                SWP_NOSIZE or SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_NOOWNERZORDER or SWP_FRAMECHANGED,
            )
            true
        }.getOrElse {
            frame = MemorySegment.NULL
            false
        }
    }

    /** 标题栏的位置变化时由界面调用。 */
    fun updateLayout(bar: Rect, buttons: Map<Button, Rect>) {
        layout = Layout(bar, buttons)
    }

    /** 不显示标题栏时（如播放器全屏）整个窗口都是客户区。 */
    fun clearLayout() {
        layout = null
        hovered = null
        pressed = null
    }

    private fun upcall(name: String): MemorySegment {
        val type = MethodType.methodType(
            Long::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        )
        val handle = MethodHandles.lookup().findVirtual(WindowsCaption::class.java, name, type).bindTo(this)
        return Linker.nativeLinker().upcallStub(handle, WNDPROC, arena)
    }

    /**
     * 框架窗口的窗口过程。上调里抛出的异常会直接让 JVM 崩溃，所以整体兜住，
     * 出错时退回原来的窗口过程。
     */
    @Suppress("unused") // 经 upcallStub 调用
    private fun frameProc(hwnd: MemorySegment, message: Int, wParam: Long, lParam: Long): Long {
        try {
            when (message) {
                WM_NCCALCSIZE -> return onNcCalcSize(hwnd, wParam, lParam)
                WM_NCHITTEST -> {
                    val native = callFrame(hwnd, message, wParam, lParam)
                    // 系统判定为边框的交给系统；落在客户区的再看是不是标题栏
                    return if (native == HTCLIENT.toLong()) hitTest(lParam).toLong() else native
                }
                WM_NCMOUSEMOVE -> {
                    val button = buttonOf(wParam)
                    if (button != null) trackNonClientLeave(hwnd)
                    hovered = button
                    // 仍交给系统：贴靠布局的弹出靠 DefWindowProc 看到 HTMAXBUTTON 上的悬停
                }
                WM_NCMOUSELEAVE -> {
                    hovered = null
                    pressed = null
                }
                WM_NCLBUTTONDOWN, WM_NCLBUTTONDBLCLK -> buttonOf(wParam)?.let {
                    pressed = it
                    return 0
                }
                WM_NCLBUTTONUP -> {
                    val button = buttonOf(wParam)
                    val wasPressed = pressed
                    pressed = null
                    if (button != null) {
                        if (button == wasPressed) perform(hwnd, button)
                        return 0
                    }
                }
                WM_NCACTIVATE -> isActive = wParam != 0L
                WM_SIZE -> {
                    when (wParam) {
                        SIZE_MAXIMIZED -> isMaximized = true
                        SIZE_RESTORED -> isMaximized = false
                    }
                    if (originalChildProcs.isEmpty()) hookChildren()
                }
                WM_NCDESTROY -> {
                    User32.setWindowLongPtr.invokeWithArguments(hwnd, GWLP_WNDPROC, originalFrameProc)
                    live -= this
                    return callFrame(hwnd, message, wParam, lParam)
                }
            }
            return callFrame(hwnd, message, wParam, lParam)
        } catch (_: Throwable) {
            return runCatching { callFrame(hwnd, message, wParam, lParam) }.getOrDefault(0L)
        }
    }

    @Suppress("unused") // 经 upcallStub 调用
    private fun childProc(hwnd: MemorySegment, message: Int, wParam: Long, lParam: Long): Long {
        val original = originalChildProcs[hwnd.address()] ?: return 0
        try {
            when (message) {
                WM_NCHITTEST -> if (hitTest(lParam) != HTCLIENT) return HTTRANSPARENT.toLong()
                WM_NCDESTROY -> {
                    User32.setWindowLongPtr.invokeWithArguments(hwnd, GWLP_WNDPROC, original)
                    originalChildProcs.remove(hwnd.address())
                }
            }
            return callWindowProc(original, hwnd, message, wParam, lParam)
        } catch (_: Throwable) {
            return runCatching { callWindowProc(original, hwnd, message, wParam, lParam) }.getOrDefault(0L)
        }
    }

    /**
     * 先按原样计算（AWT 在这里更新 insets），再只把顶边改回窗口顶端。最大化的窗口四边都伸出
     * 屏幕一个边框的宽度，顶边也要同样缩回，否则标题栏有一截在屏幕外；缩进量取系统刚给左边
     * 算出的边框宽度，与左右下三边一致。
     *
     * lParam 在 wParam 为 TRUE 时指向 NCCALCSIZE_PARAMS，否则指向 RECT；两者开头都是
     * 要计算的那个矩形。
     */
    private fun onNcCalcSize(hwnd: MemorySegment, wParam: Long, lParam: Long): Long {
        val proposed = MemorySegment.ofAddress(lParam).reinterpret(16)
        val originalLeft = proposed.get(JAVA_INT, 0)
        val originalTop = proposed.get(JAVA_INT, 4)
        val result = callFrame(hwnd, WM_NCCALCSIZE, wParam, lParam)
        val zoomed = User32.isZoomed.invokeWithArguments(hwnd) as Int != 0
        val border = proposed.get(JAVA_INT, 0) - originalLeft
        proposed.set(JAVA_INT, 4, if (zoomed) originalTop + border else originalTop)
        return result
    }

    /** 屏幕坐标下的命中测试，框架窗口与画布共用。 */
    private fun hitTest(lParam: Long): Int {
        val current = layout ?: return HTCLIENT
        val screenX = lParam.toInt().toShort().toInt()
        val screenY = (lParam.toInt() shr 16).toShort().toInt()

        if (!isMaximized && hasThickFrame()) {
            val band = resizeBorder()
            toClient(frame, screenX, screenY)
            val x = point.get(JAVA_INT, 0)
            val y = point.get(JAVA_INT, 4)
            if (y in 0 until band) {
                User32.getClientRect.invokeWithArguments(frame, rect)
                val width = rect.get(JAVA_INT, 8)
                // 角上留得比顶边宽一些，与系统边框的斜向缩放区一致
                return when {
                    x < band * 2 -> HTTOPLEFT
                    x >= width - band * 2 -> HTTOPRIGHT
                    else -> HTTOP
                }
            }
        }

        toClient(if (canvas != MemorySegment.NULL) canvas else frame, screenX, screenY)
        val position = Offset(point.get(JAVA_INT, 0).toFloat(), point.get(JAVA_INT, 4).toFloat())
        for ((button, bounds) in current.buttons) {
            if (bounds.contains(position)) {
                return when (button) {
                    Button.MINIMIZE -> HTMINBUTTON
                    Button.MAXIMIZE -> HTMAXBUTTON
                    Button.CLOSE -> HTCLOSE
                }
            }
        }
        return if (current.bar.contains(position)) HTCAPTION else HTCLIENT
    }

    private fun perform(hwnd: MemorySegment, button: Button) {
        val command = when (button) {
            Button.MINIMIZE -> SC_MINIMIZE
            Button.MAXIMIZE -> if (isMaximized) SC_RESTORE else SC_MAXIMIZE
            // 与点系统关闭按钮同路：DefWindowProc 发 WM_CLOSE，AWT 转成 windowClosing
            Button.CLOSE -> SC_CLOSE
        }
        // 最小化或最大化后鼠标可能不再经过这里，收不到 WM_NCMOUSELEAVE
        hovered = null
        User32.postMessage.invokeWithArguments(hwnd, WM_SYSCOMMAND, command.toLong(), 0L)
    }

    /** 画布 HWND 之类的子窗口在 addNotify 时建好；此时若还没有，等首个 WM_SIZE 再找。 */
    private fun hookChildren() {
        var child = User32.getWindow.invokeWithArguments(frame, GW_CHILD) as MemorySegment
        while (child != MemorySegment.NULL) {
            if (!originalChildProcs.containsKey(child.address())) {
                // 先登记再替换：替换后到来的第一条消息就要查得到原窗口过程
                val original = User32.getWindowLongPtr.invokeWithArguments(child, GWLP_WNDPROC) as MemorySegment
                originalChildProcs[child.address()] = original
                User32.setWindowLongPtr.invokeWithArguments(child, GWLP_WNDPROC, childProcStub)
                if (canvas == MemorySegment.NULL) canvas = child
            }
            child = User32.getWindow.invokeWithArguments(child, GW_HWNDNEXT) as MemorySegment
        }
    }

    private fun trackNonClientLeave(hwnd: MemorySegment) {
        trackMouseEvent.set(JAVA_INT, 0, TRACKMOUSEEVENT_BYTES.toInt())
        trackMouseEvent.set(JAVA_INT, 4, TME_LEAVE or TME_NONCLIENT)
        trackMouseEvent.set(ADDRESS, 8, hwnd)
        trackMouseEvent.set(JAVA_INT, 16, 0)
        User32.trackMouseEvent.invokeWithArguments(trackMouseEvent)
    }

    private fun hasThickFrame(): Boolean =
        (User32.getWindowLong.invokeWithArguments(frame, GWL_STYLE) as Int) and WS_THICKFRAME != 0

    /** 标题栏被收进客户区之前，系统在顶端留给缩放的厚度，按窗口当前所在显示器的 DPI 取。 */
    private fun resizeBorder(): Int {
        val dpi = User32.getDpiForWindow.invokeWithArguments(frame) as Int
        return (User32.getSystemMetricsForDpi.invokeWithArguments(SM_CYSIZEFRAME, dpi) as Int) +
            (User32.getSystemMetricsForDpi.invokeWithArguments(SM_CXPADDEDBORDER, dpi) as Int)
    }

    private fun toClient(hwnd: MemorySegment, screenX: Int, screenY: Int) {
        point.set(JAVA_INT, 0, screenX)
        point.set(JAVA_INT, 4, screenY)
        User32.screenToClient.invokeWithArguments(hwnd, point)
    }

    private fun buttonOf(hitTest: Long): Button? = when (hitTest.toInt()) {
        HTMINBUTTON -> Button.MINIMIZE
        HTMAXBUTTON -> Button.MAXIMIZE
        HTCLOSE -> Button.CLOSE
        else -> null
    }

    private fun callFrame(hwnd: MemorySegment, message: Int, wParam: Long, lParam: Long): Long =
        callWindowProc(originalFrameProc, hwnd, message, wParam, lParam)

    private fun callWindowProc(proc: MemorySegment, hwnd: MemorySegment, message: Int, wParam: Long, lParam: Long): Long =
        User32.callWindowProc.invokeWithArguments(proc, hwnd, message, wParam, lParam) as Long

    private object User32 {
        private val lookup = SymbolLookup.libraryLookup("user32", Arena.global())
        private val linker = Linker.nativeLinker()

        private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

        val setWindowLongPtr = handle("SetWindowLongPtrW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
        val getWindowLongPtr = handle("GetWindowLongPtrW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        val getWindowLong = handle("GetWindowLongW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val callWindowProc = handle(
            "CallWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG),
        )
        val postMessage = handle("PostMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))
        val isZoomed = handle("IsZoomed", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val getWindow = handle("GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        val getClientRect = handle("GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val screenToClient = handle("ScreenToClient", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val trackMouseEvent = handle("TrackMouseEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val getDpiForWindow = handle("GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val getSystemMetricsForDpi = handle("GetSystemMetricsForDpi", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
        val setWindowPos = handle(
            "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        )
    }

    private companion object {
        /** 子类化期间持有实例：upcall 存根的内存随实例回收，窗口销毁前不能被回收。 */
        val live: MutableSet<WindowsCaption> = ConcurrentHashMap.newKeySet()

        val WNDPROC: FunctionDescriptor = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG)

        const val GWLP_WNDPROC = -4
        const val GWL_STYLE = -16
        const val WS_THICKFRAME = 0x00040000
        const val GW_HWNDNEXT = 2
        const val GW_CHILD = 5

        const val WM_SIZE = 0x0005
        const val WM_NCDESTROY = 0x0082
        const val WM_NCCALCSIZE = 0x0083
        const val WM_NCHITTEST = 0x0084
        const val WM_NCACTIVATE = 0x0086
        const val WM_NCMOUSEMOVE = 0x00A0
        const val WM_NCLBUTTONDOWN = 0x00A1
        const val WM_NCLBUTTONUP = 0x00A2
        const val WM_NCLBUTTONDBLCLK = 0x00A3
        const val WM_SYSCOMMAND = 0x0112
        const val WM_NCMOUSELEAVE = 0x02A2

        const val SIZE_RESTORED = 0L
        const val SIZE_MAXIMIZED = 2L

        const val HTTRANSPARENT = -1
        const val HTCLIENT = 1
        const val HTCAPTION = 2
        const val HTMINBUTTON = 8
        const val HTMAXBUTTON = 9
        const val HTTOP = 12
        const val HTTOPLEFT = 13
        const val HTTOPRIGHT = 14
        const val HTCLOSE = 20

        const val SC_MINIMIZE = 0xF020
        const val SC_MAXIMIZE = 0xF030
        const val SC_CLOSE = 0xF060
        const val SC_RESTORE = 0xF120

        const val SM_CYSIZEFRAME = 33
        const val SM_CXPADDEDBORDER = 92

        const val TME_LEAVE = 0x00000002
        const val TME_NONCLIENT = 0x00000010
        const val TRACKMOUSEEVENT_BYTES = 24L

        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
        const val SWP_NOOWNERZORDER = 0x0200
    }
}
