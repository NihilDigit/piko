package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.presentation.localizedName
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
    private val partSeparator: String get() = tr("，", ", ", "、", ", ", "，")

    fun present(item: TransferItem): TaskRowModel {
        val base = describe(item)
        return when (item) {
            is TransferItem.Local -> base.copy(
                amount = if (item.task.totalBytes > 0) "${item.task.downloadedBytes.toReadableSize()} / ${item.task.totalBytes.toReadableSize()}" else null,
                rate = item.task.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" },
                destination = item.task.destinationPath.replace('\\', '/').substringBeforeLast('/', "").ifEmpty { tr("本机", "This device", "この端末", "이 기기", "本機") },
            )
            is TransferItem.LocalBatch -> base.copy(amount = tr("${item.completedCount} / ${item.tasks.size} 项", "${item.completedCount} / ${enCount(item.tasks.size, "item", "items")}", "${item.completedCount} / ${item.tasks.size} 項目", "${item.completedCount} / ${item.tasks.size}개 항목", "${item.completedCount} / ${item.tasks.size} 項"),
                rate = item.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" })
            is TransferItem.UploadBatch -> base.copy(amount = tr("${item.completedCount} / ${item.tasks.size} 项", "${item.completedCount} / ${enCount(item.tasks.size, "item", "items")}", "${item.completedCount} / ${item.tasks.size} 項目", "${item.completedCount} / ${item.tasks.size}개 항목", "${item.completedCount} / ${item.tasks.size} 項"),
                rate = item.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" })
            is TransferItem.Upload -> base.copy(amount = "${item.task.processedBytes.toReadableSize()} / ${item.task.size.toReadableSize()}",
                rate = item.task.speedBytesPerSec.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" })
            is TransferItem.Cloud -> base.copy(amount = item.task.sizeParts().joinToString(partSeparator))
            is TransferItem.Pack -> base.copy(amount = item.job.sizeLabel())
            is TransferItem.Instant -> base.copy(amount = item.record.details().joinToString(partSeparator))
            is TransferItem.InstantAttempt -> base.copy(destination = item.work.target.localizedName)
            else -> base
        }
    }

    private fun describe(item: TransferItem): TaskRowModel = when (item) {
        is TransferItem.Local -> item.task.let { task -> TaskRowModel(
            task.displayName, tr("下载", "Downloads", "ダウンロード", "다운로드", "下載"), task.statusLabel() ?: tr("正在下载", "Downloading", "ダウンロード中", "다운로드 중", "正在下載"),
            downloadMeter(task.status, task.progress, task.totalBytes > 0 || task.isSegment),
            if (task.status == DownloadStatus.FAILED) task.failureReason() else task.statusDetails().joinToString(partSeparator),
            tr("本机", "This device", "この端末", "이 기기", "本機"), Icons.Outlined.Download,
        ) }
        is TransferItem.LocalBatch -> TaskRowModel(
            item.batch.folderName, tr("文件夹下载", "Folder download", "フォルダーのダウンロード", "폴더 다운로드", "資料夾下載"), item.statusLabel() ?: tr("正在下载", "Downloading", "ダウンロード中", "다운로드 중", "正在下載"),
            if (item.listing?.isListing == true) WorkMeter.Indeterminate else downloadMeter(item.status, item.progress, item.totalBytes > 0),
            item.statusDetails().joinToString(partSeparator), tr("本机", "This device", "この端末", "이 기기", "本機"), Icons.Outlined.Folder,
        )
        is TransferItem.UploadBatch -> TaskRowModel(
            item.title, if (item.isFolder) tr("文件夹上传", "Folder upload", "フォルダーのアップロード", "폴더 업로드", "資料夾上傳") else tr("批量上传", "Batch upload", "一括アップロード", "일괄 업로드", "批次上傳"), item.statusLabel(),
            if (item.tasks.any { it.status.isActive }) WorkMeter.Determinate(item.progress) else WorkMeter.None,
            "${item.processedBytes.toReadableSize()} / ${item.totalBytes.toReadableSize()}",
            item.parentName, if (item.isFolder) Icons.Outlined.Folder else Icons.Outlined.Upload,
        )
        is TransferItem.Upload -> item.task.let { task -> TaskRowModel(
            task.fileName, tr("上传", "Uploads", "アップロード", "업로드", "上傳"), task.statusLabel() ?: tr("正在上传", "Uploading", "アップロード中", "업로드 중", "正在上傳"),
            if (task.status in setOf(UploadStatus.HASHING, UploadStatus.UPLOADING)) {
                if (task.size > 0) WorkMeter.Determinate(task.progress) else WorkMeter.Indeterminate
            } else WorkMeter.None,
            if (task.status == UploadStatus.FAILED) task.failureReason() else task.statusDetails().joinToString(partSeparator),
            task.parentName, Icons.Outlined.Upload,
        ) }
        is TransferItem.Cloud -> TaskRowModel(
            item.task.displayName, tr("云端离线", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "雲端離線"), item.task.statusLabel(),
            when (item.task.phase) {
                io.github.nihildigit.pikpak.TaskPhase.RUNNING -> WorkMeter.Determinate(item.task.progress.coerceIn(0, 100) / 100f)
                io.github.nihildigit.pikpak.TaskPhase.PENDING -> WorkMeter.Indeterminate
                else -> WorkMeter.None
            }, item.task.sizeParts().joinToString(partSeparator), tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), Icons.Outlined.CloudDownload,
        )
        is TransferItem.Pack -> TaskRowModel(
            item.job.folderName, tr("整包离线", "Download entire package to cloud", "一括クラウドダウンロード", "전체 클라우드 다운로드", "整包離線"), item.statusLabel(),
            when (item.job.stage) {
                OfflinePackStage.DOWNLOADING -> WorkMeter.Determinate(item.progress.coerceIn(0, 100) / 100f)
                OfflinePackStage.PRUNING, OfflinePackStage.QUEUED -> WorkMeter.Indeterminate
                else -> WorkMeter.None
            }, item.job.detail() ?: item.job.sizeLabel(), tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), Icons.Outlined.CloudDownload,
        )
        is TransferItem.Instant -> TaskRowModel(item.record.name, tr("保存", "Save", "保存", "저장", "儲存"), tr("已保存", "Saved", "保存しました", "저장했습니다", "已儲存"), detail = item.record.details().joinToString(partSeparator), icon = Icons.Outlined.Bolt)
        is TransferItem.Extract -> from(item.job.workProgress(), tr("解压", "Extract", "解凍", "압축 해제", "解壓縮"), Icons.Outlined.Unarchive)
        is TransferItem.Vault -> from(
            item.record?.let { it.workProgress(state.vaultUndoStatus(it)) } ?: vault.workProgress()
                ?: WorkProgress(WorkKind.VAULT, item.folderName, WorkMeter.None, tr("等待更新", "Waiting for update", "更新待ち", "업데이트 대기 중", "等待更新")),
            if (item.restoring) tr("恢复归档", "Restore archive", "アーカイブを復元", "보관 항목 복원", "還原歸檔") else tr("归档", "Archive", "アーカイブ", "보관", "歸檔"), Icons.Outlined.Inventory2,
        )
        is TransferItem.Write -> from(item.work.workProgress(state.writeUndoStatus(item.work)), tr("文件处理", "File operations", "ファイル操作", "파일 작업", "檔案處理"))
        is TransferItem.History -> from(item.work.workProgress(), tr("历史记录", "History", "履歴", "기록", "歷史記錄"))
        is TransferItem.InstantAttempt -> from(item.work.workProgress(), if (item.work.reference) tr("引用保存", "Save references", "参照として保存", "참조로 저장", "引用儲存") else tr("保存", "Save", "保存", "저장", "儲存"), Icons.Outlined.Bolt)
        is TransferItem.Share -> from(item.work.workProgress(state.shareUndoStatus(item.work)), tr("分享转存", "Save share", "共有を保存", "공유 저장", "分享轉存"), Icons.Outlined.Share)
        is TransferItem.OfflineSubmit -> from(item.work.workProgress(), tr("离线下载请求", "Cloud download request", "クラウドダウンロードリクエスト", "클라우드 다운로드 요청", "離線下載請求"), Icons.Outlined.CloudDownload)
        is TransferItem.UploadPreparing -> from(item.work.workProgress(), tr("上传准备", "Upload preparation", "アップロードの準備", "업로드 준비", "上傳準備"), Icons.Outlined.Upload)
        is TransferItem.VaultEdit -> from(item.work.workProgress(state.vaultEditUndoStatus(item.work)), tr("归档清单", "Archive manifest", "アーカイブ一覧", "보관 목록", "歸檔清單"))
    }

    private fun from(progress: WorkProgress, kind: String, icon: ImageVector = Icons.Outlined.Description) =
        TaskRowModel(progress.title, kind, progress.status, progress.meter, progress.detail, icon = icon)

    private fun downloadMeter(status: DownloadStatus, progress: Float, known: Boolean): WorkMeter = when (status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED ->
            if (known) WorkMeter.Determinate(progress.coerceIn(0f, 1f)) else WorkMeter.Indeterminate
        else -> WorkMeter.None
    }
}
