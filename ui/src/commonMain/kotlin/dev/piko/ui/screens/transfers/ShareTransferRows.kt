package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.ShareSaveWork
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun shareTransferActions(work: ShareSaveWork, undo: DriveChangeJournal.UndoResult?, canUndo: Boolean, canRecheck: Boolean,
    onUndo: () -> Unit, onRecheck: () -> Unit, onForget: () -> Unit, onShow: (() -> Unit)?): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onShow))
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, if (work.undoLimited) tr("撤销可用的命名记录", "Undo available renames", "有効な改名記録を元に戻す", "사용 가능한 이름 변경 취소") else tr("撤销本次命名", "Undo these renames", "今回の改名を元に戻す", "이번 이름 변경 취소"), onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인"), onRecheck))
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제"), onForget))
    }
}

@Composable
internal fun ShareTransferDetails(work: ShareSaveWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress(undo)
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) }, actions = actions,
        metaParts = listOf(tr("分享转存", "Save share", "共有を保存", "공유 저장"), progress.status), extraLines = {
            progress.detail?.let { Text(it) }
            work.result?.let { result ->
                val names = work.command.sources.associate { it.id to it.name }
                if (result.confirmedSources.isNotEmpty()) Text(tr("已确认转存：\n${result.confirmedSources.joinToString("\n") { names[it] ?: it }}", "Confirmed saved:\n${result.confirmedSources.joinToString("\n") { names[it] ?: it }}", "保存確認済み：\n${result.confirmedSources.joinToString("\n") { names[it] ?: it }}", "저장 확인됨:\n${result.confirmedSources.joinToString("\n") { names[it] ?: it }}"))
                if (result.unconfirmedSources.isNotEmpty()) Text(tr("结果待确认：\n${result.unconfirmedSources.joinToString("\n") { names[it] ?: it }}", "Unconfirmed:\n${result.unconfirmedSources.joinToString("\n") { names[it] ?: it }}", "結果未確認：\n${result.unconfirmedSources.joinToString("\n") { names[it] ?: it }}", "결과 미확인:\n${result.unconfirmedSources.joinToString("\n") { names[it] ?: it }}"))
                if (result.failedSources.isNotEmpty() || result.notAttemptedSources.isNotEmpty()) Text(tr("未转存：\n${(result.failedSources + result.notAttemptedSources).joinToString("\n") { names[it] ?: it }}", "Not saved:\n${(result.failedSources + result.notAttemptedSources).joinToString("\n") { names[it] ?: it }}", "未保存：\n${(result.failedSources + result.notAttemptedSources).joinToString("\n") { names[it] ?: it }}", "저장 안 됨:\n${(result.failedSources + result.notAttemptedSources).joinToString("\n") { names[it] ?: it }}"))
            }
            if (work.pendingRenames.isNotEmpty()) Text(stringResource(Res.string.text_some_renames_are_unconfirmed_checking_again_reads_actual_names_without_renam))
            if (work.undoLimited) Text(stringResource(Res.string.text_older_rename_undo_data_has_expired_those_changes_cannot_be_fully_undone))
            if (work.command.canonicalNames && !work.baselineAvailable) Text(stringResource(Res.string.text_the_pre_submission_folder_could_not_be_checked_original_names_were_kept))
            else if (work.namingSkipped > 0 && work.associatedOutputs.size < work.command.sources.size) Text(stringResource(Res.string.text_some_new_items_could_not_be_uniquely_matched_their_names_were_not_changed))
            if (work.remoteTaskId == null && work.result?.unconfirmedSources?.isNotEmpty() == true)
                Text(stringResource(Res.string.text_no_remote_task_reference_is_available_inspect_the_saved_folder_and_cloud_tas))
            Text(stringResource(Res.string.text_undo_only_reverts_confirmed_renames_and_keeps_saved_files_removing_the_recor))
        },
    )
}
