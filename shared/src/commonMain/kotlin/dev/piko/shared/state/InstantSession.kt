package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkSaveCompletion(val id: Long, val outcome: InstantSaveOutcome)
data class InstantSaveExecution(val draftId: Long, val trackedOffline: Boolean, val trackedInstantAttempt: Boolean)

/** One editable import. Closing it removes the editor immediately. */
class InstantDraft internal constructor(
    val id: Long,
    val state: InstantSheetState,
    private val draftScope: CoroutineScope,
    private val saveScope: CoroutineScope,
    private val onClosed: (InstantDraft) -> Unit,
) {
    var isClosed by mutableStateOf(false)
        private set

    val endNeedsConfirm: Boolean
        get() = !state.hasLocalSave && state.shareSave?.isSaving != true && state.hasPickedFiles

    val hasUncommittedWork: Boolean
        get() = !state.hasLocalSave && state.shareSave?.isSaving != true && (!state.isBlank || state.hasPickedFiles)

    fun close() {
        if (isClosed) return
        isClosed = true
        onClosed(this)
        draftScope.cancel()
        if (state.hasLocalSave) {
            // The save owns its own scope and preview lease until its receipt is recorded.
            saveScope.launch {
                snapshotFlow { state.hasLocalSave }.first { !it }
                saveScope.cancel()
            }
        } else {
            saveScope.cancel()
        }
    }

    internal fun cancelForAccountLeave() {
        if (!isClosed) {
            isClosed = true
            onClosed(this)
        }
        draftScope.cancel()
        saveScope.cancel()
    }
}

/** Account-level registry of independent imports and their unacknowledged completions. */
class InstantSession(
    private val newScope: () -> CoroutineScope,
    private val newStateForDraft: ((draftScope: CoroutineScope, saveScope: CoroutineScope, initialMagnet: String, targetContext: SaveTargetContext) -> InstantSheetState)? = null,
    private val newState: (scope: CoroutineScope, initialMagnet: String) -> InstantSheetState,
) {
    var generation by mutableLongStateOf(0L)
        private set

    var drafts by mutableStateOf<List<InstantDraft>>(emptyList())
        private set
    private val ownedDrafts = MutableStateFlow<List<InstantDraft>>(emptyList())
    private var executionRevision by mutableLongStateOf(0L)

    /** Accepted saves remain here after their editor closes, until work settles or the account leaves. */
    val executingSaves: List<InstantSaveExecution>
        get() {
            executionRevision
            return ownedDrafts.value.mapNotNull { draft ->
                draft.takeIf { it.state.hasLocalSave }?.let {
                    InstantSaveExecution(it.id, trackedOffline = !it.state.hasUntrackedLocalSave,
                        trackedInstantAttempt = it.state.hasTrackedInstantAttempt)
                }
            }
        }

    // Compatibility adapter while the UI moves from a single editor to draft identities.
    private var legacyDraft by mutableStateOf<InstantDraft?>(null)
    val state: InstantSheetState? get() = legacyDraft?.takeUnless { it.isClosed }?.state
    val endNeedsConfirm: Boolean get() = legacyDraft?.endNeedsConfirm == true
    val hasUncommittedWork: Boolean get() = legacyDraft?.hasUncommittedWork == true

    private val _pendingCompletions = MutableStateFlow<List<LinkSaveCompletion>>(emptyList())
    val pendingCompletions: StateFlow<List<LinkSaveCompletion>> = _pendingCompletions.asStateFlow()
    private val completionEpoch = MutableStateFlow(0L)

    fun acknowledgeCompletion(id: Long) {
        _pendingCompletions.update { pending -> pending.filterNot { it.id == id } }
    }

    fun clear() {
        completionEpoch.update { it + 1 }
        ownedDrafts.value.forEach(InstantDraft::cancelForAccountLeave)
        ownedDrafts.value = emptyList()
        executionRevision++
        legacyDraft = null
        _pendingCompletions.value = emptyList()
    }

    fun openDraft(initialLink: String = "", targetContext: SaveTargetContext? = null): InstantDraft {
        val draftScope = newScope()
        val saveScope = newScope()
        val state = if (newStateForDraft == null || targetContext == null) newState(draftScope, initialLink)
            else newStateForDraft.invoke(draftScope, saveScope, initialLink, targetContext)
        val draft = InstantDraft(++generation, state, draftScope, saveScope) { closed ->
            drafts = drafts - closed
            if (legacyDraft === closed) legacyDraft = null
        }
        ownedDrafts.update { it + draft }
        executionRevision++
        saveScope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            ownedDrafts.update { owned -> owned - draft }
            executionRevision++
        }
        drafts = drafts + draft
        val epoch = completionEpoch.value
        saveScope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.outcomes.collect { outcome ->
                if (completionEpoch.value == epoch) {
                    _pendingCompletions.update { it + LinkSaveCompletion(draft.id, outcome) }
                }
                draft.close()
            }
        }
        return draft
    }

    fun start(initialMagnet: String = "") {
        end()
        legacyDraft = openDraft(initialMagnet)
    }

    fun end() {
        legacyDraft?.close()
        legacyDraft = null
    }
}
