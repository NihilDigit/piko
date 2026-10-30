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

internal fun instantAttemptActions(work: InstantSaveAttempt, onShow: (() -> Unit)?, onForget: () -> Unit, onRecheck: (() -> Unit)? = null): List<SheetAction> = buildList {
    if (onRecheck != null) add(SheetAction(Icons.Outlined.Refresh, tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인", "重新查詢結果"), onRecheck))
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
                if (receipt.confirmed.isNotEmpty()) Text(tr("已确认保存：\n${receipt.confirmed.keys.joinToString("\n")}", "Confirmed saved:\n${receipt.confirmed.keys.joinToString("\n")}", "保存確認済み：\n${receipt.confirmed.keys.joinToString("\n")}", "저장 확인됨:\n${receipt.confirmed.keys.joinToString("\n")}", "已確認儲存：\n${receipt.confirmed.keys.joinToString("\n")}"))
                if (receipt.failed.isNotEmpty()) Text(tr("确定未保存：\n${receipt.failed.joinToString("\n")}", "Confirmed unsaved:\n${receipt.failed.joinToString("\n")}", "未保存確認済み：\n${receipt.failed.joinToString("\n")}", "미저장 확인됨:\n${receipt.failed.joinToString("\n")}", "已確認未儲存：\n${receipt.failed.joinToString("\n")}"))
                if (receipt.notAttempted.isNotEmpty()) Text(tr("未执行：\n${receipt.notAttempted.joinToString("\n")}", "Not attempted:\n${receipt.notAttempted.joinToString("\n")}", "未実行：\n${receipt.notAttempted.joinToString("\n")}", "미실행:\n${receipt.notAttempted.joinToString("\n")}", "未執行：\n${receipt.notAttempted.joinToString("\n")}"))
                if (receipt.unconfirmed.isNotEmpty()) Text(tr("结果待确认：\n${receipt.unconfirmed.joinToString("\n")}", "Unconfirmed:\n${receipt.unconfirmed.joinToString("\n")}", "結果未確認：\n${receipt.unconfirmed.joinToString("\n")}", "결과 미확인:\n${receipt.unconfirmed.joinToString("\n")}", "結果待確認：\n${receipt.unconfirmed.joinToString("\n")}"))
                receipt.unconfirmedFolder?.let { Text(tr("目录创建结果待确认：$it", "Folder creation unconfirmed: $it", "フォルダーの作成結果は未確認：$it", "폴더 생성 결과 미확인: $it", "資料夾建立結果待確認：$it")) }
                if (receipt.namingUnconfirmed.isNotEmpty()) Text(tr("已保存但名称待确认：\n${receipt.namingUnconfirmed.joinToString("\n")}", "Saved; names unconfirmed:\n${receipt.namingUnconfirmed.joinToString("\n")}", "保存済み、名前未確認：\n${receipt.namingUnconfirmed.joinToString("\n")}", "저장됨, 이름 미확인:\n${receipt.namingUnconfirmed.joinToString("\n")}", "已儲存但名稱待確認：\n${receipt.namingUnconfirmed.joinToString("\n")}"))
                if (receipt.requiresInspection) Text(stringResource(Res.string.text_checking_again_verifies_saved_files_items_without_a_unique_match_remain_unco))
            }
            Text(stringResource(Res.string.text_removing_the_record_does_not_delete_files_or_cancel_submitted_requests))
        },
    )
}
