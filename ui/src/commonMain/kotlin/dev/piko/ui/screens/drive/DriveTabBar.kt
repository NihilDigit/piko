package dev.piko.ui.screens.drive

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveTab
import dev.piko.shared.state.DuplicateFinderState
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
    /** 查重标签上显示它的进度与结果。 */
    duplicates: DuplicateFinderState?,
    /** 从信息流跳出来浏览的那个标签，见 PikoMainScaffold 的 feedDetourTab。 */
    feedTabId: Long?,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // 新开的标签在右边看不见时滚过去
    LaunchedEffect(activeId) {
        val index = tabs.indexOfFirst { it.id == activeId }
        if (index == tabs.lastIndex) scroll.animateScrollTo(scroll.maxValue)
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
                modifier = Modifier.weight(1f, fill = false).fillMaxHeight().verticalWheelScrollsRow(scroll).horizontalScroll(scroll).selectableGroup(),
                verticalAlignment = Alignment.Bottom,
            ) {
                val activeIndex = tabs.indexOfFirst { it.id == activeId }
                tabs.forEachIndexed { index, tab ->
                    TabChip(
                        tab,
                        duplicates = duplicates.takeIf { tab.isDuplicates },
                        fromFeed = tab.id == feedTabId,
                        active = index == activeIndex,
                        first = index == 0,
                        // 两个非活动标签之间画一道分隔，挨着活动标签的不画：活动标签自己的轮廓已经分开了
                        divider = index < tabs.lastIndex && index != activeIndex && index + 1 != activeIndex,
                        onSelect = { onSelect(tab.id) },
                        // 只剩一个时关不掉，不给叉
                        onClose = if (tabs.size > 1) ({ onClose(tab.id) }) else null,
                    )
                }
            }
            TooltipIconButton(
                Icons.Outlined.Add,
                "新建标签页",
                onNewTab,
                shortcut = newTabShortcut,
                modifier = Modifier.onGloballyPositioned { dragArea.plusRight = it.boundsInWindow().right },
            )
        }
        caption.buttons?.invoke()
    }
}

/**
 * 「+」后面到这一行末尾的空白。按位置登记，不放一个带权重的 Spacer：标签那一行是 weight(1f, fill = false)，
 * 再来一个带权重的就与它平分剩余宽度，标签多时只能占一半。
 */
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
    duplicates: DuplicateFinderState?,
    fromFeed: Boolean,
    active: Boolean,
    first: Boolean,
    divider: Boolean,
    onSelect: () -> Unit,
    onClose: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val contentColor = if (active) colors.onSurface else colors.onSurfaceVariant
    // 查重标签不接拖放：那里不是文件夹
    val target = tab.stack.lastOrNull()?.takeIf { !tab.isDuplicates }
    val title = if (duplicates != null) "查重：${duplicates.root.name}" else tab.title
    val tooltip = when {
        fromFeed -> "从信息流打开：" + tab.stack.joinToString(" › ") { it.name }
        duplicates == null -> tab.stack.joinToString(" › ") { it.name }
        duplicates.isScanning -> "正在查找重复文件，已扫描 ${duplicates.scannedFolders} 个文件夹。关闭标签页将结束查找"
        else -> {
            val groups = duplicates.report.identical.size + duplicates.report.versions.size
            (if (groups == 0) "未发现重复文件" else "发现 $groups 组重复文件") + "。关闭标签页将结束查找"
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IslandTab(
            active = active,
            first = first,
            divider = divider,
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
                            if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) onClose?.invoke()
                        }
                    }
                },
        ) {
            Spacer(Modifier.width(10.dp))
            if (duplicates?.isScanning == true) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                // 信息流的标签换成信息流的图标，用第三色：它停着的是普通文件夹，标题与别的标签没有分别
                Icon(
                    when {
                        tab.isDuplicates -> Icons.Outlined.FileCopy
                        fromFeed -> Icons.Outlined.SwipeVertical
                        else -> Icons.Outlined.Folder
                    },
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
