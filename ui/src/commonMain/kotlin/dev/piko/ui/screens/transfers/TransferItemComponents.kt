package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.sourceUrl
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteWork
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.shared.state.isOutputDeleted
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.PikoServices
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.SheetActionRole
import dev.piko.ui.components.withSelectAction
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.text_clear_completed_records_from_all_categories_downloaded_and_cloud_files_are_k
import dev.piko.ui.resources.text_clear_completed_tasks
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.TaskPhase

internal class TransferActionContext(
    val state: TransfersState,
    val services: PikoServices,
    val localFiles: LocalFileActions,
    val isSpoilerBlurEnabled: Boolean,
    val wideLayout: Boolean,
    val isBatchExpanded: (String) -> Boolean,
    val isKeyRevealed: (String) -> Boolean,
    val onToggleBatch: (String) -> Unit,
    val onTogglePreviewReveal: (String) -> Unit,
    val onOpenBatchFolder: (DownloadBatch, (String) -> Unit) -> Unit,
    val onOpenCloudFileById: (fileId: String, fileName: String) -> Unit,
    val onCopySourceUrl: (String) -> Unit,
    val onPlayLocal: (DownloadTask) -> Unit,
    val onPlayCloud: (fileId: String, fileName: String) -> Unit,
    val onCreateImport: (String) -> Unit,
    val onShowDetails: (String) -> Unit,
    val onConfirmSingleSideEffectDelete: (String) -> Unit,
)

private fun locateWriteIdOf(work: FileWriteWork): String = work.command.target.id

private fun hasLocalPreview(task: DownloadTask): Boolean =
    task.thumbnailLink.isNotEmpty() || task.destinationPath.isNotEmpty()

internal fun buildTransferBatchIntents(
    item: TransferItem.LocalBatch,
    ctx: TransferActionContext,
): LocalBatchIntents {
    val id = item.batch.id
    return LocalBatchIntents(
        onToggleExpand = { ctx.onToggleBatch(item.key) },
        onPause = { ctx.state.pauseBatch(id) },
        onResume = { ctx.state.resumeBatch(id) },
        onRetryListing = { ctx.state.retryListing(id) },
        onConfirm = { ctx.state.confirmListing(id) },
        onOpenFolder = { ctx.onOpenBatchFolder(item.batch) { ctx.localFiles.openExternally(it, isMedia = false) } },
        onRevealFolder = { ctx.onOpenBatchFolder(item.batch) { ctx.localFiles.openContainingFolder(it) } },
        onRemove = { ctx.state.removeBatch(id) },
    )
}

internal fun buildTransferPlayAction(
    item: TransferItem,
    ctx: TransferActionContext,
): (() -> Unit)? = when (item) {
    is TransferItem.Local -> if (item.task.canPlayNow()) ({ ctx.onPlayLocal(item.task) }) else null
    is TransferItem.Cloud -> if (item.task.canPlayVideo) ({ ctx.onPlayCloud(item.task.fileId, item.task.displayName) }) else null
    is TransferItem.Instant -> if (item.record.canPlayVideo) ({ ctx.onPlayCloud(item.record.locateId, item.record.name) }) else null
    is TransferItem.Upload -> if (item.task.canPlayVideo) ({ ctx.onPlayCloud(requireNotNull(item.task.fileId), item.task.fileName) }) else null
    is TransferItem.Pack -> if (item.job.canOpen && item.job.folderName.isPlayableVideo()) ({ ctx.onPlayCloud(item.job.outputId, item.job.folderName) }) else null
    else -> null
}

private fun resubmitCloudOf(task: DriveTask, state: TransfersState): (() -> Unit)? =
    task.sourceUrl?.let { { state.resubmitCloud(task) } }

