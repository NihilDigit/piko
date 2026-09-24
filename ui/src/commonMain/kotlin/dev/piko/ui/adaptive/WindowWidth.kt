package dev.piko.ui.adaptive

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * 窗口宽度档位，按 M3 的断点：compact 小于 600dp，medium 到 840dp，expanded 及以上。
 * 桌面窗口缩放与平板分屏走同一套判断，布局只看窗口多宽，不看是什么设备。
 */
enum class WidthClass { Compact, Medium, Expanded }

@Composable
fun currentWidthClass(): WidthClass {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.Medium
        else -> WidthClass.Compact
    }
}

/**
 * 单栏内容在宽窗口里的最大行长。M3 布局规范要求宽窗口控制行长，而不是把一行设置或
 * 一条传输任务拉满两千像素；超出部分留白，内容居中。
 */
val ReadableContentMaxWidth = 840.dp

fun Modifier.readableWidth(): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ReadableContentMaxWidth)
