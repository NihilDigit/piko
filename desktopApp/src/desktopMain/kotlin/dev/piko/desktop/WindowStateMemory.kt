package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * 记住窗口上次的位置、大小与是否最大化，存在 settings.properties 的 window.<name>.* 下。
 *
 * 只在窗口处于普通状态时记录位置与大小：最大化与全屏时 WindowState 里的尺寸是铺满屏幕的
 * 那份，存下来会让取消最大化后窗口仍然占满屏幕。全屏不作为启动状态恢复，开播放器就全屏
 * 不是用户想要的。
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberRememberedWindowState(
    settings: DesktopSettingsStore,
    name: String,
    defaultSize: DpSize,
    /** 无边框全屏（见 WindowsFullscreen）不经过 placement，由调用方告知。 */
    isBorderlessFullscreen: () -> Boolean = { false },
): WindowState {
    val prefix = "window.$name."
    val state = rememberWindowState(
        placement = if (settings.get(prefix + "maximized") == "true") WindowPlacement.Maximized else WindowPlacement.Floating,
        position = savedPosition(settings, prefix) ?: WindowPosition.PlatformDefault,
        size = savedSize(settings, prefix) ?: defaultSize,
    )
    LaunchedEffect(state) {
        // 拖动与缩放时每帧都在变，停下来再写盘
        snapshotFlow { Triple(state.placement, state.position, state.size) }
            .debounce(500)
            .collect { (placement, position, size) ->
                if (placement == WindowPlacement.Fullscreen || isBorderlessFullscreen()) return@collect
                settings.set(prefix + "maximized", (placement == WindowPlacement.Maximized).toString())
                if (placement != WindowPlacement.Floating) return@collect
                if (position is WindowPosition.Absolute) {
                    settings.set(prefix + "x", position.x.value.toString())
                    settings.set(prefix + "y", position.y.value.toString())
                }
                settings.set(prefix + "width", size.width.value.toString())
                settings.set(prefix + "height", size.height.value.toString())
            }
    }
    return state
}

private fun savedSize(settings: DesktopSettingsStore, prefix: String): DpSize? {
    val width = settings.get(prefix + "width").toFloatOrNull() ?: return null
    val height = settings.get(prefix + "height").toFloatOrNull() ?: return null
    return DpSize(width.dp, height.dp)
}

/**
 * 上次的位置落在已拔掉的显示器上时不恢复，交给系统摆放，否则窗口开在看不见的地方。
 * 标题栏左端要在某块屏幕上能抓到，才算可见。
 */
private fun savedPosition(settings: DesktopSettingsStore, prefix: String): WindowPosition? {
    val x = settings.get(prefix + "x").toFloatOrNull() ?: return null
    val y = settings.get(prefix + "y").toFloatOrNull() ?: return null
    val titleBarGrip = Rectangle(x.toInt(), y.toInt(), 120, 32)
    val visible = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.intersects(titleBarGrip) }
    }.getOrDefault(false)
    return if (visible) WindowPosition(x.dp, y.dp) else null
}