internal fun buildTransferItemActions(
    item: TransferItem,
    ctx: TransferActionContext,
): List<SheetAction> {
    val state = ctx.state
    val services = ctx.services
    val base = when (item) {
        is TransferItem.UploadPreparing -> uploadPreparationActions(
            item.work,
            { ctx.onOpenCloudFileById(item.work.parentId, item.work.parentName) },
            {
                if (item.work.hasSideEffects) ctx.onConfirmSingleSideEffectDelete(item.key)
                else state.remove(item)
            },
        )
        is TransferItem.UploadBatch -> uploadBatchActions(
            item,
            ctx.isBatchExpanded(item.key),
            { ctx.onToggleBatch(item.key) },
            { state.pauseUploadBatch(item.batchId) },
            { state.resumeUploadBatch(item.batchId) },
            { ctx.onConfirmSingleSideEffectDelete(item.key) },
            onShowInDrive = { ctx.onOpenCloudFileById(item.parentId, item.parentName) },
        )
        is TransferItem.LocalBatch -> localBatchActions(
            item,
            ctx.isBatchExpanded(item.key),
            buildTransferBatchIntents(item, ctx),
        )
        is TransferItem.Local -> localTransferActions(
            task = item.task,
            files = ctx.localFiles,
            onPlay = { ctx.onPlayLocal(item.task) },
            onStart = { state.resumeLocal(item.task.taskId) },
            onPause = { state.pauseLocal(item.task.taskId) },
            onRemove = { state.removeLocal(item.task.taskId) },
            previewHidden = if (ctx.isSpoilerBlurEnabled && hasLocalPreview(item.task)) !ctx.isKeyRevealed(item.key) else null,
            onTogglePreview = { ctx.onTogglePreviewReveal(item.key) },
            onShowInDrive = item.task.fileId.takeIf { it.isNotEmpty() && !item.task.isSegment }?.let { id -> { ctx.onOpenCloudFileById(id, item.task.fileName) } },
        )
        is TransferItem.Upload -> uploadTransferActions(
            task = item.task,
            onPlay = buildTransferPlayAction(item, ctx),
            onOpen = { item.task.fileId?.let { id -> ctx.onOpenCloudFileById(id, item.task.fileName) } },
            onOpenParent = { ctx.onOpenCloudFileById(item.task.parentId, item.task.parentName) },
            onResume = { state.resumeUpload(item.task.taskId) },
            onPause = { state.pauseUpload(item.task.taskId) },
            onRemove = { state.removeUpload(item.task.taskId) },
            onRevealLocal = item.task.sourceUri.takeIf { it.isNotEmpty() && !it.startsWith("content:") }?.let { path -> { ctx.localFiles.openContainingFolder(path) } },
        )
        is TransferItem.Cloud -> cloudTransferActions(
            task = item.task,
            onPlay = buildTransferPlayAction(item, ctx),
            onResubmit = resubmitCloudOf(item.task, state),
            onDelete = { state.deleteCloud(item.task.id) },
            onOpen = { ctx.onOpenCloudFileById(item.task.fileId, item.task.fileName) },
            onCopySource = item.task.sourceUrl?.let { url -> { ctx.onCopySourceUrl(url) } },
        )
        is TransferItem.Pack -> packTransferActions(
            item = item,
            onOpen = { ctx.onOpenCloudFileById(item.job.outputId, item.job.folderName) },
            onRetry = { state.retryPack(item.job.taskId) },
            onDiscard = { state.discardPack(item.job.taskId) },
            onCopySource = item.job.url.takeIf { it.isNotEmpty() }?.let { url -> { ctx.onCopySourceUrl(url) } },
        )
        is TransferItem.Instant -> instantTransferActions(
            record = item.record,
            onPlay = buildTransferPlayAction(item, ctx),
            onOpen = { ctx.onOpenCloudFileById(item.record.locateId, item.record.name) },
            onRemove = { state.removeInstant(item.record.id) },
        )
        is TransferItem.InstantAttempt -> instantAttemptActions(
            item.work,
            item.work.locateId?.let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } },
            {
                if (item.work.receipt?.requiresInspection == true) ctx.onConfirmSingleSideEffectDelete(item.key)
                else state.remove(item)
            },
            onRetry = if (services.instantSession.canRetryAttempt(item.work.id)) ({ services.instantSession.retryAttempt(item.work.id); Unit }) else null,
            onRecheck = if (services.instantSaveRecords.canRecheck(item.work.id)) ({ services.instantSaveRecords.recheck(item.work.id, services.driveRepository); Unit }) else null,
        )
        is TransferItem.Share -> shareTransferActions(
            item.work,
            state.shareUndoStatus(item.work),
            state.canUndoShare(item.work),
            services.shareSaves.canRecheck(item.work.id),
            { state.undoShare(item.work) },
            { services.shareSaves.recheck(item.work.id) },
            {
                if (item.work.result?.unconfirmedSources?.isNotEmpty() == true || item.work.pendingRenames.isNotEmpty()) {
                    ctx.onConfirmSingleSideEffectDelete(item.key)
                } else state.remove(item)
            },
            (item.work.associatedOutputs.values.firstOrNull() ?: item.work.command.target.id).let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } },
        )
        is TransferItem.OfflineSubmit -> offlineSubmitActions(
            item.work,
            (item.work.outputId ?: item.work.target.id).let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } },
            {
                if (item.work.status == dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED) ctx.onConfirmSingleSideEffectDelete(item.key)
                else state.remove(item)
            },
        )
        is TransferItem.Extract -> extractTransferActions(
            item = item,
            onShowInDrive = { ctx.onOpenCloudFileById(item.job.file.id, item.job.file.name) },
            onSkip = { state.skipExtract(item.job.id) },
            onRecheck = { state.recheckExtract(item.job.id) },
            onInputPassword = if (item.job.status is ArchiveJobStatus.NeedsPassword) ({ state.promptExtractPassword(item.job.id); Unit }) else null,
        )
        is TransferItem.Vault -> vaultTransferActions(
            item,
            state.isVaultStopping,
            { state.stopVault(item.work.id) },
            { state.dismissVault(item.work.id) },
            item.record?.let(state::canUndoVault) == true,
            item.record?.let(state::vaultUndoStatus),
            { item.record?.let(state::undoVault) },
            ctx.onCreateImport,
        )
        is TransferItem.Write -> {
            val work = item.work
            fileWriteActions(
                work,
                state.writeUndoStatus(work),
                state.canUndoWrite(work),
                services.fileWrites.canRecheck(work.id),
                { state.undoWrite(work) },
                { services.fileWrites.recheck(work.id) },
                {
                    if (work.result?.unconfirmed?.isNotEmpty() == true) ctx.onConfirmSingleSideEffectDelete(item.key)
                    else state.remove(item)
                },
                locateWriteIdOf(work)?.let { id -> { ctx.onOpenCloudFileById(id, work.command.title) } },
            )
        }
    }
    return base + SheetAction(Icons.Outlined.Info, tr("详情", "Details", "詳細", "세부 정보", "詳細資料"),
        { ctx.onShowDetails(item.key) }, group = ActionGroup.Properties)
}

