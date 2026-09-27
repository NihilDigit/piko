package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.boundedStaggeredCells
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.FileSortOrder
import dev.piko.data.repository.label
import dev.piko.shared.data.PikoSortField
import dev.piko.shared.data.field
import dev.piko.shared.data.isAscending
import dev.piko.ui.components.ContextMenuArea
import dev.piko.shared.state.DriveFolderView
import dev.piko.shared.state.DriveListItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.FileRowSkeleton
import dev.piko.ui.components.SkeletonBlock
import dev.piko.ui.components.SkeletonGroup
import dev.piko.ui.components.icon
import dev.piko.ui.components.MediaTagRow
import dev.piko.ui.components.PikoDropdownMenu
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘列表的三种排列。名字存进偏好（PikoUserPreferences.driveViewModeFlow），不要改名。
 * 视图切换里的第四项信息流不在这里，见 ViewModeToggle。
 */
internal enum class DriveViewMode {
    LIST,
    POSTER,
    GALLERY,
    ;

    /** 海报墙与图库都是带页边距的网格，整行项与页眉的排布相同。 */
    val isGrid: Boolean get() = this != LIST

    companion object {
        fun of(name: String): DriveViewMode = entries.firstOrNull { it.name == name } ?: LIST
    }
}

/**
 * 三种视图都用 LazyVerticalStaggeredGrid，共用同一个状态与作品头、分区标题这些整行项。
 * 海报墙的卡片等高（16:9 封面加两行标题），图库是正方形，在这个网格里排出来都是齐整的行。
 *
 * 列表视图单列宽度下限 360dp，手机上始终一列，横屏平板上自动排成两列以上。M3 列表规范
 * 要求宽窗口下控制行长或改为多栏，否则一行名字会被拉得很长。
 *
 * 海报墙的卡宽下限：手机上 160dp，排两列（原先 128dp 在 432dp 宽的手机上排成三列，名字只剩
 * 一行四个汉字）；宽窗口里 240dp，封面够大，模糊时也辨得出轮廓。
 *
 * 图库的格宽下限：手机上 104dp，432dp 宽排三列，与系统相册相近；宽窗口里 140dp。
 */
private val ListColumnMinWidth = 360.dp
// 宽窗口里列表行的宽度范围：再窄名字只剩一行，再宽名字与行尾按钮隔得太远
private val ListColumnMinWidthWide = 320.dp
private val ListColumnMaxWidth = 480.dp
private val PosterColumnMinWidthCompact = 160.dp
private val PosterColumnMinWidth = 240.dp
private val PosterColumnMaxWidth = 320.dp
private val GalleryColumnMinWidthCompact = 104.dp
private val GalleryColumnMinWidth = 140.dp

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
    /** 文件夹在可见区域里，挂起到预取完成，离开时随行的协程取消，见 DriveScreenState.onFolderVisible。 */
    val onFolderVisible: suspend (FileStat) -> Unit,
)

/** 列表前面固定的几项：页眉，以及有时出现的折叠横幅。分区跳转与副标题反查要扣掉它们。 */
internal fun driveLeadingItemCount(hasFoldBanner: Boolean): Int = 1 + (if (hasFoldBanner) 1 else 0)

