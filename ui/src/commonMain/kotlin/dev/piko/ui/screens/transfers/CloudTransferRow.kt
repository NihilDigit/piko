package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.state.isOutputDeleted
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.TaskPhase

private val DriveTask.isActive: Boolean
    get() = phase == TaskPhase.PENDING || phase == TaskPhase.RUNNING

private val DriveTask.isFailed: Boolean
    get() = phase == TaskPhase.ERROR && !isOutputDeleted

// 完成的任务才有可打开的文件；fileId 缺失时点了也无处可去
internal val DriveTask.canOpen: Boolean
    get() = phase == TaskPhase.COMPLETE && fileId.isNotEmpty()

internal val DriveTask.canPlayVideo: Boolean
    get() = canOpen && (fileName.isPlayableVideo() || name.isPlayableVideo())

internal val DriveTask.displayName: String
    get() = name.ifEmpty { fileName.ifEmpty { tr("离线任务", "Cloud download task", "クラウドダウンロードタスク", "클라우드 다운로드 작업", "離線任務") } }

internal fun DriveTask.statusLabel(): String = when {
    isOutputDeleted -> tr("文件已删除", "File deleted", "ファイルは削除されています", "파일이 삭제되었습니다", "檔案已刪除")
    phase == TaskPhase.PENDING -> tr("排队中", "Queued", "待機中", "대기 중", "排隊中")
    phase == TaskPhase.RUNNING -> "$progress%"
    phase == TaskPhase.COMPLETE -> tr("已完成", "Completed", "完了", "완료", "已完成")
    else -> tr("离线失败", "Cloud download failed", "クラウドダウンロード失敗", "클라우드 다운로드 실패", "離線下載失敗")
}

internal fun DriveTask.sizeParts(): List<String> =
    listOfNotNull(fileSize.toLongOrNull()?.takeIf { it > 0 }?.toReadableSize())

/** 失败原因。服务端的 message 可能为空。 */
internal fun DriveTask.failureReason(): String = message.ifEmpty { tr("服务端未给出原因", "No reason provided by server", "サーバーから理由が返されませんでした", "서버에서 사유를 제공하지 않았습니다", "伺服器未提供原因") }

// 文件已删除的任务不是失败，不用错误色；弱化由 FileListItem 的 dimmed 统一处理
@Composable
private fun DriveTask.statusColor(): Color = when {
    isOutputDeleted -> MaterialTheme.colorScheme.onSurfaceVariant
    phase == TaskPhase.COMPLETE -> LocalStatusColors.current.success
    isFailed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}



/** 云端任务的详情面板：完整名称、状态、失败原因与全部操作。 */
@Composable
internal fun CloudTransferDetails(task: DriveTask, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val statusColor = task.statusColor()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = task.displayName,
        headerIcon = { TaskFileTypeIcon(task.fileName.ifEmpty { task.displayName }, Icons.Outlined.CloudDownload) },
        actions = actions,
        metaParts = listOf(tr("云端", "Cloud", "クラウド", "클라우드", "雲端"), task.statusLabel()) + task.sizeParts(),
        extraLines = {
            if (task.isFailed) Text(text = task.failureReason(), color = statusColor)
        },
    )
}

/** 点按一项做的事：完成视频直接播放，其余完成的在网盘中显示，失败的重新提交。 */
internal fun cloudPrimaryAction(
    task: DriveTask,
    onPlay: (() -> Unit)?,
    onResubmit: (() -> Unit)?,
    onOpen: () -> Unit,
): (() -> Unit)? = when {
    task.canPlayVideo && onPlay != null -> onPlay
    task.canOpen -> onOpen
    task.isFailed -> onResubmit
    else -> null
}

/** 云端任务的全部操作，详情面板与右键菜单共用。 */
internal fun cloudTransferActions(
    task: DriveTask,
    onPlay: (() -> Unit)?,
    onResubmit: (() -> Unit)?,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onCopySource: (() -> Unit)? = null,
): List<SheetAction> = buildList {
    if (task.canPlayVideo && onPlay != null) add(SheetAction(Icons.Outlined.PlayArrow, tr("播放", "Play", "再生", "재생", "播放"), onPlay))
    if (task.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onOpen))
    if (onCopySource != null) add(SheetAction(Icons.Outlined.ContentCopy, tr("复制来源链接", "Copy source link", "ソースリンクをコピー", "출처 링크 복사", "複製來源連結"), onCopySource))
    if (onResubmit != null && (task.isFailed || task.isOutputDeleted)) {
        add(SheetAction(Icons.Outlined.Refresh, if (task.isOutputDeleted) tr("重新下载", "Download again", "再ダウンロード", "다시 다운로드", "重新下載") else tr("重试", "Retry", "再試行", "다시 시도", "重試"), onResubmit))
    }
    val deleteLabel = when {
        task.isActive -> tr("删除任务", "Delete task", "タスクを削除", "작업 삭제", "刪除任務")
        task.phase == TaskPhase.COMPLETE -> tr("移除记录", "Remove record", "記録を削除", "기록 삭제", "移除記錄")
        else -> tr("删除记录", "Delete record", "記録を削除", "기록 삭제", "刪除記錄")
    }
    add(SheetAction(Icons.Outlined.Delete, deleteLabel, onDelete, destructive = true))
}