internal fun buildTransferPrimaryAction(
    item: TransferItem,
    ctx: TransferActionContext,
): (() -> Unit)? {
    val state = ctx.state
    return when (item) {
        is TransferItem.UploadPreparing -> if (item.children.isNotEmpty()) ({ ctx.onToggleBatch(item.key) }) else ({ ctx.onOpenCloudFileById(item.work.parentId, item.work.parentName) })
        is TransferItem.UploadBatch -> ({ ctx.onToggleBatch(item.key) })
        is TransferItem.LocalBatch -> localBatchPrimaryAction(item, buildTransferBatchIntents(item, ctx))
        is TransferItem.Local -> localPrimaryAction(
            task = item.task,
            files = ctx.localFiles,
            onPlay = { ctx.onPlayLocal(item.task) },
            onStart = { state.resumeLocal(item.task.taskId) },
            onPause = { state.pauseLocal(item.task.taskId) },
        )
        is TransferItem.Upload -> uploadPrimaryAction(
            task = item.task,
            onPlay = buildTransferPlayAction(item, ctx),
            onOpen = { item.task.fileId?.let { id -> ctx.onOpenCloudFileById(id, item.task.fileName) } },
            onResume = { state.resumeUpload(item.task.taskId) },
            onPause = { state.pauseUpload(item.task.taskId) },
        )
        is TransferItem.Cloud -> cloudPrimaryAction(
            item.task,
            buildTransferPlayAction(item, ctx),
            resubmitCloudOf(item.task, state),
        ) { ctx.onOpenCloudFileById(item.task.fileId, item.task.fileName) }
        is TransferItem.Pack -> buildTransferPlayAction(item, ctx) ?: packPrimaryAction(
            item = item,
            onOpen = { ctx.onOpenCloudFileById(item.job.outputId, item.job.folderName) },
            onRetry = { state.retryPack(item.job.taskId) },
        )
        is TransferItem.Instant -> buildTransferPlayAction(item, ctx)
            ?: ({ ctx.onOpenCloudFileById(item.record.locateId, item.record.name) })
        is TransferItem.InstantAttempt -> item.work.locateId?.let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } }
        is TransferItem.Share -> (item.work.associatedOutputs.values.firstOrNull() ?: item.work.command.target.id)
            .let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } }
        is TransferItem.OfflineSubmit -> (item.work.outputId ?: item.work.target.id)
            .let { id -> { ctx.onOpenCloudFileById(id, item.work.title) } }
        is TransferItem.Extract -> if (item.job.status is ArchiveJobStatus.NeedsPassword) {
            { state.promptExtractPassword(item.job.id); Unit }
        } else {
            { ctx.onOpenCloudFileById(item.job.file.id, item.job.file.name) }
        }
        is TransferItem.Vault -> null
        is TransferItem.Write -> locateWriteIdOf(item.work)?.let { id -> { ctx.onOpenCloudFileById(id, item.work.command.title) } }
    }
}

