package dev.piko.shared.presentation

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScrollAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一个标签的导航投影，更新只作用于所属标签，不跟随工作区切换到其他标签。 */
class TabNavigation internal constructor(private val workspace: DriveWorkspace, val tabId: Long) : DriveNavigation {
    private val initial = requireNotNull(workspace.tabSnapshot(tabId))
    private val stack = MutableStateFlow(initial.stack)
    override val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = stack.asStateFlow()
    private val history = MutableStateFlow(initial.history)
    override val historyFlow: StateFlow<FolderHistory> = history.asStateFlow()
    private val highlights = MutableStateFlow(workspace.pendingForTab(tabId))
    override val pendingHighlights: StateFlow<Set<String>> = highlights.asStateFlow()
    private var closed = false
    private val available: Boolean get() = !closed && workspace.tabSnapshot(tabId) != null

    internal fun update(tab: DriveTab) {
        if (closed) return
        stack.value = tab.stack
        history.value = tab.history
    }

    internal fun updateHighlights(ids: Set<String>) {
        if (!closed) highlights.value = ids
    }

    override fun requestHighlight(ids: Set<String>) {
        if (available) workspace.requestHighlightForTab(tabId, ids)
    }

    override fun takePendingHighlight(): Set<String> =
        if (available) workspace.takePendingForTab(tabId) else emptySet()

    override suspend fun restoreLocation(): Boolean =
        available && workspace.activeTabId.value == tabId && workspace.restoreLocation()

    override fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (available) workspace.updateTabStack(tabId, stack, restoring = true)
    }

    override fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (available) workspace.updateTabStack(tabId, stack)
    }

    override fun navigateThen(next: List<PikoPathBreadcrumb>, action: () -> Unit) {
        if (!available) return
        if (workspace.activeTabId.value == tabId) workspace.navigateThen(next, action) else action()
    }

    override fun pushFolder(id: String, name: String) = updateFolderStack(stack.value + PikoPathBreadcrumb(id, name))

    override fun popFolder(): PikoPathBreadcrumb? {
        val current = stack.value
        if (!available || current.size <= 1) return null
        updateFolderStack(current.dropLast(1))
        return current.last()
    }

    override fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        val current = stack.value
        if (!available || index !in 0 until current.lastIndex) return null
        updateFolderStack(current.take(index + 1))
        return current[index + 1]
    }

    override fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) = updateFolderStack(
        if (breadcrumb.id.isEmpty()) listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        else listOf(PikoDriveRepository.ROOT_BREADCRUMB, breadcrumb),
    )

    override fun goBack(): Boolean = available && workspace.moveTabHistory(tabId, backwards = true)
    override fun goForward(): Boolean = available && workspace.moveTabHistory(tabId, backwards = false)

    override fun scrollAnchor(folderId: String): ScrollAnchor? = workspace.scrollAnchorForTab(tabId, folderId)
    override fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) {
        if (available) workspace.saveScrollAnchorForTab(tabId, folderId, anchor)
    }

    fun close() {
        closed = true
        workspace.releaseNavigation(this)
    }
}
