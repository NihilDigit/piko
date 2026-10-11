package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.FileWriteWork
import dev.piko.ui.WorkMeter
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.workProgress

@Composable
internal fun FileWriteTransferRow(work: FileWriteWork, undo: DriveChangeJournal.UndoResult?, onDetails: () -> Unit, selection: RowSelection) {
    ProcessingTransferRow(work.workProgress(undo), onDetails, selection)
}

@Composable
internal fun ProcessingTransferRow(progress: dev.piko.ui.WorkProgress, onDetails: () -> Unit, selection: RowSelection) {
    FileListItem(
        headline = progress.title, headlineMaxLines = 1,
        onClick = onDetails, onMoreClick = onDetails,
        onLongClick = selection.onLongClick, isSelectionMode = selection.active, isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingIcon(Icons.Outlined.Description) },
        supporting = { Column {
            Text(progress.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress.meter != WorkMeter.None) WorkMeterBar(progress.meter, progress.title, Modifier.fillMaxWidth())
        } },
        trailing = { ListMoreButton(onClick = onDetails) },
    )
}

internal fun fileWriteActions(work: FileWriteWork, undo: DriveChangeJournal.UndoResult?, canUndo: Boolean, canRecheck: Boolean,
    onUndo: () -> Unit, onRecheck: () -> Unit, onForget: () -> Unit, onShow: (() -> Unit)? = null): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onShow))
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, "撤销已确认部分", onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
        add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
    }
}

@Composable
internal fun FileWriteTransferSheet(work: FileWriteWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val progress = work.workProgress(undo)
    ItemDetailsSheet(
        title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Description) }, actions = actions, onDismiss = onDismiss,
        metaParts = listOf("文件处理", progress.status),
        extraLines = {
            progress.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (progress.meter != WorkMeter.None) WorkMeterBar(progress.meter, progress.title, Modifier.fillMaxWidth())
            if (work.result?.unconfirmed?.isNotEmpty() == true) Text("重新查询只核对原操作的结果，不再次提交。没有远端任务或文件标识时，请先检查实际文件。")
            Text("移除记录不会改变文件，也不会取消服务端已经接受的操作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
