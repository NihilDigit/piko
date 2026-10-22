package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.shared.state.TransferItem
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction

internal fun TransferItem.UploadBatch.statusLabel(): String = when (status) {
    UploadStatus.QUEUED -> "等待中"
    UploadStatus.HASHING -> "校验中"
    UploadStatus.UPLOADING -> "正在上传"
    UploadStatus.PAUSED -> "已暂停"
    UploadStatus.COMPLETED -> "已上传"
    UploadStatus.FAILED -> "部分文件上传失败"
}

internal fun uploadBatchActions(item: TransferItem.UploadBatch, expanded: Boolean, onToggle: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit, onShowInDrive: (() -> Unit)? = null): List<SheetAction> = buildList {
    add(SheetAction(if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore, if (expanded) "收起" else "展开", onToggle))
    if (onShowInDrive != null) add(SheetAction(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onShowInDrive))
    if (item.tasks.any { it.status.isActive }) add(SheetAction(Icons.Outlined.Pause, "全部暂停", onPause))
    if (item.tasks.any { it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED }) {
        add(SheetAction(Icons.Outlined.PlayArrow, "继续未完成的文件", onResume))
    }
    add(SheetAction(Icons.Outlined.Delete, if (item.status == UploadStatus.COMPLETED) "移除记录" else "取消并移除", onRemove, destructive = true))
}

@Composable
internal fun UploadBatchDetails(item: TransferItem.UploadBatch, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    ItemDetailsContent(
        title = item.title, modifier = modifier, showIcon = false,
        headerIcon = { if (item.isFolder) TaskFolderVisual(item.title) else ListLeadingIcon(Icons.Outlined.Upload) },
        actions = actions,
        metaParts = listOf(item.statusLabel(), "已完成 ${item.completedCount} / ${item.tasks.size} 项", "保存到 ${item.parentName}"),
    )
}
