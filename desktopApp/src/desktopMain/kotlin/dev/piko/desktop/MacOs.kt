package dev.piko.desktop

import java.awt.Desktop
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Taskbar
import java.awt.Window
import java.awt.desktop.AppReopenedListener
import java.io.File
import javax.swing.RootPaneContainer

internal val isMacOs: Boolean = System.getProperty("os.name").startsWith("Mac")

/**
 * macOS 经 Apple 事件而不是启动参数交来应用级请求，要在 main 里尽早接上：
 * - 点开 magnet: 链接时，URL 以 openURI 事件送达，无论 Piko 是刚被拉起还是已在运行。
 * - Cmd+Q 与 Dock 菜单的「退出」默认直接结束进程，会绕过关窗时「传输未完藏进后台」的判断，
 *   这里先拦下，交给 [onQuit] 按关窗的规则处理。
 * - 窗口藏起来之后点 Dock 图标，系统只发 reopen 事件，不会自己把窗口叫回来。
 */
internal fun installMacHandlers(onOpenUri: (String) -> Unit, onQuit: () -> Unit, onReopen: () -> Unit) {
    if (!isMacOs || !Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
        desktop.setOpenURIHandler { event -> onOpenUri(event.uri.toString()) }
    }
    if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
        desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            onQuit()
        }
    }
    if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
        desktop.addAppEventListener(AppReopenedListener { onReopen() })
    }
}

/** macOS 上与 WinRTSupport 对应的系统能力。调用方先判断 [isMacOs]。 */
internal object MacOs {
    /**
     * 系统通知。经 osascript 发出，通知中心里署名为「脚本编辑器」：以 Piko 自己的名义发要用
     * UNUserNotificationCenter，它只认签过名的 bundle，现在的包没有签名。
     */
    fun showNotification(title: String, message: String): Boolean = runCatching {
        val script = "display notification ${appleScriptString(message)} with title ${appleScriptString(title)}"
        ProcessBuilder("osascript", "-e", script).start().waitFor() == 0
    }.getOrDefault(false)

    private fun appleScriptString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * 阻止显示器与系统休眠，直到返回值被关闭。caffeinate 带 -w 盯着本进程，Piko 崩溃时它也跟着退出，
     * 不会留下一个一直不让睡的进程。
     */
    fun preventSleep(): AutoCloseable? = runCatching {
        val process = ProcessBuilder("caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString()).start()
        AutoCloseable { process.destroy() }
    }.getOrNull()

    /** 在 Finder 里打开所在文件夹并选中该文件。 */
    fun revealInFinder(file: File) {
        runCatching { ProcessBuilder("open", "-R", file.absolutePath).start() }
    }

    /**
     * 系统的目录框。AWT 的 FileDialog 在 macOS 上就是原生面板，这个属性让它改选目录；
     * 属性在弹出时读取，用完放回，免得之后的选文件框也只能选目录。取消时为 null。
     */
    fun pickFolder(owner: Window?, initial: File?, title: String): File? {
        System.setProperty(DIRECTORY_DIALOG_PROPERTY, "true")
        val dialog = when (owner) {
            is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
            else -> FileDialog(owner as? Frame, title, FileDialog.LOAD)
        }
        initial?.let { dialog.directory = it.absolutePath }
        try {
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return File(dialog.directory, name)
        } finally {
            dialog.dispose()
            System.setProperty(DIRECTORY_DIALOG_PROPERTY, "false")
        }
    }

    /** 标题栏与窗口边框的深浅。JDK 在 macOS 上读根面板的这个属性，设成 NSAppearance 的名字。 */
    fun setWindowAppearance(window: Window, dark: Boolean) {
        val rootPane = (window as? RootPaneContainer)?.rootPane ?: return
        rootPane.putClientProperty("apple.awt.windowAppearance", if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
    }

    /** Dock 图标上的进度条。[percent] 为 null 时隐藏；Dock 没有不确定进度的样式。 */
    fun setDockProgress(percent: Int?) {
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.PROGRESS_VALUE)) return
            taskbar.setProgressValue(percent ?: -1)
        }
    }

    private const val DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"
}
