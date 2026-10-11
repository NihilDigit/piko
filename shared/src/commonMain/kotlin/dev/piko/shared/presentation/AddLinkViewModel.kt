package dev.piko.shared.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState

class AddLinkViewModel(private val session: InstantSession) {
    private var collapsedGeneration by mutableStateOf<Long?>(null)
    private var rejectedAction by mutableStateOf<ActionAvailability.Blocked?>(null)
    val blockedAction: ActionAvailability.Blocked? get() = rejectedAction.takeIf { state != null }

    val state: InstantSheetState? get() = session.state
    val isExpanded: Boolean get() = state != null && session.generation != collapsedGeneration
    val hasCollapsedWork: Boolean get() = state?.isBlank == false && !isExpanded
    val discardNeedsConfirm: Boolean get() = session.endNeedsConfirm
    val replacementAvailability: ActionAvailability
        get() = if (state?.isSaving == true || state?.batch?.isSaving == true) {
            ActionAvailability.Blocked("文件正在保存，完成后才能放弃或换一条链接。可以先收起添加链接。")
        } else ActionAvailability.Available

    fun open(initialLink: String = "") {
        if (!allowReplacement()) return
        session.start(initialLink)
        collapsedGeneration = null
    }

    fun resume() {
        if (state == null) open() else collapsedGeneration = null
    }

    fun collapse() {
        val draft = state ?: return
        if (draft.isBlank) discard() else collapsedGeneration = session.generation
    }

    fun discard() {
        if (!allowReplacement()) return
        session.end()
        collapsedGeneration = null
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
