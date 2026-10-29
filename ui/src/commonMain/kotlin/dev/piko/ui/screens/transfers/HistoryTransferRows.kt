package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.state.HistoryEditWork
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun historyTransferActions(work: HistoryEditWork, canRecheck: Boolean, onRecheck: () -> Unit, onForget: () -> Unit): List<SheetAction> = buildList {
    if (!work.isRunning) {
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인"), onRecheck))
        add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제"), onForget))
    }
}

@Composable
internal fun HistoryTransferDetails(work: HistoryEditWork, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.History) },
        actions = actions, metaParts = listOf(tr("历史记录", "History", "履歴", "기록"), progress.status),
        extraLines = {
            progress.detail?.let { Text(it) }
            Text(stringResource(Res.string.text_only_history_records_are_changed_drive_files_are_kept_deleting_playback_reco))
            if (work.result?.completion in setOf(dev.piko.shared.state.HistoryEditCompletion.PARTIAL, dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED)) Text(stringResource(Res.string.text_checking_again_verifies_results_without_deleting_or_clearing_again))
        },
    )
}
