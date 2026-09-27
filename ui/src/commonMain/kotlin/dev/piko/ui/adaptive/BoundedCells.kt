package dev.piko.ui.adaptive

import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 列宽落在 [minWidth] 与 [maxWidth] 之间的网格：列数取让每列不超过上限的最小值，但不多到让列宽
 * 跌破下限；两者冲突时（窗口窄到连一列都装不满下限之上的范围）宁可宽一些。余宽均分。
 *
 * `StaggeredGridCells.Adaptive` 只规定下限，列数向下取整，一格可以宽到接近两倍：侧边导航栏右边
 * 719dp 的内容区里，列表视图只有一列、一行 719dp 宽，名字在左、更多按钮远在右端。
 */
fun boundedStaggeredCells(minWidth: Dp, maxWidth: Dp): StaggeredGridCells = BoundedStaggeredCells(minWidth, maxWidth)

private class BoundedStaggeredCells(private val minWidth: Dp, private val maxWidth: Dp) : StaggeredGridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): IntArray {
        // 每列连同它右边那道间距占一份，末列没有间距，所以可用宽度补上一道再除
        val span = availableSize + spacing
        val fewest = ceil(span.toFloat() / (maxWidth.roundToPx() + spacing)).toInt()
        val most = span / (minWidth.roundToPx() + spacing)
        val count = max(1, min(fewest, max(most, 1)))
        val usable = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val base = usable / count
        val remainder = usable % count
        return IntArray(count) { index -> base + if (index < remainder) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean =
        other is BoundedStaggeredCells && other.minWidth == minWidth && other.maxWidth == maxWidth

    override fun hashCode(): Int = 31 * minWidth.hashCode() + maxWidth.hashCode()
}
