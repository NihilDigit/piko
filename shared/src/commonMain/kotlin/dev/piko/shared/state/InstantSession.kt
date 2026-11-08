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

data class LinkSaveCompletion(val id: Long, val outcome: InstantSaveOutcome, val draftId: Long = id)
data class InstantSaveExecution(val draftId: Long, val trackedOffline: Boolean, val trackedInstantAttempt: Boolean)

/** One editable import. Closing it removes the editor immediately. */
class InstantDraft internal constructor(
    val id: Long,
    initialState: InstantSheetState,
    private val draftScope: CoroutineScope,
    private val saveScope: CoroutineScope,
    private val onClosed: (InstantDraft) -> Unit,
) {
    var state by mutableStateOf(initialState)
        internal set
    internal val submitted = androidx.compose.runtime.mutableStateListOf<InstantSheetState>()
    internal val hasRecoverableSave: Boolean get() = submitted.any {
        it.hasRetainedInstantAttempt && (it.hasInstantRetry || it.saveIssue?.requiresInspection == true)
    }
    internal val hasExecutingSave: Boolean get() = submitted.any { it.hasLocalSave || it.shareSave?.isSaving == true } || state.hasLocalSave || state.shareSave?.isSaving == true

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
        if (hasExecutingSave || hasRecoverableSave) {
            // The save owns its own scope and preview lease until its receipt is recorded.
            saveScope.launch {
                snapshotFlow { hasExecutingSave || hasRecoverableSave }.first { !it }
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
            return ownedDrafts.value.flatMap { draft ->
                (draft.submitted + draft.state).filter { it.hasLocalSave }.map {
                    InstantSaveExecution(draft.id, trackedOffline = !it.hasUntrackedLocalSave,
                        trackedInstantAttempt = it.hasTrackedInstantAttempt)
                }
            }
        }

    // Compatibility adapter while the UI moves from a single editor to draft identities.
    private var legacyDraft by mutableStateOf<InstantDraft?>(null)
    val state: InstantSheetState? get() = (legacyDraft?.takeUnless { it.isClosed } ?: drafts.lastOrNull { !it.isClosed })?.state
    val endNeedsConfirm: Boolean get() = legacyDraft?.endNeedsConfirm == true
    val hasUncommittedWork: Boolean get() = legacyDraft?.hasUncommittedWork == true

    private val _pendingCompletions = MutableStateFlow<List<LinkSaveCompletion>>(emptyList())
    val pendingCompletions: StateFlow<List<LinkSaveCompletion>> = _pendingCompletions.asStateFlow()
    private var completionId = 0L
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
        val editorScopes = mutableMapOf<InstantSheetState, Pair<CoroutineScope, CoroutineScope>>()
        fun createEditor(link: String): InstantSheetState {
            fun child(parent: CoroutineScope) = CoroutineScope(parent.coroutineContext + kotlinx.coroutines.SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]))
            val editorScope = child(saveScope)
            val executionScope = child(saveScope)
            val state = if (newStateForDraft == null || targetContext == null) newState(executionScope, link)
                else newStateForDraft.invoke(editorScope, executionScope, link, targetContext)
            editorScopes[state] = editorScope to executionScope
            return state
        }
        val state = createEditor(initialLink)
        val draft = InstantDraft(++generation, state, draftScope, saveScope) { closed ->
            if (!closed.state.hasLocalSave && closed.state.shareSave?.isSaving != true) {
                editorScopes[closed.state]?.let { (editing, executing) -> editing.cancel(); executing.cancel() }
                editorScopes -= closed.state
            }
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
        fun observe(editor: InstantSheetState) {
            fun detach() {
                if (draft.isClosed || draft.state !== editor) return
                draft.submitted += editor
                val next = createEditor("")
                draft.state = next
                executionRevision++
                observe(next)
                saveScope.launch {
                    snapshotFlow { editor.hasLocalSave || editor.shareSave?.isSaving == true ||
                        editor.hasRetainedInstantAttempt && (editor.hasInstantRetry || editor.saveIssue?.requiresInspection == true) }.first { !it }
                    draft.submitted -= editor
                    editorScopes[editor]?.let { (editing, executing) -> editing.cancel(); executing.cancel() }
                    editorScopes -= editor
                    executionRevision++
                }
            }
            editor.onSaveAccepted = ::detach
            requireNotNull(editorScopes[editor]).second.launch {
                snapshotFlow { editor.shareSave?.isSaving == true || editor.batch?.rows?.any { it.state.shareSave?.isSaving == true } == true }
                    .first { it }
                detach()
            }
            requireNotNull(editorScopes[editor]).second.launch(start = CoroutineStart.UNDISPATCHED) {
                editor.outcomes.collect { outcome ->
                    if (completionEpoch.value == epoch) {
                        _pendingCompletions.update { it + LinkSaveCompletion(++completionId, outcome, draft.id) }
                    }
                }
            }
        }
        observe(state)
        return draft
    }

    fun canRetryAttempt(id: String): Boolean = ownedDrafts.value.any { draft ->
        draft.submitted.any { it.containsInstantAttempt(id) && !it.isSaving && it.retryFileCount > 0 }
    }
    fun retryAttempt(id: String): Boolean = ownedDrafts.value.asSequence().flatMap { it.submitted.asSequence() }
        .firstOrNull { it.containsInstantAttempt(id) }?.retrySubmittedAttempt() == true

    fun start(initialMagnet: String = "") {
        end()
        legacyDraft = openDraft(initialMagnet)
    }

    fun end() {
        legacyDraft?.close()
        legacyDraft = null
    }
}