private fun pauseOrResumeActionOf(
    item: TransferItem,
    ctx: TransferActionContext,
): SheetAction? {
    val state = ctx.state
    return when {
        state.isPausable(item) -> SheetAction(
            Icons.Outlined.Pause,
            tr("暂停", "Pause", "一時停止", "일시 중지", "暫停"),
            { state.pause(item) },
        )
        state.isResumable(item) -> {
            val isFailed = (item is TransferItem.Local && item.task.status == DownloadStatus.FAILED) ||
                (item is TransferItem.Upload && item.task.status == UploadStatus.FAILED)
            val isUpload = item is TransferItem.Upload || item is TransferItem.UploadBatch
            val resumeIcon = when {
                isFailed -> Icons.Outlined.Refresh
                isUpload -> Icons.Outlined.Upload
                else -> Icons.Outlined.Download
            }
            val resumeLabel = when {
                isFailed -> tr("重试", "Retry", "再試行", "다시 시도", "重試")
                isUpload -> tr("继续上传", "Resume upload", "アップロードを再開", "업로드 계속", "繼續上傳")
                else -> tr("继续下载", "Resume download", "ダウンロードを再開", "다운로드 계속", "繼續下載")
            }
            SheetAction(resumeIcon, resumeLabel, { state.resume(item) })
        }
        else -> buildTransferItemActions(item, ctx).firstOrNull {
            it.role == SheetActionRole.Stop ||
                it.label == tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인", "重新查詢結果") ||
                it.label == tr("解析来源链接", "Resolve source link", "ソースリンクを解析", "출처 링크 분석", "解析來源連結")
        }
    }
}

internal fun buildTransferQuickAction(
    item: TransferItem,
    ctx: TransferActionContext,
): SheetAction? {
    val state = ctx.state
    return when (item) {
        is TransferItem.Local -> when {
            item.task.status == DownloadStatus.COMPLETED -> if (item.task.isMedia()) {
                SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayLocal(item.task) })
            } else {
                SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기", "開啟所在資料夾"), { ctx.localFiles.openContainingFolder(item.task.destinationPath) })
            }
            else -> pauseOrResumeActionOf(item, ctx)
        }
        is TransferItem.Cloud -> when {
            item.task.canPlayVideo -> SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayCloud(item.task.fileId, item.task.displayName) })
            item.task.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.task.fileId, item.task.fileName) })
            item.task.phase == TaskPhase.ERROR && !item.task.isOutputDeleted ->
                resubmitCloudOf(item.task, state)?.let { SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도", "重試"), it) }
            else -> null
        }
        is TransferItem.Instant -> when {
            item.record.canPlayVideo -> SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayCloud(item.record.locateId, item.record.name) })
            else -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.record.locateId, item.record.name) })
        }
        is TransferItem.Upload -> when {
            item.task.canPlayVideo -> SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayCloud(requireNotNull(item.task.fileId), item.task.fileName) })
            item.task.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(requireNotNull(item.task.fileId), item.task.fileName) })
            else -> pauseOrResumeActionOf(item, ctx)
        }
        is TransferItem.Pack -> when {
            item.job.canOpen && item.job.folderName.isPlayableVideo() -> SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayCloud(item.job.outputId, item.job.folderName) })
            item.job.canOpen -> SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.job.outputId, item.job.folderName) })
            !item.isBusy && item.job.stage == OfflinePackStage.FAILED -> SheetAction(Icons.Outlined.Refresh, tr("重试", "Retry", "再試行", "다시 시도", "重試"), { state.retryPack(item.job.taskId) })
            else -> null
        }
        else -> pauseOrResumeActionOf(item, ctx) ?: buildTransferItemActions(item, ctx).firstOrNull {
            it.label == tr("输入解压密码", "Enter archive password", "解凍パスワードを入力", "압축 해제 비밀번호 입력", "輸入解壓縮密碼") ||
                it.role == SheetActionRole.Stop ||
                it.label == tr("重新查询结果", "Check results again", "結果を再確認", "결과 다시 확인", "重新查詢結果") ||
                it.label == tr("重试", "Retry", "再試行", "다시 시도", "重試") ||
                it.label == tr("仍然下载", "Download anyway", "ダウンロードを続行", "계속 다운로드", "仍然下載") ||
                it.label == tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示")
        }
    }
}

