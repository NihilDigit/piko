package dev.piko.ui.screens.transfers

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.OfflineSubmitWork
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun offlineSubmitActions(work: OfflineSubmitWork, onShow: (() -> Unit)?, onForget: () -> Unit): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄"), onForget))
}

@Composable
internal fun OfflineSubmitDetails(work: OfflineSubmitWork, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        metaParts = listOf(tr("离线下载请求", "Cloud download request", "クラウドダウンロードリクエスト", "클라우드 다운로드 요청", "離線下載請求"), progress.status), actions = actions, extraLines = {
            Text(tr("保存目标：${work.target.localizedName}", "Destination: ${work.target.localizedName}", "保存先：${work.target.localizedName}", "저장 대상: ${work.target.localizedName}", "儲存目標：${work.target.localizedName}"))
            Text(work.message)
            if (work.status == OfflineSubmitStatus.UNCONFIRMED) Text(stringResource(Res.string.text_no_remote_task_reference_was_received_inspect_cloud_tasks_and_the_destinatio))
            Text(stringResource(Res.string.text_removing_the_record_does_not_cancel_requests_delete_outputs_or_establish_tha))
        })
}
