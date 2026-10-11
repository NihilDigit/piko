package dev.piko.ui

import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.download.FolderListing
import dev.piko.shared.presentation.needsAttention
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.shared.state.ShareSaveCompletion
import dev.piko.shared.state.VaultEntryEditCompletion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf

internal fun ArchiveJobStatus.needsDesktopSession(): Boolean = this is ArchiveJobStatus.NeedsPassword ||
    this is ArchiveJobStatus.Finished && outcome != ArchiveCompletion.SUCCEEDED
internal fun FolderListing.needsDesktopSession(): Boolean = !isListing

/** 桌面关窗后保留仍需人的运行期状态；持久下载/上传失败不依赖这份进程状态。 */
fun PikoServices.hasDesktopResumeContext(): Boolean {
    if (uploadManager.pendingRequest.value != null) return true
    val account = driveRepository.currentAccount ?: return false
    val journal = driveRepository.changes.state.value
    fun undoFailed(ids: List<Long>) = ids.any { journal.undo[it]?.status == DriveChangeJournal.UndoStatus.FAILED }
    return instantSession.state?.isBlank == false || duplicateSession.state != null ||
        trashSession.failure != null || trashSession.completedCount > 0 ||
        (archiveExtractSession.jobs + archiveExtractSession.completedJobs).any { it.status.needsDesktopSession() } ||
        folderVaultSession.records.any { it.needsAttention(it.undoChangeId?.let(journal.undo::get)) } ||
        fileWrites.works.value.any { it.account == account && (it.result?.completion != FileWriteCompletion.SUCCEEDED || undoFailed(it.result?.undoChangeIds.orEmpty())) } ||
        historyEdits.works.value.any { it.account == account && it.result?.completion != HistoryEditCompletion.SUCCEEDED } ||
        vaultEntryEdits.works.value.any { it.account == account && (it.result?.completion != VaultEntryEditCompletion.SUCCEEDED || undoFailed(it.result?.undoChangeIds.orEmpty())) } ||
        shareSaves.works.value.any { it.account == account && (it.result?.completion != ShareSaveCompletion.SUCCEEDED || undoFailed(it.undoChangeIds)) } ||
        instantSaveRecords.attempts.value.any { it.account == account } ||
        uploadManager.preparations.value.any { it.account == account && !it.isRunning } ||
        downloadManager.listings.value.values.any { it.account == account && it.needsDesktopSession() } ||
        instantMagnetRepository.offlineSubmissions.works.value.any { it.account == account && it.status != OfflineSubmitStatus.COMPLETE }
}

/** 是否可在关窗后自动退出，与 Android 的“是否需要执行前台服务”分别判断。 */
fun PikoServices.desktopSessionChanges(): Flow<Boolean> = desktopSessionRetentionChanges(
    snapshotFlow { hasDesktopResumeContext() }, backgroundWorkChanges(), fileWorkChanges(), instantSaveRecords.attempts.map { Unit },
    uploadManager.pendingRequest.map { Unit },
) { hasBackgroundWork() || hasDesktopResumeContext() }

internal fun desktopSessionRetentionChanges(context: Flow<Boolean>, execution: Flow<Boolean>, results: Flow<Unit>,
    attempts: Flow<Unit>, requests: Flow<Unit> = flowOf(Unit), readRetained: () -> Boolean): Flow<Boolean> = combine(context, execution, results, attempts, requests) { _, _, _, _, _ ->
    readRetained()
}.distinctUntilChanged()
