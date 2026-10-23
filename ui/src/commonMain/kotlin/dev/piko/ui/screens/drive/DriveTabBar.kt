package dev.piko.ui.screens.drive

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
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.layout.onSizeChanged
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
import dev.piko.ui.components.IslandTab
import dev.piko.ui.components.IslandTabBarHeight
import androidx.compose.foundation.layout.Spacer
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.WindowCaption
import dev.piko.ui.platform.rememberCaptionSlot
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    // 新开的标签在右边看不见时滚过去
    LaunchedEffect(activeId, draggingId) {
        if (draggingId != null) return@LaunchedEffect
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index == 0) scroll.animateScrollTo(0)
        else if (index == tabs.lastIndex) scroll.animateScrollTo(scroll.maxValue)
    }
    // 开着几个标签时标签栏在最上面，照 Chrome：窗口按钮在末尾，「+」后面的空白能拖动窗口
    val caption = rememberCaptionSlot()
    val windowCaption = LocalWindowCaption.current
    val dragArea = remember(windowCaption) { TabBarDragArea(windowCaption) }
    DisposableEffect(dragArea) { onDispose { dragArea.clear() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(caption.modifier)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .onGloballyPositioned { dragArea.onRow(it.boundsInWindow()) }
            .height(IslandTabBarHeight)
            // 第一个标签贴着页眉那块岛的左边，活动时左边竖直上去，与岛的左边连成一条线
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标签与「+」挤在左边，占满除窗口按钮外的宽度，窗口按钮才落在最右
        Row(modifier = Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            // 标签贴着这一行的下沿，活动的那个与下面的页眉连成一片
            Row(
                modifier = Modifier.weight(1f, fill = false).fillMaxHeight().onSizeChanged { viewportWidth = it.width.toFloat() }
                    .verticalWheelScrollsRow(scroll).horizontalScroll(scroll).selectableGroup(),
                verticalAlignment = Alignment.Bottom,
            ) {
                val activeIndex = tabs.indexOfFirst { it.id == activeId }
                tabs.forEachIndexed { index, tab ->
                    key(tab.id) {
                        TabChip(
                            tab,
                            session = sessionTab(tab),
                            fromFeed = tab.id == feedTabId,
                            active = index == activeIndex,
                            first = index == 0,
                            showDivider = index != activeIndex && index + 1 != activeIndex,
                            onSelect = { onSelect(tab.id) },
                            // 只剩一个时关不掉，不给叉
                            onClose = if (canClose(tab.id)) ({ onClose(tab.id) }) else null,
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
            TooltipIconButton(
                Icons.Outlined.Add,
                "新建标签页",
                onNewTab,
                shortcut = newTabShortcut,
                modifier = Modifier.onGloballyPositioned { dragArea.plusRight = it.boundsInWindow().right },
            )
            if (onCloseOthers != null && tabs.size > 1) {
                var menuOpen by remember { mutableStateOf(false) }
                Box(Modifier.onGloballyPositioned { dragArea.plusRight = it.boundsInWindow().right }) {
                    TooltipIconButton(Icons.Outlined.MoreVert, "标签页操作", { menuOpen = true })
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭其他标签") },
                            enabled = tabs.any { it.id != activeId && canClose(it.id) },
                            onClick = { menuOpen = false; onCloseOthers(activeId) },
                        )
                    }
                }
            }
        }
        caption.buttons?.invoke()
    }
}

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

/** 标签操作之后的剩余空白登记为窗口拖动区域。 */
private class TabBarDragArea(private val caption: WindowCaption?) {
    private val key = Any()
    private var row = Rect.Zero
    var plusRight = 0f
        set(value) {
            field = value
            publish()
        }

    fun onRow(bounds: Rect) {
        row = bounds
        publish()
    }

    fun clear() = caption?.setDragArea(key, null)

    private fun publish() {
        caption?.setDragArea(key, Rect(plusRight, row.top, row.right, row.bottom).takeIf { it.width > 0f })
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
        fromFeed -> "从信息流打开：" + tab.stack.joinToString(" › ") { it.name }
        session != null -> session.tooltip
        else -> tab.stack.joinToString(" › ") { it.name }
    }
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
            modifier = modifier
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
            // 叉的位置一直留着，出现与消失时标签不变宽
            Box(Modifier.padding(start = 4.dp).size(24.dp), contentAlignment = Alignment.Center) {
                if (onClose != null && (active || hovered)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭标签页",
                        tint = contentColor,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
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

private val TabWidth = 220.dp
