package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr
import dev.piko.ui.components.SheetActionRole

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
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
        add(SheetAction(Icons.Outlined.Key, tr("输入解压密码", "Enter archive password", "解凍パスワードを入力", "압축 해제 비밀번호 입력"), onInputPassword))
    }
    add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onShowInDrive))
    if (item.job.status is ArchiveJobStatus.NeedsPassword) add(SheetAction(Icons.Outlined.Close, tr("放弃解压", "Abandon extraction", "解凍を中止", "압축 해제 취소"), onSkip, destructive = true))
    val finished = item.job.status as? ArchiveJobStatus.Finished
    if (finished != null) {
        if (finished.outcome == ArchiveCompletion.UNCONFIRMED && item.job.remoteTaskId != null) {
            add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인"), onRecheck))
        }
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제"), onSkip))
    }
}

@Composable
internal fun ExtractTransferDetails(item: TransferItem.Extract, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val work = item.job.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = item.job.file.name,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Unarchive) },
        actions = actions,
        metaParts = listOf(tr("解压", "Extract", "解凍", "압축 해제"), work.status),
        // 提交前后都可能停在这里，说清解到哪里、为什么不能取消
        extraLines = { WorkDetails(work, if ((item.job.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED) {
            tr("服务端仍可能继续解压。重新查询不会再次提交；没有任务标识时，请先检查压缩包所在目录。", "The server may still be extracting. Checking again will not resubmit. If no task ID exists, inspect the archive’s folder first.", "サーバーで解凍が続いている可能性があります。再確認で再送信はしません。タスクIDがない場合は、先にアーカイブの保存先を確認してください。", "서버에서 압축 해제가 계속될 수 있습니다. 다시 확인해도 재제출하지 않습니다. 작업 식별자가 없으면 압축 파일의 폴더를 먼저 확인하세요.")
        } else tr("解压到压缩包所在的文件夹。已提交的解压由服务端完成，无法中途取消。", "Extract into the archive’s folder. Submitted extractions run on the server and cannot be cancelled midway.", "アーカイブと同じフォルダーに解凍します。送信後の解凍はサーバーで行われ、途中で取り消せません。", "압축 파일이 있는 폴더에 해제합니다. 제출된 압축 해제는 서버에서 수행되며 중간에 취소할 수 없습니다.")) },
    )
}

private fun TransferItem.Vault.kindLabel(): String = if (restoring) tr("取消归档", "Undo archive", "アーカイブを元に戻す", "보관 취소") else tr("归档", "Archive", "アーカイブ", "보관")

private fun TransferItem.Vault.icon() = if (restoring) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2


internal fun vaultTransferActions(
    item: TransferItem.Vault,
    stopping: Boolean,
    onStop: () -> Unit,
    onRemoveRecord: () -> Unit,
    canUndo: Boolean,
    undo: DriveChangeJournal.UndoResult?,
    onUndo: () -> Unit,
    onCreateImport: (String) -> Unit,
): List<SheetAction> = buildList {
    val sources = item.record?.missingSources.orEmpty()
    if (sources.isNotEmpty()) add(SheetAction(Icons.Outlined.Link, tr("解析来源链接", "Resolve source link", "ソースリンクを解析", "출처 링크 분석"), { onCreateImport(sources.joinToString("\n")) }))
    if (item.record == null) {
        if (!stopping) add(SheetAction(Icons.Outlined.Stop, if (item.restoring) tr("停止恢复", "Stop restore", "復元を停止", "복원 중지") else tr("停止归档", "Stop archiving", "アーカイブを停止", "보관 중지"), onStop, role = SheetActionRole.Stop))
    } else if (undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, if (undo?.status == DriveChangeJournal.UndoStatus.FAILED) tr("重试撤销", "Retry undo", "元に戻す操作を再試行", "실행 취소 다시 시도") else tr("撤销本次处理", "Undo this operation", "今回の処理を元に戻す", "이번 작업 실행 취소"), onUndo))
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제"), onRemoveRecord))
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
            val note = if (item.record != null) tr("移除记录只清除此处的显示，不改变已处理的文件。", "Removing the record only hides it here; processed files are unchanged.", "記録を削除しても、この画面の表示だけが消え、処理済みファイルは変わりません。", "기록을 삭제하면 이 화면의 표시만 지우며 처리된 파일은 변경하지 않습니다.") else tr("停止后，已处理完的文件夹保持现状。", "Completed folders remain unchanged after stopping.", "停止しても処理済みのフォルダーはそのまま保持します。", "중지 후에도 처리가 끝난 폴더는 현재 상태로 유지됩니다.")
            if (work != null) WorkDetails(work, note) else Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
