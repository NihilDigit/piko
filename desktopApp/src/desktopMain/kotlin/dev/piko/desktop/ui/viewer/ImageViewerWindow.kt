package dev.piko.desktop.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import dev.piko.desktop.isMacOs
import dev.piko.desktop.rememberRememberedWindowState
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsFullscreen
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.ui.ImageViewerRequest
import dev.piko.ui.LocalAppLanguage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.drive.ImageViewerSurface
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.player.PlayerWindowActions
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.workbench.ShortcutsDialog
import io.github.nihildigit.pikpak.FileStat
import java.awt.Dimension
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ViewerTitleBarColors = TitleBarColors(Color.Black, Color.White)

/**
 * 桌面独立看图窗口：承载共享的 [ImageViewerSurface]，提供独立窗口尺寸记忆、无边框窗口标题栏与全屏控制、
 * 置顶开关、快捷键一览及主窗口定位回跳。
 */
@Composable
fun ImageViewerWindow(
    request: ImageViewerRequest,
    services: PikoServices,
    platform: PikoPlatform,
    settings: DesktopSettingsStore,
    appearance: Appearance,
    icon: Painter?,
    onLocate: ((FileStat) -> Unit)? = null,
    onClose: () -> Unit,
) {
    if (request.images.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var isFullscreen by remember { mutableStateOf(false) }
    val windowState = rememberRememberedWindowState(
        settings = settings,
        name = "imageViewer",
        defaultSize = DpSize(1040.dp, 720.dp),
        isBorderlessFullscreen = { isFullscreen },
    )
    val closeScope = rememberCoroutineScope()
    var closeRequested by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (!closeRequested) {
            closeRequested = true
            if (isMacOs && windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = WindowPlacement.Floating
                closeScope.launch {
                    delay(220)
                    onClose()
                }
            } else {
                onClose()
            }
        }
    }

    val initialName = request.images[request.initialIndex.coerceIn(request.images.indices)].name
    var title by remember(request) { mutableStateOf(initialName) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var fallbackKeyHandler by remember { mutableStateOf<((KeyEvent) -> Boolean)?>(null) }
    val shortcutModifier = platform.shortcutModifier
    val windowVisible = !windowState.isMinimized
    val windowTitle = tr(
        "$title - Piko 看图",
        "$title - Piko Viewer",
        "$title - Piko ビューアー",
        "$title - Piko 뷰어",
        "$title - Piko 看圖",
    )

    PikoWindow(
        onCloseRequest = requestClose,
        title = windowTitle,
        icon = icon,
        state = windowState,
        windowVisible = windowVisible,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.F1 || (shortcutModifier.isPressed(event) && event.key == Key.Slash))
            ) {
                shortcutsOpen = !shortcutsOpen
                true
            } else if (event.type == KeyEventType.KeyDown && shortcutModifier.isPressed(event) && event.key == Key.W) {
                requestClose()
                true
            } else if (shortcutsOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                shortcutsOpen = false
                true
            } else {
                false
            }
        },
        onKeyEvent = { event ->
            if (shortcutsOpen) false else fallbackKeyHandler?.invoke(event) == true
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(420, 360)
        }
        TitleBarThemeEffect(window, dark = true)
        PixelAlignedContentEffect(window)
        val windowId = remember(request) { "image-viewer-${System.identityHashCode(request)}" }
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowId, focused) {
            services.onWindowFocusChanged(windowId, focused)
        }
        DisposableEffect(windowId) {
            onDispose { services.onWindowClosed(windowId) }
        }
        val fullscreen = remember(window) { WindowsFullscreen(window) }
        val pointerSource = remember { PointerSource(initialTouchLike = false) }
        val effectiveLanguage by PikoLocale.effectiveLanguageState
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
            LocalDriveAppearancePrefs provides driveAppearancePrefs,
        ) {
            val usesSystemFullscreen = !WinRTSupport.isWindows
            val inFullscreen = if (usesSystemFullscreen) windowState.placement == WindowPlacement.Fullscreen else isFullscreen
            PikoTheme(appearance = appearance) {
                WindowFrame(
                    title = windowTitle,
                    icon = icon,
                    colors = ViewerTitleBarColors,
                    showTitleBar = !inFullscreen && !isMacOs && !shortcutsOpen,
                    onCloseInContent = requestClose,
                    immersiveFullscreen = inFullscreen,
                ) {
                    val toggleFullscreen = {
                        if (usesSystemFullscreen) {
                            windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Fullscreen
                            }
                        } else {
                            if (fullscreen.isFullscreen) fullscreen.exit() else fullscreen.enter()
                            isFullscreen = fullscreen.isFullscreen
                        }
                    }
                    val framelessWindow = LocalFramelessWindow.current
                    var alwaysOnTop by remember(window) { mutableStateOf(window.isAlwaysOnTop) }
                    val effectiveAlwaysOnTop = framelessWindow?.isAlwaysOnTop ?: alwaysOnTop
                    val windowActions = PlayerWindowActions(
                        isMaximized = windowState.placement == WindowPlacement.Maximized,
                        isFullscreen = inFullscreen,
                        isAlwaysOnTop = effectiveAlwaysOnTop,
                        showWindowButtons = WinRTSupport.isWindows,
                        topChromePadding = if (isMacOs && !inFullscreen) 36.dp else 0.dp,
                        onMinimize = { windowState.isMinimized = true },
                        onToggleMaximize = {
                            if (inFullscreen) {
                                toggleFullscreen()
                            } else {
                                windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
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
                        onClose = requestClose,
                    )
                    ImageViewerSurface(
                        images = request.images,
                        initialIndex = request.initialIndex,
                        onDismiss = requestClose,
                        windowActions = windowActions,
                        onToggleFullscreen = toggleFullscreen,
                        onCurrentFileChange = { current -> title = current.name },
                        onLocate = onLocate,
                        externalModalOpen = shortcutsOpen,
                        registerKeyFallback = { fallbackKeyHandler = it },
                        modifier = Modifier.trackInputModality(),
                    )
                    if (shortcutsOpen) {
                        ShortcutsDialog(shortcutModifier, onDismiss = { shortcutsOpen = false })
                    }
                }
            }
        }
    }
}
