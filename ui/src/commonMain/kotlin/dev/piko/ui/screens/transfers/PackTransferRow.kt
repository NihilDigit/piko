package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

internal fun TransferItem.Pack.statusLabel(): String = if (isBusy) tr("正在处理任务", "Processing task", "タスクを処理中", "작업 처리 중", "正在處理任務") else when (job.stage) {
    OfflinePackStage.QUEUED -> tr("排队中", "Queued", "待機中", "대기 중", "排隊中")
    OfflinePackStage.DOWNLOADING -> "$progress%"
    OfflinePackStage.PRUNING -> tr("处理中", "Processing", "処理中", "처리 중", "處理中")
    OfflinePackStage.DONE -> tr("已完成", "Completed", "完了", "완료", "已完成")
    OfflinePackStage.FAILED -> if (job.cleanupFailed) tr("处理失败", "Processing failed", "処理に失敗しました", "처리 실패", "處理失敗") else tr("离线失败", "Cloud download failed", "クラウドダウンロード失敗", "클라우드 다운로드 실패", "離線下載失敗")
}

/**
 * 结束后的一句说明，只在有话要说时出现：完成时是改名失败这类附注，失败时是原因。
 * 删掉未选文件是保存的实现细节，不写。
 */
internal fun OfflinePackJob.detail(): String? = when (stage) {
    OfflinePackStage.QUEUED, OfflinePackStage.DOWNLOADING, OfflinePackStage.PRUNING -> null
    OfflinePackStage.DONE -> message.ifEmpty { null }
    OfflinePackStage.FAILED -> message.ifEmpty { tr("服务端未给出原因", "No reason provided by server", "サーバーから理由が返されませんでした", "서버에서 사유를 제공하지 않았습니다", "伺服器未提供原因") }
}


@Composable
private fun OfflinePackJob.statusColor(): Color = when (stage) {
    OfflinePackStage.DONE -> LocalStatusColors.current.success
    OfflinePackStage.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 「保留 / 整包」。只勾了一部分时整包大小会误导，旧记录没有保留大小就只写整包。 */
internal fun OfflinePackJob.sizeLabel(): String =
    if (keptBytes in 1 until totalBytes) "${keptBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
    else totalBytes.toReadableSize()

internal val OfflinePackJob.canOpen: Boolean
    get() = stage == OfflinePackStage.DONE && outputId.isNotEmpty()


/** 点按一项做的事：完成的打开，失败的重试。 */
internal fun packPrimaryAction(item: TransferItem.Pack, onOpen: () -> Unit, onRetry: () -> Unit): (() -> Unit)? = when {
    item.isBusy -> null
    item.job.canOpen -> onOpen
    item.job.stage == OfflinePackStage.FAILED -> onRetry
    else -> null
}

/** 打包离线任务的全部操作，详情面板与右键菜单共用。 */
internal fun packTransferActions(
    item: TransferItem.Pack,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onCopySource: (() -> Unit)? = null,
): List<SheetAction> {
    val job = item.job
    return buildList {
        if (item.isBusy) return@buildList
        if (job.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onOpen))
        if (onCopySource != null) {
            add(SheetAction(androidx.compose.material.icons.Icons.Outlined.ContentCopy, tr("复制来源链接", "Copy source link", "ソースリンクをコピー", "출처 링크 복사", "複製來源連結"), onCopySource))
        }
        if (job.stage == OfflinePackStage.FAILED) {
            add(SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도", "重試"), onRetry))
        }
        // 取消会连同服务端的占位文件一起删掉；已完成的只删任务记录，文件留在网盘里
        val discardLabel = if (job.isActive) tr("取消任务", "Cancel task", "タスクをキャンセル", "작업 취소", "取消任務") else tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄")
        add(SheetAction(Icons.Outlined.Delete, discardLabel, onDiscard, destructive = true))
    }
}

/** 整包离线的详情面板。 */
@Composable
internal fun PackTransferDetails(item: TransferItem.Pack, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val job = item.job
    val statusColor = job.statusColor()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = job.folderName,
        headerIcon = { TaskFolderVisual(job.folderName) },
        actions = actions,
        metaParts = listOf(tr("云端", "Cloud", "クラウド", "클라우드", "雲端"), item.statusLabel(), job.sizeLabel()),
        extraLines = {
            job.detail()?.let {
                Text(text = it, color = if (job.stage == OfflinePackStage.FAILED) statusColor else Color.Unspecified)
            }
        },
    )
}
