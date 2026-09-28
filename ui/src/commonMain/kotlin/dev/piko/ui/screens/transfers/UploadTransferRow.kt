package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

/** 上传中没有状态词：进度条与速度已经说明它在传。 */
private fun UploadTask.statusLabel(): String? = when (status) {
    UploadStatus.QUEUED -> "等待中"
    UploadStatus.HASHING -> "校验中"
    UploadStatus.UPLOADING -> null
    UploadStatus.PAUSED -> "已暂停"
    UploadStatus.COMPLETED -> if (isInstant) "秒传完成" else "已上传"
    UploadStatus.FAILED -> "上传失败"
}

private fun UploadTask.statusDetails(): List<String> {
    val progress = "${processedBytes.toReadableSize()} / ${size.toReadableSize()}"
    return when (status) {
        UploadStatus.QUEUED, UploadStatus.COMPLETED -> listOf(size.toReadableSize())
        UploadStatus.HASHING, UploadStatus.UPLOADING -> buildList {
            add(progress)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            remainingTime(size - processedBytes, speedBytesPerSec)?.let { add("剩余 $it") }
        }
        // 重启后读回的任务不带进度（见调度器的 restore），只显示大小
        UploadStatus.PAUSED -> if (processedBytes > 0) listOf(progress) else listOf(size.toReadableSize())
        UploadStatus.FAILED -> emptyList()
    }
}

private val UploadTask.showsProgress: Boolean
    get() = status == UploadStatus.HASHING || status == UploadStatus.UPLOADING

private val UploadTask.canOpen: Boolean
    get() = status == UploadStatus.COMPLETED && fileId != null

private fun UploadTask.failureReason(): String = errorMessage?.takeIf { it.isNotBlank() } ?: "网络中断"


@Composable
private fun UploadTask.statusColor(): Color = when (status) {
    UploadStatus.COMPLETED -> LocalStatusColors.current.success
    UploadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 上传的列表项，版式与下载项一致，以「上传」标记区分。点按完成的项跳到网盘里的文件。 */
@Composable
internal fun UploadTransferRow(
    task: UploadTask,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val statusColor = task.statusColor()
    FileListItem(
        headline = task.fileName,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        // 图标只说类型，状态由文字与颜色说：原来完成的上传与完成的离线都是同一个打勾的云，分不出哪个是哪个
        leading = { ListLeadingMedia(thumbnail = null, fallback = { ListLeadingIcon(Icons.Outlined.CloudUpload) }, isSpoilerBlurred = false) },
        onClick = { uploadPrimaryAction(task, onOpen, onResume, onPause)?.invoke() },
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = if (task.showsProgress) 1 else 2,
        supporting = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "上传",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    // 完成的不写「已上传」，所在的分组已经说了；秒传补一个来源
                    val label = if (task.status == UploadStatus.COMPLETED) "秒传".takeIf { task.isInstant } else task.statusLabel()
                    label?.let { Text(text = it, color = statusColor, maxLines = 1) }
                    val failure = if (task.status == UploadStatus.FAILED) listOf(task.failureReason().lineSequence().first()) else emptyList()
                    MetaRow(parts = task.statusDetails() + failure, modifier = Modifier.weight(1f, fill = false))
                }
                if (task.showsProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    UploadStatus.HASHING, UploadStatus.UPLOADING, UploadStatus.QUEUED -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停")
                    }
                    UploadStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "继续上传")
                    }
                    UploadStatus.FAILED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                    }
                    UploadStatus.COMPLETED -> Unit
                }
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/** 上传项的详情面板：完整文件名、目标目录、状态与全部操作。 */
@Composable
internal fun UploadTransferSheet(task: UploadTask, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val statusColor = task.statusColor()
    ItemDetailsSheet(
        title = task.fileName,
        headerIcon = { ListLeadingIcon(Icons.Outlined.CloudUpload) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOfNotNull("上传", task.statusLabel()) + task.statusDetails(),
        extraLines = {
            Text(text = "保存到 ${task.parentName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.status == UploadStatus.FAILED) Text(text = task.failureReason(), color = statusColor)
        },
    )
}

/** 点按一项做的事：完成的跳到网盘里的文件，暂停与失败的继续，传输中的暂停。 */
internal fun uploadPrimaryAction(task: UploadTask, onOpen: () -> Unit, onResume: () -> Unit, onPause: () -> Unit): (() -> Unit)? =
    when (task.status) {
        UploadStatus.COMPLETED -> onOpen.takeIf { task.canOpen }
        UploadStatus.PAUSED, UploadStatus.FAILED -> onResume
        UploadStatus.HASHING, UploadStatus.UPLOADING -> onPause
        UploadStatus.QUEUED -> null
    }

/** 上传任务的全部操作，详情面板与右键菜单共用。 */
internal fun uploadTransferActions(
    task: UploadTask,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
): List<SheetAction> = buildList {
        when (task.status) {
            UploadStatus.COMPLETED -> if (task.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onOpen))
            UploadStatus.HASHING, UploadStatus.UPLOADING, UploadStatus.QUEUED -> add(SheetAction(Icons.Outlined.Pause, "暂停", onPause))
            UploadStatus.PAUSED -> add(SheetAction(Icons.Outlined.PlayArrow, "继续上传", onResume))
            UploadStatus.FAILED -> add(SheetAction(Icons.Outlined.Refresh, "重试", onResume))
        }
        // 未完成的一并删掉网盘里那个上传中的文件；已完成的只删记录
        val removeLabel = if (task.status == UploadStatus.COMPLETED) "移除记录" else "取消上传"
        add(SheetAction(Icons.Outlined.Delete, removeLabel, onRemove, destructive = true))
}
