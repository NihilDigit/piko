package dev.piko.desktop

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
internal fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier.fillMaxHeight(), style = themedScrollbarStyle())
}

@Composable
internal fun ListScrollbar(state: LazyStaggeredGridState, modifier: Modifier) {
    val adapter = remember(state) { StaggeredGridScrollbarAdapter(state) }
    VerticalScrollbar(adapter, modifier.fillMaxHeight(), style = themedScrollbarStyle())
}

/** 默认样式是固定的灰色，深色主题下几乎看不见，改为跟随主题。 */
@Composable
private fun themedScrollbarStyle(): ScrollbarStyle {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return LocalScrollbarStyle.current.copy(
        unhoverColor = onSurface.copy(alpha = 0.24f),
        hoverColor = onSurface.copy(alpha = 0.48f),
    )
}

/**
 * foundation 只给列表与规则网格提供适配器，瀑布流没有。瀑布流各列高度不一，没有「行」可数，
 * 这里按可见条目的平均高度估算整体高度与当前位置。可见条目分布在几列里，跨度除以条目数
 * 得到的是按列摊薄后的高度，乘以总条目数正好是整体高度的估计。
 */
private class StaggeredGridScrollbarAdapter(private val state: LazyStaggeredGridState) : ScrollbarAdapter {
    private fun averageItemExtent(): Double {
        val items = state.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return 0.0
        val top = items.minOf { it.offset.y }
        val bottom = items.maxOf { it.offset.y + it.size.height }
        return (bottom - top).toDouble() / items.size
    }

    override val viewportSize: Double
        get() = with(state.layoutInfo) { (viewportEndOffset - viewportStartOffset).toDouble() }

    override val contentSize: Double
        get() = with(state.layoutInfo) {
            averageItemExtent() * totalItemsCount + beforeContentPadding + afterContentPadding
        }

    override val scrollOffset: Double
        get() = averageItemExtent() * state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset

    override suspend fun scrollTo(scrollOffset: Double) {
        val extent = averageItemExtent()
        val count = state.layoutInfo.totalItemsCount
        if (extent <= 0.0 || count == 0) return
        val target = scrollOffset.coerceIn(0.0, (contentSize - viewportSize).coerceAtLeast(0.0))
        val index = (target / extent).toInt().coerceIn(0, count - 1)
        state.scrollToItem(index, (target - index * extent).toInt())
    }
}
