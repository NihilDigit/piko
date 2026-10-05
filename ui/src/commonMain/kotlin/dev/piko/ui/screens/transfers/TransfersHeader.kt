package dev.piko.ui.screens.transfers

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.connectedToggleShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.components.IslandTab
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Upload
import dev.piko.ui.components.IslandTabBarHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectableGroup

/**
 * 没有外框时（手机与 medium）传输页的页头，见 [CompactTransfersHeader]。有外框时是 [TransfersTabRow] 加 [TransfersActionBar]。
 * 速度与蜗牛模式在底栏（TransfersFooter）。操作做不了时不出现（没有暂停的就没有「全部继续」），不摆灰按钮。
 */
@Composable
internal fun TransfersHeader(
    state: TransfersState,
    selectedCount: Int,
    /** 一项传输也没有时不给筛选：四个 0 只是占地方。 */
    showFilter: Boolean,
    onPauseSelected: (() -> Unit)?,
    onResumeSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    /** 下拉刷新用不了（鼠标）或多半用不上（宽窗口）时给的刷新按钮，见 showsRefreshButton。 */
    onRefresh: (() -> Unit)? = null,
    /** 列表已离开顶端。页头据此换成 surfaceContainer，与内容分开（M3 top app bar 的 on scroll 状态）。 */
    scrolled: Boolean = false,
) = CompactTransfersHeader(state, selectedCount, showFilter, scrolled, onPauseSelected, onResumeSelected, onDeleteSelected, onRefresh)

/**
 * 有外框时传输页顶上那一行：类别是一排标签，与网盘页的位置标签同一种（[IslandTab]），活动的那个接着下面的岛。
 * 原来是一组连体筛选按钮，与网盘页的标签栏长得不一样，从网盘切过来时顶上整行换了样子。
 * 这一行贴着窗口顶，标题栏并进内容时窗口按钮画在末尾，标签后面的空白是拖动区。
 */
