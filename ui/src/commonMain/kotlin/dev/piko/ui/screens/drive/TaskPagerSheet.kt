package dev.piko.ui.screens.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/**
 * 移动端多个独立任务共用的 standard bottom sheet。调用方持有上下文顺序、活动 key 和展开状态；
 * 关掉某个上下文时从 [models] 移除它，最后一个移除后面板消失。页的 key 不依赖位置，避免关闭前一页后
 * 后续页错接先前页面的组合状态。展开时用单行标签切换完整内容，底部查看更多由 [onViewDetails]
 * 交给独立详情页处理。
 * 面板始终包住 [content]，增删任务不重建浏览页。
 */
@Composable
internal fun TaskSheetScaffold(
    models: List<TaskSheetModel>,
    activeKey: Any?,
    expanded: Boolean,
    onActiveKeyChange: (Any) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onViewDetails: (Any) -> Unit,
    content: @Composable () -> Unit,
) {
    val keys = models.map { it.key }
    require(keys.size == keys.distinct().size) { "Task sheet keys must be unique" }
    val activeIndex = taskSheetPageIndex(keys, activeKey)
    val activeModel = models.getOrNull(activeIndex)
    val tabs = rememberLazyListState()
    // 活动 key 由调用方持有；恢复旧页号会在上下文数量变化后指向另一件事。
    val pagerState = withoutSavedState {
        rememberPagerState(initialPage = activeIndex.coerceAtLeast(0)) {
            models.size.coerceAtLeast(1)
        }
    }
    val latestModels by rememberUpdatedState(models)
    val latestActiveKey by rememberUpdatedState(activeKey)
    val latestOnActiveKeyChange by rememberUpdatedState(onActiveKeyChange)
    val latestOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val latestExpanded by rememberUpdatedState(expanded)

    LaunchedEffect(keys, activeKey, expanded) {
        if (activeIndex >= 0 && pagerState.currentPage != activeIndex) {
            pagerState.scrollToPage(activeIndex)
        }
        if (expanded && activeIndex >= 0) tabs.animateScrollToItem(activeIndex)
    }
    LaunchedEffect(pagerState) {
        var previousPage: Int? = null
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val was = previousPage
            previousPage = page
            if (was == null || was == page) return@collect
            val model = latestModels.getOrNull(page) ?: return@collect
            if (model.key != latestActiveKey) latestOnActiveKeyChange(model.key)
        }
    }

    // SheetValue 不保存在页面状态里：恢复一个当前布局不能达到的档位会在组合时抛异常。
    val sheetState = withoutSavedState {
        rememberBottomSheetState(
            initialValue = if (expanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
        )
    }
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val density = LocalDensity.current
    var headerHeight by remember(density) { mutableIntStateOf(with(density) { 72.dp.roundToPx() }) }
    var containerHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(models.isNotEmpty(), expanded) {
        if (models.isEmpty()) return@LaunchedEffect
        if (expanded) {
            snapshotFlow { sheetState.hasExpandedState }.first { it }
            sheetState.expand()
        } else {
            snapshotFlow { sheetState.hasPartiallyExpandedState }.first { it }
            sheetState.partialExpand()
        }
    }
    LaunchedEffect(sheetState) {
        var previous: SheetValue? = null
        snapshotFlow { sheetState.currentValue }.collect { value ->
            val was = previous
            previous = value
            if (was == null || was == SheetValue.Hidden || value == SheetValue.Hidden) return@collect
            if (latestModels.isEmpty()) return@collect
            val nowExpanded = value == SheetValue.Expanded
            if (nowExpanded != latestExpanded) latestOnExpandedChange(nowExpanded)
        }
    }

    val peekHeight = if (activeModel == null) 0.dp else with(density) { 48.dp + headerHeight.toDp() }
    // 子页的返回处理器在下面组合，优先处理选择、搜索和报告内导航；没有子页动作时才收起面板。
    BackHandler(enabled = activeModel != null && expanded) { latestOnExpandedChange(false) }
    BottomSheetScaffold(
        sheetContent = {
            if (activeModel != null) {
                // 详情较长时内容区滚动，主文件页始终至少露出三成。
                val maxHeight = with(density) { containerHeight.toDp() } * 0.70f - 48.dp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (containerHeight > 0) Modifier.heightIn(max = maxHeight.coerceAtLeast(0.dp)) else Modifier)
                        .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
                ) {
                    if (expanded) {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LazyRow(state = tabs, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                items(models, key = { it.key }) { model ->
                                    val selected = model.key == activeModel.key
                                    Column(
                                        Modifier
                                            .widthIn(min = 88.dp, max = 168.dp)
                                            .fillMaxHeight()
                                            .selectable(selected = selected, role = Role.Tab) { onActiveKeyChange(model.key) }
                                            .semantics { contentDescription = "切换到：${model.tabTitle}" },
                                    ) {
                                        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                model.tabTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Box(
                                            Modifier.fillMaxWidth().height(2.dp).background(
                                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            ),
                                        )
                                    }
                                }
                            }
                            Box(Modifier.padding(end = 8.dp)) { activeModel.actions() }
                        }
                    } else {
                        Box(Modifier.onSizeChanged { headerHeight = it.height }.clickable(onClickLabel = "展开") { onExpandedChange(true) }) {
                            activeModel.header()
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        key = { models[it].key },
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) { page ->
                        val model = models[page]
                        Column(
                            Modifier.fillMaxWidth().then(
                                if (model.key == activeModel.key) Modifier else Modifier.clearAndSetSemantics {},
                            ),
                        ) { model.body(this) }
                    }
                    if (expanded) {
                        Button(
                            onClick = { onViewDetails(activeModel.key) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        ) { Text("查看更多") }
                    }
                }
            }
        },
        modifier = Modifier.onSizeChanged { containerHeight = it.height },
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = if (activeModel != null) ({ BottomSheetDefaults.DragHandle() }) else null,
        sheetSwipeEnabled = activeModel != null,
        sheetContainerColor = ShortNavigationBarDefaults.containerColor,
        containerColor = Color.Transparent,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) { content() }
    }
}

/** Returns -1 when the sheet has no contexts; a removed active key falls back to the first context. */
internal fun taskSheetPageIndex(keys: List<Any>, activeKey: Any?): Int =
    if (keys.isEmpty()) -1 else keys.indexOf(activeKey).takeIf { it >= 0 } ?: 0
