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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
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
import dev.piko.shared.state.isOutputDeleted
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase

private val OfflineTask.isActive: Boolean
    get() = phase == TaskPhase.PENDING || phase == TaskPhase.RUNNING

private val OfflineTask.isFailed: Boolean
    get() = phase == TaskPhase.ERROR && !isOutputDeleted

// 完成的任务才有可打开的文件；fileId 缺失时点了也无处可去
private val OfflineTask.canOpen: Boolean
    get() = phase == TaskPhase.COMPLETE && fileId.isNotEmpty()

private val OfflineTask.displayName: String
    get() = name.ifEmpty { fileName.ifEmpty { "离线任务" } }

private fun OfflineTask.statusLabel(): String = when {
    isOutputDeleted -> "文件已删除"
    phase == TaskPhase.PENDING -> "排队中"
    phase == TaskPhase.RUNNING -> "$progress%"
    phase == TaskPhase.COMPLETE -> "已完成"
    else -> "离线失败"
}

private fun OfflineTask.sizeParts(): List<String> =
    listOfNotNull(fileSize.toLongOrNull()?.takeIf { it > 0 }?.toReadableSize())

/** 失败原因。服务端的 message 可能为空。 */
private fun OfflineTask.failureReason(): String = message.ifEmpty { "服务端未给出原因" }

// 文件已删除的任务不是失败，不用错误色；弱化由 FileListItem 的 dimmed 统一处理
@Composable
private fun OfflineTask.statusColor(): Color = when {
    isOutputDeleted -> MaterialTheme.colorScheme.onSurfaceVariant
    phase == TaskPhase.COMPLETE -> LocalStatusColors.current.success
    isFailed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun OfflineTask.phaseIcon(): ImageVector = when {
    isOutputDeleted -> Icons.Outlined.CloudOff
    phase == TaskPhase.COMPLETE -> Icons.Outlined.CloudDone
    phase == TaskPhase.ERROR -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.CloudDownload
}

/**
 * 云端离线任务的列表项，版式与本地下载项一致，以「云端」标记区分。
 *
 * [onResubmit] 为 null 表示任务缺少来源链接，无法重新提交，不提供该操作。
 * 失败的任务在尾部保留重试按钮；文件已删除的任务整行弱化，重新下载只在详情面板里。
 */
@Composable
internal fun CloudTransferRow(
    task: OfflineTask,
    /** 产出的缩略图，取不到时为 null，显示状态图标。 */
    thumbnail: String?,
    isSpoilerBlurred: Boolean,
    onResubmit: (() -> Unit)?,
    onOpen: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = task.statusColor()
    FileListItem(
        headline = task.displayName,
        // ListLeadingIcon 填满父级，要由 ListLeadingMedia 定出 56dp；直接放进 leading 会撑满整行
        leading = {
            ListLeadingMedia(
                thumbnail = thumbnail,
                fallback = { ListLeadingIcon(task.phaseIcon()) },
                isSpoilerBlurred = isSpoilerBlurred,
            )
        },
        onClick = {
            when {
                task.canOpen -> onOpen()
                task.isFailed -> onResubmit?.invoke()
                else -> Unit
            }
        },
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = if (task.isActive) 1 else 2,
        dimmed = task.isOutputDeleted,
        supporting = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "云端",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(text = task.statusLabel(), color = statusColor, maxLines = 1)
                    // 失败原因只取首行，与状态同在一行，由 MetaRow 截断；完整信息在详情面板里
                    val parts = if (task.isFailed) listOf(task.failureReason().lineSequence().first()) else task.sizeParts()
                    MetaRow(parts = parts, modifier = Modifier.weight(1f, fill = false))
                }
                if (task.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.isFailed && onResubmit != null) {
                    IconButton(onClick = onResubmit) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                    }
                }
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/** 云端任务的详情面板：完整名称、状态、失败原因与全部操作。 */
@Composable
internal fun CloudTransferSheet(
    task: OfflineTask,
    onResubmit: (() -> Unit)?,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        if (task.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "打开", onOpen))
        if (onResubmit != null && (task.isFailed || task.isOutputDeleted)) {
            add(SheetAction(Icons.Outlined.Refresh, if (task.isOutputDeleted) "重新下载" else "重试", onResubmit))
        }
        val deleteLabel = when {
            task.isActive -> "删除任务"
            task.phase == TaskPhase.COMPLETE -> "移除"
            else -> "删除记录"
        }
        add(SheetAction(Icons.Outlined.Delete, deleteLabel, onDelete, destructive = true))
    }
    val statusColor = task.statusColor()

    ItemDetailsSheet(
        title = task.displayName,
        headerIcon = { ListLeadingIcon(task.phaseIcon()) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf("云端", task.statusLabel()) + task.sizeParts(),
        extraLines = {
            if (task.isFailed) Text(text = task.failureReason(), color = statusColor)
        },
    )
}
