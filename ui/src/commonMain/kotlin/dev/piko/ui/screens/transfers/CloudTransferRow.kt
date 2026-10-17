package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.piko.shared.state.isOutputDeleted
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
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
private val DriveTask.canOpen: Boolean
    get() = phase == TaskPhase.COMPLETE && fileId.isNotEmpty()

internal val DriveTask.displayName: String
    get() = name.ifEmpty { fileName.ifEmpty { "离线任务" } }

internal fun DriveTask.statusLabel(): String = when {
    isOutputDeleted -> "文件已删除"
    phase == TaskPhase.PENDING -> "排队中"
    phase == TaskPhase.RUNNING -> "$progress%"
    phase == TaskPhase.COMPLETE -> "已完成"
    else -> "离线失败"
}

internal fun DriveTask.sizeParts(): List<String> =
    listOfNotNull(fileSize.toLongOrNull()?.takeIf { it > 0 }?.toReadableSize())

/** 失败原因。服务端的 message 可能为空。 */
internal fun DriveTask.failureReason(): String = message.ifEmpty { "服务端未给出原因" }

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
        headerIcon = { ListLeadingIcon(Icons.Outlined.CloudDownload) },
        actions = actions,
        metaParts = listOf("云端", task.statusLabel()) + task.sizeParts(),
        extraLines = {
            if (task.isFailed) Text(text = task.failureReason(), color = statusColor)
        },
    )
}

/** 点按一项做的事：完成的打开，失败的重新提交。 */
internal fun cloudPrimaryAction(task: DriveTask, onResubmit: (() -> Unit)?, onOpen: () -> Unit): (() -> Unit)? = when {
    task.canOpen -> onOpen
    task.isFailed -> onResubmit
    else -> null
}

/** 云端任务的全部操作，详情面板与右键菜单共用。 */
internal fun cloudTransferActions(
    task: DriveTask,
    onResubmit: (() -> Unit)?,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
): List<SheetAction> = buildList {
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
