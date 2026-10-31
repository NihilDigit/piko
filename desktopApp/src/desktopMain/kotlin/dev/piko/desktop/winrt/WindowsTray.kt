package dev.piko.desktop.winrt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Windows 原生 Unicode 系统托盘与右键菜单。
 *
 * Compose Desktop 的 Tray 底层使用 java.awt.PopupMenu，而 JDK 18+ 默认 UTF-8 字符集下，
 * Windows AWT 的 WMenuItemPeer 仍将 UTF-8 字节直接传给 ANSI 接口 AppendMenuA，在中文 Windows
 * （CP936/GBK）上必然把非 ASCII 菜单项显示为乱码。这里直接使用 java.awt.TrayIcon（仅承载图标与
 * 宽字符 Tooltip）配合 FFM 直调 user32.dll 的宽字符菜单接口（CreatePopupMenu + AppendMenuW +
 * TrackPopupMenu），彻底消除编码乱码并保留 Windows 11 原生圆角与深浅色菜单样式。
 */
@Composable
internal fun WindowsSystemTray(
    tooltip: String,
    darkTheme: Boolean,
    showLabel: String,
    quitLabel: String,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    val currentShowLabel by rememberUpdatedState(showLabel)
    val currentQuitLabel by rememberUpdatedState(quitLabel)
    val currentDarkTheme by rememberUpdatedState(darkTheme)
    val currentOnShow by rememberUpdatedState(onShow)
    val currentOnQuit by rememberUpdatedState(onQuit)

    val awtImage: Image = remember {
        runCatching {
            object {}.javaClass.getResourceAsStream("/app-icon.png")?.use(ImageIO::read)
        }.getOrNull() ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    }

    val trayIcon = remember(awtImage) {
        val menuShowing = AtomicBoolean(false)
        TrayIcon(awtImage, tooltip, null).apply {
            isImageAutoSize = true
            addActionListener {
                SwingUtilities.invokeLater { currentOnShow() }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) {
                        SwingUtilities.invokeLater { currentOnShow() }
                    } else if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) {
                        if (!menuShowing.compareAndSet(false, true)) return
                        val showText = currentShowLabel
                        val quitText = currentQuitLabel
                        val dark = currentDarkTheme
                        Thread({
                            try {
                                when (Win32PopupMenu.show(showText, quitText, dark)) {
                                    ID_SHOW -> SwingUtilities.invokeLater { currentOnShow() }
                                    ID_QUIT -> SwingUtilities.invokeLater { currentOnQuit() }
                                }
                            } finally {
                                menuShowing.set(false)
                            }
                        }, "Piko-Tray-Menu").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            })
        }
    }

    LaunchedEffect(trayIcon, tooltip) {
        SwingUtilities.invokeLater { trayIcon.toolTip = tooltip }
    }

    DisposableEffect(trayIcon) {
        val tray = runCatching { SystemTray.getSystemTray() }.getOrNull()
        SwingUtilities.invokeLater {
            runCatching { tray?.add(trayIcon) }
        }
        onDispose {
            SwingUtilities.invokeLater {
                runCatching { tray?.remove(trayIcon) }
            }
        }
    }
}

private const val ID_SHOW = 1001
private const val ID_QUIT = 1002

private object Win32PopupMenu {
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_EX_TOOLWINDOW = 0x00000080

    private const val MF_STRING = 0x0000
    private const val MF_SEPARATOR = 0x0800

    private const val TPM_RIGHTBUTTON = 0x0002
    private const val TPM_BOTTOMALIGN = 0x0020
    private const val TPM_RETURNCMD = 0x0100

    private const val WM_NULL = 0x0000

    // uxtheme.dll 未公开导出序号（自 Windows 10 1903 起稳定用于 Win32 原生弹出菜单深浅色）
    private const val PREFERRED_APP_MODE_FORCE_DARK = 2
    private const val PREFERRED_APP_MODE_FORCE_LIGHT = 3

    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32", Arena.global())