internal fun buildTransferExtraAction(
    item: TransferItem,
    ctx: TransferActionContext,
): SheetAction? {
    val expanded = ctx.isBatchExpanded(item.key)
    return when (item) {
        is TransferItem.LocalBatch, is TransferItem.UploadBatch -> if (item.children.isNotEmpty()) SheetAction(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            if (expanded) tr("收起文件", "Collapse files", "ファイルを折りたたむ", "파일 접기", "收合檔案") else tr("展开文件", "Expand files", "ファイルを展開", "파일 펼치기", "展開檔案"),
            { ctx.onToggleBatch(item.key) },
        ) else null
        is TransferItem.Local -> when {
            item.task.status == DownloadStatus.COMPLETED && item.task.isMedia() && ctx.wideLayout ->
                SheetAction(Icons.Outlined.FolderOpen, tr("打开所在文件夹", "Open containing folder", "保存先フォルダーを開く", "파일 위치 열기", "開啟所在資料夾"), { ctx.localFiles.openContainingFolder(item.task.destinationPath) })
            item.task.canPlayWhileDownloading() ->
                SheetAction(Icons.Outlined.PlayCircle, tr("播放", "Play", "再生", "재생", "播放"), { ctx.onPlayLocal(item.task) })
            else -> null
        }
        is TransferItem.Cloud -> if (item.task.canPlayVideo) {
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.task.fileId, item.task.fileName) })
        } else null
        is TransferItem.Instant -> if (item.record.canPlayVideo) {
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.record.locateId, item.record.name) })
        } else null
        is TransferItem.Upload -> if (item.task.canPlayVideo) {
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(requireNotNull(item.task.fileId), item.task.fileName) })
        } else null
        is TransferItem.Pack -> if (item.job.canOpen && item.job.folderName.isPlayableVideo()) {
            SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), { ctx.onOpenCloudFileById(item.job.outputId, item.job.folderName) })
        } else null
        else -> null
    }
}

/**
 * 顶层无状态可跳过任务前导视觉组件（取代原先嵌在 `TransfersScreen` 内部的局部不可跳过函数，
 * 确保下载进度跳动时未变化的行可由 Compose 直接跳过重组）。
 */
@Composable
internal fun TransferItemLeadingVisual(
    item: TransferItem,
    isBlurred: Boolean,
    state: TransfersState,
    taskPresenter: TaskPresenter,
    localFiles: LocalFileActions,
) {
    when (item) {
        is TransferItem.Local -> LocalTransferVisual(item.task, isBlurred)
        is TransferItem.LocalBatch -> TaskFolderVisual(item.batch.folderName)
        is TransferItem.UploadBatch -> if (item.isFolder) TaskFolderVisual(item.title) else ListLeadingIcon(Icons.Outlined.Upload)
        is TransferItem.Upload -> {
            val isVisualFile = item.task.fileName.isPlayableVideo() ||
                item.task.fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
            val uploadThumb = remember(item.task.sourceUri, item.task.fileName) {
                localFiles.thumbnailModel(item.task.sourceUri).takeIf { isVisualFile }
            } ?: item.task.fileId?.let(state::thumbnailOf)
            ListLeadingMedia(
                thumbnail = uploadThumb,
                fallback = { TaskFileTypeIcon(item.task.fileName, Icons.Outlined.Upload) },
                isSpoilerBlurred = isBlurred,
                showPlayOverlay = item.task.canPlayVideo,
            )
        }
        is TransferItem.Cloud -> ListLeadingMedia(
            thumbnail = state.thumbnailOf(item.task.fileId),
            fallback = { TaskFileTypeIcon(item.task.fileName.ifEmpty { item.task.displayName }, Icons.Outlined.CloudDownload) },
            isSpoilerBlurred = isBlurred,
            showPlayOverlay = item.task.canPlayVideo,
        )
        is TransferItem.Pack -> ListLeadingMedia(
            thumbnail = state.thumbnailOf(item.job.outputId),
            fallback = { TaskFolderVisual(item.job.folderName) },
            isSpoilerBlurred = isBlurred,
            showPlayOverlay = item.job.canOpen && item.job.folderName.isPlayableVideo(),
        )
        is TransferItem.Instant -> ListLeadingMedia(
            thumbnail = state.thumbnailOf(item.record.locateId),
            fallback = {
                if (item.record.fileCount > 1 && !item.record.name.contains('.')) TaskFolderVisual(item.record.name)
                else TaskFileTypeIcon(item.record.name, Icons.Outlined.Bolt)
            },
            isSpoilerBlurred = isBlurred,
            showPlayOverlay = item.record.canPlayVideo,
        )
        is TransferItem.Extract -> TaskFileTypeIcon(item.job.file.name, Icons.Outlined.Unarchive)
        is TransferItem.Vault -> TaskFolderVisual(item.folderName)
        else -> ListLeadingIcon(taskPresenter.present(item).icon)
    }
}

