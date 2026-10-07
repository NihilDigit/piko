package dev.piko.ui.screens.drive

import androidx.compose.ui.input.pointer.isTertiaryPressed
import dev.piko.ui.components.LocalFileDrag
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.components.ItemColumnMinWidth
import androidx.compose.ui.draw.alpha
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.components.fileDropTarget
import dev.piko.ui.components.fileDragSource
import dev.piko.ui.components.FileDragPayload
import dev.piko.ui.components.marqueeSelection
import dev.piko.ui.components.OwnClicks
import dev.piko.ui.components.LocalOwnClicks
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.adaptive.WidthClass
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
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.menuItemShape
import dev.piko.ui.components.selectionClicks
import dev.piko.ui.components.zoomOnWheel
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘列表的三种排列。名字存进偏好（PikoUserPreferences.driveViewModeFlow），不要改名；
 * 先后即菜单里的顺序，海报墙是默认，排在最后。
 * 信息流不在这里，它的开关在网盘页顶栏上，见 FeedToggle。
 */
internal enum class DriveViewMode {
    LIST,
    GALLERY,
    POSTER,
    ;

    /** 海报墙与图库都是带页边距的网格，整行项与页眉的排布相同。 */
    val isGrid: Boolean get() = this != LIST

    companion object {
        fun of(name: String): DriveViewMode = entries.firstOrNull { it.name == name } ?: POSTER
    }
}

/**
 * 三种视图都用按行对齐的 LazyVerticalGrid，共用同一个状态与作品头、分区标题这些整行项。
 * 不用瀑布流：它把每一项放进当前最矮的一栏，各栏宽度因除不尽差一两个像素时，按宽度算高度的海报与方块
 * 就高低不齐，第二行的第一项落到最右边；列表行高不一时顺序更是在各栏间乱跳。文件要能按行读。
 *
 * 列表视图单列宽度下限 360dp，手机上始终一列，横屏平板上自动排成两列以上。M3 列表规范
 * 要求宽窗口下控制行长或改为多栏，否则一行名字会被拉得很长。
 *
 * 海报墙与图库的卡宽下限随卡片大小分三档，见 [tileMinWidth]。
 */
// 与各内容列表页同一个栏宽，见 PikoItemGrid
private val ListColumnMinWidth = ItemColumnMinWidth

private const val DraggedAlpha = 0.4f

private const val KEY_HEADER = "drive_header"
private const val KEY_FOLD = "drive_fold"

