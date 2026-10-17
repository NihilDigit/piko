package dev.piko.ui.screens.transfers

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
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.workProgress

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
internal fun FileWriteTransferDetails(work: FileWriteWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress(undo)
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Description) }, actions = actions, metaParts = listOf("文件处理", progress.status),
        extraLines = {
            progress.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (progress.meter != WorkMeter.None) WorkMeterBar(progress.meter, progress.title, Modifier.fillMaxWidth())
            if (work.result?.unconfirmed?.isNotEmpty() == true) Text("重新查询只核对原操作的结果，不再次提交。没有远端任务或文件标识时，请先检查实际文件。")
            Text("移除记录不会改变文件，也不会取消服务端已经接受的操作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
