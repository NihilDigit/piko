package dev.piko.ui.screens.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.TooltipIconButton
import kotlinx.coroutines.flow.first

/** 在 [block] 里 rememberSaveable 退化成 remember：不往页面的保存表里存，也不从中恢复。 */
@Composable
private fun <T> withoutSavedState(block: @Composable () -> T): T {
    var result: T? = null
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * 窄窗口网盘页底部那一块 sheet 里眼下放的东西：查找重复或添加链接，同一时刻只有一件（见 workbench/TaskSlot）。
 * 收起时露出 [header]，展开再看到 [body]。
 */
internal class TaskSheetModel(
    /** 换了一件事时按它重新定档。 */
    val key: Any,
    val expanded: Boolean,
    val onExpandedChange: (Boolean) -> Unit,
    val header: @Composable () -> Unit,
    val body: @Composable ColumnScope.() -> Unit,
)

/**
 * M3 的 standard bottom sheet，两档：展开与部分展开（只露出头一截），没有隐藏这一档，关掉只能经头上的关闭。
 * 不用 modal：modal 挡住整个界面，而这里要能收起来回文件夹里看一眼、接着操作（bottom-sheets.md 的 Standard bottom sheets）。
 * 部分展开是同一个容器停在低的一档，不是另画一条把手：曾经收起后另留一条把手、状态条，那是窄窗口里另造的挂起状态。
 * 拖动条点一下在两档间切换，这是 M3 要的不靠拖动的入口；收起时点露出的那一截也展开。
 *
 * [model] 为 null 时没有 sheet，只剩 [content]。不按有无另包一层：换一层包装会让整个网盘页重建。
 */
@Composable
internal fun TaskSheetScaffold(model: TaskSheetModel?, content: @Composable () -> Unit) {
    // 不存不恢复：停在哪一档由下面按 model.expanded 推，用不着存下来的。存下来反而会出事：网盘页离开组合再回来时
    // （桌面从设置返回即是）库按存下的值重建，存下的是不在 enabledValues 里的档位时当场抛异常，整个窗口崩掉（2026-10-08）
    val sheetState = withoutSavedState {
        rememberBottomSheetState(
            initialValue = if (model?.expanded == true) SheetValue.Expanded else SheetValue.PartiallyExpanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
        )
    }
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val density = LocalDensity.current
    var headerHeight by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    val latestModel by rememberUpdatedState(model)

    val wantExpanded = model?.expanded == true
    LaunchedEffect(model?.key, wantExpanded) {
        if (model == null) return@LaunchedEffect
        // 刚出现时内容还没量过，没有那一档可去
        if (wantExpanded) {
            snapshotFlow { sheetState.hasExpandedState }.first { it }
            sheetState.expand()
        } else {
            snapshotFlow { sheetState.hasPartiallyExpandedState }.first { it }
            sheetState.partialExpand()
        }
    }
    // 人拖动或点拖动条换了一档，记回去。头一个值、换了一件事、从没有 sheet（Hidden）里出来，都是布局在定档，不是人换的：
    // 当成人换的会把刚打开的添加链接记成收起，而什么都没粘过的收起就是结束
    LaunchedEffect(sheetState) {
        var previous: Pair<Any?, SheetValue>? = null
        snapshotFlow { latestModel?.key to sheetState.currentValue }.collect { now ->
            val before = previous
            previous = now
            val current = latestModel ?: return@collect
            if (before == null || before.first != now.first) return@collect
            if (before.second == SheetValue.Hidden || now.second == SheetValue.Hidden) return@collect
            val expanded = now.second == SheetValue.Expanded
            if (expanded != current.expanded) current.onExpandedChange(expanded)
        }
    }

    val peekHeight = if (model == null) 0.dp else with(density) { DragHandleHeight + headerHeight.toDp() }
    BottomSheetScaffold(
        sheetContent = {
            if (model != null) {
                // 展开到顶也留出 M3 的上边距，网盘页的顶栏仍看得见
                val maxHeight = with(density) { containerHeight.toDp() } - SheetTopMargin - DragHandleHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (containerHeight > 0) Modifier.heightIn(max = maxHeight.coerceAtLeast(0.dp)) else Modifier)
                        // 底部导航栏已让出手势横条，键盘弹起时只再让出多出来的那一截
                        .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
                ) {
                    Box(
                        Modifier
                            .onSizeChanged { headerHeight = it.height }
                            .then(if (model.expanded) Modifier else Modifier.clickable(onClickLabel = "展开") { model.onExpandedChange(true) }),
                    ) {
                        model.header()
                    }
                    model.body(this)
                }
            }
        },
        modifier = Modifier.onSizeChanged { containerHeight = it.height },
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = if (model != null) ({ BottomSheetDefaults.DragHandle() }) else null,
        sheetSwipeEnabled = model != null,
        // 与底部导航栏同色，收起时露出的那一截与导航栏连成一片，见 PikoSheet
        sheetContainerColor = ShortNavigationBarDefaults.containerColor,
        containerColor = Color.Transparent,
    ) { padding ->
        // 网盘页让出露着的那一截：列表末尾、FAB 与 Snackbar 都在它上面
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) { content() }
    }
    BackHandler(enabled = model?.expanded == true) { latestModel?.onExpandedChange(false) }
}

/**
 * sheet 头上那一截，收起时只露出它：标题、状态，进行中时一条进度，右端是结束。
 * [action] 是收起时也要够得着的那一步（扫完了的「查看」）。
 */
@Composable
internal fun TaskSheetHeader(
    title: String,
    status: String?,
    closeLabel: String,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    progress: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            TooltipIconButton(Icons.Outlined.Close, closeLabel, onClose, enabled = closeEnabled)
        }
        if (progress) {
            // 等的是几分钟、总量事先不知道，用不确定的线性进度条，从头到尾同一根（progress-indicators.md）
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

// BottomSheetDefaults.DragHandle 的 4dp 加上下各 22dp
private val DragHandleHeight = 48.dp

// bottom-sheets.md 的 Top margin
private val SheetTopMargin = 72.dp
