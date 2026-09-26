package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileCopy
import dev.piko.ui.components.TooltipIconButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.data.repository.FileSortOrder
import dev.piko.shared.data.PikoSortField
import dev.piko.shared.data.field
import dev.piko.shared.data.isAscending
import dev.piko.ui.components.ContextMenuArea
import dev.piko.shared.state.DriveFolderView
import dev.piko.shared.state.DriveListItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.FileRowSkeleton
import dev.piko.ui.components.SkeletonGroup
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * 两种视图都用 LazyVerticalStaggeredGrid，共用同一个状态与作品头、分区标题这些整行项。
 * 海报墙的卡片等高（16:9 封面加两行标题），在这个网格里排出来就是齐整的行。
 *
 * 列表视图单列宽度下限 360dp，手机上始终一列，横屏平板上自动排成两列以上。M3 列表规范
 * 要求宽窗口下控制行长或改为多栏，否则一行名字会被拉得很长。
 *
 * 海报墙的卡宽下限：手机上 160dp，排两列（原先 128dp 在 432dp 宽的手机上排成三列，名字只剩
 * 一行四个汉字）；宽窗口里 240dp，封面够大，模糊时也辨得出轮廓。
 */
private val ListColumnMinWidth = 360.dp
private val PosterColumnMinWidthCompact = 160.dp
private val PosterColumnMinWidth = 240.dp

private const val KEY_HEADER = "drive_header"
private const val KEY_FOLD = "drive_fold"

/** 列表或海报墙里的每一项需要的回调，由 DriveScreen 按条目绑定。 */
internal class DriveItemCallbacks(
    val onOpen: (FileStat) -> Unit,
    val onMore: (FileStat) -> Unit,
    val onLongPress: (FileStat) -> Unit,
    val onSelect: (FileStat, Boolean) -> Unit,
    /** 右键菜单的内容，与操作面板相同。 */
    val contextActions: (FileStat) -> List<SheetAction>,
    val onToggleSection: (blockId: String) -> Unit,
    /** 文件夹进入可见区域，见 DriveScreenState.onFolderVisible。 */
    val onFolderVisible: (FileStat) -> Unit,
)

/** 列表前面固定的几项：页眉，以及有时出现的折叠横幅。分区跳转与副标题反查要扣掉它们。 */
internal fun driveLeadingItemCount(hasFoldBanner: Boolean): Int = 1 + (if (hasFoldBanner) 1 else 0)

