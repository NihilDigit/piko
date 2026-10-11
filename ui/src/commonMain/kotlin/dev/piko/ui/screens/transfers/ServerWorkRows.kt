package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.workProgress

/** 待输密码要人处理，与下载失败一样用醒目的颜色；其余是进行中的常态。 */
@Composable
private fun WorkProgress.statusColor(): Color =
    if (meter == WorkMeter.None) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 行的第二、三行，版式同下载行：类别标签、主状态与次要说明一行，进度条一行。
 * 次要说明（当前项、停止中的说明）放在最后，窄了先截它。
 */
@Composable
private fun WorkSupporting(kindLabel: String, work: WorkProgress) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = kindLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(text = work.status, color = work.statusColor(), maxLines = 1)
            work.detail?.let { MetaRow(parts = listOf(it), modifier = Modifier.weight(1f, fill = false)) }
        }
        if (work.meter != WorkMeter.None) {
            Spacer(Modifier.height(4.dp))
            WorkMeterBar(work.meter, work.title, Modifier.fillMaxWidth())
        }
    }
}

/** 详情面板标题下的状态、说明与进度条，与行读同一份 [WorkProgress]。 */
@Composable
private fun WorkDetails(work: WorkProgress, note: String) {
    work.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (work.meter != WorkMeter.None) {
        WorkMeterBar(work.meter, work.title, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 服务端解压的列表项。密码框由 ArchiveExtractHost 在应用最外层弹出；待输密码的行尾给「放弃解压」，
 * 前一个包的密码还在验证、框还没轮到它时，从这里就能放弃。
 */
@Composable
internal fun ExtractTransferRow(
    item: TransferItem.Extract,
    onShowInDrive: () -> Unit,
    onSkip: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val work = item.job.workProgress()
    val needsPassword = item.job.status is ArchiveJobStatus.NeedsPassword
    FileListItem(
        headline = item.job.file.name,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingIcon(if (needsPassword) Icons.Outlined.Lock else Icons.Outlined.Unarchive) },
        onClick = onShowInDrive,
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = 1,
        supporting = { WorkSupporting("解压", work) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (needsPassword) TooltipIconButton(Icons.Outlined.Close, "放弃解压", onClick = onSkip)
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/** 解压的全部操作。提交后的解压在服务端取消不了，只有待输密码的能放弃。 */
internal fun extractTransferActions(item: TransferItem.Extract, onShowInDrive: () -> Unit, onSkip: () -> Unit, onRecheck: () -> Unit): List<SheetAction> = buildList {
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
internal fun ExtractTransferSheet(item: TransferItem.Extract, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val work = item.job.workProgress()
    ItemDetailsSheet(
        title = item.job.file.name,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Unarchive) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf("解压", work.status),
        // 提交前后都可能停在这里，说清解到哪里、为什么不能取消
        extraLines = { WorkDetails(work, if ((item.job.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED) {
            "服务端仍可能继续解压。重新查询不会再次提交；没有任务标识时，请先检查压缩包所在目录。"
        } else "解压到压缩包所在的文件夹。已提交的解压由服务端完成，无法中途取消。") },
    )
}

private fun TransferItem.Vault.kindLabel(): String = if (restoring) "取消归档" else "归档"

private fun TransferItem.Vault.icon() = if (restoring) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2

/** 归档和恢复从指定工作的进度转为结果记录；详情不会随下一项工作改变对象。 */
@Composable
internal fun VaultTransferRow(
    item: TransferItem.Vault,
    session: FolderVaultSession,
    onMoreClick: () -> Unit,
    undo: DriveChangeJournal.UndoResult? = null,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val work = item.record?.workProgress(undo) ?: session.takeIf { it.activeWork?.id == item.work.id }?.workProgress() ?: return
    FileListItem(
        headline = item.folderName,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        leading = { ListLeadingIcon(item.icon()) },
        onClick = onMoreClick,
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = 1,
        supporting = { WorkSupporting(item.kindLabel(), work) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.record == null) TooltipIconButton(Icons.Outlined.Stop, "停止", onClick = { session.stop(item.work.id) }, enabled = !session.stopping)
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

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
internal fun VaultTransferSheet(
    item: TransferItem.Vault,
    session: FolderVaultSession,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    undo: DriveChangeJournal.UndoResult? = null,
) {
    val work = item.record?.workProgress(undo) ?: session.takeIf { it.activeWork?.id == item.work.id }?.workProgress()
    ItemDetailsSheet(
        title = item.folderName,
        headerIcon = { ListLeadingIcon(item.icon()) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOfNotNull(item.kindLabel(), work?.status),
        extraLines = {
            // 停止不回滚：进行中的文件夹处理完才停，已写成的引用保留
            val note = if (item.record != null) "移除记录只清除此处的显示，不改变已处理的文件。" else "停止后，已处理完的文件夹保持现状。"
            if (work != null) WorkDetails(work, note) else Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
