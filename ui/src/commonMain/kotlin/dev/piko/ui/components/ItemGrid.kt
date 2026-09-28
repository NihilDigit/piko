package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 列表每一栏的最小宽度。网盘的列表视图与各个内容列表页（传输、我的分享）同一个数，
 * 窗口越宽栏越多，每一行不随窗口拉长。
 */
val ItemColumnMinWidth = 360.dp

/**
 * 内容列表页的多栏网格，照网盘的列表视图：每栏至少 [ItemColumnMinWidth]，行用 FileListItem，分组标题横跨整行（[fullLineItem]）。
 * 手机上只排得下一栏，与原来的单列一样。
 *
 * 不再把一栏收在居中的阅读宽度里、也不让一行横跨整个卡片：前者宽窗口两边各空出一大块，后者一行名字与一行状态
 * 之间隔着上千 dp。M3 的大屏版式也说列表在 expanded 档改为多栏（lists.md、cards.md 的 Adaptive design）。
 *
 * 用按行对齐的网格，不用瀑布流：瀑布流把每一项放进当前最矮的一栏，行高不一（带不带进度条、说明几行）时
 * 顺序在各栏间跳来跳去；就算高度相同，各栏宽度也会因除不尽差一两个像素，按宽度算高度的卡片就不齐，
 * 第二行的第一项落到最右边去。列表要能按行读。
 */
@Composable
fun PikoItemGrid(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp),
    /** 挂在网格本身上的，比如框选（marqueeSelection）：它按网格里条目的位置算，要与网格同一个原点。 */
    gridModifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(ItemColumnMinWidth),
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().then(gridModifier),
            content = content,
        )
        LocalPikoPlatform.current.ListScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** 横跨所有栏的一项：分组标题、「加载更多」一类。 */
fun LazyGridScope.fullLineItem(
    key: Any,
    contentType: Any? = null,
    content: @Composable LazyGridItemScope.() -> Unit,
) = item(key = key, contentType = contentType, span = { GridItemSpan(maxLineSpan) }, content = content)
