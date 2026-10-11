package dev.piko.shared.presentation

import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TrashSession

/** 尚可继续编辑、查看或处理的会话；导航方式由各端工作区决定。 */
class TaskContinuations(
    val addLink: AddLinkViewModel,
    private val duplicates: DuplicateSession,
    val cleanup: TrashSession,
) {
    val duplicate: DuplicateFinderState? get() = duplicates.state
    val hasDraft: Boolean get() = addLink.state?.let { !it.isBlank && !it.hasRetainedInstantAttempt && !it.hasRetainedShareWork && !it.hasRetainedOfflineSubmission } == true
    val hasCleanup: Boolean get() = cleanup.progress != null || cleanup.failure != null || cleanup.completedCount > 0 || cleanup.isUndoing

    fun count(kind: TransferKind): Int = when (kind) {
        TransferKind.ALL -> (if (hasDraft) 1 else 0) + fileCount
        TransferKind.FILES -> fileCount
        else -> 0
    }

    private val fileCount: Int get() = (if (duplicate != null) 1 else 0) + (if (hasCleanup) 1 else 0)

    fun stopDuplicate(expected: DuplicateFinderState) {
        if (duplicates.state === expected) expected.stopScan()
    }

    fun discardDuplicate(expected: DuplicateFinderState) {
        if (duplicates.state === expected) duplicates.end()
    }
}
