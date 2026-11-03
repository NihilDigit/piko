package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr
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
    val tabCloseNeedsConfirm: Boolean get() = draft?.hasUncommittedWork ?: (session?.hasUncommittedWork == true)
    val replacementAvailability: ActionAvailability
        get() = if (draft == null && (state?.isSaving == true || state?.batch?.isSaving == true)) {
            ActionAvailability.Blocked(tr(
                "正在保存文件，请待完成后再关闭或更换链接。可先收起面板。",
                "Files are being saved. Wait until saving finishes before discarding or replacing links. You can collapse this panel.",
                "ファイルを保存中です。破棄またはリンクの変更は完了後に行ってください。パネルを折りたたむことは可能です。",
                "파일을 저장 중입니다. 완료된 후에 취소하거나 링크를 변경할 수 있습니다. 패널을 먼저 접어 둘 수 있습니다.",
                "正在儲存檔案，請待完成後再關閉或更換連結。可先收起面板。",
            ))
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

    /**
     * 冷启动恢复上次保存的标签（[DriveWorkspace.restoreLocation]）时，初始占位标签 ID 会被替换为存盘标签 ID。
     * 若添加链接在恢复完成前已打开并绑在初始占位标签上，当场将其迁移到恢复后的活动标签 [fallbackTabId]，
     * 避免冷启动从外部链接首次拉起应用时面板因旧占位 ID 失效而消失。
     */
    fun rebindTabIfMissing(validTabIds: Set<Long>, fallbackTabId: Long?) {
        val owner = ownerTabId ?: return
        if (state != null && validTabIds.isNotEmpty() && owner !in validTabIds) {
            ownerTabId = fallbackTabId?.takeIf { it in validTabIds } ?: validTabIds.first()
        }
    }

    fun collapse() {
        val draft = state ?: return
        if (draft.isBlank) discard() else collapsedGeneration = generation
    }

    fun discard() {
        if (!allowReplacement()) return
        forceDiscard()
    }

    fun forceDiscard() {
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
