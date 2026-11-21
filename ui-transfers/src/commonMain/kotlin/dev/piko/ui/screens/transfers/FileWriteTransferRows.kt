package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onShow))
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, tr("撤销已确认部分", "Undo confirmed changes", "確認済みの変更を元に戻す", "확인된 변경 실행 취소", "復原已確認部分"), onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인", "重新查詢結果"), onRecheck))
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄"), onForget))
    }
}

@Composable
internal fun FileWriteTransferDetails(work: FileWriteWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress(undo)
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Description) }, actions = actions, metaParts = listOf(tr("文件处理", "File operations", "ファイル操作", "파일 작업", "檔案處理"), progress.status),
        extraLines = {
            progress.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (progress.meter != WorkMeter.None) WorkMeterBar(progress.meter, progress.title, Modifier.fillMaxWidth())
            if (work.result?.unconfirmed?.isNotEmpty() == true) Text(stringResource(Res.string.text_checking_again_verifies_the_original_operation_without_resubmitting_it_if_no))
            Text(stringResource(Res.string.text_removing_this_record_does_not_change_files_or_cancel_operations_accepted_by_), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