/**
 * 顶层无状态可跳过任务行容器：单行进度变化时仅该行重组。
 */
@Composable
internal fun TransferItemRowSlot(
    item: TransferItem,
    index: Int,
    count: Int,
    checkboxActive: Boolean,
    isSelected: Boolean,
    isCompleted: Boolean,
    isAttention: Boolean,
    isBlurred: Boolean,
    taskLayout: TaskRowLayout,
    state: TransfersState,
    taskPresenter: TaskPresenter,
    localFiles: LocalFileActions,
    quickAction: SheetAction?,
    extraAction: SheetAction?,
    playAction: (() -> Unit)?,
    contextActions: () -> List<SheetAction>,
    onPlaced: (LayoutCoordinates) -> Unit,
    onSelect: () -> Unit,
    onToggleSelect: () -> Unit,
    onExtendSelect: () -> Unit,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trailingCount = (if (quickAction != null) 1 else 0) + (if (extraAction != null) 1 else 0)
    val hasLeadingClick = !checkboxActive && playAction != null
    val selection = RowSelection(
        active = checkboxActive,
        selected = isSelected,
        onToggle = onToggleSelect,
        onLongClick = onLongClick,
    )
    ContextMenuArea(
        actions = contextActions,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .onPlaced(onPlaced)
                .transferClicks(
                    onSelect = onSelect,
                    onToggle = onToggleSelect,
                    onExtend = onExtendSelect,
                    onOpen = onOpen,
                    trailingPassThrough = if (trailingCount == 0) 0.dp else (trailingCount * 48 + 12).dp,
                    leadingPassThrough = if (hasLeadingClick) 68.dp else 0.dp,
                ),
        ) {
            TaskRow(
                model = taskPresenter.present(item),
                selection = selection,
                layout = taskLayout,
                index = index,
                count = count,
                item = item,
                completed = isCompleted,
                attention = isAttention,
                onOpen = onOpen,
                onToggle = onToggleSelect,
                quickAction = quickAction,
                extraAction = extraAction,
                onLeadingClick = playAction,
                leadingVisual = {
                    TransferItemLeadingVisual(
                        item = item,
                        isBlurred = isBlurred,
                        state = state,
                        taskPresenter = taskPresenter,
                        localFiles = localFiles,
                    )
                },
            )
        }
    }
}

@Composable
internal fun TransferTaskDetailsPane(
    item: TransferItem?,
    state: TransfersState,
    vaultSession: FolderVaultSession,
    checkboxActive: Boolean,
    rawActions: List<SheetAction>,
    onCloseDetails: () -> Unit,
    onEnterSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = item ?: return
    val actions = withSelectAction(
        rawActions
            .filterNot { it.group == ActionGroup.Properties }
            .map { action ->
                SheetAction(
                    icon = action.icon,
                    label = action.label,
                    onClick = {
                        onCloseDetails()
                        action.onClick()
                    },
                    destructive = action.destructive,
                    group = action.group,
                    checked = action.checked,
                    onPrepare = action.onPrepare,
                    tier = action.tier,
                    shortLabel = action.shortLabel,
                    pending = action.pending,
                )
            },
        if (LocalPointerSource.current.isTouchLike && !checkboxActive) ({ onCloseDetails(); onEnterSelection(current.key) }) else null,
    )
    when (current) {
        is TransferItem.UploadBatch -> UploadBatchDetails(current, actions, modifier = modifier)
        is TransferItem.LocalBatch -> LocalBatchDetails(current, actions, modifier = modifier)
        is TransferItem.Local -> LocalTransferDetails(current.task, actions, modifier = modifier)
        is TransferItem.Upload -> UploadTransferDetails(current.task, actions, modifier = modifier)
        is TransferItem.UploadPreparing -> UploadPreparingDetails(current.work, actions, modifier = modifier)
        is TransferItem.Cloud -> CloudTransferDetails(current.task, actions, modifier = modifier)
        is TransferItem.Pack -> PackTransferDetails(current, actions, modifier = modifier)
        is TransferItem.Instant -> InstantTransferDetails(current.record, actions, modifier = modifier)
        is TransferItem.InstantAttempt -> InstantAttemptDetails(current.work, actions, modifier = modifier)
        is TransferItem.Share -> ShareTransferDetails(current.work, state.shareUndoStatus(current.work), actions, modifier = modifier)
        is TransferItem.OfflineSubmit -> OfflineSubmitDetails(current.work, actions, modifier = modifier)
        is TransferItem.Extract -> ExtractTransferDetails(current, actions, modifier = modifier)
        is TransferItem.Vault -> VaultTransferDetails(current, vaultSession, actions, current.record?.let(state::vaultUndoStatus), modifier = modifier)
        is TransferItem.Write -> FileWriteTransferDetails(current.work, state.writeUndoStatus(current.work), actions, modifier = modifier)
    }
}

