package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.VaultEntryEditWork
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun vaultEditActions(work: VaultEntryEditWork, undo: DriveChangeJournal.UndoResult?, canUndo: Boolean, canRecheck: Boolean,
    onUndo: () -> Unit, onRecheck: () -> Unit, onForget: () -> Unit): List<SheetAction> = buildList {
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, tr("撤销已确认部分", "Undo confirmed changes", "確認済みの変更を元に戻す", "확인된 변경 실행 취소", "復原已確認部分"), onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인", "重新查詢結果"), onRecheck))
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄"), onForget))
    }
}

@Composable
internal fun VaultEditTransferDetails(work: VaultEntryEditWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress(undo)
    ItemDetailsContent(modifier = modifier, showIcon = false, title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Description) },
        actions = actions, metaParts = listOf(tr("归档清单", "Archive manifest", "アーカイブ一覧", "보관 목록", "歸檔清單"), progress.status), extraLines = {
            progress.detail?.let { Text(it) }
            Text(stringResource(Res.string.text_only_manifest_entries_are_changed_real_drive_files_are_kept_undo_applies_onl))
            if (work.result?.unconfirmed?.isNotEmpty() == true) Text(stringResource(Res.string.text_checking_again_reads_the_actual_manifest_without_rewriting_it_removing_recor))
        },
    )
}
