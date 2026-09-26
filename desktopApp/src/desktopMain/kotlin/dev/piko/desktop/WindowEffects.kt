package dev.piko.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.piko.desktop.winrt.TaskbarProgress
import dev.piko.desktop.winrt.WindowChrome
import dev.piko.download.DownloadStatus
import dev.piko.shared.download.PikoDownloadCoordinator
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Window
import javax.swing.JFrame
import kotlin.math.abs
import kotlin.math.round

/**
 * 让 Compose 画面与窗口像素一比一，不被系统拉伸。
 *
 * skiko 0.150 的 Direct3D 交换链按 SkiaLayer 的逻辑尺寸乘缩放后截断取整，承载它的 Canvas 子窗口却在
 * 乘积带 .5 时多加 1 个逻辑像素（skiko 的 adjustSizeToContentScale，为了不在窗口边缘露白线）。子窗口
 * 客户区因此比 backbuffer 大 1 到 2 个物理像素，DXGI 把整帧拉伸上去，所有文字与细线被重采样。150% 下
 * 逻辑宽高任一为奇数、125% 下多数尺寸都会中招，最大化也不例外；100% 与 200% 不受影响。
 *
 * 这里把 Compose 面板的逻辑尺寸向上取到「乘以缩放恰为整数」的倍数，逻辑尺寸、子窗口与 backbuffer 三者
 * 便相等。多出的不足一步（150% 下 1 个、125% 下至多 3 个逻辑像素）落在客户区外，被父窗口裁掉；向下取
 * 会在右下露出一条窗口底色，深色主题下看得见。改用 OpenGL 同样不拉伸，但播放窗口的 MediaMP 画面表面要 Direct3D。
 */
@Composable
fun PixelAlignedContentEffect(window: JFrame) {
    DisposableEffect(window) {
        val pane = window.contentPane
        val original = pane.layout
        pane.layout = PixelAlignedLayout
        pane.revalidate()
        onDispose { pane.layout = original }
    }
}

private object PixelAlignedLayout : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}
    override fun preferredLayoutSize(parent: Container): Dimension = parent.components.firstOrNull()?.preferredSize ?: Dimension()
    override fun minimumLayoutSize(parent: Container): Dimension = parent.components.firstOrNull()?.minimumSize ?: Dimension()

    // 显示器缩放变化时 skiko 会 revalidate SkiaLayer，失效一路传到 JRootPane，这里随之按新缩放重排
    override fun layoutContainer(parent: Container) {
        val step = pixelAlignedStep(parent.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0)
        val width = roundUp(parent.width, step)
        val height = roundUp(parent.height, step)
        for (child in parent.components) child.setBounds(0, 0, width, height)
    }

    /** 乘以 [scale] 得整数的最小逻辑像素数：125% 为 4，150% 为 2，200% 为 1。 */
    private fun pixelAlignedStep(scale: Double): Int =
        (1..8).firstOrNull { abs(it * scale - round(it * scale)) < 1e-4 } ?: 1

    private fun roundUp(value: Int, step: Int): Int = (value + step - 1) / step * step
}

/**
 * 系统画的窗口外框跟随应用主题。标题栏已由 [WindowFrame] 自绘，这里管的是剩下仍归系统的部分：
 * Windows 上是 DWM 画的 1px 边框，macOS 上是红绿灯与窗口边缘。不设的话，系统是浅色而
 * 应用选了深色时，深色窗口外围一圈浅色描边。
 *
 * 用 DisposableEffect 而不是 LaunchedEffect：前者在组合提交时同步执行，赶在窗口首次显示之前，
 * 后者要等下一帧，窗口会先闪一下浅色标题栏。
 */
@Composable
fun TitleBarThemeEffect(window: Window, dark: Boolean) {
    DisposableEffect(window, dark) {
        if (isMacOs) MacOs.setWindowAppearance(window, dark) else WindowChrome.setDarkTitleBar(window, dark)
        onDispose {}
    }
}

/** 任务栏按钮（macOS 是 Dock 图标）上显示正在进行的下载的总进度。暂停与失败的任务不计入，没有进行中的任务时清除。 */
@Composable
fun TaskbarDownloadProgress(window: Window, downloads: PikoDownloadCoordinator) {
    LaunchedEffect(window, downloads) {
        downloads.tasks.collect { tasks ->
            val active = tasks.values.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            val total = active.sumOf { it.totalBytes }
            if (isMacOs) {
                MacOs.setDockProgress(if (active.isEmpty() || total <= 0) null else (active.sumOf { it.downloadedBytes } * 100 / total).toInt())
                return@collect
            }
            when {
                active.isEmpty() -> TaskbarProgress.clear(window)
                // 总大小未知，给不出百分比
                total <= 0 -> TaskbarProgress.setState(window, TaskbarProgress.State.INDETERMINATE)
                else -> {
                    TaskbarProgress.setState(window, TaskbarProgress.State.NORMAL)
                    TaskbarProgress.setProgress(window, active.sumOf { it.downloadedBytes }, total)
                }
            }
        }
    }
}
