package dev.piko.desktop.winrt

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/** 窗口标题栏的深浅色。系统不会按应用主题替 Win32 窗口切换，只能逐个窗口设置。 */
object WindowChrome {
    // dwmapi.h 自 Windows 10 20H1 起的取值；更早的 1809 到 1909 用的是未公开的 19，这里不兼容
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    private val dwmSetWindowAttribute: MethodHandle by lazy {
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("dwmapi", Arena.global()).find("DwmSetWindowAttribute").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
        )
    }

    /**
     * 把 [window] 的标题栏设为深色或浅色。窗口尚未 displayable（没有 HWND）、非 Windows
     * 或调用失败时返回 false。任意线程可调。
     *
     * 在 addNotify 之后、首次显示之前调用，窗口一出现就是目标颜色。已显示的窗口在 Windows 11
     * 上设置后立即重绘，不需要再用 SetWindowPos(SWP_FRAMECHANGED) 或切换焦点去触发
     * （Windows 11 26220 实测）。
     */
    fun setDarkTitleBar(window: Window, dark: Boolean): Boolean {
        if (!WinRTSupport.isWindows) return false
        return runCatching {
            val hwnd = Win32Window.handleOf(window) ?: return false
            Arena.ofConfined().use { arena ->
                val value = arena.allocateFrom(JAVA_INT, if (dark) 1 else 0)
                val hr = dwmSetWindowAttribute.invokeWithArguments(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4) as Int
                hr >= 0
            }
        }.getOrDefault(false)
    }
}

/** AWT 窗口到 Win32 HWND 的换算，供标题栏、任务栏进度与目录选择框共用。 */
internal object Win32Window {
    /**
     * 返回 [window] 的 HWND；窗口还没有原生对等体时为 null。
     *
     * AWT 自己的 WComponentPeer.getHWnd 在 sun.awt.windows 里，模块未开放，反射要加 --add-opens。
     * ComposeWindow 公开了 windowHandle，是编译期可见的 API，优先走它（实测即顶层框架的 HWND，
     * 不是 skiko 的渲染子窗口）。其余窗口经 JNA 的 Native.getWindowPointer，内部走 JAWT；
     * JNA 只是 MediaMP 带进来的运行时依赖，编译类路径上没有，所以只能反射调用。
     */
    fun handleOf(window: Window): MemorySegment? {
        if (!window.isDisplayable) return null
        val handle = when (window) {
            is ComposeWindow -> window.windowHandle
            else -> jnaWindowPointer(window)
        }
        return if (handle == 0L) null else MemorySegment.ofAddress(handle)
    }

    private fun jnaWindowPointer(window: Window): Long = runCatching {
        val native = Class.forName("com.sun.jna.Native")
        val pointerClass = Class.forName("com.sun.jna.Pointer")
        val pointer = native.getMethod("getWindowPointer", Window::class.java).invoke(null, window)
            ?: return 0L
        pointerClass.getMethod("nativeValue", pointerClass).invoke(null, pointer) as Long
    }.getOrDefault(0L)
}
