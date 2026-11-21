package dev.piko.desktop.ui.clips

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.PikoWindow
import dev.piko.desktop.PixelAlignedContentEffect
import dev.piko.desktop.TitleBarColors
import dev.piko.desktop.TitleBarThemeEffect
import dev.piko.desktop.WindowFrame
import dev.piko.desktop.bringToFront
import dev.piko.desktop.rememberRememberedWindowState
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.ClipFeedLinks
import dev.piko.ui.LocalAppLanguage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.player.PlayerWindowActions
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.workbench.ShortcutsDialog

/**
 * 信息流从侧栏弹出的独立窗口，同播放窗口：可以一边刷一边浏览网盘，Windows 上没有标题栏，片段顶栏的名字兼做拖动区。
 * 界面与侧栏、窄窗口的全屏形态是同一个 [ClipFeedScreen]；这里只提供窗口与它读的几个 CompositionLocal。
 * 窗口开着时应用内只留占位，同一时刻只有一个 ClipFeedScreen 在组合里，见 PikoMainScaffold。
 *
 * [raise] 每变一次把窗口调到前台：窗口已开着时再点弹出，应当回到它而不是没有反应。
 * 关窗即是收回主窗口（[ClipFeedLinks.dock]），与普通播放窗口一样只有一个 ×：信息流回到侧栏接着刷，
 * 侧栏被占着时挂起。Alt+F4、任务栏上的关闭也走这一条，刷了半天的队列不会一不小心丢掉；关掉信息流在主窗口里做。
 */
@Composable
fun ClipFeedWindow(
    links: ClipFeedLinks,
    raise: Int,
    services: PikoServices,
    platform: PikoPlatform,
    settings: DesktopSettingsStore,
    appearance: Appearance,
    icon: Painter?,
    pausedByHost: Boolean = false,
) {
    val windowState = rememberRememberedWindowState(settings, "clips", DpSize(1000.dp, 620.dp))
    val windowVisible = !windowState.isMinimized
    var shortcutsOpen by remember { mutableStateOf(false) }
    val shortcutModifier = platform.shortcutModifier
    PikoWindow(
        onCloseRequest = links.dock,
        onFatalClose = links.close,
        title = tr("信息流 - Piko", "Feed - Piko", "フィード - Piko", "피드 - Piko", "資訊流 - Piko"),
        icon = icon,
        state = windowState,
        windowVisible = windowVisible,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.F1 || (shortcutModifier.isPressed(event) && event.key == Key.Slash))
            ) {
                shortcutsOpen = !shortcutsOpen
                true
            } else if (shortcutsOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                shortcutsOpen = false
                true
            } else {
                false
            }
        },
    ) {
        TitleBarThemeEffect(window, dark = true)
        PixelAlignedContentEffect(window)
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) {
            services.onWindowFocusChanged("clips", focused)
        }
        DisposableEffect(Unit) {
            onDispose { services.onWindowClosed("clips") }
        }
        LaunchedEffect(raise) { bringToFront(window) }
        // 独立的组合树，主窗口根部的输入来源追踪与布局偏好需要显式注入
        val pointerSource = remember { PointerSource(initialTouchLike = false) }
        val effectiveLanguage = PikoLocale.effectiveLanguageState.value
        val playerLayoutPrefs by services.preferences.playerLayoutPrefsFlow
            .collectAsState(initial = services.preferences.currentSnapshot.playerLayout)
        val driveAppearancePrefs by services.preferences.driveAppearancePrefsFlow
            .collectAsState(initial = services.preferences.currentSnapshot.driveAppearance)
        CompositionLocalProvider(
            LocalAppLanguage provides effectiveLanguage,
            LocalPikoServices provides services,
            LocalPikoPlatform provides platform,
            LocalPointerSource provides pointerSource,
            LocalWindowVisible provides windowVisible,
            LocalPlayerLayoutPrefs provides playerLayoutPrefs,
            dev.piko.ui.screens.drive.LocalDriveAppearancePrefs provides driveAppearancePrefs,
        ) {
            PikoTheme(appearance = appearance) {
                WindowFrame(
                    title = tr("信息流 - Piko", "Feed - Piko", "フィード - Piko", "피드 - Piko", "資訊流 - Piko"),
                    icon = icon,
                    colors = ClipTitleBarColors,
                    showTitleBar = windowState.placement != WindowPlacement.Fullscreen,
                    onCloseInContent = links.dock,
                    immersiveFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                ) {
                    val framelessWindow = LocalFramelessWindow.current
                    var alwaysOnTop by remember(window) { mutableStateOf(window.isAlwaysOnTop) }
                    val effectiveAlwaysOnTop = framelessWindow?.isAlwaysOnTop ?: alwaysOnTop
                    val windowActions = PlayerWindowActions(
                        isMaximized = windowState.placement == WindowPlacement.Maximized,
                        isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                        isAlwaysOnTop = effectiveAlwaysOnTop,
                        showWindowButtons = WinRTSupport.isWindows,
                        onMinimize = { windowState.isMinimized = true },
                        onToggleMaximize = {
                            windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                        },
                        onToggleAlwaysOnTop = {
                            val next = !effectiveAlwaysOnTop
                            if (framelessWindow != null) {
                                framelessWindow.setAlwaysOnTop(next)
                            } else {
                                window.isAlwaysOnTop = next
                            }
                            alwaysOnTop = next
                        },
                        onClose = links.dock,
                    )
                    ClipFeedScreen(
                        // Esc 与返回也是收回主窗口
                        onBackClick = links.dock,
                        onPlayFull = links.playFull,
                        onLocate = links.locate,
                        onDock = links.dock,
                        pausedByHost = pausedByHost,
                        windowActions = windowActions,
                        modifier = Modifier.trackPointerSource(pointerSource),
                    )
                    if (shortcutsOpen) {
                        ShortcutsDialog(shortcutModifier, onDismiss = { shortcutsOpen = false })
                    }
                }
            }
        }
    }
}

// 画面四周是黑的，macOS 上仍画的标题栏与之连成一片，不随应用主题
private val ClipTitleBarColors = TitleBarColors(container = Color.Black, content = Color.White)