@Composable
internal fun DriveFileGrid(
    items: List<DriveListItem>,
    isPosterMode: Boolean,
    gridState: LazyStaggeredGridState,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    highlightedIds: Set<String>,
    isBlurred: (FileStat) -> Boolean,
    hitLocations: Map<String, String>,
    /** 文件夹的解析结果；原始文件名模式下恒为 null。 */
    folderView: (FileStat) -> DriveFolderView?,
    callbacks: DriveItemCallbacks,
    bottomPadding: Dp,
    header: @Composable () -> Unit,
    foldBanner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val leadingItemCount = driveLeadingItemCount(foldBanner != null)
    val horizontalPadding = gridHorizontalPadding(isPosterMode)
    val itemSpacing = gridItemSpacing(isPosterMode)

    Box(modifier = modifier.fillMaxSize()) {
        // 刚秒传成功时滚到新条目。视图模式是异步读出来的偏好，首帧拿到的还是默认值，
        // 所以它也要进 key，否则真值到达前的滚动会停在错误的位置。
        LaunchedEffect(items, highlightedIds, isPosterMode) {
            if (highlightedIds.isEmpty()) return@LaunchedEffect
            val entryIndex = items.indexOfFirst { it is DriveListItem.File && it.file.id in highlightedIds }
            if (entryIndex >= 0) gridState.animateScrollToItem(leadingItemCount + entryIndex)
        }

        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = gridCells(isPosterMode),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = bottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalItemSpacing = itemSpacing,
        ) {
            item(key = KEY_HEADER, span = StaggeredGridItemSpan.FullLine, contentType = KEY_HEADER) {
                // 页眉总是整行宽，内边距由它自己决定，两种视图下排布一致。海报墙模式的
                // contentPadding 会把它缩进 16dp，这里向两侧撑回去
                Box(modifier = if (isPosterMode) Modifier.bleedHorizontal(horizontalPadding) else Modifier) {
                    header()
                }
            }
            if (foldBanner != null) {
                item(key = KEY_FOLD, span = StaggeredGridItemSpan.FullLine, contentType = KEY_FOLD) {
                    Box(modifier = Modifier.padding(horizontal = if (isPosterMode) 0.dp else 16.dp)) {
                        foldBanner()
                    }
                }
            }

            itemsIndexed(
                items,
                key = { _, item -> item.key },
                span = { _, item -> if (item is DriveListItem.File) StaggeredGridItemSpan.SingleLane else StaggeredGridItemSpan.FullLine },
                contentType = { _, item ->
                    when (item) {
                        is DriveListItem.WorkHeader -> "work"
                        is DriveListItem.SectionHeader -> "section"
                        is DriveListItem.File -> if (item.file.isFolder) "folder" else "file"
                    }
                },
            ) { index, item ->
                when (item) {
                    is DriveListItem.WorkHeader -> WorkHeaderRow(
                        header = item,
                        modifier = Modifier.animateItem().padding(horizontal = if (isPosterMode) 0.dp else 16.dp),
                    )
                    is DriveListItem.SectionHeader -> SectionHeaderRow(
                        header = item,
                        // 海报墙的网格已有 16dp 边距，文字与卡片左缘对齐即可
                        inset = if (isPosterMode) 4.dp else 16.dp,
                        onClick = { callbacks.onToggleSection(item.blockId) },
                        modifier = Modifier.animateItem(),
                    )
                    is DriveListItem.File -> {
                        val file = item.file
                        if (file.isFolder) LaunchedEffect(file.id) { callbacks.onFolderVisible(file) }
                        DriveCell(
                            file = file,
                            text = cellText(item, if (file.isFolder) folderView(file) else null),
                            isPosterMode = isPosterMode,
                            isSelectionMode = isSelectionMode,
                            isSelected = file.id in selectedIds,
                            isHighlighted = file.id in highlightedIds,
                            isBlurred = isBlurred(file),
                            locationLabel = hitLocations[file.id],
                            callbacks = callbacks,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        LocalPikoPlatform.current.ListScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun gridCells(isPosterMode: Boolean): StaggeredGridCells {
    val posterMinWidth = if (currentWidthClass() == WidthClass.Compact) PosterColumnMinWidthCompact else PosterColumnMinWidth
    return StaggeredGridCells.Adaptive(if (isPosterMode) posterMinWidth else ListColumnMinWidth)
}

private fun gridHorizontalPadding(isPosterMode: Boolean): Dp = if (isPosterMode) 16.dp else 0.dp

private fun gridItemSpacing(isPosterMode: Boolean): Dp = if (isPosterMode) 8.dp else 0.dp

/**
 * 首载时的骨架，不含页眉。网格本身也是同一种 LazyVerticalStaggeredGrid，列数、边距与间距取真实网格
 * 的同一套参数：宽窗口里列表排成多列，海报墙按卡宽下限换列数，另算一遍迟早与真实网格对不上。
 * 不可滚动，条目数给够一屏，多出来的懒加载不会组合。
 */
@Composable
internal fun DriveGridSkeleton(isPosterMode: Boolean, modifier: Modifier = Modifier) {
    val itemSpacing = gridItemSpacing(isPosterMode)
    SkeletonGroup(modifier = modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = gridCells(isPosterMode),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gridHorizontalPadding(isPosterMode)),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalItemSpacing = itemSpacing,
            userScrollEnabled = false,
        ) {
            items(SKELETON_ITEM_COUNT) { index ->
                val titleFraction = SkeletonTitleWidths[index % SkeletonTitleWidths.size]
                if (isPosterMode) PosterCardSkeleton(titleFraction) else FileRowSkeleton(titleFraction = titleFraction)
            }
        }
    }
}

private const val SKELETON_ITEM_COUNT = 40

// 标题宽度逐项错开，一排等长的色块读起来像表格
private val SkeletonTitleWidths = listOf(0.62f, 0.45f, 0.74f, 0.52f, 0.68f, 0.4f)

/** 单元格上的文字：解析出的标题与标签。[title] 为 null 时照原样显示名字。 */
private class CellText(val title: String?, val tags: List<String>, val code: String? = null, val resolution: String? = null)

private val RawCellText = CellText(null, emptyList())

private fun cellText(item: DriveListItem.File, folder: DriveFolderView?): CellText {
    val view = item.view
    return when {
        view != null -> CellText(view.title, view.tags, view.code, view.resolution)
        folder != null && (folder.title != null || folder.tags.isNotEmpty()) ->
            CellText(folder.title ?: item.file.name, folder.tags, folder.code, folder.resolution)
        else -> RawCellText
    }
}

/** 作品头：作品名与作品内共有的标签，只出现一次，各行因此只挂有区分度的标签。 */
@Composable
private fun WorkHeaderRow(header: DriveListItem.WorkHeader, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        header.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (header.tags.isNotEmpty()) MediaTagRow(tags = header.tags, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 分区标题，点按展开或收起。海报墙没有吸顶标题，它随内容滚走；当前所在的分区由顶栏副标题给出。 */
@Composable
private fun SectionHeaderRow(header: DriveListItem.SectionHeader, inset: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = inset, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.label,
                    style = if (header.isWork) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = if (header.isWork) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (header.tags.isNotEmpty()) MediaTagRow(tags = header.tags, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(
                imageVector = if (header.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (header.expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DriveCell(
    file: FileStat,
    text: CellText,
    isPosterMode: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isBlurred: Boolean,
    locationLabel: String?,
    callbacks: DriveItemCallbacks,
    modifier: Modifier,
) {
    ContextMenuArea(actions = { callbacks.contextActions(file) }, modifier = modifier) {
        if (isPosterMode) {
            PosterCard(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isSpoilerBlurred = isBlurred,
                isHighlighted = isHighlighted,
                onClick = { callbacks.onOpen(file) },
                onLongClick = { callbacks.onLongPress(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
                onMoreClick = { callbacks.onMore(file) },
                title = text.title,
                tags = text.tags,
                code = text.code,
                resolution = text.resolution,
            )
        } else {
            FileListItem(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isHighlighted = isHighlighted,
                // 文件夹没有缩略图，不走防窥
                isSpoilerBlurred = isBlurred && !file.isFolder,
                locationLabel = locationLabel,
                onClick = { callbacks.onOpen(file) },
                onLongClick = { callbacks.onLongPress(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
                onMoreClick = { callbacks.onMore(file) },
                title = text.title,
                tags = text.tags,
                code = text.code,
            )
        }
    }
}

/**
 * 排序字段与方向。ascending 与 descending 分别是该字段两个方向的枚举值，
 * defaultOrder 是切到这个字段时的起始方向：名称从 A 到 Z，时间与大小从新到旧、从大到小。
 */
@Composable
private fun SortDirectionIcon(order: FileSortOrder, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (order.isAscending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
        contentDescription = if (order.isAscending) "升序" else "降序",
        modifier = modifier,
    )
}

/**
 * 列表页眉：搜索时的结果说明、排序、视图切换。
 *
 * 排序与视图切换原先挤在顶栏，与新建、搜索一起共四个图标。M3 顶栏规范建议只放一到
 * 两个动作；这两个是作用于列表本身的控件，放进随列表滚走的页眉，不再常驻占位。
 * 排序按钮写出当前字段与方向。菜单里再点当前字段即切换升降序，点其他字段则按该字段的
 * 起始方向排，不必为六种组合各列一项。
 */
@Composable
internal fun DriveListHeader(
    summary: String?,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    isPosterMode: Boolean,
    onTogglePosterMode: () -> Unit,
    onFindDuplicates: (() -> Unit)?,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (summary != null) {
            // 与排序按钮的图标同落在 16dp 页边距上：外层只给了 4dp，这里补 TextButton 的 12dp
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
        // 排序靠左、视图切换靠右，读作列表自身的控件；原先两者都靠右，像是顶栏放不下挤下来的第二排
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("按${sortOrder.field.label}")
                    Spacer(modifier = Modifier.width(2.dp))
                    SortDirectionIcon(sortOrder, modifier = Modifier.size(16.dp))
                }
                PikoDropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    val fields = PikoSortField.entries
                    fields.forEachIndexed { index, field ->
                        val isCurrent = field.owns(sortOrder)
                        // 参数按位置传：带 shape 的这个重载，尾部槽位在桌面端的 material3 里叫 trailingIcon，
                        // Android 端已改名 trailingContent，没有两端通用的参数名。可选中的菜单项
                        // （selected 与 shapes）两端同样对不上，Android 端已挪去 SelectableDropdownMenuItem，
                        // 所以当前字段仍只靠尾部的方向箭头标出
                        DropdownMenuItem(
                            {
                                showSortMenu = false
                                onSortChange(field.selectFrom(sortOrder))
                            },
                            { Text(field.label) },
                            menuItemShape(index, fields.size),
                            Modifier,
                            null,
                            { if (isCurrent) SortDirectionIcon(sortOrder) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (onFindDuplicates != null) {
                TooltipIconButton(Icons.Outlined.FileCopy, "在此文件夹查找重复", onFindDuplicates)
            }
            ViewModeToggle(isPosterMode = isPosterMode, onTogglePosterMode = onTogglePosterMode)
        }
    }
}

/** 列表与海报墙二选一，M3 Expressive 连体按钮组，当前视图为选中态。 */
@Composable
private fun ViewModeToggle(isPosterMode: Boolean, onTogglePosterMode: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(
            checked = !isPosterMode,
            onCheckedChange = { if (isPosterMode) onTogglePosterMode() },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            contentPadding = ViewToggleContentPadding,
        ) {
            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "列表视图", modifier = Modifier.size(20.dp))
        }
        ToggleButton(
            checked = isPosterMode,
            onCheckedChange = { if (!isPosterMode) onTogglePosterMode() },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            contentPadding = ViewToggleContentPadding,
        ) {
            Icon(Icons.Filled.GridView, contentDescription = "海报视图", modifier = Modifier.size(20.dp))
        }
    }
}

private val ViewToggleContentPadding = PaddingValues(horizontal = 12.dp)

/** 启发式折叠提示。作为列表的一项随内容滚走，不再常驻在列表上方。 */
@Composable
internal fun FoldBanner(
    isFolded: Boolean,
    hiddenCount: Int,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (isFolded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = if (isFolded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isFolded) "已折叠 $hiddenCount 个次要项" else "已显示全部，含 $hiddenCount 个次要项",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFolded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggle) {
                Text(if (isFolded) "显示全部" else "恢复折叠")
            }
        }
    }
}

/** 在父级给的宽度两侧各多占 [bleed]，用来抵消容器的水平内边距。 */
private fun Modifier.bleedHorizontal(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = (bleed * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        ),
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-bleed.roundToPx(), 0)
    }
}