/** 列表或海报墙里的每一项需要的回调，由 DriveScreen 按条目绑定。 */
internal class DriveItemCallbacks(
    val onOpen: (FileStat) -> Unit,
    /** mac 上焦点在这一项时按回车，照 Finder 是改名。 */
    val onRename: (FileStat) -> Unit,
    /** 条目上的更多按钮与菜单键：打开操作面板。 */
    val onMore: (FileStat) -> Unit,
    /** 焦点在这一项时按 Alt+Enter：打开它的属性。由条目自己接住，否则条目的单击先把回车当成打开。 */
    val onProperties: (FileStat) -> Unit,
    /** 条目排好位置时交出它的布局坐标，属性卡片据此贴在它旁边。只存不重组，见 PropertiesAnchors。 */
    val onPlaced: (FileStat, LayoutCoordinates) -> Unit,
    /** 进入多选并选中这一项：移动端是长按，桌面是右键菜单里的「选择」。 */
    val onStartSelection: (FileStat) -> Unit,
    val onSelect: (FileStat, Boolean) -> Unit,
    /**
     * Ctrl（⌘）点选，见 [selectionClicks]。这两个与 [onBoxSelect] 交的是行的 key（DriveListItem.File.key），
     * 不是文件 ID：查找重复里同一个文件占两行，连选从哪一行数起要分得清。
     */
    val onToggleSelect: (rowKey: String) -> Unit,
    /** Shift 点选。 */
    val onExtendSelect: (rowKey: String) -> Unit,
    /** 按住这一项拖动时拖出去的那一批，见 [fileDragSource]。 */
    val dragPayload: (FileStat) -> FileDragPayload?,
    /** 鼠标中键点了这一项：文件夹在新标签页里打开。 */
    val onMiddleClick: (FileStat) -> Unit,
    /** 框选，见 [marqueeSelection]。[base] 是文件 ID，[boxedRows] 是行的 key。 */
    val onBoxSelect: (base: Set<String>, boxedRows: Set<String>) -> Unit,
    /** 鼠标单击了网格的空白处。 */
    val onBackgroundClick: () -> Unit,
    /** 焦点进出这一项（含它里面的更多按钮），键盘操作据此知道作用于哪一项。 */
    val onFocusChanged: (FileStat, Boolean) -> Unit,
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
    /** 海报墙或图库的卡片大小；列表视图不看它。 */
    tileSize: TileSize,
    /** 主修饰键加滚轮，见 [zoomOnWheel]。 */
    onZoom: (larger: Boolean) -> Unit,
    gridState: LazyGridState,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    highlightedIds: Set<String>,
    highlightRevision: Int,
    structureReady: Boolean,
    locatedItemId: String?,
    isBlurred: (FileStat) -> Boolean,
    hitLocations: Map<String, String>,
    /** 文件夹的解析结果；原始文件名模式下恒为 null。 */
    folderView: (FileStat) -> DriveFolderView?,
    callbacks: DriveItemCallbacks,
    bottomPadding: Dp,
    /** 条目上画不画打开操作面板的更多按钮，见 DriveScreen 的 itemMoreButton。 */
    moreButton: Boolean,
    /** 网格空白处的右键菜单。 */
    backgroundActions: () -> List<SheetAction>,
    /** 连同右侧侧栏在内的宽度，栏数按它定，见 [StableColumns]；null 时按网格自己的宽度。 */
    columnReferenceWidth: Dp?,
    /** 鼠标最近点过、取得焦点的一项。按住它拖动是移动，按住别的没选中的条目拖动是框选。 */
    activeItemId: String?,
    /** 列过的文件夹空不空，海报墙给空文件夹画空的封面，见 PikoDriveRepository.folderEmptiness。 */
    emptyFolders: Map<String, Boolean> = emptyMap(),
    /** 挂归档标记的文件夹，见 PikoDriveRepository.vaultedFolders。 */
    vaultedFolders: Set<String> = emptySet(),
    /** 要把键盘焦点移到的那一行（行的 key），移过去后回调 [onKeyboardFocusMoved]。 */
    keyboardFocusTarget: String?,
    onKeyboardFocusMoved: () -> Unit,
    header: @Composable () -> Unit,
    foldBanner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val leadingItemCount = driveLeadingItemCount(foldBanner != null)
    val latestItems by rememberUpdatedState(items)
    val latestStructureReady by rememberUpdatedState(structureReady)
    val latestLeadingItemCount by rememberUpdatedState(leadingItemCount)
    val horizontalPadding = gridHorizontalPadding(viewMode)
    val itemSpacing = gridItemSpacing(viewMode)
    // 整行项在网格视图里已有页边距，列表视图里自己缩进
    val rowInset = if (viewMode.isGrid) 0.dp else 16.dp

    // 定位的描边等滚动停下再亮：它亮起时闪两下引开视线，赶上内容刚换、列表还在滚的那一刻就白闪了。
    // 从信息流跳过去时新目录要列一两秒，内容一到，滚动与闪烁同时开始（2026-10-06 桌面端日志）
    var highlightSettled by remember { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize().zoomOnWheel(onZoom)) {
        // 有条目要定位时滚到它（刚秒传的、从别处「在网盘中显示」的）。视图模式是异步读出来的偏好，首帧拿到的还是默认值，
        // 所以它也要进 key，否则真值到达前的滚动会停在错误的位置。
        LaunchedEffect(highlightRevision, highlightedIds, viewMode) {
            if (highlightedIds.isEmpty()) return@LaunchedEffect
            highlightSettled = false
            try {
                val index = snapshotFlow {
                    if (!latestStructureReady) -1 else latestItems.indexOfFirst { it is DriveListItem.File && it.file.id in highlightedIds }
                        .takeIf { it >= 0 }?.let { latestLeadingItemCount + it } ?: -1
                }.first { it >= 0 }
                gridState.animateScrollToItem(index)
            } finally {
                highlightSettled = true
            }
        }

        val fileIdByRow = remember(items) { items.filterIsInstance<DriveListItem.File>().associate { it.key to it.file.id } }
        // 空白处的右键菜单：查看、排序、刷新、粘贴、新建这些作用于整个文件夹的操作，照资源管理器。
        // 命令栏上照样都有，这里是鼠标用户就近的捷径；条目自己的菜单在里层，先接住
        ContextMenuArea(actions = backgroundActions, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            // 网格的可用宽度扣掉了两侧边距，参照宽度也扣掉，侧栏关着时两者相等
            columns = gridCells(viewMode, tileSize, columnReferenceWidth?.minus(horizontalPadding * 2)),
            modifier = modifier
                .fillMaxSize()
                .marqueeSelection(
                    gridState = gridState,
                    selectedIds = selectedIds,
                    boxedKey = { key -> (key as? String)?.takeIf { it in fileIdByRow } },
                    onSelect = callbacks.onBoxSelect,
                    onBackgroundClick = callbacks.onBackgroundClick,
                    // 点过的那一项（取得焦点）与选中的一样，按住它拖是移动
                    movable = { row -> fileIdByRow[row]?.let { id -> id in selectedIds || id == activeItemId } == true },
                ),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = bottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            item(key = KEY_HEADER, span = { GridItemSpan(maxLineSpan) }, contentType = KEY_HEADER) {
                // 页眉总是整行宽，内边距由它自己决定，各视图下排布一致。网格视图的
                // contentPadding 会把它缩进 16dp，这里向两侧撑回去
                Box(modifier = if (viewMode.isGrid) Modifier.bleedHorizontal(horizontalPadding) else Modifier) {
                    header()
                }
            }
            if (foldBanner != null) {
                item(key = KEY_FOLD, span = { GridItemSpan(maxLineSpan) }, contentType = KEY_FOLD) {
                    Box(modifier = Modifier.padding(horizontal = rowInset)) {
                        foldBanner()
                    }
                }
            }

            itemsIndexed(
                items,
                key = { _, item -> item.key },
                span = { _, item -> GridItemSpan(if (item is DriveListItem.File) 1 else maxLineSpan) },
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
                            rowKey = item.key,
                            text = cellText(item, if (file.isFolder) folderView(file) else null),
                            viewMode = viewMode,
                            isSelectionMode = isSelectionMode,
                            isSelected = file.id in selectedIds,
                            isHighlighted = highlightSettled && (file.id in highlightedIds || file.id == locatedItemId),
                            isBlurred = isBlurred(file),
                            locationLabel = hitLocations[file.id],
                            callbacks = callbacks,
                            moreButton = moreButton,
                            isEmptyFolder = file.isFolder && emptyFolders[file.id] == true,
                            folderHasVault = file.isFolder && file.id in vaultedFolders,
                            requestFocus = item.key == keyboardFocusTarget,
                            onFocusRequested = onKeyboardFocusMoved,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        }
        LocalPikoPlatform.current.ListScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun gridCells(viewMode: DriveViewMode, tileSize: TileSize, referenceWidth: Dp? = null): GridCells {
    val compact = currentWidthClass() == WidthClass.Compact
    val minSize = if (viewMode.isGrid) tileMinWidth(viewMode, tileSize, compact) else ListColumnMinWidth
    return StableColumns(minSize, referenceWidth)
}

/**
 * 按 [referenceWidth]（列表连同右侧信息流栏的总宽度）定栏数，网格自己的宽度只决定每栏多宽。
 * 按网格自己的宽度定的话，侧栏滑出的动画里网格每一帧都在变窄，五栏掉到四栏再到三栏，每掉一栏整屏条目换行重排，
 * 还带着位移动画满屏乱飞。现在侧栏是从每一栏借宽度，卡片一起收窄，谁也不换行。
 * 借得太多、一栏窄过下限的 [MIN_FRACTION] 时才少排一栏，免得卡片挤成一条。
 */
private class StableColumns(private val minSize: Dp, private val referenceWidth: Dp?) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val min = minSize.roundToPx()
        val reference = maxOf(referenceWidth?.roundToPx() ?: 0, availableSize)
        var count = ((reference + spacing) / (min + spacing)).coerceAtLeast(1)
        val floor = (min * MIN_FRACTION).toInt()
        while (count > 1 && (availableSize - spacing * (count - 1)) / count < floor) count--
        val total = availableSize - spacing * (count - 1)
        // 除不尽的几个像素分给前几栏，与 GridCells.Adaptive 相同
        return List(count) { index -> total / count + if (index < total % count) 1 else 0 }
    }

    override fun equals(other: Any?) = other is StableColumns && other.minSize == minSize && other.referenceWidth == referenceWidth

    override fun hashCode() = 31 * minSize.hashCode() + referenceWidth.hashCode()

    private companion object {
        const val MIN_FRACTION = 0.7f
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
 * 首载时的骨架，不含页眉。网格本身也是同一种 LazyVerticalGrid，列数、边距与间距取真实网格
 * 的同一套参数：宽窗口里列表排成多列，海报墙按卡宽下限换列数，另算一遍迟早与真实网格对不上。
 * 不可滚动，条目数给够一屏，多出来的懒加载不会组合。
 */
@Composable
internal fun DriveGridSkeleton(viewMode: DriveViewMode, tileSize: TileSize, modifier: Modifier = Modifier) {
    val itemSpacing = gridItemSpacing(viewMode)
    SkeletonGroup(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = gridCells(viewMode, tileSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gridHorizontalPadding(viewMode)),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
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
internal class CellText(val title: String?, val tags: List<String>, val code: String? = null, val resolution: String? = null)

internal val RawCellText = CellText(null, emptyList())

internal fun cellText(item: DriveListItem.File, folder: DriveFolderView?): CellText {
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
    rowKey: String,
    text: CellText,
    viewMode: DriveViewMode,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    isBlurred: Boolean,
    locationLabel: String?,
    callbacks: DriveItemCallbacks,
    moreButton: Boolean,
    isEmptyFolder: Boolean,
    folderHasVault: Boolean,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    modifier: Modifier,
) {
    val isMac = LocalPikoPlatform.current.shortcutModifier == ShortcutModifier.Command
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
            onFocusRequested()
        }
    }
    // 正被拖着的条目淡下去，看得出拖走的是哪几项
    val drag = LocalFileDrag.current
    val beingDragged = drag?.payload?.ids?.contains(file.id) == true
    // 条目里的更多按钮登记在这里，单击选中的那一层不截它的点击
    val ownClicks = remember { OwnClicks() }
    CompositionLocalProvider(LocalOwnClicks provides ownClicks) {
    ContextMenuArea(
        actions = { callbacks.contextActions(file) },
        onSelect = if (isSelectionMode) null else ({ callbacks.onStartSelection(file) }),
        modifier = modifier
            .alpha(if (beingDragged) DraggedAlpha else 1f)
            .focusRequester(focusRequester)
            .onFocusChanged { callbacks.onFocusChanged(file, it.hasFocus) }
            .onPlaced { callbacks.onPlaced(file, it) }
            // 鼠标点到哪一项，键盘就从哪一项接着走，与文件管理器相同。条目自己的单击不取焦点；
            // 触屏不取，否则点过的项留着一层焦点底色
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.changes.any { it.type == PointerType.Mouse }) {
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                }
            }
            .selectionClicks(
                onToggle = { callbacks.onToggleSelect(rowKey) },
                onExtend = { callbacks.onExtendSelect(rowKey) },
                // 多选时条目上画着勾选框，单击照旧是勾选或取消
                onDoubleClick = if (isSelectionMode) null else ({ callbacks.onOpen(file) }),
                ownClicks = ownClicks,
            )
            // Finder 里回车是改名；资源管理器里回车是打开，交给条目自己的单击
            .onPreviewKeyEvent { event ->
                val isReturn = event.key == Key.Enter || event.key == Key.NumPadEnter
                // 资源管理器的 Alt+Enter 是属性
                if (isReturn && !isMac && event.isAltPressed) {
                    if (event.type == KeyEventType.KeyDown) callbacks.onProperties(file)
                    return@onPreviewKeyEvent true
                }
                if (!isReturn || !isMac || isSelectionMode) return@onPreviewKeyEvent false
                // 按下与松开都吃掉：只吃按下的话，条目的单击在松开时照样触发
                if (event.type == KeyEventType.KeyDown) callbacks.onRename(file)
                true
            }
            .fileDragSource { callbacks.dragPayload(file) }
            .pointerInput(file.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) callbacks.onMiddleClick(file)
                    }
                }
            }
            // 文件夹接得住拖来的条目
            .then(if (file.isFolder) Modifier.fileDropTarget("cell:${file.id}", PikoPathBreadcrumb(file.id, file.name)) else Modifier),
    ) {
        when (viewMode) {
            DriveViewMode.GALLERY -> GalleryTile(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isSpoilerBlurred = isBlurred,
                isHighlighted = isHighlighted,
                onClick = { callbacks.onOpen(file) },
                onLongClick = { callbacks.onStartSelection(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
                folderHasVault = folderHasVault,
            )
            DriveViewMode.POSTER -> PosterCard(
                file = file,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                isSpoilerBlurred = isBlurred,
                isHighlighted = isHighlighted,
                onClick = { callbacks.onOpen(file) },
                onLongClick = { callbacks.onStartSelection(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
                onMoreClick = { callbacks.onMore(file) },
                moreButton = moreButton,
                isEmptyFolder = isEmptyFolder,
                title = text.title,
                tags = text.tags,
                code = text.code,
                resolution = text.resolution,
                folderHasVault = folderHasVault,
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
                onLongClick = { callbacks.onStartSelection(file) },
                onSelectToggle = { callbacks.onSelect(file, it) },
                onMoreClick = { callbacks.onMore(file) },
                moreButton = moreButton,
                title = text.title,
                tags = text.tags,
                code = text.code,
                folderHasVault = folderHasVault,
            )
        }
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
    tileSize: TileSize,
    onTileSizeChange: (TileSize) -> Unit,
    /** 为 false 时只留搜索结果的说明：宽窗口里这些控件在命令栏上。 */
    showControls: Boolean = true,
) {
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
        if (!showControls) {
            if (summary != null) Spacer(Modifier.height(8.dp))
            return@Column
        }
        // 排序靠左、视图切换靠右，读作列表自身的控件；原先两者都靠右，像是顶栏放不下挤下来的第二排。
        // 窄到放不下时先把视图切换、再把类型筛选收进「更多」：排序按钮写着当前的排法，最后收
        val showFilter = typeFilter != null || availableTypes.size > 1
        AdaptiveBar(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            leading = listOfNotNull(
                BarItem("sort", 30, sortOverflowActions(sortOrder, onSortChange)) { SortButton(sortOrder, onSortChange) },
                BarItem("filter", 20, typeFilterOverflowActions(typeFilter, availableTypes, onTypeFilterChange)) {
                    TypeFilterButton(typeFilter, availableTypes, onTypeFilterChange)
                }.takeIf { showFilter },
            ),
            // 大小紧挨在视图切换前面，放不下时先于它收起
            trailing = listOfNotNull(
                BarItem("size", 5, tileSizeActions(viewMode, tileSize, onTileSizeChange)) {
                    TileSizeButton(tileSize, onTileSizeChange)
                }.takeIf { viewMode.isGrid },
                BarItem("view", 10, viewModeOverflowActions(viewMode, onViewModeChange)) {
                    ViewModeToggle(viewMode = viewMode, onViewModeChange = onViewModeChange)
                },
            ),
        )
    }
}

/** 排序收进「更多」时摊成几项，当前的一项打勾并写出方向，再点它是翻转，与排序按钮的菜单相同。 */
internal fun sortOverflowActions(sortOrder: FileSortOrder, onSortChange: (FileSortOrder) -> Unit): List<SheetAction> =
    PikoSortField.entries.map { field ->
        val current = field.owns(sortOrder)
        val direction = if (sortOrder.isAscending) "升序" else "降序"
        SheetAction(
            Icons.AutoMirrored.Outlined.Sort,
            if (current) "按${field.label}（$direction）" else "按${field.label}",
            { onSortChange(field.selectFrom(sortOrder)) },
            group = ActionGroup.Sort,
            checked = current,
        )
    }

internal fun typeFilterOverflowActions(
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
): List<SheetAction> =
    listOf(SheetAction(Icons.Outlined.FilterList, "全部类型", { onTypeFilterChange(null) }, group = ActionGroup.Filter, checked = typeFilter == null)) +
        availableTypes.map { (category, count) ->
            SheetAction(category.icon(), "${category.label}（$count）", { onTypeFilterChange(category) }, group = ActionGroup.Filter, checked = category == typeFilter)
        }

internal fun viewModeOverflowActions(viewMode: DriveViewMode, onViewModeChange: (DriveViewMode) -> Unit): List<SheetAction> =
    DriveViewMode.entries.map { mode ->
        SheetAction(mode.icon(selected = mode == viewMode), mode.label, { onViewModeChange(mode) }, group = ActionGroup.View, checked = mode == viewMode)
    }

/**
 * 排序按钮，写出当前字段与方向。菜单里再点当前字段即切换升降序，点其他字段则按该字段的起始方向排，
 * 不必为六种组合各列一项。列表页眉与宽窗口的命令栏共用。
 */
@Composable
internal fun SortButton(sortOrder: FileSortOrder, onSortChange: (FileSortOrder) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { showMenu = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("按${sortOrder.field.label}")
            Spacer(modifier = Modifier.width(2.dp))
            SortDirectionIcon(sortOrder, modifier = Modifier.size(16.dp))
        }
        PikoDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            val fields = PikoSortField.entries
            fields.forEachIndexed { index, field ->
                val isCurrent = field.owns(sortOrder)
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onSortChange(field.selectFrom(sortOrder))
                    },
                    text = { Text(field.label) },
                    shape = menuItemShape(index, fields.size),
                    trailingIcon = { if (isCurrent) SortDirectionIcon(sortOrder) },
                )
            }
        }
    }
}

