package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.InstantSaveAttempt
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun instantAttemptActions(work: InstantSaveAttempt, onShow: (() -> Unit)?, onForget: () -> Unit, onRetry: (() -> Unit)? = null): List<SheetAction> = buildList {
    if (onRetry != null) add(SheetAction(Icons.Outlined.Refresh, tr("重试未完成项", "Retry unfinished items", "未完了の項目を再試行", "미완료 항목 다시 시도", "重試未完成項"), onRetry))
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄"), onForget))
}

@Composable
internal fun InstantAttemptDetails(work: InstantSaveAttempt, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        metaParts = listOf(if (work.reference) tr("引用保存", "Save references", "参照として保存", "참조로 저장", "引用儲存") else tr("网盘保存", "Save to drive", "ドライブに保存", "드라이브 저장", "儲存至雲端硬碟"), progress.status), actions = actions, extraLines = {
            Text(tr("保存目标：${work.target.localizedName}", "Destination: ${work.target.localizedName}", "保存先：${work.target.localizedName}", "저장 대상: ${work.target.localizedName}", "儲存目標：${work.target.localizedName}"))
            progress.detail?.let { Text(it) }
            work.receipt?.let { receipt ->
                if (receipt.confirmed.isNotEmpty()) Text(tr("已保存：\n${receipt.confirmed.keys.joinToString("\n")}", "Saved:\n${receipt.confirmed.keys.joinToString("\n")}", "保存済み：\n${receipt.confirmed.keys.joinToString("\n")}", "저장됨:\n${receipt.confirmed.keys.joinToString("\n")}", "已儲存：\n${receipt.confirmed.keys.joinToString("\n")}"))
                if (receipt.failed.isNotEmpty()) Text(tr("保存失败：\n${receipt.failed.joinToString("\n")}", "Failed:\n${receipt.failed.joinToString("\n")}", "失敗：\n${receipt.failed.joinToString("\n")}", "실패:\n${receipt.failed.joinToString("\n")}", "儲存失敗：\n${receipt.failed.joinToString("\n")}"))
                if (receipt.notAttempted.isNotEmpty()) Text(tr("未执行：\n${receipt.notAttempted.joinToString("\n")}", "Not attempted:\n${receipt.notAttempted.joinToString("\n")}", "未実行：\n${receipt.notAttempted.joinToString("\n")}", "미실행:\n${receipt.notAttempted.joinToString("\n")}", "未執行：\n${receipt.notAttempted.joinToString("\n")}"))
            }
            Text(stringResource(Res.string.text_removing_the_record_does_not_delete_files_or_cancel_submitted_requests))
        },
    )
}
