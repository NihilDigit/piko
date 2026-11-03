package dev.piko.shared.presentation

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.FolderReferenceSource
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScrollAnchor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 失效位置整帧丢弃，不将其截短成从未访问过的云端父目录。 */
fun FolderHistory.retainingValid(
    currentStack: List<PikoPathBreadcrumb> = emptyList(),
    isValid: (String) -> Boolean,
): FolderHistory {
    fun retain(frames: List<List<PikoPathBreadcrumb>>): List<List<PikoPathBreadcrumb>> {
        val kept = mutableListOf<List<PikoPathBreadcrumb>>()
        for (frame in frames) {
            if (frame.isNotEmpty() && frame.all { isValid(it.id) } && kept.lastOrNull() != frame) kept += frame
        }
        if (kept.lastOrNull() == currentStack) kept.removeAt(kept.lastIndex)
        return kept
    }
    return FolderHistory(retain(back), retain(forward))
}

/** 每个浏览页面自己的位置、历史与滚动记忆，文件数据和改动仍由仓库共享。 */
interface DriveNavigation {
    val rootDepth: Int get() = 1
    val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>>
    val historyFlow: StateFlow<FolderHistory>
    val pendingHighlights: StateFlow<Set<String>>
    fun takePendingHighlight(): Set<String>
    fun requestHighlight(ids: Set<String>)
    suspend fun restoreLocation(): Boolean
    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>)
    fun updateFolderStack(stack: List<PikoPathBreadcrumb>)
    fun navigateThen(next: List<PikoPathBreadcrumb>, action: () -> Unit)
    fun pushFolder(id: String, name: String)
    fun popFolder(): PikoPathBreadcrumb?
    fun popToBreadcrumb(index: Int): PikoPathBreadcrumb?
    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb)
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun scrollAnchor(folderId: String): ScrollAnchor?
    fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor)
}

/** 资料页不占用文件标签，也不写入文件页的上次位置。 */
class LibraryNavigation(
    private val repository: PikoDriveRepository,
    library: DriveLibrary?,
    scope: CoroutineScope,
    initialStack: List<PikoPathBreadcrumb> = listOf(requireNotNull(library).crumb),
) : DriveNavigation, FolderReferenceSource {
    private val account = repository.currentAccount
    override val rootDepth = initialStack.size
    private val stack = MutableStateFlow(initialStack)
    override val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = stack.asStateFlow()
    private val history = MutableStateFlow(FolderHistory())
    override val historyFlow: StateFlow<FolderHistory> = history.asStateFlow()
    private val highlights = MutableStateFlow<Set<String>>(emptySet())
    override val pendingHighlights: StateFlow<Set<String>> = highlights.asStateFlow()
    private val anchors = mutableMapOf<String, ScrollAnchor>()

    init {
        repository.registerFolderReferences(this)
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    override fun takePendingHighlight() = highlights.value.also { highlights.value = emptySet() }
    override fun requestHighlight(ids: Set<String>) { highlights.value = ids }
    override suspend fun restoreLocation() = false
    override fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) this.stack.value = repository.folderIndex.resolve(stack)
    }
    internal fun restoreLocation(stack: List<PikoPathBreadcrumb>, history: FolderHistory) {
        if (stack.isEmpty()) return
        this.stack.value = repository.folderIndex.resolve(repository.localMounts.restoredStack(stack))
        this.history.value = history.retainingValid(this.stack.value, repository.localMounts::isMountedCached)
            .resolved(repository.folderIndex)
    }
    override fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        val next = repository.folderIndex.resolve(stack)
        val previous = folderStackFlow.value
        if (next == previous) return
        history.value = history.value.visited(previous)
        this.stack.value = next
    }
    override fun navigateThen(next: List<PikoPathBreadcrumb>, action: () -> Unit) = action()
    override fun pushFolder(id: String, name: String) = updateFolderStack(folderStackFlow.value + PikoPathBreadcrumb(id, name))
    override fun popFolder(): PikoPathBreadcrumb? {
        val stack = folderStackFlow.value
        if (stack.size <= 1) return null
        updateFolderStack(stack.dropLast(1))
        return stack.last()
    }
    override fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        val stack = folderStackFlow.value
        if (index !in 0 until stack.lastIndex) return null
        updateFolderStack(stack.take(index + 1))
        return stack[index + 1]
    }
    override fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) = updateFolderStack(
        if (breadcrumb.id.isEmpty()) listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        else listOf(PikoDriveRepository.ROOT_BREADCRUMB, breadcrumb),
    )
    override fun goBack(): Boolean {
        refreshFolderReferences()
        val history = historyFlow.value
        val target = history.back.lastOrNull() ?: return false
        this.history.value = FolderHistory(history.back.dropLast(1), history.forward + listOf(folderStackFlow.value))
        stack.value = target
        return true
    }
    override fun goForward(): Boolean {
        refreshFolderReferences()
        val history = historyFlow.value
        val target = history.forward.lastOrNull() ?: return false
        this.history.value = FolderHistory(history.back + listOf(folderStackFlow.value), history.forward.dropLast(1))
        stack.value = target
        return true
    }
    override fun scrollAnchor(folderId: String) = anchors[folderId]
    override fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) { anchors[folderId] = anchor }
    override val retainedPaths: List<List<PikoPathBreadcrumb>>
        get() = listOf(stack.value) + history.value.back + history.value.forward

    override fun refreshFolderReferences() {
        if (account != repository.currentAccount) return
        stack.value = repository.folderIndex.resolve(repository.localMounts.restoredStack(stack.value))
        history.value = history.value.retainingValid(stack.value, repository.localMounts::isMountedCached)
            .resolved(repository.folderIndex)
    }

    fun close() = repository.unregisterFolderReferences(this)
}
