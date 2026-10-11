package dev.piko.shared.presentation

import dev.piko.shared.data.FolderReferenceSource
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.RecentFolders
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.archive
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.library
import dev.piko.shared.data.outsideArchives
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.state.SaveTargetContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 移动端的主文件上下文。只有一份位置和历史，不创建或订阅桌面标签。 */
class MobileFileWorkspace(
    private val repository: PikoDriveRepository,
    private val account: String,
    private val cacheStore: PikoCacheStore? = null,
    parentScope: CoroutineScope,
) : FileWorkspace, FolderReferenceSource {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val navigation = LibraryNavigation(repository, null, scope, listOf(PikoDriveRepository.ROOT_BREADCRUMB))
    private val recent = RecentFolders(cacheStore, scope)
    private var closed = false
    private val available: Boolean get() = !closed && scope.isActive && repository.currentAccount == account
    private var pendingSave: Job? = null
    private var lastRequestedPath: List<PikoPathBreadcrumb>? = null
    private var highlightRevision = 0L
    private var hasLocation = false

    override val clipboard = DriveClipboardState()
    override val folderStackFlow get() = navigation.folderStackFlow
    override val historyFlow get() = navigation.historyFlow
    override val pendingHighlights get() = navigation.pendingHighlights
    override val recentFoldersFlow: StateFlow<List<List<PikoPathBreadcrumb>>> get() = recent.flow
    override val saveTargetContext = SaveTargetContext(
        currentFolder = { if (available) saveFolder(folderStackFlow.value) else PikoDriveRepository.ROOT_BREADCRUMB },
        folders = folderStackFlow.map(::saveFolder).distinctUntilChanged(),
    )

    init {
        check(repository.currentAccount == account)
        repository.registerFolderReferences(this)
        recent.switchAccount(account)
        job.invokeOnCompletion { repository.unregisterFolderReferences(this) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.accountChanges.first { it != account }
            close()
        }
    }

    private fun saveFolder(path: List<PikoPathBreadcrumb>) =
        path.lastOrNull { isDriveFolderId(it.id) } ?: PikoDriveRepository.ROOT_BREADCRUMB
    private fun rooted(path: List<PikoPathBreadcrumb>) =
        if (path.firstOrNull()?.id == "") path else listOf(PikoDriveRepository.ROOT_BREADCRUMB) + path

    private fun changed() {
        if (!available) return
        hasLocation = true
        val path = folderStackFlow.value
        if (path.library == null && path.archive == null) recent.visited(path)
        val savedPath = path.outsideArchives()
        if (lastRequestedPath == savedPath) return
        lastRequestedPath = savedPath
        val store = cacheStore ?: return
        val saved = SavedMobileLocation(savedPath.map { MobileCrumb(it.id, it.name) })
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(1_000)
            if (available) runSuspendCatching { store.write(key("mobile-drive-location-"), json.encodeToString(SavedMobileLocation.serializer(), saved)) }
        }
    }

    override suspend fun restoreLocation(): Boolean {
        val store = cacheStore ?: return false
        if (!available || folderStackFlow.value.singleOrNull()?.id != "" || pendingHighlights.value.isNotEmpty()) return false
        val initial = folderStackFlow.value
        val revision = highlightRevision
        val path = runSuspendCatching {
            val own = store.read(key("mobile-drive-location-"))
            if (own != null) json.decodeFromString(SavedMobileLocation.serializer(), own).path
            else {
                // v1.1.0/v1.2.0 的移动端沿用标签缓存，迁移时只取一个真实文件位置，不恢复标签或资料页。
                val legacy = store.read(key("drive-tabs-"))?.let { json.decodeFromString(LegacyMobileTabs.serializer(), it) }
                    ?: return@runSuspendCatching emptyList()
                val ordered = listOfNotNull(legacy.tabs.getOrNull(legacy.active)) + legacy.tabs
                ordered.firstOrNull { crumbs -> crumbs.isNotEmpty() && crumbs.map { PikoPathBreadcrumb(it.id, it.name) }.library == null }.orEmpty()
            }
        }.getOrNull().orEmpty().map { PikoPathBreadcrumb(it.id, it.name) }.outsideArchives()
        if (!available || folderStackFlow.value !== initial || revision != highlightRevision || path.isEmpty() || path.library != null) return false
        navigation.restoreFolderStack(rooted(path))
        changed()
        return true
    }

    override fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (!available || stack.isEmpty() || stack.library != null) return
        navigation.updateFolderStack(rooted(stack))
        changed()
    }

    override fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (!available || stack.isEmpty() || stack.library != null) return
        navigation.restoreFolderStack(rooted(stack))
        changed()
    }

    override fun navigateThen(next: List<PikoPathBreadcrumb>, action: () -> Unit) { if (available) action() }
    override fun pushFolder(id: String, name: String) = updateFolderStack(folderStackFlow.value + PikoPathBreadcrumb(id, name))
    override fun popFolder(): PikoPathBreadcrumb? =
        if (!available) null else navigation.popFolder().also { changed() }
    override fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? =
        if (!available) null else navigation.popToBreadcrumb(index).also { changed() }
    override fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) = updateFolderStack(
        if (breadcrumb.id.isEmpty()) listOf(PikoDriveRepository.ROOT_BREADCRUMB) else listOf(PikoDriveRepository.ROOT_BREADCRUMB, breadcrumb),
    )
    override fun goBack(): Boolean = available && navigation.goBack().also { if (it) changed() }
    override fun goForward(): Boolean = available && navigation.goForward().also { if (it) changed() }
    override fun scrollAnchor(folderId: String): ScrollAnchor? = navigation.scrollAnchor(folderId)
    override fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) { if (available) navigation.saveScrollAnchor(folderId, anchor) }
    override fun takePendingHighlight(): Set<String> = if (available) navigation.takePendingHighlight() else emptySet()
    override fun requestHighlight(ids: Set<String>) {
        if (!available) return
        highlightRevision++
        navigation.requestHighlight(ids)
    }
    override fun removeRecentFolder(folderId: String) { if (available) recent.remove(folderId) }

    private class Location(val owner: MobileFileWorkspace, val path: List<PikoPathBreadcrumb>, val history: FolderHistory) : FileLocation
    override fun currentLocation(): FileLocation = Location(this, folderStackFlow.value, historyFlow.value)
    override fun returnTo(location: FileLocation) {
        if (!available || location !is Location || location.owner !== this) return
        navigation.restoreLocation(location.path, location.history)
        changed()
    }

    override val retainedPaths get() = navigation.retainedPaths
    override val referencedPaths get() = retainedPaths + recent.flow.value
    override fun refreshFolderReferences() {
        if (!available) return
        navigation.refreshFolderReferences()
        recent.rewrite { paths -> paths.mapNotNull { path -> repository.folderIndex.resolve(path).takeIf { it.last().id == path.last().id } } }
        if (hasLocation) changed()
    }

    fun close() {
        if (closed) return
        closed = true
        clipboard.clear()
        navigation.close()
        repository.unregisterFolderReferences(this)
        scope.cancel()
    }

    private fun key(prefix: String) = prefix + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"
}

@Serializable private class MobileCrumb(val id: String, val name: String)
@Serializable private class SavedMobileLocation(val path: List<MobileCrumb>)
@Serializable private class LegacyMobileTabs(val tabs: List<List<MobileCrumb>>, val active: Int)
private val json = Json { ignoreUnknownKeys = true }
