package dev.piko.ui.screens.transfers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import dev.piko.download.DownloadStatus
import dev.piko.shared.state.TransferItem
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalStatusColors

/** 状态词。下载中不写，理由同单个文件的行。 */
private fun TransferItem.LocalBatch.statusLabel(): String? {
    listing?.let { listing ->
        return when {
            listing.error != null -> "列出失败"
            listing.quotaExcess != null -> "将超出今日下载额度"
            else -> "正在列出文件夹…"
        }
    }
    return when (status) {
        DownloadStatus.COMPLETED -> "已完成"
        DownloadStatus.DOWNLOADING -> null
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.PENDING -> "等待中"
        DownloadStatus.FAILED -> "$failedCount 个文件下载失败"
    }
}

private fun TransferItem.LocalBatch.statusDetails(): List<String> {
    listing?.let { listing ->
        val excess = listing.quotaExcess
        return when {
            listing.error != null -> listOf(listing.error.orEmpty().lineSequence().first())
            excess != null -> listOf("待下载 ${excess.neededBytes.toReadableSize()}", "今日剩余 ${excess.remainingBytes.toReadableSize()}")
            else -> listOf("已找到 ${listing.filesFound} 个文件", listing.bytesFound.toReadableSize())
        }
    }
    val bytes = "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
    val files = "$completedCount / ${tasks.size} 个文件"
    return when (status) {
        DownloadStatus.COMPLETED -> listOf("${tasks.size} 个文件", totalBytes.toReadableSize())
        DownloadStatus.DOWNLOADING -> buildList {
            add(files)
            add(bytes)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            remainingTime(totalBytes - downloadedBytes, speedBytesPerSec)?.let { add("剩余 $it") }
        }
        DownloadStatus.PENDING, DownloadStatus.PAUSED -> listOf(files, bytes)
        DownloadStatus.FAILED -> listOf(files, totalBytes.toReadableSize())
    }
}

