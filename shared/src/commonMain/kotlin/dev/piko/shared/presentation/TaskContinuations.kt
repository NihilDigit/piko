package dev.piko.shared.presentation

import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TrashSession

/** Tasks that can be resumed after their page is hidden. Context identity selects the exact task. */
class TaskContinuations(
    private val drafts: () -> List<DraftContinuation>,
    private val duplicates: () -> List<DuplicateContinuation>,
    val cleanup: TrashSession,
) {
    data class DraftContinuation(
        val contextId: Long,
        val addLink: AddLinkViewModel,
        val onResume: () -> Unit,
        val onClose: () -> Unit,
    )

    data class DuplicateContinuation(
        val contextId: Long,
        val finder: DuplicateFinderState,
        val onResume: () -> Unit,
        val onClose: () -> Unit,
    )

    /** Existing single-session callers keep their behavior while workspace owners move to IDs. */
    constructor(addLink: AddLinkViewModel, duplicates: DuplicateSession, cleanup: TrashSession) : this(
        drafts = { listOf(DraftContinuation(0L, addLink, addLink::resume, addLink::discard)) },
        duplicates = {
            duplicates.state?.let { finder ->
                listOf(DuplicateContinuation(0L, finder, {}, { if (duplicates.state === finder) duplicates.end() }))
            }.orEmpty()
        },
        cleanup = cleanup,
    )

    /** Includes drafts attached to retained transfers, which do not get a separate draft row. */
    val allDraftEntries: List<DraftContinuation> get() = drafts()

    val draftEntries: List<DraftContinuation> get() = allDraftEntries.filter { entry ->
        entry.addLink.state?.let { !it.isBlank && !it.hasRetainedInstantAttempt && !it.hasRetainedShareWork && !it.hasRetainedOfflineSubmission } == true
    }
    val duplicateEntries: List<DuplicateContinuation> get() = duplicates()

    val hasDraft: Boolean get() = draftEntries.isNotEmpty()
    val hasCleanup: Boolean get() = cleanup.progress != null || cleanup.failure != null || cleanup.completedCount > 0 || cleanup.isUndoing

    // Kept for existing single-session callers and diagnostics.
    val duplicate: DuplicateFinderState? get() = duplicateEntries.firstOrNull()?.finder

    fun count(kind: TransferKind): Int = when (kind) {
        TransferKind.ALL -> draftEntries.size + fileCount
        TransferKind.FILES -> fileCount
        else -> 0
    }

    private val fileCount: Int get() = duplicateEntries.size + if (hasCleanup) 1 else 0

    private fun current(entry: DraftContinuation): Boolean =
        allDraftEntries.any { it.contextId == entry.contextId && it.addLink === entry.addLink }

    private fun current(entry: DuplicateContinuation): Boolean =
        duplicateEntries.any { it.contextId == entry.contextId && it.finder === entry.finder }

    fun resumeDraft(entry: DraftContinuation) { if (current(entry)) entry.onResume() }
    fun discardDraft(entry: DraftContinuation) { if (current(entry)) entry.onClose() }
    fun resumeDuplicate(entry: DuplicateContinuation) { if (current(entry)) entry.onResume() }
    fun stopDuplicate(entry: DuplicateContinuation) { if (current(entry)) entry.finder.stopScan() }
    fun discardDuplicate(entry: DuplicateContinuation) { if (current(entry)) entry.onClose() }

    fun stopDuplicate(expected: DuplicateFinderState) {
        duplicateEntries.firstOrNull { it.finder === expected }?.let(::stopDuplicate)
    }

    fun discardDuplicate(expected: DuplicateFinderState) {
        duplicateEntries.firstOrNull { it.finder === expected }?.let(::discardDuplicate)
    }
}