    private fun fn(name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(user32.find(name).orElseThrow(), descriptor)

    private val createWindowExW = fn(
        "CreateWindowExW",
        FunctionDescriptor.of(
            ADDRESS,
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS,
        ),
    )
    private val createPopupMenu = fn("CreatePopupMenu", FunctionDescriptor.of(ADDRESS))
    private val appendMenuW = fn("AppendMenuW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS))
    private val setMenuDefaultItem = fn("SetMenuDefaultItem", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
    private val destroyMenu = fn("DestroyMenu", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val destroyWindow = fn("DestroyWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val getCursorPos = fn("GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val setForegroundWindow = fn("SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    private val trackPopupMenu = fn(
        "TrackPopupMenu",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
    )
    private val postMessageW = fn("PostMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG))

    private val setPreferredAppMode: MethodHandle? by lazy {
        runCatching {
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val loadLib = linker.downcallHandle(
                kernel32.find("LoadLibraryW").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS),
            )
            val getProc = linker.downcallHandle(
                kernel32.find("GetProcAddress").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            )
            Arena.ofConfined().use { arena ->
                val hUxTheme = loadLib.invokeWithArguments(arena.utf16Z("uxtheme.dll")) as MemorySegment
                if (hUxTheme == MemorySegment.NULL) return@runCatching null
                val addr = getProc.invokeWithArguments(hUxTheme, MemorySegment.ofAddress(135L)) as MemorySegment
                if (addr == MemorySegment.NULL) null
                else linker.downcallHandle(addr, FunctionDescriptor.of(JAVA_INT, JAVA_INT))
            }
        }.getOrNull()
    }

    private val flushMenuThemes: MethodHandle? by lazy {
        runCatching {
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val loadLib = linker.downcallHandle(
                kernel32.find("LoadLibraryW").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS),
            )
            val getProc = linker.downcallHandle(
                kernel32.find("GetProcAddress").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
            )
            Arena.ofConfined().use { arena ->
                val hUxTheme = loadLib.invokeWithArguments(arena.utf16Z("uxtheme.dll")) as MemorySegment
                if (hUxTheme == MemorySegment.NULL) return@runCatching null
                val addr = getProc.invokeWithArguments(hUxTheme, MemorySegment.ofAddress(136L)) as MemorySegment
                if (addr == MemorySegment.NULL) null
                else linker.downcallHandle(addr, FunctionDescriptor.ofVoid())
            }
        }.getOrNull()
    }

    fun show(showLabel: String, quitLabel: String, darkTheme: Boolean): Int = runCatching {
        runCatching {
            val mode = if (darkTheme) PREFERRED_APP_MODE_FORCE_DARK else PREFERRED_APP_MODE_FORCE_LIGHT
            setPreferredAppMode?.invokeWithArguments(mode)
            flushMenuThemes?.invokeWithArguments()
        }
        Arena.ofConfined().use { arena ->
            // Win32 HWND 绑定创建线程的消息队列；在当前菜单线程内创建并在退出时销毁，确保多次呼出始终有效
            val hwnd = createWindowExW.invokeWithArguments(
                WS_EX_TOOLWINDOW,
                arena.utf16Z("STATIC"),
                arena.utf16Z(""),
                WS_POPUP,
                0, 0, 0, 0,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
            ) as MemorySegment
            if (hwnd == MemorySegment.NULL) return 0
            try {
                val hMenu = createPopupMenu.invokeWithArguments() as MemorySegment
                if (hMenu == MemorySegment.NULL) return 0
                try {
                    appendMenuW.invokeWithArguments(hMenu, MF_STRING, ID_SHOW.toLong(), arena.utf16Z(showLabel))
                    appendMenuW.invokeWithArguments(hMenu, MF_SEPARATOR, 0L, MemorySegment.NULL)
                    appendMenuW.invokeWithArguments(hMenu, MF_STRING, ID_QUIT.toLong(), arena.utf16Z(quitLabel))
                    setMenuDefaultItem.invokeWithArguments(hMenu, ID_SHOW, 0)

                    val pt = arena.allocate(8L, 4L)
                    getCursorPos.invokeWithArguments(pt)
                    val x = pt.get(JAVA_INT, 0L)
                    val y = pt.get(JAVA_INT, 4L)

                    setForegroundWindow.invokeWithArguments(hwnd)
                    val selected = trackPopupMenu.invokeWithArguments(
                        hMenu,
                        TPM_RETURNCMD or TPM_RIGHTBUTTON or TPM_BOTTOMALIGN,
                        x,
                        y,
                        0,
                        hwnd,
                        MemorySegment.NULL,
                    ) as Int
                    postMessageW.invokeWithArguments(hwnd, WM_NULL, 0L, 0L)
                    selected
                } finally {
                    destroyMenu.invokeWithArguments(hMenu)
                }
            } finally {
                destroyWindow.invokeWithArguments(hwnd)
            }
        }
    }.getOrDefault(0)

    private fun Arena.utf16Z(text: String): MemorySegment {
        val chars = text.toCharArray()
        val segment = allocate((chars.size + 1L) * 2L, 2L)
        for (i in chars.indices) {
            segment.set(JAVA_CHAR, i * 2L, chars[i])
        }
        segment.set(JAVA_CHAR, chars.size * 2L, '\u0000')
        return segment
    }
}
