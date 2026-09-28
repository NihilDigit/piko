package dev.piko.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    ThemedScrollbar(scrolling = state.isScrollInProgress, modifier = modifier) { barModifier, style, interactions ->
        VerticalScrollbar(adapter, barModifier, style = style, interactionSource = interactions)
    }
}

@Composable
internal fun ListScrollbar(state: LazyGridState, modifier: Modifier) {
    val adapter = rememberScrollbarAdapter(state)
    ThemedScrollbar(scrolling = state.isScrollInProgress, modifier = modifier) { barModifier, style, interactions ->
        VerticalScrollbar(adapter, barModifier, style = style, interactionSource = interactions)
    }
}

/**
 * 细、圆头、不用时淡出。默认样式是一根常驻的直角灰条，立在内容区边上，比列表本身还显眼；
 * 颜色也是固定的灰，深色主题下几乎看不见。
 *
 * 淡出只改透明度，不移出组合：滚动条的命中区域还在原处，鼠标移过去照样唤出、照样能拖。
 */
@Composable
private fun ThemedScrollbar(
    scrolling: Boolean,
    modifier: Modifier,
    bar: @Composable (Modifier, ScrollbarStyle, MutableInteractionSource) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val dragged by interactions.collectIsDraggedAsState()
    // 滚轮一格只让 isScrollInProgress 亮一两百毫秒，直接跟着它走的话滚动条一闪就没了，
    // 读不出自己在哪儿。停下后再留一会儿
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            recentlyScrolled = true
        } else {
            delay(ScrollbarLingerMillis)
            recentlyScrolled = false
        }
    }
    val visible = scrolling || recentlyScrolled || hovered || dragged
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrollbarAlpha")
    val colors = MaterialTheme.colorScheme
    bar(
        modifier.fillMaxHeight().padding(vertical = 4.dp, horizontal = ScrollbarEdgeGap).alpha(alpha),
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = CircleShape,
            hoverDurationMillis = 150,
            unhoverColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
            hoverColor = colors.onSurfaceVariant.copy(alpha = 0.7f),
        ),
        interactions,
    )
}

private const val ScrollbarLingerMillis = 1200L

/** 离窗口边缘留一线，贴死在边上时圆头的外半边会被切平。 */
private val ScrollbarEdgeGap = 2.dp
