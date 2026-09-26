package dev.piko.desktop.winrt

import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * 无边框全屏：去掉标题栏与边框，把窗口铺满所在的显示器。做法同 vlcj 的 Win32FullScreenHandler，
 * Animeko 与 Bilby 的桌面端也是如此。
 *
 * 不用 Compose 的 WindowPlacement.Fullscreen。Bilby 在同一套 CMP 1.12 与 MediaMP 0.5.0 上实测，
 * 切到那一档时 Skiko 在 Direct3DContextHandler.flush 里崩溃（EXCEPTION_ILLEGAL_INSTRUCTION），
 * 之后画布不再跟随窗口尺寸；mpv 的画面正经 MediaMP 共享着 Skiko 的 D3D 设备。这里窗口始终是
 * 普通窗口，只换样式与位置，交换链照常按尺寸变化调整。
 */
internal class WindowsFullscreen(private val window: Window) {
    private class Saved(val style: Int, val exStyle: Int, val left: Int, val top: Int, val right: Int, val bottom: Int, val maximized: Boolean)

    /** 进全屏之前的样式与位置，退出时原样放回。非 null 即处于全屏。 */
    private var saved: Saved? = null

    val isFullscreen: Boolean get() = saved != null

    /** 窗口还没有 HWND 或调用失败时保持原样，[isFullscreen] 仍为 false。 */
    fun enter() {
        if (saved != null || !WinRTSupport.isWindows) return
        val hwnd = Win32Window.handleOf(window) ?: return
        runCatching {
            // 先还原再取位置：最大化时 GetWindowRect 得到的是铺满工作区的矩形，退出时应回到最大化，
            // 而不是那个尺寸的普通窗口
            val maximized = User32.isZoomed.invokeWithArguments(hwnd) as Int != 0
            if (maximized) User32.showWindow.invokeWithArguments(hwnd, SW_RESTORE)
            val style = User32.getWindowLong.invokeWithArguments(hwnd, GWL_STYLE) as Int
            val exStyle = User32.getWindowLong.invokeWithArguments(hwnd, GWL_EXSTYLE) as Int
            Arena.ofConfined().use { arena ->
                val rect = arena.allocate(RECT_BYTES)
                User32.getWindowRect.invokeWithArguments(hwnd, rect)
                saved = Saved(style, exStyle, rect.int(0), rect.int(1), rect.int(2), rect.int(3), maximized)

                User32.setWindowLong.invokeWithArguments(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
                User32.setWindowLong.invokeWithArguments(hwnd, GWL_EXSTYLE, exStyle and EDGE_STYLES.inv())

                val monitor = User32.monitorFromWindow.invokeWithArguments(hwnd, MONITOR_DEFAULTTONEAREST) as MemorySegment
                val info = arena.allocate(MONITORINFO_BYTES)
                info.set(JAVA_INT, 0, MONITORINFO_BYTES.toInt())
                User32.getMonitorInfo.invokeWithArguments(monitor, info)
                // rcMonitor 紧跟在 cbSize 之后
                val left = info.int(1)
                val top = info.int(2)
                User32.setWindowPos.invokeWithArguments(
                    hwnd, MemorySegment.NULL, left, top, info.int(3) - left, info.int(4) - top, REPOSITION_FLAGS,
                )
            }
        }.onFailure { saved = null }
    }

    fun exit() {
        val state = saved ?: return
        saved = null
        val hwnd = Win32Window.handleOf(window) ?: return
        runCatching {
            User32.setWindowLong.invokeWithArguments(hwnd, GWL_STYLE, state.style)
            User32.setWindowLong.invokeWithArguments(hwnd, GWL_EXSTYLE, state.exStyle)
            User32.setWindowPos.invokeWithArguments(
                hwnd, MemorySegment.NULL, state.left, state.top, state.right - state.left, state.bottom - state.top,
                REPOSITION_FLAGS,
            )
            if (state.maximized) User32.showWindow.invokeWithArguments(hwnd, SW_MAXIMIZE)
        }
    }

    private fun MemorySegment.int(index: Int): Int = getAtIndex(JAVA_INT, index.toLong())

    private object User32 {
        private val lookup = SymbolLookup.libraryLookup("user32", Arena.global())
        private val linker = Linker.nativeLinker()

        private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

        val isZoomed = handle("IsZoomed", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        val showWindow = handle("ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

        // 32 位的 GetWindowLongW 足够：样式位都在低 32 位，不涉及指针
        val getWindowLong = handle("GetWindowLongW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val setWindowLong = handle("SetWindowLongW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
        val getWindowRect = handle("GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val monitorFromWindow = handle("MonitorFromWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
        val getMonitorInfo = handle("GetMonitorInfoW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        val setWindowPos = handle(
            "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
        )
    }

    private companion object {
        const val GWL_STYLE = -16
        const val GWL_EXSTYLE = -20
        const val WS_CAPTION = 0x00C00000
        const val WS_THICKFRAME = 0x00040000
        const val WS_EX_DLGMODALFRAME = 0x00000001
        const val WS_EX_WINDOWEDGE = 0x00000100
        const val WS_EX_CLIENTEDGE = 0x00000200
        const val WS_EX_STATICEDGE = 0x00020000
        const val EDGE_STYLES = WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE
        const val SW_MAXIMIZE = 3
        const val SW_RESTORE = 9
        const val MONITOR_DEFAULTTONEAREST = 2
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOACTIVATE = 0x0010
        const val SWP_FRAMECHANGED = 0x0020
        const val REPOSITION_FLAGS = SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED
        const val RECT_BYTES = 16L
        const val MONITORINFO_BYTES = 40L
    }
}
