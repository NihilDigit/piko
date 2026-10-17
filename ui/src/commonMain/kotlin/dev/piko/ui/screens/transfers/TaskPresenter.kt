package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.state.*
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.WorkMeter
import dev.piko.ui.WorkProgress
import dev.piko.ui.WorkKind
import dev.piko.ui.workProgress
import dev.piko.ui.components.toReadableSize

internal data class TaskRowModel(
    val title: String, val kind: String, val status: String,
    val meter: WorkMeter = WorkMeter.None, val detail: String? = null,
    val destination: String? = null, val icon: ImageVector = Icons.Outlined.Description,
    val amount: String? = null, val rate: String? = null,
)

/** One projection for all task-row layouts; execution remains with the existing sessions. */
internal class TaskPresenter(private val state: TransfersState, private val vault: FolderVaultSession) {
    fun present(item: TransferItem): TaskRowModel {
        val base = describe(item)
        return when (item) {
            is TransferItem.Local -> base.copy(
                amount = if (item.task.totalBytes > 0) "${item.task.downloadedBytes.toReadableSize()} / ${item.task.totalBytes.toReadableSize()}" else null,
                rate = item.task.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" },
                destination = item.task.destinationPath.replace('\\', '/').substringBeforeLast('/', "").ifEmpty { "本机" },
            )
            is TransferItem.LocalBatch -> base.copy(amount = "${item.completedCount} / ${item.tasks.size} 项",
                rate = item.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" })
            is TransferItem.Upload -> base.copy(amount = "${item.task.processedBytes.toReadableSize()} / ${item.task.size.toReadableSize()}",
                rate = item.task.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" })
            is TransferItem.Cloud -> base.copy(amount = item.task.sizeParts().joinToString(" · "))
            is TransferItem.Pack -> base.copy(amount = item.job.sizeLabel())
            is TransferItem.Instant -> base.copy(amount = item.record.details().joinToString(" · "))
            is TransferItem.InstantAttempt -> base.copy(destination = item.work.target.name)
            else -> base
        }
    }

    private fun describe(item: TransferItem): TaskRowModel = when (item) {
        is TransferItem.Local -> item.task.let { task -> TaskRowModel(
            task.displayName, "下载", task.statusLabel() ?: "正在下载",
            downloadMeter(task.status, task.progress, task.totalBytes > 0 || task.isSegment),
            if (task.status == DownloadStatus.FAILED) task.failureReason() else task.statusDetails().joinToString(" · "),
            "本机", Icons.Outlined.Download,
        ) }
        is TransferItem.LocalBatch -> TaskRowModel(
            item.batch.folderName, "文件夹下载", item.statusLabel() ?: "正在下载",
            if (item.listing?.isListing == true) WorkMeter.Indeterminate else downloadMeter(item.status, item.progress, item.totalBytes > 0),
            item.statusDetails().joinToString(" · "), "本机", Icons.Outlined.Folder,
        )
        is TransferItem.Upload -> item.task.let { task -> TaskRowModel(
            task.fileName, "上传", task.statusLabel() ?: "正在上传",
            if (task.status in setOf(UploadStatus.HASHING, UploadStatus.UPLOADING)) {
                if (task.size > 0) WorkMeter.Determinate(task.progress) else WorkMeter.Indeterminate
            } else WorkMeter.None,
            if (task.status == UploadStatus.FAILED) task.failureReason() else task.statusDetails().joinToString(" · "),
            task.parentName, Icons.Outlined.Upload,
        ) }
        is TransferItem.Cloud -> TaskRowModel(
            item.task.displayName, "云端离线", item.task.statusLabel(),
            when (item.task.phase) {
                io.github.nihildigit.pikpak.TaskPhase.RUNNING -> WorkMeter.Determinate(item.task.progress.coerceIn(0, 100) / 100f)
                io.github.nihildigit.pikpak.TaskPhase.PENDING -> WorkMeter.Indeterminate
                else -> WorkMeter.None
            }, item.task.sizeParts().joinToString(" · "), "网盘", Icons.Outlined.CloudDownload,
        )
        is TransferItem.Pack -> TaskRowModel(
            item.job.folderName, "整包离线", item.statusLabel(),
            when (item.job.stage) {
                OfflinePackStage.DOWNLOADING -> WorkMeter.Determinate(item.progress.coerceIn(0, 100) / 100f)
                OfflinePackStage.PRUNING, OfflinePackStage.QUEUED -> WorkMeter.Indeterminate
                else -> WorkMeter.None
            }, item.job.detail() ?: item.job.sizeLabel(), "网盘", Icons.Outlined.CloudDownload,
        )
        is TransferItem.Instant -> TaskRowModel(item.record.name, "保存", "已保存", detail = item.record.details().joinToString(" · "), icon = Icons.Outlined.Bolt)
        is TransferItem.Extract -> from(item.job.workProgress(), "解压", Icons.Outlined.Unarchive)
        is TransferItem.Vault -> from(
            item.record?.let { it.workProgress(state.vaultUndoStatus(it)) } ?: vault.workProgress()
                ?: WorkProgress(WorkKind.VAULT, item.folderName, WorkMeter.None, "等待更新"),
            if (item.restoring) "恢复归档" else "归档", Icons.Outlined.Inventory2,
        )
        is TransferItem.Write -> from(item.work.workProgress(state.writeUndoStatus(item.work)), "文件处理")
        is TransferItem.History -> from(item.work.workProgress(), "历史记录")
        is TransferItem.InstantAttempt -> from(item.work.workProgress(), if (item.work.reference) "引用保存" else "保存", Icons.Outlined.Bolt)
        is TransferItem.Share -> from(item.work.workProgress(state.shareUndoStatus(item.work)), "分享转存", Icons.Outlined.Share)
        is TransferItem.OfflineSubmit -> from(item.work.workProgress(), "离线提交", Icons.Outlined.CloudDownload)
        is TransferItem.UploadPreparing -> from(item.work.workProgress(), "上传准备", Icons.Outlined.Upload)
        is TransferItem.VaultEdit -> from(item.work.workProgress(state.vaultEditUndoStatus(item.work)), "归档清单")
    }

    private fun from(progress: WorkProgress, kind: String, icon: ImageVector = Icons.Outlined.Description) =
        TaskRowModel(progress.title, kind, progress.status, progress.meter, progress.detail, icon = icon)

    private fun downloadMeter(status: DownloadStatus, progress: Float, known: Boolean): WorkMeter = when (status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED ->
            if (known) WorkMeter.Determinate(progress.coerceIn(0f, 1f)) else WorkMeter.Indeterminate
        else -> WorkMeter.None
    }
}