@Composable
private fun TransferItem.LocalBatch.statusColor() = when {
    listing?.quotaExcess != null -> MaterialTheme.colorScheme.error
    status == DownloadStatus.COMPLETED -> LocalStatusColors.current.success
    status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 文件夹下载这一组能对它做的事，行尾按钮、详情面板与右键菜单共用。 */
internal class LocalBatchIntents(
    val onToggleExpand: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onRetryListing: () -> Unit,
    val onConfirm: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onRevealFolder: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * 点按做的事：列完了是展开或收起，列出中、列出失败与待确认没有文件可列，打开详情，那里有可做的事。
 * 窄行的轻点、宽行的双击共用。
 */
internal fun localBatchPrimaryAction(item: TransferItem.LocalBatch, intents: LocalBatchIntents, onDetails: () -> Unit): () -> Unit =
    if (item.listing != null) onDetails else intents.onToggleExpand

/**
 * 文件夹下载的一组：一行汇总，展开后各文件接在下面各占一行（由传输页排进列表）。
 * 行尾是与状态对应的快捷按钮、展开按钮与更多，比单个文件多一个，见 [BatchRowTrailingWidth]。
 */
@Composable
internal fun LocalBatchRow(
    item: TransferItem.LocalBatch,
    expanded: Boolean,
    intents: LocalBatchIntents,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val listing = item.listing
    val status = item.status
    val statusColor = item.statusColor()
    val inProgress = listing?.isListing == true ||
        (listing == null && status in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED))
    FileListItem(
        headline = item.batch.folderName,
        leading = {
            ListLeadingMedia(
                thumbnail = null,
                fallback = { ListLeadingIcon(if (expanded) Icons.Filled.Folder else Icons.Outlined.Folder) },
                isSpoilerBlurred = false,
            )
        },
        onClick = localBatchPrimaryAction(item, intents, onMoreClick),
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = if (inProgress) 1 else 2,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        supporting = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 完成的不写「已完成」，所在的分组已经说了
                    item.statusLabel()?.takeIf { listing != null || status != DownloadStatus.COMPLETED }?.let { label ->
                        Text(text = label, color = statusColor, maxLines = 1)
                    }
                    MetaRow(parts = item.statusDetails(), modifier = Modifier.weight(1f, fill = false))
                }
                if (inProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // 列出时不知道一共有多少，给不确定进度条
                    if (listing != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    listing?.error != null -> IconButton(onClick = intents.onRetryListing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                    }
                    listing?.quotaExcess != null -> IconButton(onClick = intents.onConfirm) {
                        Icon(Icons.Outlined.Download, contentDescription = "仍然下载")
                    }
                    listing != null -> Unit
                    status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PENDING -> IconButton(onClick = intents.onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "全部暂停")
                    }
                    status == DownloadStatus.PAUSED -> IconButton(onClick = intents.onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "全部继续")
                    }
                    status == DownloadStatus.FAILED -> IconButton(onClick = intents.onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试失败的文件")
                    }
                }
                if (item.tasks.isNotEmpty()) {
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        label = "batchChevron",
                    )
                    IconButton(onClick = intents.onToggleExpand) {
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.rotate(rotation),
                        )
                    }
                }
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/** 一组的全部操作，详情面板与右键菜单共用。 */
internal fun localBatchActions(item: TransferItem.LocalBatch, expanded: Boolean, intents: LocalBatchIntents): List<SheetAction> = buildList {
    val listing = item.listing
    if (listing != null) {
        when {
            listing.error != null -> add(SheetAction(Icons.Outlined.Refresh, "重试", intents.onRetryListing))
            listing.quotaExcess != null -> add(SheetAction(Icons.Outlined.Download, "仍然下载", intents.onConfirm))
        }
        val label = if (listing.error != null) "移除" else "取消"
        add(SheetAction(Icons.Outlined.Close, label, intents.onRemove))
        return@buildList
    }
    add(
        SheetAction(
            icon = if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
            label = if (expanded) "收起" else "展开",
            onClick = intents.onToggleExpand,
        ),
    )
    val status = item.status
    when (status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> add(SheetAction(Icons.Outlined.Pause, "全部暂停", intents.onPause))
        DownloadStatus.PAUSED -> add(SheetAction(Icons.Outlined.PlayArrow, "全部继续", intents.onResume))
        DownloadStatus.FAILED -> add(SheetAction(Icons.Outlined.Refresh, "重试失败的文件", intents.onResume))
        DownloadStatus.COMPLETED -> Unit
    }
    // 有一个文件落了盘，文件夹就在了
    if (item.completedCount > 0) {
        add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "打开文件夹", intents.onOpenFolder))
        add(SheetAction(Icons.Outlined.FolderOpen, "打开所在文件夹", intents.onRevealFolder))
    }
    val removeLabel = if (status == DownloadStatus.COMPLETED) "删除本地文件" else "取消并删除"
    add(SheetAction(Icons.Outlined.Delete, removeLabel, intents.onRemove, destructive = true))
}

/** 一组的详情面板：文件夹名、汇总与全部操作。待确认时把额度的事说完整。 */
@Composable
internal fun LocalBatchSheet(item: TransferItem.LocalBatch, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val statusColor = item.statusColor()
    val listing = item.listing
    ItemDetailsSheet(
        title = item.batch.folderName,
        headerIcon = { ListLeadingIcon(Icons.Outlined.Folder) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOfNotNull(item.statusLabel()) + item.statusDetails(),
        extraLines = {
            when {
                listing?.quotaExcess != null -> Text(text = "超出部分会下载失败，可明日再重试。", color = statusColor)
                listing?.error != null -> Text(text = listing.error.orEmpty(), color = statusColor)
                else -> Unit
            }
        },
    )
}

/** 一组的行尾：快捷按钮、展开与更多，各 48dp；transferClicks 在这一段不截点击。 */
internal val BatchRowTrailingWidth = 144.dp
