package dev.piko.desktop.ui.clips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.PikoWindow
import dev.piko.desktop.PixelAlignedContentEffect
import dev.piko.desktop.TitleBarColors
import dev.piko.desktop.TitleBarThemeEffect
import dev.piko.desktop.WindowFrame
import dev.piko.desktop.bringToFront
import dev.piko.desktop.rememberRememberedWindowState
import dev.piko.ui.ClipFeedLinks
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme

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
) {
    val windowState = rememberRememberedWindowState(settings, "clips", DpSize(1000.dp, 620.dp))
    PikoWindow(
        onCloseRequest = links.dock,
        title = "信息流 - Piko",
        icon = icon,
        state = windowState,
    ) {
        TitleBarThemeEffect(window, dark = true)
        PixelAlignedContentEffect(window)
        LaunchedEffect(raise) { bringToFront(window) }
        // 独立的组合树，主窗口根部的输入来源追踪管不到这里
        val pointerSource = remember { PointerSource() }
        CompositionLocalProvider(
            LocalPikoServices provides services,
            LocalPikoPlatform provides platform,
            LocalPointerSource provides pointerSource,
        ) {
            PikoTheme(appearance = appearance) {
                WindowFrame(
                    title = "信息流 - Piko",
                    icon = icon,
                    colors = ClipTitleBarColors,
                    onCloseInContent = links.dock,
                ) {
                    ClipFeedScreen(
                        // Esc 与返回也是收回主窗口
                        onBackClick = links.dock,
                        onPlayFull = links.playFull,
                        onLocate = links.locate,
                        onDock = links.dock,
                        modifier = Modifier.trackPointerSource(pointerSource),
                    )
                }
            }
        }
    }
}

// 画面四周是黑的，macOS 上仍画的标题栏与之连成一片，不随应用主题
private val ClipTitleBarColors = TitleBarColors(container = Color.Black, content = Color.White)
