package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

private fun TransferItem.Pack.statusLabel(): String = when (job.stage) {
    OfflinePackStage.QUEUED -> "排队中"
    OfflinePackStage.DOWNLOADING -> "$progress%"
    OfflinePackStage.PRUNING -> "清理中"
    OfflinePackStage.DONE -> "已完成"
    OfflinePackStage.FAILED -> if (job.cleanupFailed) "清理失败" else "离线失败"
}

/**
 * 结束后的一句说明，只在有话要说时出现：完成时是改名失败这类附注，失败时是原因。
 * 删掉未选文件是保存的实现细节，不写。
 */
private fun OfflinePackJob.detail(): String? = when (stage) {
    OfflinePackStage.QUEUED, OfflinePackStage.DOWNLOADING, OfflinePackStage.PRUNING -> null
    OfflinePackStage.DONE -> message.ifEmpty { null }
    OfflinePackStage.FAILED -> message.ifEmpty { "服务端未给出原因" }
}

private fun OfflinePackJob.icon(): ImageVector = when (stage) {
    OfflinePackStage.QUEUED, OfflinePackStage.DOWNLOADING -> Icons.Outlined.CloudDownload
    OfflinePackStage.PRUNING -> Icons.Outlined.CleaningServices
    OfflinePackStage.DONE -> Icons.Outlined.CloudDone
    OfflinePackStage.FAILED -> Icons.Outlined.ErrorOutline
}

@Composable
private fun OfflinePackJob.statusColor(): Color = when (stage) {
    OfflinePackStage.DONE -> LocalStatusColors.current.success
    OfflinePackStage.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val OfflinePackJob.canOpen: Boolean
    get() = stage == OfflinePackStage.DONE && outputId.isNotEmpty()

/**
 * 整包离线的列表项，版式与云端任务一致。下载阶段显示进度，清理阶段是不定进度条：
 * 删除按目录层级进行，没有可报的百分比。
 */
@Composable
internal fun PackTransferRow(
    item: TransferItem.Pack,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val job = item.job
    FileListItem(
        headline = job.folderName,
        leading = { ListLeadingMedia(thumbnail = null, fallback = { ListLeadingIcon(job.icon()) }) },
        onClick = {
            when {
                job.canOpen -> onOpen()
                job.stage == OfflinePackStage.FAILED -> onRetry()
                else -> Unit
            }
        },
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = if (job.isActive) 1 else 2,
        supporting = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 与普通云端任务同一个标签：秒传与整包离线的区别用户不必知道
                    Text(
                        text = "云端",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(text = item.statusLabel(), color = job.statusColor(), maxLines = 1)
                }
                job.detail()?.let { detail ->
                    Text(
                        text = detail.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (job.stage) {
                    OfflinePackStage.QUEUED, OfflinePackStage.DOWNLOADING -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OfflinePackStage.PRUNING -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> Unit
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.stage == OfflinePackStage.FAILED) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                    }
                }
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/** 整包离线的详情面板。 */
@Composable
internal fun PackTransferSheet(
    item: TransferItem.Pack,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val job = item.job
    val actions = buildList {
        if (job.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "打开", onOpen))
        if (job.stage == OfflinePackStage.FAILED) {
            add(SheetAction(Icons.Outlined.Refresh, if (job.cleanupFailed) "重新清理" else "重试", onRetry))
        }
        // 取消会连同服务端的占位文件一起删掉；已完成的只删任务记录，文件留在网盘里
        val discardLabel = if (job.isActive) "取消任务" else "移除"
        add(SheetAction(Icons.Outlined.Delete, discardLabel, onDiscard, destructive = true))
    }
    val statusColor = job.statusColor()
    ItemDetailsSheet(
        title = job.folderName,
        headerIcon = { ListLeadingIcon(job.icon()) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOf("云端", item.statusLabel(), job.totalBytes.toReadableSize()),
        extraLines = {
            job.detail()?.let {
                Text(text = it, color = if (job.stage == OfflinePackStage.FAILED) statusColor else Color.Unspecified)
            }
        },
    )
}
