package dev.piko.ui.screens.transfers

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

internal fun TransferItem.Pack.statusLabel(): String = if (isBusy) "正在处理任务" else when (job.stage) {
    OfflinePackStage.QUEUED -> "排队中"
    OfflinePackStage.DOWNLOADING -> "$progress%"
    OfflinePackStage.PRUNING -> "处理中"
    OfflinePackStage.DONE -> "已完成"
    OfflinePackStage.FAILED -> if (job.cleanupFailed) "处理失败" else "离线失败"
}

/**
 * 结束后的一句说明，只在有话要说时出现：完成时是改名失败这类附注，失败时是原因。
 * 删掉未选文件是保存的实现细节，不写。
 */
internal fun OfflinePackJob.detail(): String? = when (stage) {
    OfflinePackStage.QUEUED, OfflinePackStage.DOWNLOADING, OfflinePackStage.PRUNING -> null
    OfflinePackStage.DONE -> message.ifEmpty { null }
    OfflinePackStage.FAILED -> message.ifEmpty { "服务端未给出原因" }
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
        if (job.canOpen) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onOpen))
        if (onCopySource != null) {
            add(SheetAction(androidx.compose.material.icons.Icons.Outlined.ContentCopy, "复制来源链接", onCopySource))
        }
        if (job.stage == OfflinePackStage.FAILED) {
            add(SheetAction(Icons.Outlined.Refresh, "重试", onRetry))
        }
        // 取消会连同服务端的占位文件一起删掉；已完成的只删任务记录，文件留在网盘里
        val discardLabel = if (job.isActive) "取消任务" else "移除记录"
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
        metaParts = listOf("云端", item.statusLabel(), job.sizeLabel()),
        extraLines = {
            job.detail()?.let {
                Text(text = it, color = if (job.stage == OfflinePackStage.FAILED) statusColor else Color.Unspecified)
            }
        },
    )
}
