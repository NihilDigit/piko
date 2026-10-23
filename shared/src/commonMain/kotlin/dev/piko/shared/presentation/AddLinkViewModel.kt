package dev.piko.shared.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.state.InstantDraft
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState

class AddLinkViewModel private constructor(
    private val session: InstantSession?,
    private val draft: InstantDraft?,
) {
    constructor(session: InstantSession) : this(session, null)
    constructor(draft: InstantDraft) : this(null, draft)
    private var collapsedGeneration by mutableStateOf<Long?>(null)
    var ownerTabId by mutableStateOf<Long?>(null)
        private set
    private var rejectedAction by mutableStateOf<ActionAvailability.Blocked?>(null)
    val blockedAction: ActionAvailability.Blocked? get() = rejectedAction.takeIf { state != null }

    val state: InstantSheetState? get() = draft?.takeUnless { it.isClosed }?.state ?: session?.state
    private val generation: Long get() = draft?.id ?: session?.generation ?: 0L
    val isExpanded: Boolean get() = state != null && generation != collapsedGeneration
    val hasCollapsedWork: Boolean get() = state?.isBlank == false && !isExpanded
    val discardNeedsConfirm: Boolean get() = draft?.endNeedsConfirm ?: (session?.endNeedsConfirm == true)
    val replacementAvailability: ActionAvailability
        get() = if (draft == null && (state?.isSaving == true || state?.batch?.isSaving == true)) {
            ActionAvailability.Blocked("文件正在保存，完成后才能放弃或换一条链接。可以先收起添加链接。")
        } else ActionAvailability.Available

    fun open(initialLink: String = "", tabId: Long? = ownerTabId) {
        if (!allowReplacement()) return
        ownerTabId = tabId
        if (draft != null) {
            if (!draft.isClosed && initialLink.isNotBlank() && draft.state.isBlank) draft.state.updateInput(initialLink)
        } else session?.start(initialLink)
        collapsedGeneration = null
    }

    fun resume(tabId: Long? = ownerTabId) {
        if (state == null) open(tabId = tabId) else {
            if (ownerTabId == null && tabId != null) ownerTabId = tabId
            collapsedGeneration = null
        }
    }

    fun collapse() {
        val draft = state ?: return
        if (draft.isBlank) discard() else collapsedGeneration = generation
    }

    fun discard() {
        if (!allowReplacement()) return
        if (draft != null) draft.close() else session?.end()
        collapsedGeneration = null
        ownerTabId = null
    }

    fun dismissBlockedAction() {
        rejectedAction = null
    }

    private fun allowReplacement(): Boolean {
        val availability = replacementAvailability
        if (availability is ActionAvailability.Blocked) {
            rejectedAction = availability
            return false
        }
        rejectedAction = null
        return true
    }
}
