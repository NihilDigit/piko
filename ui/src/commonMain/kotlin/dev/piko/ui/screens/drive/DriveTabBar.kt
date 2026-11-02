package dev.piko.ui.screens.drive

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.presentation.DriveTab
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.IslandTab
import dev.piko.ui.components.IslandTabBarHeight
import androidx.compose.foundation.layout.Spacer
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 会话占着的标签（查找重复）上显示的东西：标题、悬停说明、是否还在扫（标签上转圈）与图标。
 * 由网盘页按各自的会话给出，标签栏不认识具体是哪一种。
 */
internal class SessionTab(val title: String, val tooltip: String, val busy: Boolean, val icon: ImageVector)

/**
 * 网盘页的标签栏，只在宽窗口、开了不止一个标签时出现。一个标签是一个位置，各有各的后退与前进：
 * 整理文件时开两三个，把东西拖到另一个标签上就是移进它停着的文件夹。
 * 点一下切过去，中键点它或点叉关掉；关闭按钮平时只在活动标签与鼠标停着的那个上出现，免得一排叉。
 */
@Composable
internal fun DriveTabBar(
    tabs: List<DriveTab>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    newTabShortcut: String,
    /** 会话标签上显示它的进度与结果；普通的标签为 null。 */
    sessionTab: (DriveTab) -> SessionTab?,
    /** 从信息流跳出来浏览的那个标签，见 PikoMainScaffold 的 feedDetourTab。 */
    feedTabId: Long?,
    canClose: (Long) -> Boolean = { tabs.size > 1 },
    canPin: (Long) -> Boolean = { false },
    isPinned: (Long) -> Boolean = { id -> tabs.any { it.id == id && it.isPinned } },
    onTogglePin: (Long) -> Unit = {},
    onCloseOthers: ((Long) -> Unit)? = null,
    onMove: ((Long, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val latestTabs by rememberUpdatedState(tabs)
    val latestMove by rememberUpdatedState(onMove)
    val latestSelect by rememberUpdatedState(onSelect)
    val tabWidthPx = with(LocalDensity.current) { TabWidth.toPx() }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var startIndex by remember { mutableIntStateOf(0) }
    var startScroll by remember { mutableIntStateOf(0) }
    var travel by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    fun updateOrder() {
        val id = draggingId ?: return
        val current = latestTabs.indexOfFirst { it.id == id }
        if (current < 0 || latestTabs.size <= 1) return
        val target = (startIndex + (travel + scroll.value - startScroll) / tabWidthPx)
            .roundToInt().coerceIn(0, latestTabs.lastIndex)
        if (target != current) latestMove?.invoke(id, target)
    }
    val center = startIndex * tabWidthPx - startScroll + travel + tabWidthPx / 2
    val edge = when {
        draggingId == null || viewportWidth <= 0f -> 0
        center < tabWidthPx / 4 -> -1
        center > viewportWidth - tabWidthPx / 4 -> 1
        else -> 0
    }
    LaunchedEffect(draggingId, edge) {
        if (edge == 0) return@LaunchedEffect
        while (draggingId != null) {
            if (scroll.scrollBy(edge * tabWidthPx / 18) == 0f) break
            updateOrder()
            delay(16)
        }
    }
    // 活动标签或标签数量变化时，确保当前活动标签完整落在可视范围内
    LaunchedEffect(activeId, tabs.size, viewportWidth, draggingId) {
        if (draggingId != null || viewportWidth <= 0f) return@LaunchedEffect
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index < 0) return@LaunchedEffect
        val tabLeft = (index * tabWidthPx).roundToInt()
        val tabRight = ((index + 1) * tabWidthPx).roundToInt()
        val viewWidth = viewportWidth.roundToInt()
        when {
            index == 0 -> scroll.animateScrollTo(0)
            index == tabs.lastIndex -> scroll.animateScrollTo(scroll.maxValue)
            tabLeft < scroll.value -> scroll.animateScrollTo(tabLeft)
            tabRight > scroll.value + viewWidth -> scroll.animateScrollTo((tabRight - viewWidth).coerceAtLeast(0))
        }
    }
    // 开着几个标签时标签栏在最上面，照 Chrome：标签少时操作按钮紧跟最后一个标签，标签多时铺满至右侧保留拖窗区前，不与空白区平分宽度
    val caption = rememberCaptionSlot()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .height(IslandTabBarHeight)
            // 第一个标签贴着页眉那块岛的左边，活动时左边竖直上去，与岛的左边连成一条线
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabStripWithActionsAndDragArea(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            tabsContent = {
                // 标签贴着这一行的下沿，活动的那个与下面的页眉连成一片
                Row(
                    modifier = Modifier.fillMaxHeight().onSizeChanged { viewportWidth = it.width.toFloat() }
                        .verticalWheelScrollsRow(scroll).horizontalScroll(scroll).selectableGroup(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                val activeIndex = tabs.indexOfFirst { it.id == activeId }
                val closeOthersLabel = stringResource(Res.string.text_close_other_tabs)
                tabs.forEachIndexed { index, tab ->
                    val tabPinned = isPinned(tab.id)
                    val hasClosableOthers = onCloseOthers != null && tabs.any { it.id != tab.id && !isPinned(it.id) && canClose(it.id) }
                    key(tab.id) {
                        TabChip(
                            tab,
                            session = sessionTab(tab),
                            fromFeed = tab.id == feedTabId,
                            active = index == activeIndex,
                            first = index == 0,
                            showDivider = index != activeIndex && index + 1 != activeIndex,
                            onSelect = { onSelect(tab.id) },
                            // 只剩一个或已固定时关不掉，不给叉
                            onClose = if (!tabPinned && canClose(tab.id)) ({ onClose(tab.id) }) else null,
                            pinned = tabPinned,
                            onTogglePin = if (canPin(tab.id)) ({ onTogglePin(tab.id) }) else null,
                            onCloseOthers = if (hasClosableOthers) ({ onCloseOthers.invoke(tab.id) }) else null,
                            closeOthersLabel = closeOthersLabel,
                            modifier = Modifier.zIndex(if (draggingId == tab.id) 1f else 0f)
                                .graphicsLayer {
                                    translationX = if (draggingId == tab.id) (travel + scroll.value - startScroll - (index - startIndex) * tabWidthPx).coerceAtLeast(-index * tabWidthPx) else 0f
                                }.tabReorderGesture(
                                    enabled = onMove != null && tabs.size > 1,
                                    onStart = {
                                        startIndex = latestTabs.indexOfFirst { it.id == tab.id }
                                        startScroll = scroll.value
                                        travel = 0f
                                        draggingId = tab.id
                                        latestSelect(tab.id)
                                    },
                                    onDelta = { travel += it; updateOrder() },
                                    onEnd = { draggingId = null; travel = 0f },
                                ),
                        )
                    }
                }
                }
            },
            actionsContent = {
                val canCloseOtherTabs = onCloseOthers != null && tabs.any { it.id != activeId && !isPinned(it.id) && canClose(it.id) }
                // 标签高度为 36dp 且贴着 44dp 标签栏下沿；操作按钮行同样取 36dp 并贴底摆放，使「+」「×」与标签文字严格处于同一水平中线
                Row(
                    modifier = Modifier.height(TabActionBandHeight).padding(start = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TabBarIconButton(
                        icon = Icons.Outlined.Add,
                        label = tr("新建标签页", "New tab", "新しいタブ", "새 탭", "新增分頁"),
                        onClick = onNewTab,
                        shortcut = newTabShortcut,
                    )
                    if (canCloseOtherTabs) {
                        TabBarIconButton(
                            icon = Icons.Outlined.Close,
                            label = stringResource(Res.string.text_close_other_tabs),
                            onClick = { onCloseOthers.invoke(activeId) },
                        )
                    }
                }
            },
            dragAreaContent = {
                Box(Modifier.fillMaxHeight().windowDragArea())
            },
        )
        caption.buttons?.invoke()
    }
}

/**
 * 单趟测量标签条、紧随其后的操作按钮（「+」「×」）与右侧剩余的窗口拖拽空白区（不使用 SubcomposeLayout，避免桌面端弹出菜单销毁崩溃）：
 * - 标签较少时，标签条按实际内容收缩，操作按钮紧贴最后一个标签，右侧整片剩余空白均可拖拽窗口；
 * - 标签较多时，标签条可一直向右铺满至 `总宽 - 操作按钮宽 - 最小拖窗留白`，不会被空白 Spacer 平分截断在左半边。
 */
@Composable
private fun TabStripWithActionsAndDragArea(
    tabsContent: @Composable () -> Unit,
    actionsContent: @Composable () -> Unit,
    dragAreaContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            tabsContent()
            actionsContent()
            dragAreaContent()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth
        val height = constraints.maxHeight
        val minDragPx = MinWindowDragReserve.roundToPx().coerceAtMost((totalWidth / 4).coerceAtLeast(0))

        val actionsPlaceable = measurables[1].measure(
            Constraints(minWidth = 0, maxWidth = totalWidth, minHeight = 0, maxHeight = height),
        )
        val maxTabsWidth = (totalWidth - actionsPlaceable.width - minDragPx).coerceAtLeast(0)
        val tabsPlaceable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = maxTabsWidth, minHeight = height, maxHeight = height),
        )
        val dragWidth = (totalWidth - tabsPlaceable.width - actionsPlaceable.width).coerceAtLeast(0)
        val dragPlaceable = measurables[2].measure(
            Constraints.fixed(dragWidth, height),
        )

        layout(totalWidth, height) {
            tabsPlaceable.placeRelative(0, 0)
            // 贴着标签栏底边与 36dp 的标签岛对齐，避免在 44dp 全高内居中导致按钮视觉偏高 4dp
            actionsPlaceable.placeRelative(tabsPlaceable.width, height - actionsPlaceable.height)
            dragPlaceable.placeRelative(tabsPlaceable.width + actionsPlaceable.width, 0)
        }
    }
}