@Composable
internal fun ClearCompletedTasksDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.text_clear_completed_tasks)) },
        text = { Text(stringResource(Res.string.text_clear_completed_records_from_all_categories_downloaded_and_cloud_files_are_k)) },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("清除", "Clear", "消去", "지우기", "清除"),
                destructive = true,
                onClick = onConfirm,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
internal fun DeleteSelectedTasksDialog(
    items: List<TransferItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deletesFiles = items.any {
        (it is TransferItem.Local && it.task.status == DownloadStatus.COMPLETED) ||
            (it is TransferItem.LocalBatch && it.completedCount > 0)
    }
    val cancelsUploads = items.any {
        (it is TransferItem.Upload && it.task.status != UploadStatus.COMPLETED) ||
            (it is TransferItem.UploadBatch && it.tasks.any { task -> task.status != UploadStatus.COMPLETED })
    }
    val notes = listOfNotNull(
        tr("已下载的文件会从本机删除。", "Downloaded files will be deleted from this device.", "ダウンロード済みファイルをこの端末から削除します。", "다운로드한 파일이 기기에서 삭제됩니다.", "已下載的檔案會從本機刪除。").takeIf { deletesFiles },
        tr("未完成的上传任务将取消，云端未传完的临时文件将一并删除。", "Unfinished uploads will be cancelled and incomplete cloud files deleted.", "未完了のアップロードをキャンセルし、クラウド上の不完全なファイルも削除します。", "미완료 업로드를 취소하고 클라우드의 미완성 파일도 함께 삭제합니다.", "未完成的上傳任務將取消，雲端未傳完的暫存檔案將一併刪除。").takeIf { cancelsUploads },
        tr("仅移除云端任务记录，已保存至网盘的文件不受影响。", "Only cloud task records will be removed; files saved in drive are kept.", "クラウドタスクの記録のみを削除します。ドライブに保存済みのファイルは保持されます。", "클라우드 작업 기록만 삭제하며 드라이브에 저장된 파일은 유지됩니다.", "僅移除雲端任務記錄，已儲存至雲端硬碟的檔案不受影響。").takeIf { items.any { TransferKind.CLOUD.matches(it) } },
        tr("待确认的文件操作可能已在服务端执行。移除记录不会取消已提交的操作，重试前请先核对实际文件。", "Unconfirmed file operations may have already run on the server. Removing records does not cancel them; inspect files before retrying.", "未確認のファイル操作はサーバーで既に実行されている可能性があります。記録を削除しても操作は取り消されないため、再試行前に実際のファイルを確認してください。", "미확인 파일 작업이 서버에서 이미 실행되었을 수 있습니다. 기록을 삭제해도 작업은 취소되지 않으므로 다시 시도하기 전에 실제 파일을 확인해 주세요.", "待確認的檔案操作可能已在伺服器執行。移除記錄不會取消已提交的操作，重試前請先核對實際檔案。").takeIf {
            items.any { it is TransferItem.Write && it.work.result?.unconfirmed?.isNotEmpty() == true }
        },        tr("部分保存请求可能已在服务端执行。移除记录不会取消请求，重新保存前请先核对目标文件夹。", "Some save requests may have already run. Removing records does not cancel them; check the destination folder before saving again.", "一部の保存リクエストは既に実行されている可能性があります。記録を削除しても取り消されないため、再保存前に保存先フォルダーを確認してください。", "일부 저장 요청이 이미 실행되었을 수 있습니다. 기록을 삭제해도 취소되지 않으므로 다시 저장하기 전에 대상 폴더를 확인해 주세요.", "部分儲存請求可能已在伺服器執行。移除記錄不會取消請求，重新儲存前請先核對目標資料夾。").takeIf {
            items.any { it is TransferItem.InstantAttempt && it.work.receipt?.requiresInspection == true }
        },
        tr("分享转存或重命名结果待确认。移除记录不会取消已提交的请求，重试前请先核对目标文件夹。", "Share save or rename results are unconfirmed. Removing records does not cancel submitted requests; check the destination folder first.", "共有保存または名前変更の結果が未確認です。記録を削除しても送信済みのリクエストは取り消されません。", "공유 저장 또는 이름 변경 결과가 미확인 상태입니다. 기록을 삭제해도 제출된 요청은 취소되지 않습니다.", "分享轉存或重新命名結果待確認。移除記錄不會取消已提交的請求，重試前請先核對目標資料夾。").takeIf {
            items.any { it is TransferItem.Share && (it.work.result?.unconfirmedSources?.isNotEmpty() == true || it.work.pendingRenames.isNotEmpty()) }
        },
        tr("离线下载请求可能已在服务端执行。移除记录不会取消云端任务，重新提交前请先核对云端任务列表。", "The cloud download request may have already run. Removing records does not cancel remote tasks; check cloud tasks before resubmitting.", "クラウドダウンロードは既に実行されている可能性があります。記録を削除してもリモートタスクは取り消されません。", "클라우드 다운로드 요청이 이미 실행되었을 수 있습니다. 기록을 삭제해도 원격 작업은 취소되지 않습니다.", "離線下載請求可能已在伺服器執行。移除記錄不會取消雲端任務，重新提交前請先核對雲端任務清單。").takeIf {
            items.any { it is TransferItem.OfflineSubmit && it.work.status == dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED }
        },
        tr("上传准备可能已创建文件夹或将部分文件加入队列。移除此记录不会删除已创建的文件夹或取消队列中的上传任务。", "Upload preparation may have created folders or queued files. Removing this record does not delete created folders or cancel queued uploads.", "アップロード準備によりフォルダーの作成やキューへの追加が行われた可能性があります。この記録を削除しても、作成済みフォルダーやキュー内のアップロードは取り消されません。", "업로드 준비 중 폴더가 생성되었거나 파일이 대기열에 추가되었을 수 있습니다. 이 기록을 삭제해도 생성된 폴더나 대기열의 업로드는 취소되지 않습니다.", "上傳準備可能已建立資料夾或將部分檔案加入佇列。移除此記錄不會刪除已建立的資料夾或取消佇列中的上傳任務。").takeIf {
            items.any { it is TransferItem.UploadPreparing && it.work.hasSideEffects }
        },
    )
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("删除所选的 ${items.size} 项？", "Delete ${items.size} selected items?", "選択した ${items.size} 項目を削除しますか？", "선택한 ${items.size}개 항목을 삭제할까요?", "刪除所選的 ${items.size} 項？")) },
        text = if (notes.isEmpty()) null else ({ Text(notes.joinToString("\n")) }),
        confirmButton = { PikoDialogConfirm(tr("删除", "Delete", "削除", "삭제", "刪除"), onClick = onConfirm, destructive = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

internal fun buildTransferContextActions(
    item: TransferItem,
    ctx: TransferActionContext,
    bulkSelected: List<TransferItem>,
    visibleOrder: List<String>,
    pauseSelected: (() -> Unit)?,
    resumeSelected: (() -> Unit)?,
    onConfirmBulkDelete: () -> Unit,
): List<SheetAction> {
    val state = ctx.state
    if (item.key in state.selectedKeys && bulkSelected.size > 1) {
        val pausableCount = bulkSelected.count(state::isPausable)
        val resumableCount = bulkSelected.count(state::isResumable)
        return buildList {
            pauseSelected?.let { add(SheetAction(Icons.Outlined.Pause, tr("暂停所选 $pausableCount 项", "Pause $pausableCount selected items", "選択した $pausableCount 項目を一時停止", "선택한 ${pausableCount}개 항목 일시 중지", "暫停所選 $pausableCount 項"), it)) }
            resumeSelected?.let { add(SheetAction(Icons.Outlined.Download, tr("继续所选 $resumableCount 项", "Resume $resumableCount selected items", "選択した $resumableCount 項目を再開", "선택한 ${resumableCount}개 항목 계속", "繼續所選 $resumableCount 項"), it)) }
            if (bulkSelected.size < visibleOrder.size) {
                add(SheetAction(Icons.Outlined.SelectAll, tr("全选", "Select all", "すべて選択", "모두 선택", "全選"), { state.selectAll(visibleOrder) }, group = ActionGroup.Select))
            }
            add(SheetAction(Icons.Outlined.Close, tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"), state::clearSelection, group = ActionGroup.Select))
            add(SheetAction(Icons.Outlined.Delete, tr("删除所选 ${bulkSelected.size} 项", "Delete ${bulkSelected.size} selected items", "選択した ${bulkSelected.size} 項目を削除", "선택한 ${bulkSelected.size}개 항목 삭제", "刪除所選 ${bulkSelected.size} 項"), onConfirmBulkDelete, destructive = true))
        }
    }
    return buildTransferItemActions(item, ctx)
}