/**
 * 类型筛选，只在列表里有两类以上文件、或已在筛选时出现：只有一类时筛了等于没筛。
 * 菜单里每类带上数量，选之前就知道会剩多少。列表页眉与宽窗口的命令栏共用。
 */
@Composable
internal fun TypeFilterButton(
    typeFilter: FileCategory?,
    availableTypes: List<Pair<FileCategory, Int>>,
    onTypeFilterChange: (FileCategory?) -> Unit,
) {
    if (typeFilter == null && availableTypes.size <= 1) return
    var showMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { showMenu = true }) {
            Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(typeFilter?.label ?: "全部类型")
        }
        PikoDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            val options = listOf<Pair<FileCategory?, Int?>>(null to null) + availableTypes
            options.forEachIndexed { index, (category, count) ->
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
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

/** 视图的图标：平时描边，当前的那个视图用实心，与全应用「选中即实心」一致。 */
internal fun DriveViewMode.icon(selected: Boolean = false) = when (this) {
    DriveViewMode.LIST -> if (selected) Icons.AutoMirrored.Filled.ViewList else Icons.AutoMirrored.Outlined.ViewList
    DriveViewMode.POSTER -> if (selected) Icons.Filled.GridView else Icons.Outlined.GridView
    DriveViewMode.GALLERY -> if (selected) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary
}

internal val DriveViewMode.label
    get() = when (this) {
        DriveViewMode.LIST -> "列表"
        DriveViewMode.POSTER -> "海报墙"
        DriveViewMode.GALLERY -> "图库"
    }

/**
 * 视图切换，M3 Expressive 连体按钮组：列表、海报墙、图库三选一。窄屏在列表上方，宽窗口在命令栏右端、信息流旁边：
 * 两者都是换一种方式看这个文件夹。只有图标，名字靠悬停提示。
 */
@Composable
internal fun ViewModeToggle(
    viewMode: DriveViewMode,
    onViewModeChange: (DriveViewMode) -> Unit,
) {
    val modes = DriveViewMode.entries
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { index, mode ->
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(mode.label) } },
                state = rememberTooltipState(),
            ) {
                ToggleButton(
                    checked = mode == viewMode,
                    onCheckedChange = { if (mode != viewMode) onViewModeChange(mode) },
                    shapes = connectedToggleShapes(index, modes.size),
                    contentPadding = ViewToggleContentPadding,
                ) {
                    Icon(mode.icon(selected = mode == viewMode), contentDescription = "${mode.label}视图", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private val ViewToggleContentPadding = PaddingValues(horizontal = 12.dp)

/** 窄窗口列表页眉里的卡片大小：图标是眼下这一档，点开三档里挑。宽窗口的在命令栏的视图菜单里。 */
@Composable
private fun TileSizeButton(tileSize: TileSize, onTileSizeChange: (TileSize) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(tileSize.icon, "卡片大小：${tileSize.label}", { showMenu = true })
        PikoDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            TileSizeMenuItems(tileSize) {
                showMenu = false
                onTileSizeChange(it)
            }
        }
    }
}

/** 大、中、小三项，眼下这一档用强调色，与视图菜单里当前视图的写法一致。列表页眉与命令栏的视图菜单共用。 */
@Composable
internal fun TileSizeMenuItems(tileSize: TileSize, firstIndex: Int = 0, total: Int = TileSize.entries.size, onSelect: (TileSize) -> Unit) {
    TileSize.entries.reversed().forEachIndexed { index, option ->
        val current = option == tileSize
        val tint = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified
        DropdownMenuItem(
            text = { Text(option.label, color = tint) },
            leadingIcon = { Icon(option.icon, contentDescription = null, tint = if (current) tint else LocalContentColor.current, modifier = Modifier.size(20.dp)) },
            shape = menuItemShape(firstIndex + index, total),
            onClick = { onSelect(option) },
        )
    }
}

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
