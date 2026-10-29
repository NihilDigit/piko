package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

/** 上传中没有状态词：进度条与速度已经说明它在传。 */
internal fun UploadTask.statusLabel(): String? = when (status) {
    UploadStatus.QUEUED -> tr("等待中", "Waiting", "待機中", "대기 중")
    UploadStatus.HASHING -> tr("校验中", "Verifying", "検証中", "확인 중")
    UploadStatus.UPLOADING -> null
    UploadStatus.PAUSED -> tr("已暂停", "Paused", "一時停止中", "일시 중지됨")
    UploadStatus.COMPLETED -> if (isInstant) tr("秒传完成", "Instant upload complete", "インスタントアップロード完了", "즉시 업로드 완료") else tr("已上传", "Uploaded", "アップロード済み", "업로드됨")
    UploadStatus.FAILED -> tr("上传失败", "Upload failed", "アップロード失敗", "업로드 실패")
}

internal fun UploadTask.statusDetails(): List<String> {
    val progress = "${processedBytes.toReadableSize()} / ${size.toReadableSize()}"
    return when (status) {
        UploadStatus.QUEUED, UploadStatus.COMPLETED -> listOf(size.toReadableSize())
        UploadStatus.HASHING, UploadStatus.UPLOADING -> buildList {
            add(progress)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            remainingTime(size - processedBytes, speedBytesPerSec)?.let { add(tr("剩余 $it", "$it remaining", "残り $it", "$it 남음")) }
        }
        // 重启后读回的任务不带进度（见调度器的 restore），只显示大小
        UploadStatus.PAUSED -> if (processedBytes > 0) listOf(progress) else listOf(size.toReadableSize())
        UploadStatus.FAILED -> emptyList()
    }
}

internal val UploadTask.canOpen: Boolean
    get() = status == UploadStatus.COMPLETED && !fileId.isNullOrEmpty()

internal val UploadTask.canPlayVideo: Boolean
    get() = canOpen && fileName.isPlayableVideo()

internal fun UploadTask.failureReason(): String = errorMessage?.takeIf { it.isNotBlank() } ?: tr("网络中断", "Network interrupted", "ネットワークが切断されました", "네트워크 연결이 끊어졌습니다")


@Composable
private fun UploadTask.statusColor(): Color = when (status) {
    UploadStatus.COMPLETED -> LocalStatusColors.current.success
    UploadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}


/** 上传项的详情面板：完整文件名、目标目录、状态与全部操作。 */
@Composable
internal fun UploadTransferDetails(task: UploadTask, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val statusColor = task.statusColor()
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = task.fileName,
        headerIcon = { TaskFileTypeIcon(task.fileName, Icons.Outlined.CloudUpload) },
        actions = actions,
        metaParts = listOfNotNull(tr("上传", "Uploads", "アップロード", "업로드"), task.statusLabel()) + task.statusDetails(),
        extraLines = {
            Text(text = tr("保存到 ${task.parentName}", "Saved to ${task.parentName}", "保存先 ${task.parentName}", "저장 위치 ${task.parentName}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.status == UploadStatus.FAILED) Text(text = task.failureReason(), color = statusColor)
        },
    )
}

/** 点按一项做的事：完成视频直接播放，其余完成的跳到网盘里的文件，暂停与失败的继续，传输中的暂停。 */
internal fun uploadPrimaryAction(
    task: UploadTask,
    onPlay: (() -> Unit)?,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
): (() -> Unit)? =
    when (task.status) {
        UploadStatus.COMPLETED -> if (task.canPlayVideo && onPlay != null) onPlay else onOpen.takeIf { task.canOpen }
        UploadStatus.PAUSED, UploadStatus.FAILED -> onResume
        UploadStatus.HASHING, UploadStatus.UPLOADING -> onPause
        UploadStatus.QUEUED -> null
    }

/** 上传任务的全部操作，详情面板与右键菜单共用。 */
internal fun uploadTransferActions(
    task: UploadTask,
    onPlay: (() -> Unit)?,
    onOpen: () -> Unit,
    onOpenParent: (() -> Unit)?,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
    onRevealLocal: (() -> Unit)? = null,
): List<SheetAction> = buildList {
    when (task.status) {
        UploadStatus.COMPLETED -> {
            if (task.canPlayVideo && onPlay != null) add(SheetAction(Icons.Outlined.PlayArrow, tr("播放", "Play", "再生", "재생"), onPlay))
            if (task.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpen))
            else if (onOpenParent != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpenParent))
        }
        UploadStatus.HASHING, UploadStatus.UPLOADING, UploadStatus.QUEUED -> {
            add(SheetAction(Icons.Outlined.Pause, tr("暂停", "Pause", "一時停止", "일시 중지"), onPause))
            if (onOpenParent != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpenParent))
        }
        UploadStatus.PAUSED -> {
            add(SheetAction(Icons.Outlined.PlayArrow, tr("继续上传", "Resume upload", "アップロードを再開", "업로드 계속"), onResume))
            if (onOpenParent != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpenParent))
        }
        UploadStatus.FAILED -> {
            add(SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도"), onResume))
            if (onOpenParent != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpenParent))
        }
    }
    if (onRevealLocal != null) {
        add(SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기"), onRevealLocal))
    }
    // 未完成的一并删掉网盘里那个上传中的文件；已完成的只删记录
    val removeLabel = if (task.status == UploadStatus.COMPLETED) tr("移除记录", "Remove record", "記録を削除", "기록 삭제") else tr("取消上传", "Cancel upload", "アップロードをキャンセル", "업로드 취소")
    add(SheetAction(Icons.Outlined.Delete, removeLabel, onRemove, destructive = true))
}
