package dev.piko.ui.screens.transfers

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import dev.piko.ui.components.PikoTopBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
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
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.Bottom) {
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
        if (!showTabs) Spacer(Modifier.weight(1f).fillMaxHeight().windowDragArea())
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
        TransferKind.FILES -> Icons.Outlined.SwapVert
    }

private val KindTabMinWidth = 96.dp

/** 紧凑页头为标题和类型菜单保留的宽度。 */
private val InlineFilterMinWidth = 180.dp

/**
 * 手机上的页头只有一行：标题「任务」、类型菜单、操作。筛选原来另起一行，页头连状态栏占去一百多 dp，
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(container)
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets),
    ) {
        val filterInline = showFilter
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
                        text = "任务",
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        modifier = if (caption.atTop) Modifier.windowDragArea() else Modifier,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (filterInline) {
                        CompactKindFilter(state)
                        Spacer(Modifier.weight(1f))
                    } else {
                        // 标题与操作之间的空白，标题栏并进内容时是拖动区
                        Spacer(Modifier.weight(1f).height(40.dp).then(if (caption.atTop) Modifier.windowDragArea() else Modifier))
                    }
                }
            },
            // 类型菜单与标题优先保留，操作放不下时进入更多菜单。
            middleMinWidth = if (filterInline) InlineFilterMinWidth else 96.dp,
            reserveMiddle = true,
            fillMiddle = true,
            end = caption.buttons,
            dragWindow = caption.atTop,
        )
    }
}

/** 紧凑窗口用类型菜单，避免把所有类别挤在标题旁。空类别保留在菜单中。 */
@Composable
private fun CompactKindFilter(state: TransfersState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("${state.filter.label} ${state.counts[state.filter] ?: 0}", maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, contentDescription = "筛选任务类型", modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TransferKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text("${kind.label} ${state.counts[kind] ?: 0}") },
                    leadingIcon = { Icon(kind.icon, contentDescription = null) },
                    onClick = { expanded = false; state.changeFilter(kind) },
                )
            }
        }
    }
}
