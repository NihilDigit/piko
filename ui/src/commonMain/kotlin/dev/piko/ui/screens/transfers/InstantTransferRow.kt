package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.InstantSaveRecord
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

private fun InstantSaveRecord.details(): List<String> = buildList {
    // 一个视频连同字幕时文件数大于 1，只存一个文件时不必再说「1 个文件」
    if (fileCount > 1) add("$fileCount 个文件")
    add(totalBytes.toReadableSize())
}

/** 秒传的列表项，版式与上传项一致。秒传当场完成，只有「已完成」这一种状态；点按跳到网盘里的文件。 */
@Composable
internal fun InstantTransferRow(
    record: InstantSaveRecord,
    onOpen: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    FileListItem(
        headline = record.name,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingMedia(thumbnail = null, fallback = { ListLeadingIcon(Icons.Outlined.Bolt) }, isSpoilerBlurred = false) },
        onClick = onOpen,
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = 2,
        supporting = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "秒传",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                // 不写「已保存」：秒传当场完成，所在的分组已经说了
                MetaRow(parts = record.details(), modifier = Modifier.weight(1f, fill = false))
            }
        },
        trailing = { ListMoreButton(onClick = onMoreClick) },
    )
}

/** 秒传项的详情面板：完整名称、保存位置与操作。 */
@Composable
internal fun InstantTransferSheet(record: InstantSaveRecord, actions: List<SheetAction>, onDismiss: () -> Unit) {
    ItemDetailsSheet(
        title = record.name,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf("秒传", "已保存") + record.details(),
        extraLines = {
            Text(text = "保存到 ${record.targetName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/** 秒传记录的全部操作，详情面板与右键菜单共用。 */
internal fun instantTransferActions(onOpen: () -> Unit, onRemove: () -> Unit): List<SheetAction> = listOf(
    SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onOpen),
    SheetAction(Icons.Outlined.Delete, "移除记录", onRemove, destructive = true),
)
