package dev.piko.desktop

import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

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
