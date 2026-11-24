package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import dev.piko.shared.log.PikoLog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Linux X11/XWayland 全屏控制：向窗口管理器发送 EWMH `_NET_WM_STATE` ClientMessage
 * 切换 `_NET_WM_STATE_FULLSCREEN`，而不走 Skiko/AWT 的 `GraphicsDevice.setFullScreenWindow`。
 *
 * Skiko 的 `WindowPlacement.Fullscreen` 在 Linux 上调用 `GraphicsDevice.setFullScreenWindow(window)`
 * （AWT 独占全屏），在 KDE Plasma / KWin / XWayland + GLX 组合下会导致 GLX 交换链失步、
 * 画面冻结与退出全屏后错位重影（issue #60）。改由窗口管理器经 EWMH 原子接管全屏后，
 * 窗口始终保持普通顶层窗口身份，GLX/OpenGL 表面仅按常规 `ConfigureNotify` 调整尺寸。
 *
 * 若 `libX11.so.6` 不可用或无法取得 X11 窗口句柄，[enter] / [exit] 返回 false，由调用方安全回退至
 * `WindowPlacement.Fullscreen`。
 */
internal class LinuxFullscreen(
    private val window: Window,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var requestedFullscreen: Boolean = false
    private var lastRequestNanos: Long = 0L

    val isFullscreen: Boolean get() = requestedFullscreen

    /** 请求进入 EWMH 全屏；返回 false 表示未能通过 X11 发送，调用方应回退。 */
    fun enter(): Boolean {
        if (requestedFullscreen) return true
        if (!sendFullscreenState(enable = true)) return false
        requestedFullscreen = true
        lastRequestNanos = nowNanos()
        return true
    }

    /** 请求退出 EWMH 全屏；返回 false 表示未能通过 X11 发送，调用方应回退。 */
    fun exit(): Boolean {
        if (!requestedFullscreen) return true
        if (!sendFullscreenState(enable = false)) return false
        requestedFullscreen = false
        lastRequestNanos = nowNanos()
        return true
    }

    /**
     * 读取窗口当前的 `_NET_WM_STATE` 属性并同步 [isFullscreen]。
     * 用于响应用户通过窗口管理器快捷键（如 KWin 全屏切换）退出或进入全屏。
     * 在本类刚发出切换请求的短暂过渡窗口内跳过覆盖，避免在 WM 尚未处理 ClientMessage 前误回滚状态。
     */
    fun syncFromWindowManager(): Boolean {
        if (isWithinTransitionGrace(lastRequestNanos, nowNanos())) return requestedFullscreen
        val actual = queryFullscreen() ?: return requestedFullscreen
        requestedFullscreen = actual
        return requestedFullscreen
    }

    internal fun queryFullscreen(): Boolean? {
        if (!isLinux || !window.isDisplayable) return null
        val x11 = X11.instance ?: return null
        return runCatching {
            val display = x11.openDisplay(MemorySegment.NULL)
            if (display == MemorySegment.NULL) return@runCatching null
            try {
                val target = resolveClientWindow(x11, display) ?: return@runCatching null
                val netWmState = internAtom(x11, display, "_NET_WM_STATE", onlyIfExists = true)
                val netWmStateFullscreen = internAtom(x11, display, "_NET_WM_STATE_FULLSCREEN", onlyIfExists = true)
                if (netWmState == 0L || netWmStateFullscreen == 0L) return@runCatching null
                readAtomProperty(x11, display, target.clientWindow, netWmState)?.contains(netWmStateFullscreen)
            } finally {
                x11.closeDisplay(display)
            }
        }.onFailure { PikoLog.w(TAG, "Failed to query X11 _NET_WM_STATE", it) }
            .getOrNull()
    }

    private fun sendFullscreenState(enable: Boolean): Boolean {
        if (!isLinux || !window.isDisplayable) return false
        val x11 = X11.instance ?: return false
        return runCatching {
            val display = x11.openDisplay(MemorySegment.NULL)
            if (display == MemorySegment.NULL) return@runCatching false
            try {
                val target = resolveClientWindow(x11, display) ?: return@runCatching false
                val netWmState = internAtom(x11, display, "_NET_WM_STATE", onlyIfExists = false)
                val netWmStateFullscreen = internAtom(x11, display, "_NET_WM_STATE_FULLSCREEN", onlyIfExists = false)
                if (netWmState == 0L || netWmStateFullscreen == 0L) return@runCatching false

                Arena.ofConfined().use { arena ->
                    val event = arena.allocate(XEVENT_BYTES)
                    // XClientMessageEvent on Linux LP64:
                    // 0: type (int), 8: serial (ulong), 16: send_event (int),
                    // 24: display (ptr), 32: window (Window/ulong), 40: message_type (Atom/ulong),
                    // 48: format (int), 56..95: data.l[0..4] (5 * long)
                    event.set(JAVA_INT, 0L, CLIENT_MESSAGE)
                    event.set(JAVA_LONG, 8L, 0L)
                    event.set(JAVA_INT, 16L, 1)
                    event.set(ADDRESS, 24L, display)
                    event.set(JAVA_LONG, 32L, target.clientWindow)
                    event.set(JAVA_LONG, 40L, netWmState)
                    event.set(JAVA_INT, 48L, 32)
                    event.set(JAVA_LONG, 56L, if (enable) NET_WM_STATE_ADD else NET_WM_STATE_REMOVE)
                    event.set(JAVA_LONG, 64L, netWmStateFullscreen)
                    event.set(JAVA_LONG, 72L, 0L)
                    event.set(JAVA_LONG, 80L, SOURCE_INDICATION_APPLICATION)
                    event.set(JAVA_LONG, 88L, 0L)

                    val sent = x11.sendEvent(display, target.rootWindow, 0, ROOT_EVENT_MASK, event)
                    x11.flush(display)
                    sent != 0
                }
            } finally {
                x11.closeDisplay(display)
            }
        }.onFailure { PikoLog.w(TAG, "Failed to toggle X11 EWMH fullscreen (enable=$enable)", it) }
            .getOrDefault(false)
    }

    private data class X11Target(val clientWindow: Long, val rootWindow: Long)

    /**
     * 解析 ICCCM 顶层客户窗口：Skiko 的 `windowHandle` 沿 `XQueryTree` 一路爬到 `parent == root`，
     * 在重父化窗口管理器（KWin X11、Xfwm4、Openbox 等）下拿到的是装饰框窗口而非 AWT 客户窗口。
     * 这里从 `SkiaLayer.contentHandle`（Canvas 子窗口）向上遍历，优先命中带 `WM_STATE` 属性的客户窗口；
     * 若未找到（如无重父化或尚未映射），回退至 `root` 下的顶层窗口或 `ComposeWindow.windowHandle`。
     */
    private fun resolveClientWindow(x11: X11, display: MemorySegment): X11Target? {
        val contentXid = runCatching { findSkiaLayer(window)?.contentHandle ?: 0L }.getOrDefault(0L)
        val fallbackXid = runCatching { (window as? ComposeWindow)?.windowHandle ?: 0L }.getOrDefault(0L)
        val startXid = contentXid.takeIf { it != 0L } ?: fallbackXid
        if (startXid == 0L) return null

        val wmStateAtom = internAtom(x11, display, "WM_STATE", onlyIfExists = true)
        var current = startXid
        var root = x11.defaultRootWindow(display)
        Arena.ofConfined().use { arena ->
            val rootOut = arena.allocate(JAVA_LONG)
            val parentOut = arena.allocate(JAVA_LONG)
            val childrenOut = arena.allocate(ADDRESS)
            val nchildrenOut = arena.allocate(JAVA_INT)
            repeat(MAX_WINDOW_TREE_DEPTH) {
                if (wmStateAtom != 0L && hasProperty(x11, display, current, wmStateAtom)) {
                    // 取该窗口所在屏幕的 root
                    if (x11.queryTree(display, current, rootOut, parentOut, childrenOut, nchildrenOut) != 0) {
                        val children = childrenOut.get(ADDRESS, 0L)
                        if (children != MemorySegment.NULL) x11.free(children)
                        val queriedRoot = rootOut.get(JAVA_LONG, 0L)
                        if (queriedRoot != 0L) root = queriedRoot
                    }
                    return X11Target(clientWindow = current, rootWindow = root)
                }
                if (x11.queryTree(display, current, rootOut, parentOut, childrenOut, nchildrenOut) == 0) {
                    return@repeat
                }
                val children = childrenOut.get(ADDRESS, 0L)
                if (children != MemorySegment.NULL) x11.free(children)
                val queriedRoot = rootOut.get(JAVA_LONG, 0L)
                val parent = parentOut.get(JAVA_LONG, 0L)
                if (queriedRoot != 0L) root = queriedRoot
                if (parent == 0L || parent == root) {
                    return X11Target(clientWindow = current, rootWindow = root)
                }
                current = parent
            }
        }
        return X11Target(clientWindow = fallbackXid.takeIf { it != 0L } ?: current, rootWindow = root)
    }

    private fun internAtom(x11: X11, display: MemorySegment, name: String, onlyIfExists: Boolean): Long =
        Arena.ofConfined().use { arena ->
            x11.internAtom(display, arena.allocateFrom(name), if (onlyIfExists) 1 else 0)
        }

    private fun hasProperty(x11: X11, display: MemorySegment, xid: Long, property: Long): Boolean =
        Arena.ofConfined().use { arena ->
            val actualType = arena.allocate(JAVA_LONG)
            val actualFormat = arena.allocate(JAVA_INT)
            val nitems = arena.allocate(JAVA_LONG)
            val bytesAfter = arena.allocate(JAVA_LONG)
            val prop = arena.allocate(ADDRESS)
            val status = x11.getWindowProperty(
                display, xid, property, 0L, 0L, 0, ANY_PROPERTY_TYPE,
                actualType, actualFormat, nitems, bytesAfter, prop,
            )
            val data = prop.get(ADDRESS, 0L)
            if (data != MemorySegment.NULL) x11.free(data)
            status == 0 && actualType.get(JAVA_LONG, 0L) != 0L
        }

    private fun readAtomProperty(x11: X11, display: MemorySegment, xid: Long, property: Long): LongArray? =
        Arena.ofConfined().use { arena ->
            val actualType = arena.allocate(JAVA_LONG)
            val actualFormat = arena.allocate(JAVA_INT)
            val nitems = arena.allocate(JAVA_LONG)
            val bytesAfter = arena.allocate(JAVA_LONG)
            val prop = arena.allocate(ADDRESS)
            val status = x11.getWindowProperty(
                display, xid, property, 0L, MAX_ATOM_PROPERTY_WORDS, 0, XA_ATOM,
                actualType, actualFormat, nitems, bytesAfter, prop,
            )
            val data = prop.get(ADDRESS, 0L)
            try {
                if (status != 0) return@use null
                if (actualType.get(JAVA_LONG, 0L) == 0L) return@use LongArray(0)
                if (actualFormat.get(JAVA_INT, 0L) != 32 || data == MemorySegment.NULL) return@use LongArray(0)
                val count = nitems.get(JAVA_LONG, 0L).coerceIn(0L, MAX_ATOM_PROPERTY_WORDS).toInt()
                if (count == 0) return@use LongArray(0)
                // Xlib 约定：在 64 位 Linux 上 format=32 的属性数据按 unsigned long（8 字节）存储
                val view = data.reinterpret(count.toLong() * JAVA_LONG.byteSize())
                LongArray(count) { i -> view.getAtIndex(JAVA_LONG, i.toLong()) }
            } finally {
                if (data != MemorySegment.NULL) x11.free(data)
            }
        }

    private class X11 private constructor(private val lookup: SymbolLookup) {
        private val linker = Linker.nativeLinker()

        private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

        private val xOpenDisplay = handle("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val xCloseDisplay = handle("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        private val xDefaultRootWindow = handle("XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
        private val xInternAtom = handle("XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT))
        private val xSendEvent = handle(
            "XSendEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS),
        )
        private val xFlush = handle("XFlush", FunctionDescriptor.of(JAVA_INT, ADDRESS))
        private val xQueryTree = handle(
            "XQueryTree",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        )
        private val xGetWindowProperty = handle(
            "XGetWindowProperty",
            FunctionDescriptor.of(
                JAVA_INT,
                ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
            ),
        )
        private val xFree = handle("XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS))

        fun openDisplay(name: MemorySegment): MemorySegment = xOpenDisplay.invokeWithArguments(name) as MemorySegment
        fun closeDisplay(display: MemorySegment): Int = xCloseDisplay.invokeWithArguments(display) as Int
        fun defaultRootWindow(display: MemorySegment): Long = xDefaultRootWindow.invokeWithArguments(display) as Long
        fun internAtom(display: MemorySegment, name: MemorySegment, onlyIfExists: Int): Long =
            xInternAtom.invokeWithArguments(display, name, onlyIfExists) as Long
        fun sendEvent(display: MemorySegment, target: Long, propagate: Int, mask: Long, event: MemorySegment): Int =
            xSendEvent.invokeWithArguments(display, target, propagate, mask, event) as Int
        fun flush(display: MemorySegment): Int = xFlush.invokeWithArguments(display) as Int
        fun queryTree(
            display: MemorySegment,
            window: Long,
            rootOut: MemorySegment,
            parentOut: MemorySegment,
            childrenOut: MemorySegment,
            nchildrenOut: MemorySegment,
        ): Int = xQueryTree.invokeWithArguments(display, window, rootOut, parentOut, childrenOut, nchildrenOut) as Int
        fun getWindowProperty(
            display: MemorySegment,
            window: Long,
            property: Long,
            offset: Long,
            length: Long,
            delete: Int,
            reqType: Long,
            actualTypeOut: MemorySegment,
            actualFormatOut: MemorySegment,
            nitemsOut: MemorySegment,
            bytesAfterOut: MemorySegment,
            propOut: MemorySegment,
        ): Int = xGetWindowProperty.invokeWithArguments(
            display, window, property, offset, length, delete, reqType,
            actualTypeOut, actualFormatOut, nitemsOut, bytesAfterOut, propOut,
        ) as Int
        fun free(ptr: MemorySegment): Int = xFree.invokeWithArguments(ptr) as Int

        companion object {
            val instance: X11? by lazy {
                if (!isLinux) return@lazy null
                runCatching {
                    X11(SymbolLookup.libraryLookup("libX11.so.6", Arena.global()))
                }.onFailure { PikoLog.w(TAG, "libX11.so.6 is unavailable; falling back to AWT fullscreen", it) }
                    .getOrNull()
            }
        }
    }

    internal companion object {
        private const val TAG = "LinuxFullscreen"
        private const val CLIENT_MESSAGE = 33
        private const val NET_WM_STATE_REMOVE = 0L
        private const val NET_WM_STATE_ADD = 1L
        private const val SOURCE_INDICATION_APPLICATION = 1L
        private const val SUBSTRUCTURE_NOTIFY_MASK = 1L shl 19
        private const val SUBSTRUCTURE_REDIRECT_MASK = 1L shl 20
        private const val ROOT_EVENT_MASK = SUBSTRUCTURE_REDIRECT_MASK or SUBSTRUCTURE_NOTIFY_MASK
        private const val ANY_PROPERTY_TYPE = 0L
        private const val XA_ATOM = 4L
        private const val XEVENT_BYTES = 192L
        private const val MAX_WINDOW_TREE_DEPTH = 32
        private const val MAX_ATOM_PROPERTY_WORDS = 1024L
        internal const val TRANSITION_GRACE_NANOS = 250_000_000L

        internal fun isWithinTransitionGrace(lastRequestNanos: Long, nowNanos: Long): Boolean =
            lastRequestNanos != 0L && (nowNanos - lastRequestNanos) in 0L until TRANSITION_GRACE_NANOS
    }
}

/**
 * 监听 Linux 窗口尺寸变化并同步窗口管理器侧的 `_NET_WM_STATE_FULLSCREEN` 状态，
 * 让用户经 KWin/Mutter 快捷键进出全屏时界面与窗口记忆保持一致。
 */
@Composable
internal fun ObserveLinuxFullscreenEffect(
    window: Window,
    fullscreen: LinuxFullscreen?,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    if (!isLinux || fullscreen == null) return
    DisposableEffect(window, fullscreen) {
        // 合并高频 resize 事件，并在 250ms 切换宽限期结束后确保做一次真实的 _NET_WM_STATE 核对，
        // 既避免拖拽改尺寸时每帧同步 X11 往返，又防止 WM 拒绝/单次宽限期内 resize 导致状态永久漂移。
        val settleTimer = javax.swing.Timer(300) {
            onFullscreenChanged(fullscreen.syncFromWindowManager())
        }.apply {
            isRepeats = false
        }
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                settleTimer.restart()
            }
        }
        window.addComponentListener(listener)
        onDispose {
            settleTimer.stop()
            window.removeComponentListener(listener)
        }
    }
}