@Composable
internal fun TransfersTabRow(state: TransfersState, showTabs: Boolean) {
    val caption = rememberCaptionSlot()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .height(IslandTabBarHeight)
            .padding(end = if (caption.buttons != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showTabs) {
            val kinds = TransferKind.entries
            val activeIndex = kinds.indexOf(state.filter)
            Row(Modifier.selectableGroup(), verticalAlignment = Alignment.Bottom) {
                kinds.forEachIndexed { index, kind ->
                    IslandTab(
                        active = index == activeIndex,
                        first = index == 0,
                        divider = index < kinds.lastIndex && index != activeIndex && index + 1 != activeIndex,
                        onClick = { state.changeFilter(kind) },
                        modifier = Modifier.widthIn(min = KindTabMinWidth),
                    ) {
                        Spacer(Modifier.width(14.dp))
                        Icon(
                            kind.icon,
                            contentDescription = null,
                            tint = if (index == activeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            kind.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (index == activeIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            (state.counts[kind] ?: 0).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f).fillMaxHeight().windowDragArea())
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { caption.buttons?.invoke() }
    }
}

/**
 * 岛上半段的一行。左边是读数：上下行速度与下载剩余时间。右边是能动手的，隔开一段分成两组：
 * 整页操作（有选中项时是「已选 N 项」与批量操作）与蜗牛模式。
 * 原来速度在窗口底部另成一行，这一行只有右端两三个按钮，上下都空。容量条试过放在末尾，挤得这一行读不清，去掉了；
 * 网盘用量侧边栏的账号行里已有。
 */
@Composable
internal fun TransfersActionBar(
    state: TransfersState,
    selectedCount: Int,
    wide: Boolean,
    onPauseSelected: (() -> Unit)?,
    onResumeSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Rates(state)
        Spacer(Modifier.width(12.dp))
        DownloadEta(state)
        Spacer(Modifier.weight(1f))
        // 操作一律图标按钮，名字在悬停提示里：带字的按钮与蜗牛开关、容量条挤在一行，按钮的字与开关的字分不清谁是谁
        if (selectedCount > 0) {
            Text(
                "已选 $selectedCount 项",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            onPauseSelected?.let { TooltipIconButton(Icons.Outlined.Pause, "暂停", it) }
            onResumeSelected?.let { TooltipIconButton(Icons.Outlined.PlayArrow, "继续", it) }
            TooltipIconButton(Icons.Outlined.Delete, "删除", onDeleteSelected, shortcut = "Delete", tint = MaterialTheme.colorScheme.error)
            TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection, shortcut = "Esc")
        } else {
            if (state.canResumeAll) TooltipIconButton(Icons.Outlined.PlayArrow, "全部继续", state::resumeAll)
            if (state.canClearCompleted) TooltipIconButton(Icons.Outlined.ClearAll, "清除已完成", state::clearCompleted)
            onRefresh?.let { TooltipIconButton(Icons.Outlined.Refresh, "刷新", it) }
        }
        // 两组之间只留空白，不画竖线，与网盘页的命令栏相同
        Spacer(Modifier.width(12.dp))
        SnailModeToggle()
    }
}

/** 类别标签上的图标，与侧边栏、各行用的同一套。 */
private val TransferKind.icon: ImageVector
    get() = when (this) {
        TransferKind.ALL -> Icons.Outlined.SwapVert
        TransferKind.DOWNLOAD -> Icons.Outlined.Download
        TransferKind.UPLOAD -> Icons.Outlined.Upload
        TransferKind.CLOUD -> Icons.Outlined.Cloud
    }

private val KindTabMinWidth = 96.dp

/** 手机上标题与四个筛选按钮同一行时，这一段至少要的宽度：「传输」加四个「下载 12」。 */
private val InlineFilterMinWidth = 260.dp

/**
 * 手机上的页头只有一行：标题「传输」、类型筛选、操作。筛选原来另起一行，页头连状态栏占去一百多 dp，
 * 手机上一屏本就放不下几项传输。筛选夹在中间，四个按钮等宽铺满标题与操作之间的宽度。
 *
 * 列表滚离顶端时底色换成 surfaceContainer（[scrolled]），与 M3 top app bar 的 on scroll 状态相同，
 * 页头与滚到它下面的内容分得开；颜色渐变过去，不跳。有选中项时仍是上下文顶栏，同样随滚动换色。
 */
@Composable
private fun CompactTransfersHeader(
    state: TransfersState,
    selectedCount: Int,
    showFilter: Boolean,
    scrolled: Boolean,
    onPauseSelected: (() -> Unit)?,
    onResumeSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (scrolled) colors.surfaceContainer else colors.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    if (selectedCount > 0) {
        PikoTopBar(
            title = "已选择 $selectedCount 项",
            navigationIcon = { TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection, shortcut = "Esc") },
            actions = listOfNotNull(
                onPauseSelected?.let { iconBarItem(Icons.Outlined.Pause, "暂停", it, priority = 20) },
                onResumeSelected?.let { iconBarItem(Icons.Outlined.PlayArrow, "继续", it, priority = 20) },
                iconBarItem(Icons.Outlined.Delete, "删除", onDeleteSelected, priority = 30, shortcut = "Delete", destructive = true),
            ),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = container, titleContentColor = colors.onSurface),
        )
        return
    }
    val caption = rememberCaptionSlot()
    // 桌面的窄窗口里窗口按钮画在这一行末尾，占去一百多 dp，筛选挤得连「全部」都显示不全。这时筛选另起一行、铺满全宽，
    // 即 M3 把 filter chips 放在顶栏下方的样子；手机上没有窗口按钮，仍是一行，不多占纵向空间。
    // 看标题栏是否并进内容，不看 caption.buttons：后者按每帧量到的边界算，拖动改尺寸时会短暂为 null，页头跟着跳回挤着的一行
    val filterBelow = showFilter && LocalWindowCaption.current != null
    val filter: @Composable (Modifier, PaddingValues) -> Unit = { modifier, padding ->
        // 四类按钮等宽铺满：各按内容定宽时挤在左边、宽窄不一，右边空出一截，连体按钮看着像没摆完
        KindFilter(
            current = state.filter,
            counts = state.counts,
            onChange = state::changeFilter,
            modifier = modifier,
            contentPadding = padding,
            itemPadding = 8.dp,
            fillWidth = true,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(container)
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets),
    ) {
        val filterInline = showFilter && !filterBelow
        // 标题（与同一行的筛选）、操作与窗口按钮交给 AdaptiveBar 排：操作放不下时收进「更多」，不挤标题与筛选
        AdaptiveBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 16.dp, end = 4.dp),
            trailing = listOfNotNull(
                iconBarItem(Icons.Outlined.PlayArrow, "全部继续", state::resumeAll, priority = 20).takeIf { state.canResumeAll },
                iconBarItem(Icons.Outlined.ClearAll, "清除已完成", state::clearCompleted, priority = 10).takeIf { state.canClearCompleted },
                onRefresh?.let { iconBarItem(Icons.Outlined.Refresh, "刷新", it, priority = 30) },
            ),
            middle = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "传输",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        modifier = if (caption.atTop) Modifier.windowDragArea() else Modifier,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (filterInline) {
                        filter(Modifier.weight(1f), PaddingValues(end = 8.dp))
                    } else {
                        // 标题与操作之间的空白，标题栏并进内容时是拖动区
                        Spacer(Modifier.weight(1f).height(40.dp).then(if (caption.atTop) Modifier.windowDragArea() else Modifier))
                    }
                }
            },
            // 筛选在同一行时四个按钮要放得下名字与数目，操作先让位
            middleMinWidth = if (filterInline) InlineFilterMinWidth else 96.dp,
            reserveMiddle = true,
            fillMiddle = true,
            end = caption.buttons,
            dragWindow = caption.atTop,
        )
        if (filterBelow) filter(Modifier, PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp))
    }
}

