package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.FileSortOrder
import dev.piko.ui.components.FileItemRow
import io.github.nihildigit.pikpak.FileStat

/**
 * 列表视图也用 LazyVerticalGrid：单列宽度下限 360dp，手机上始终一列，横屏平板上
 * 自动排成两列以上。M3 列表规范要求宽窗口下控制行长或改为多栏，否则一行名字会被
 * 拉得很长。网格视图则按 128dp 自适应。两种视图共用同一个 LazyGridState。
 */
private val ListColumnMinWidth = 360.dp
private val GridColumnMinWidth = 128.dp

private const val KEY_HEADER = "drive_header"
private const val KEY_FOLD = "drive_fold"
private const val KEY_FILES_START = "drive_files_start"

/** 列表或网格里的每一项需要的回调，由 DriveScreen 按条目绑定。 */
internal class DriveItemCallbacks(
    val onOpen: (FileStat) -> Unit,
    val onMore: (FileStat) -> Unit,
    val onLongPress: (FileStat) -> Unit,
    val onSelect: (FileStat, Boolean) -> Unit,
    val onToggleSpoiler: (FileStat) -> Unit,
)

@Composable
internal fun DriveFileGrid(
    files: List<FileStat>,
    isGridMode: Boolean,
    gridState: LazyGridState,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    highlightedIds: Set<String>,
    isBlurred: (FileStat) -> Boolean,
    hitLocations: Map<String, String>,
    callbacks: DriveItemCallbacks,
    bottomPadding: Dp,
    header: @Composable () -> Unit,
    foldBanner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 文件夹始终排在前面。目录列表在仓库层已经这样排好；全盘搜索的结果按到达顺序，
    // 这里一并归拢，网格视图才能让文件夹图块与文件卡片各成一段。
    val (folders, regularFiles) = remember(files) { files.partition(FileStat::isFolder) }
    val leadingItemCount = 1 + (if (foldBanner != null) 1 else 0)
    val splitSpacer = isGridMode && folders.isNotEmpty() && regularFiles.isNotEmpty()

    // 刚秒传成功时滚到新条目。视图模式是异步读出来的偏好，首帧拿到的还是默认值，
    // 所以它也要进 key，否则真值到达前的滚动会停在错误的位置。
    LaunchedEffect(files, highlightedIds, isGridMode) {
        if (highlightedIds.isEmpty()) return@LaunchedEffect
        val folderIndex = folders.indexOfFirst { it.id in highlightedIds }
        val index = if (folderIndex >= 0) {
            leadingItemCount + folderIndex
        } else {
            val fileIndex = regularFiles.indexOfFirst { it.id in highlightedIds }
            if (fileIndex < 0) return@LaunchedEffect
            leadingItemCount + folders.size + (if (splitSpacer) 1 else 0) + fileIndex
        }
        gridState.animateScrollToItem(index)
    }

    val horizontalPadding = if (isGridMode) 16.dp else 0.dp
    val itemSpacing = if (isGridMode) 8.dp else 0.dp

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(if (isGridMode) GridColumnMinWidth else ListColumnMinWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            bottom = bottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        item(key = KEY_HEADER, span = { GridItemSpan(maxLineSpan) }, contentType = KEY_HEADER) {
            // 网格模式的 16dp 页边距由 contentPadding 统一给出；列表模式没有，页眉自己补。
            // 列表模式末端只补 4dp，让视图切换按钮的图标与行尾的更多按钮对齐。
            Box(
                modifier = if (isGridMode) Modifier else Modifier.padding(start = 16.dp, end = 4.dp),
            ) {
                header()
            }
        }
        if (foldBanner != null) {
            item(key = KEY_FOLD, span = { GridItemSpan(maxLineSpan) }, contentType = KEY_FOLD) {
                Box(modifier = Modifier.padding(horizontal = if (isGridMode) 0.dp else 16.dp)) {
                    foldBanner()
                }
            }
        }

        items(folders, key = { it.id }, contentType = { "folder" }) { file ->
            val selected = file.id in selectedIds
            val highlighted = file.id in highlightedIds
            if (isGridMode) {
                FolderGridTile(
                    file = file,
                    isSelectionMode = isSelectionMode,
                    isSelected = selected,
                    isHighlighted = highlighted,
                    onClick = { callbacks.onOpen(file) },
                    onLongClick = { callbacks.onLongPress(file) },
                    onSelectToggle = { callbacks.onSelect(file, it) },
                    onMoreClick = { callbacks.onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            } else {
                FileItemRow(
                    file = file,
                    isSelectionMode = isSelectionMode,
                    isSelected = selected,
                    isHighlighted = highlighted,
                    locationLabel = hitLocations[file.id],
                    onClick = { callbacks.onOpen(file) },
                    onLongClick = { callbacks.onLongPress(file) },
                    onSelectToggle = { callbacks.onSelect(file, it) },
                    onMoreClick = { callbacks.onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (splitSpacer) {
            // 让第一张文件卡片另起一行，不与最后一排文件夹图块挤在同一行
            item(key = KEY_FILES_START, span = { GridItemSpan(maxLineSpan) }, contentType = KEY_FILES_START) {
                Spacer(modifier = Modifier.fillMaxWidth())
            }
        }

        items(regularFiles, key = { it.id }, contentType = { "file" }) { file ->
            val selected = file.id in selectedIds
            val highlighted = file.id in highlightedIds
            val blurred = isBlurred(file)
            if (isGridMode) {
                FileGridCard(
                    file = file,
                    isSelectionMode = isSelectionMode,
                    isSelected = selected,
                    isSpoilerBlurred = blurred,
                    isHighlighted = highlighted,
                    onToggleSpoiler = { callbacks.onToggleSpoiler(file) },
                    onClick = { callbacks.onOpen(file) },
                    onLongClick = { callbacks.onLongPress(file) },
                    onSelectToggle = { callbacks.onSelect(file, it) },
                    onMoreClick = { callbacks.onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            } else {
                FileItemRow(
                    file = file,
                    isSelectionMode = isSelectionMode,
                    isSelected = selected,
                    isHighlighted = highlighted,
                    isSpoilerBlurred = blurred,
                    locationLabel = hitLocations[file.id],
                    onToggleSpoiler = { callbacks.onToggleSpoiler(file) },
                    onClick = { callbacks.onOpen(file) },
                    onLongClick = { callbacks.onLongPress(file) },
                    onSelectToggle = { callbacks.onSelect(file, it) },
                    onMoreClick = { callbacks.onMore(file) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private fun FileSortOrder.shortLabel(): String = when (this) {
    FileSortOrder.NAME_ASC, FileSortOrder.NAME_DESC -> "名称"
    FileSortOrder.TIME_DESC, FileSortOrder.TIME_ASC -> "修改时间"
    FileSortOrder.SIZE_DESC, FileSortOrder.SIZE_ASC -> "大小"
}

private val SortChoices = listOf(
    FileSortOrder.TIME_DESC to "修改时间（新到旧）",
    FileSortOrder.NAME_ASC to "名称（A 到 Z）",
    FileSortOrder.SIZE_DESC to "大小（大到小）",
)

/**
 * 列表页眉：搜索时的结果说明、排序、视图切换。
 *
 * 排序与视图切换原先挤在顶栏，与新建、搜索一起共四个图标。M3 顶栏规范建议只放一到
 * 两个动作；这两个是作用于列表本身的控件，放进随列表滚走的页眉，不再常驻占位。
 * 排序按钮直接写出当前排序方式，菜单里对当前项打勾，原先两处都看不出当前状态。
 */
@Composable
internal fun DriveListHeader(
    summary: String?,
    sortOrder: FileSortOrder,
    onSortChange: (FileSortOrder) -> Unit,
    isGridMode: Boolean,
    onToggleGridMode: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Box {
            TextButton(onClick = { showSortMenu = true }) {
                Icon(
                    Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("按${sortOrder.shortLabel()}")
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                SortChoices.forEach { (order, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (order == sortOrder) Icon(Icons.Outlined.Check, contentDescription = "当前排序")
                        },
                        onClick = {
                            showSortMenu = false
                            onSortChange(order)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onToggleGridMode) {
            Icon(
                imageVector = if (isGridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                contentDescription = if (isGridMode) "切换为列表视图" else "切换为网格视图",
            )
        }
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