@Composable
internal fun DriveFileGrid(
    items: List<DriveListItem>,
    viewMode: DriveViewMode,
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
    val horizontalPadding = gridHorizontalPadding(viewMode)
    val itemSpacing = gridItemSpacing(viewMode)
    // 整行项在网格视图里已有页边距，列表视图里自己缩进
    val rowInset = if (viewMode.isGrid) 0.dp else 16.dp

    Box(modifier = modifier.fillMaxSize()) {
        // 有条目要定位时滚到它（刚秒传的、从别处「在网盘中显示」的）。视图模式是异步读出来的偏好，首帧拿到的还是默认值，
        // 所以它也要进 key，否则真值到达前的滚动会停在错误的位置。
        LaunchedEffect(items, highlightedIds, viewMode) {
            if (highlightedIds.isEmpty()) return@LaunchedEffect
            val entryIndex = items.indexOfFirst { it is DriveListItem.File && it.file.id in highlightedIds }
            if (entryIndex >= 0) gridState.animateScrollToItem(leadingItemCount + entryIndex)
        }

        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = gridCells(viewMode),
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
                // 页眉总是整行宽，内边距由它自己决定，各视图下排布一致。网格视图的
                // contentPadding 会把它缩进 16dp，这里向两侧撑回去
                Box(modifier = if (viewMode.isGrid) Modifier.bleedHorizontal(horizontalPadding) else Modifier) {
                    header()
                }
            }
            if (foldBanner != null) {
                item(key = KEY_FOLD, span = StaggeredGridItemSpan.FullLine, contentType = KEY_FOLD) {
                    Box(modifier = Modifier.padding(horizontal = rowInset)) {
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
                        modifier = Modifier.animateItem().padding(horizontal = rowInset),
                    )
                    is DriveListItem.SectionHeader -> SectionHeaderRow(
                        header = item,
                        // 网格视图已有 16dp 边距，文字与卡片左缘对齐即可
                        inset = if (viewMode.isGrid) 4.dp else 16.dp,
                        onClick = { callbacks.onToggleSection(item.blockId) },
                        modifier = Modifier.animateItem(),
                    )
                    is DriveListItem.File -> {
                        val file = item.file
                        if (file.isFolder) LaunchedEffect(file.id) { callbacks.onFolderVisible(file) }
                        DriveCell(
                            file = file,
                            text = cellText(item, if (file.isFolder) folderView(file) else null),
                            viewMode = viewMode,
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

/**
 * compact 保持原样：按下限自适应。更宽时列表与海报墙每列另有上限，列数向上取整，见 [boundedStaggeredCells]。
 */
@Composable
private fun gridCells(viewMode: DriveViewMode): StaggeredGridCells {
    if (currentWidthClass() == WidthClass.Compact) {
        return StaggeredGridCells.Adaptive(
            when (viewMode) {
                DriveViewMode.LIST -> ListColumnMinWidth
                DriveViewMode.POSTER -> PosterColumnMinWidthCompact
                DriveViewMode.GALLERY -> GalleryColumnMinWidthCompact
            },
        )
    }
    return when (viewMode) {
        DriveViewMode.LIST -> boundedStaggeredCells(ListColumnMinWidthWide, ListColumnMaxWidth)
        DriveViewMode.POSTER -> boundedStaggeredCells(PosterColumnMinWidth, PosterColumnMaxWidth)
        // 图库是照片墙，格子密一些正是要的，不设上限
        DriveViewMode.GALLERY -> StaggeredGridCells.Adaptive(GalleryColumnMinWidth)
    }
}

private fun gridHorizontalPadding(viewMode: DriveViewMode): Dp = if (viewMode.isGrid) 16.dp else 0.dp

// 图库格子之间只留一道细缝，照片连成一片；海报墙的卡片带标题，要分得开些
private fun gridItemSpacing(viewMode: DriveViewMode): Dp = when (viewMode) {
    DriveViewMode.LIST -> 0.dp
    DriveViewMode.POSTER -> 8.dp
    DriveViewMode.GALLERY -> 4.dp
}

/**
 * 首载时的骨架，不含页眉。网格本身也是同一种 LazyVerticalStaggeredGrid，列数、边距与间距取真实网格
 * 的同一套参数：宽窗口里列表排成多列，海报墙按卡宽下限换列数，另算一遍迟早与真实网格对不上。
 * 不可滚动，条目数给够一屏，多出来的懒加载不会组合。
 */
@Composable
internal fun DriveGridSkeleton(viewMode: DriveViewMode, modifier: Modifier = Modifier) {
    val itemSpacing = gridItemSpacing(viewMode)
    SkeletonGroup(modifier = modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = gridCells(viewMode),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gridHorizontalPadding(viewMode)),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalItemSpacing = itemSpacing,
            userScrollEnabled = false,
        ) {
            items(SKELETON_ITEM_COUNT) { index ->
                val titleFraction = SkeletonTitleWidths[index % SkeletonTitleWidths.size]
                when (viewMode) {
                    DriveViewMode.LIST -> FileRowSkeleton(titleFraction = titleFraction)
                    DriveViewMode.POSTER -> PosterCardSkeleton(titleFraction)
                    DriveViewMode.GALLERY -> SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.small)
                }
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
    viewMode: DriveViewMode,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isBlurred: Boolean,
    locationLabel: String?,
    callbacks: DriveItemCallbacks,
    modifier: Modifier,
) {
    ContextMenuArea(actions = { callbacks.contextActions(file) }, modifier = modifier) {
        when (viewMode) {
            DriveViewMode.GALLERY -> GalleryTile(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isSpoilerBlurred = isBlurred,
                isHighlighted = isHighlighted,
                onClick = { callbacks.onOpen(file) },
                onLongClick = { callbacks.onLongPress(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
            )
            DriveViewMode.POSTER -> PosterCard(
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
            DriveViewMode.LIST -> FileListItem(
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
 * 列表页眉：搜索时的结果说明、排序、类型筛选、视图切换。
 *
 * 排序与视图切换原先挤在顶栏，与新建、搜索一起共四个图标。M3 顶栏规范建议只放一到
 * 两个动作；这两个是作用于列表本身的控件，放进随列表滚走的页眉，不再常驻占位。
 * 排序按钮写出当前字段与方向。菜单里再点当前字段即切换升降序，点其他字段则按该字段的
 * 起始方向排，不必为六种组合各列一项。
 *
 * 类型筛选与排序同一种文字按钮，只在列表里有两类以上文件、或已在筛选时出现：只有一类时
 * 筛了等于没筛。菜单里每类带上数量，选之前就知道会剩多少。
 */
@Composable
internal fun DriveListHeader(
    summary: String?,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
    feedShown: Boolean = false,
    /** 见 ViewModeToggle。 */
    onFeedShownChange: ((Boolean) -> Unit)? = null,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
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
                        DropdownMenuItem(
                            onClick = {
                                showSortMenu = false
                                onSortChange(field.selectFrom(sortOrder))
                            },
                            text = { Text(field.label) },
                            shape = menuItemShape(index, fields.size),
                            trailingIcon = { if (isCurrent) SortDirectionIcon(sortOrder) },
                        )
                    }
                }
            }
            if (typeFilter != null || availableTypes.size > 1) {
                Box {
                    TextButton(onClick = { showTypeMenu = true }) {
                        Icon(
                            Icons.Outlined.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(typeFilter?.label ?: "全部类型")
                    }
                    PikoDropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        val options = listOf<Pair<FileCategory?, Int?>>(null to null) + availableTypes
                        options.forEachIndexed { index, (category, count) ->
                            DropdownMenuItem(
                                onClick = {
                                    showTypeMenu = false
                                    onTypeFilterChange(category)
                                },
                                text = {
                                    Text(
                                        text = category?.label ?: "全部类型",
                                        color = if (category == typeFilter) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                },
                                leadingIcon = category?.let { { Icon(it.icon(), contentDescription = null, modifier = Modifier.size(20.dp)) } },
                                trailingIcon = count?.let { { Text("$it", style = MaterialTheme.typography.labelMedium) } },
                                shape = menuItemShape(index, options.size),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ViewModeToggle(
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                feedShown = feedShown,
                onFeedShownChange = onFeedShownChange,
            )
        }
    }
}

/**
 * 视图切换，M3 Expressive 连体按钮组：列表、海报墙、图库三选一，末尾是信息流。
 *
 * 信息流不是 [DriveViewMode] 的第四个值，而是单独的开关 [feedShown]：它在宽窗口里开在右侧侧栏，
 * 主区照旧按原来的视图排列，此时两个按钮同为选中态，各自说明眼前看得到的一块；窄窗口里它盖住整个网盘页，
 * 这一行本身就看不到了。关掉信息流即回到原来的视图，不必另记「进信息流之前是哪一种」，
 * 存进偏好的视图名也不会出现一个旧版读不懂的值。[onFeedShownChange] 为 null 时不给这一项。
 */
@Composable
private fun ViewModeToggle(
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
    feedShown: Boolean,
    onFeedShownChange: ((Boolean) -> Unit)?,
) {
    val modes = DriveViewMode.entries
    val lastIndex = if (onFeedShownChange != null) modes.size else modes.lastIndex
    @Composable
    fun shapesAt(index: Int) = when (index) {
        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = mode == viewMode,
                onCheckedChange = { if (mode != viewMode) onViewModeChange(mode) },
                shapes = shapesAt(index),
                contentPadding = ViewToggleContentPadding,
            ) {
                val (icon, label) = when (mode) {
                    DriveViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList to "列表视图"
                    DriveViewMode.POSTER -> Icons.Filled.GridView to "海报视图"
                    DriveViewMode.GALLERY -> Icons.Filled.PhotoLibrary to "图库视图"
                }
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
        if (onFeedShownChange != null) {
            ToggleButton(
                checked = feedShown,
                onCheckedChange = onFeedShownChange,
                shapes = shapesAt(modes.size),
                contentPadding = ViewToggleContentPadding,
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = "信息流", modifier = Modifier.size(20.dp))
            }
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