/**
 * 类型筛选，M3 连体按钮。某一类为空时照样列出、数目为 0：按钮随任务出现消失的话，一行的位置老在变。
 * 形状不随按下与选中变：库里的连体按钮在几种形状间用弹簧过渡，内侧小圆角冲过头算出负值，
 * CornerBasedShape 直接抛异常（网盘页的视图按钮实测崩过）。
 */
@Composable
private fun KindFilter(
    current: TransferKind,
    counts: Map<TransferKind, Int>,
    onChange: (TransferKind) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /** 每个按钮两侧的内边距。手机上与标题挤在一行，收窄一些。 */
    itemPadding: Dp = 16.dp,
    /** 为 true 时各按钮等宽铺满可用宽度，不滚动；否则按内容定宽，放不下时横向滚动。 */
    fillWidth: Boolean = false,
) {
    val kinds = TransferKind.entries
    val scroll = rememberScrollState()
    Row(
        modifier = if (fillWidth) {
            modifier.fillMaxWidth().padding(contentPadding)
        } else {
            modifier.verticalWheelScrollsRow(scroll).horizontalScroll(scroll).padding(contentPadding)
        },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        kinds.forEachIndexed { index, kind ->
            ToggleButton(
                checked = kind == current,
                onCheckedChange = { if (kind != current) onChange(kind) },
                shapes = connectedToggleShapes(index, kinds.size),
                contentPadding = PaddingValues(horizontal = itemPadding),
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            ) {
                FilterLabel(kind.label, counts[kind] ?: 0)
            }
        }
    }
}

@Composable
private fun RowScope.FilterLabel(label: String, count: Int) {
    Text(label, maxLines = 1)
    Spacer(Modifier.width(6.dp))
    Text(count.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
}