@Composable
private fun TabBarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    shortcut: String? = null,
) {
    val tip = if (shortcut.isNullOrBlank()) label else "$label ($shortcut)"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tip) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val TabActionBandHeight = 36.dp
private val MinWindowDragReserve = 48.dp

/** 越过水平拖动阈值才排序，普通点选、关闭和中键操作继续由条目处理。 */
private fun Modifier.tabReorderGesture(
    enabled: Boolean,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        var overSlop = 0f
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, amount ->
            change.consume()
            overSlop = amount
        }
        if (start != null) {
            onStart()
            onDelta(overSlop)
            try {
                horizontalDrag(start.id) { change ->
                    onDelta(change.positionChange().x)
                    change.consume()
                }
            } finally {
                onEnd()
            }
        }
    }
}

/** 一个位置标签，等宽，形状与切换见 [IslandTab]。 */
@Composable
private fun TabChip(
    tab: DriveTab,
    session: SessionTab?,
    fromFeed: Boolean,
    active: Boolean,
    first: Boolean,
    showDivider: Boolean,
    onSelect: () -> Unit,
    onClose: (() -> Unit)?,
    pinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onCloseOthers: (() -> Unit)?,
    closeOthersLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val latestClose by rememberUpdatedState(onClose)
    val contentColor = if (active) colors.onSurface else colors.onSurfaceVariant
    // 会话标签不接拖放：那里不是文件夹
    val target = tab.stack.lastOrNull()?.takeIf { tab.session == null && session == null }
    val library = tab.stack.firstOrNull()?.let { DriveLibrary.of(it.id) }
    val icon = session?.icon ?: library?.icon ?: if (fromFeed) Icons.Outlined.SwipeVertical else Icons.Outlined.Folder
    val title = session?.title ?: tab.title
    val tooltip = when {
        fromFeed -> tr("从信息流打开：", "Opened from feed:", "フィードから開く：", "피드에서 열기:", "從資訊流開啟：") + tab.stack.joinToString(" › ") { it.localizedName }
        session != null -> session.tooltip
        else -> tab.stack.joinToString(" › ") { it.localizedName }
    }
    val hasContextActions = onTogglePin != null || onClose != null || onCloseOthers != null
    ContextMenuArea(
        enabled = hasContextActions,
        actions = {
            buildList {
                if (onTogglePin != null) {
                    add(
                        SheetAction(
                            icon = if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                            label = if (pinned) tr("取消固定标签页", "Unpin tab", "タブの固定を解除", "탭 고정 해제", "取消固定分頁")
                            else tr("固定标签页", "Pin tab", "タブを固定", "탭 고정", "固定分頁"),
                            onClick = onTogglePin,
                        ),
                    )
                }
                if (!pinned && onClose != null) {
                    add(
                        SheetAction(
                            icon = Icons.Outlined.Close,
                            label = tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기", "關閉分頁"),
                            onClick = onClose,
                        ),
                    )
                }
                if (onCloseOthers != null) {
                    add(
                        SheetAction(
                            icon = Icons.Outlined.Close,
                            label = closeOthersLabel,
                            onClick = onCloseOthers,
                        ),
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            IslandTab(
                active = active,
                first = first,
                showDivider = showDivider,
                onClick = onSelect,
                interactionSource = interaction,
                modifier = Modifier
                    // 拖到别的标签上：移进它停着的文件夹
                    .then(if (!active && target != null) Modifier.fileDropTarget("tab:${tab.id}", target) else Modifier)
                    .width(TabWidth)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) latestClose?.invoke()
                            }
                        }
                    },
            ) {
                Spacer(Modifier.width(10.dp))
                if (session?.busy == true) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    // 信息流的标签换成信息流的图标，用第三色：它停着的是普通文件夹，标题与别的标签没有分别
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = when {
                            fromFeed -> colors.tertiary
                            active -> colors.primary
                            else -> colors.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Box(Modifier.padding(start = 4.dp).size(24.dp), contentAlignment = Alignment.Center) {
                    if (pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = if (active) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    } else if (onClose != null && (active || hovered)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기", "關閉分頁"),
                            tint = contentColor,
                            modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/**
 * 页眉上地址栏与搜索框的底色。在页眉岛里照 M3 搜索栏取 surfaceContainerHigh；没有标签栏的窗口里页眉直接在外框色上，
 * 外框色就是 surfaceContainerHigh，那里取页面本色。由页眉所在处经 [LocalHeaderOnIsland] 告知。
 */
internal val ColorScheme.headerFieldColor: Color
    @Composable get() = if (LocalHeaderOnIsland.current) surfaceContainerHigh else surface

internal val LocalHeaderOnIsland = staticCompositionLocalOf { false }

private val TabWidth = 232.dp
