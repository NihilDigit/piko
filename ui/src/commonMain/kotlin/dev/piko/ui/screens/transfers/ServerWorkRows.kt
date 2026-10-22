package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.TransferItem
import dev.piko.ui.WorkMeter
import dev.piko.ui.WorkProgress
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.workProgress


/** 详情面板标题下的状态、说明与进度条，与行读同一份 [WorkProgress]。 */
@Composable
private fun WorkDetails(work: WorkProgress, note: String) {
    work.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (work.meter != WorkMeter.None) {
        WorkMeterBar(work.meter, work.title, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
}


/** 解压的全部操作。提交后的解压在服务端取消不了，只有待输密码的能放弃。 */
internal fun extractTransferActions(
    item: TransferItem.Extract,
    onShowInDrive: () -> Unit,
    onSkip: () -> Unit,
    onRecheck: () -> Unit,
    onInputPassword: (() -> Unit)? = null,
): List<SheetAction> = buildList {
    if (item.job.status is ArchiveJobStatus.NeedsPassword && onInputPassword != null) {
        add(SheetAction(Icons.Outlined.Key, "输入解压密码", onInputPassword))
    }
    add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onShowInDrive))
    if (item.job.status is ArchiveJobStatus.NeedsPassword) add(SheetAction(Icons.Outlined.Close, "放弃解压", onSkip, destructive = true))
    val finished = item.job.status as? ArchiveJobStatus.Finished
    if (finished != null) {
        if (finished.outcome == ArchiveCompletion.UNCONFIRMED && item.job.remoteTaskId != null) {
            add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
        }
        add(SheetAction(Icons.Outlined.Close, "移除记录", onSkip))
    }
}

@Composable
internal fun ExtractTransferDetails(item: TransferItem.Extract, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val work = item.job.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = item.job.file.name,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Unarchive) },
        actions = actions,
        metaParts = listOf("解压", work.status),
        // 提交前后都可能停在这里，说清解到哪里、为什么不能取消
        extraLines = { WorkDetails(work, if ((item.job.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED) {
            "服务端仍可能继续解压。重新查询不会再次提交；没有任务标识时，请先检查压缩包所在目录。"
        } else "解压到压缩包所在的文件夹。已提交的解压由服务端完成，无法中途取消。") },
    )
}

private fun TransferItem.Vault.kindLabel(): String = if (restoring) "取消归档" else "归档"

private fun TransferItem.Vault.icon() = if (restoring) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2


internal fun vaultTransferActions(
    item: TransferItem.Vault,
    stopping: Boolean,
    onStop: () -> Unit,
    onRemoveRecord: () -> Unit,
    canUndo: Boolean,
    undo: DriveChangeJournal.UndoResult?,
    onUndo: () -> Unit,
): List<SheetAction> = buildList {
    if (item.record == null) {
        if (!stopping) add(SheetAction(Icons.Outlined.Stop, if (item.restoring) "停止恢复" else "停止归档", onStop))
    } else if (undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, if (undo?.status == DriveChangeJournal.UndoStatus.FAILED) "重试撤销" else "撤销本次处理", onUndo))
        add(SheetAction(Icons.Outlined.Close, "移除记录", onRemoveRecord))
    }
}

@Composable
internal fun VaultTransferDetails(
    item: TransferItem.Vault,
    session: FolderVaultSession,
    actions: List<SheetAction>,
    undo: DriveChangeJournal.UndoResult? = null, modifier: Modifier = Modifier) {
    val work = item.record?.workProgress(undo) ?: session.takeIf { it.activeWork?.id == item.work.id }?.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = item.folderName,
        headerIcon = { ListLeadingIcon(item.icon()) },
        actions = actions,
        metaParts = listOfNotNull(item.kindLabel(), work?.status),
        extraLines = {
            // 停止不回滚：进行中的文件夹处理完才停，已写成的引用保留
            val note = if (item.record != null) "移除记录只清除此处的显示，不改变已处理的文件。" else "停止后，已处理完的文件夹保持现状。"
            if (work != null) WorkDetails(work, note) else Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
