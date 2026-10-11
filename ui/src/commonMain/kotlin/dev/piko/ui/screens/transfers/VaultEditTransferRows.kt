package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.VaultEntryEditWork
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun vaultEditActions(work: VaultEntryEditWork, undo: DriveChangeJournal.UndoResult?, canUndo: Boolean, canRecheck: Boolean,
    onUndo: () -> Unit, onRecheck: () -> Unit, onForget: () -> Unit): List<SheetAction> = buildList {
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, "撤销已确认部分", onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
        add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
    }
}

@Composable
internal fun VaultEditTransferSheet(work: VaultEntryEditWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val progress = work.workProgress(undo)
    ItemDetailsSheet(title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Description) },
        actions = actions, onDismiss = onDismiss, metaParts = listOf("归档清单", progress.status), extraLines = {
            progress.detail?.let { Text(it) }
            Text("只修改归档清单中的条目，不删除真实网盘文件。撤销只处理本次已确认的改动。")
            if (work.result?.unconfirmed?.isNotEmpty() == true) Text("重新查询只读取实际清单，不再次写入。移除记录不会取消已经提交的清单编辑。")
        },
    )
}
