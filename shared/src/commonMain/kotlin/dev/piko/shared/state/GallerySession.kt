package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isVaulted
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 浏览页拥有的画廊：换根目录、换号或释放 owner scope 时停止。每次请求有目录预算，
 * 只有接近已经展示的末尾才继续列目录；没有视频的大目录树也不会在后台一直走到底。
 */
class GallerySession(
    private val driveRepo: PikoDriveRepository,
    ownerScope: CoroutineScope,
) {
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
    var root by mutableStateOf<PikoPathBreadcrumb?>(null)
        private set
    var shown by mutableStateOf(false)
        private set
    var selectedVideoCount by mutableStateOf<Int?>(null)
        private set
    val scopeLabel: String get() = selectedVideoCount?.let { "已选 $it 个视频" } ?: root?.name.orEmpty()
    var account by mutableStateOf<String?>(null)
        private set
    var files by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var isComplete by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var failedFolders by mutableStateOf<Map<String, Throwable>>(emptyMap())
        private set

    private var selectedIds: Set<String>? = null
    private var rootPath = emptyList<PikoPathBreadcrumb>()
    private var folders by mutableStateOf(driveRepo.folderIndex.flow.value)
    private var discovery: VideoDiscovery? = null
    private var scanJob: Job? = null
    private var generation = 0L
    private val pool = linkedMapOf<String, FileStat>()
    private val emitted = hashSetOf<String>()
    private val folderVideos = linkedMapOf<String, List<FileStat>>()
    private val discoveredFolders = hashSetOf<String>()
    private val pendingRefresh = linkedSetOf<String>()
    private val workOf = hashMapOf<String, String>()
    private var requestedAtSize = -1
    private var visibleIds = emptySet<String>()

    init {
        scope.launch { driveRepo.folderIndex.flow.collect { folders = it } }
        scope.launch {
            driveRepo.accountChanges.collect { current ->
                if (root != null && current != account) close()
            }
        }
        scope.launch {
            driveRepo.folderChanges.collect { change ->
                if (root != null && (change.folders == null || change.folders.orEmpty().any { it in discoveredFolders })) {
                    refreshFolders(change.folders?.intersect(discoveredFolders) ?: (folderVideos.keys + requireNotNull(root).id))
                }
            }
        }
    }

    fun open(path: List<PikoPathBreadcrumb>, selected: List<FileStat>? = null) {
        val folder = path.lastOrNull() ?: return close()
        val owner = driveRepo.currentAccount ?: return close()
        val selection = selected?.filter { it.isDiscoverableVideo() && it.hasGalleryCover() }?.distinctBy { it.id }?.toList()
        val ids = selection?.mapTo(HashSet()) { it.id }
        if (root?.id == folder.id && account == owner && selectedIds == ids) {
            rootPath = path
            root = folder
            shown = true
            return
        }
        close()
        shown = true
        rootPath = path
        root = folder
        account = owner
        selectedIds = ids
        selectedVideoCount = selection?.size
        if (selection != null) {
            val request = ++generation
            loading = true
            scanJob = scope.launch {
                try {
                    selection.groupBy { it.parentId }.forEach { (folderId, items) ->
                        val batch = analyzeVideoFolder(folderId, items)
                        if (request != generation) return@launch
                        discoveredFolders += folderId
                        folderVideos[folderId] = items
                        workOf.putAll(batch.workOf)
                    }
                    rebuildCandidates()
                    appendCandidates(Int.MAX_VALUE)
                    hasMore = false
                    isComplete = true
                } finally {
                    if (request == generation) loading = false
                }
            }
            return
        }
        discovery = VideoDiscovery(driveRepo, owner, folder.id)
        discoveredFolders += folder.id
        hasMore = true
        requestMore()
    }

    fun refresh() {
        val rootId = root?.id ?: return
        refreshFolders(folderVideos.keys + rootId)
    }

    fun requestMore() {
        val traversal = discovery ?: return
        if (loading || !hasMore) return
        loading = true
        val request = ++generation
        scanJob = scope.launch {
            val previousSize = files.size
            try {
                var listed = 0
                while (traversal.hasPending && listed < FOLDERS_PER_REQUEST) {
                    // 先看几个来源，再排这一批；后续有足够的已发现候选时无需继续扫描。
                    if (folderVideos.values.asSequence().flatten().map { it.clipContentKey() }.distinct().count { it !in emitted } >= FILES_PER_REQUEST &&
                        (files.isNotEmpty() || listed >= OPENING_FOLDERS)) break
                    val batch = traversal.next() ?: break
                    listed++
                    rememberBatch(batch)
                }
                if (discovery !== traversal || request != generation) return@launch
                rebuildCandidates()
                appendCandidates()
                updateProgress(traversal)
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (discovery === traversal && request == generation) {
                    loading = false
                    if (files.size > previousSize) onVisibleFilesChanged(visibleIds)
                }
            }
        }
    }

    /** 同一批未增加结果时不自动重扫，让空树与失败目录的后续请求保持可控。 */
    fun onVisibleFilesChanged(ids: Set<String>) {
        visibleIds = ids
        if (loading || files.isEmpty() || requestedAtSize == files.size) return
        if (files.takeLast(LOAD_AHEAD_FILES).none { it.id in ids }) return
        requestedAtSize = files.size
        requestMore()
    }

    fun retry() {
        if (loading) return
        val traversal = discovery ?: return
        traversal.retryFailed()
        updateProgress(traversal)
        requestMore()
    }

    fun folderName(folderId: String): String? =
        folders.nodes[folderId]?.name ?: root?.takeIf { it.id == folderId }?.name

    fun pathTo(folderId: String): List<PikoPathBreadcrumb>? {
        val rootId = root?.id ?: return null
        val chain = ArrayDeque<PikoPathBreadcrumb>()
        val visited = hashSetOf<String>()
        var id = folderId
        while (id != rootId) {
            if (!visited.add(id)) return null
            val node = folders.nodes[id] ?: return null
            chain.addFirst(PikoPathBreadcrumb(id, node.name))
            id = node.parentId ?: return null
        }
        return rootPath.map { crumb -> folders.nodes[crumb.id]?.let { crumb.copy(name = it.name) } ?: crumb } + chain
    }

    fun close() {
        generation++
        scanJob?.cancel()
        scanJob = null
        discovery = null
        shown = false
        root = null
        selectedIds = null
        selectedVideoCount = null
        account = null
        rootPath = emptyList()
        files = emptyList()
        pool.clear()
        emitted.clear()
        folderVideos.clear()
        discoveredFolders.clear()
        pendingRefresh.clear()
        workOf.clear()
        loading = false
        isComplete = false
        hasMore = false
        failedFolders = emptyMap()
        requestedAtSize = -1
        visibleIds = emptySet()
    }

    fun dispose() {
        close()
        scope.cancel()
    }

    private fun appendCandidates(limit: Int = FILES_PER_REQUEST) {
        fun candidate(file: FileStat) = FeedCandidate(
            file.id, file.parentId, workOf[file.id] ?: "id:${file.id}", file.clipContentKey(),
        )
        val byId = pool.values.associateBy { it.id }
        val selected = selectSpreadCandidates(
            pool = pool.values.filter { it.clipContentKey() !in emitted }.map(::candidate),
            queued = emptyList(),
            watched = files.takeLast(50).map(::candidate),
            counts = emptyMap(),
            limit = limit,
        ).mapNotNull { byId[it.id] }
        emitted += selected.map { it.clipContentKey() }
        files = files + selected
    }

    private fun rememberBatch(batch: VideoFolderBatch) {
        discoveredFolders += batch.folderId
        batch.failure?.let { error ->
            Result.failure<Unit>(error).logFailure("GallerySession", "画廊列目录失败，可重试")
            return
        }
        discoveredFolders += batch.files.filter { it.isFolder }.map { it.id }
        folderVideos[batch.folderId].orEmpty().forEach { workOf.remove(it.id) }
        folderVideos[batch.folderId] = batch.files.filter { it.isDiscoverableVideo() && it.hasGalleryCover() }
        workOf.putAll(batch.workOf)
    }

    private fun rebuildCandidates() {
        val byContent = folderVideos.values.asSequence().flatten().distinctBy { it.id }.groupBy { it.clipContentKey() }
        val updated = linkedMapOf<String, FileStat>()
        for ((content, copies) in byContent) {
            updated[content] = copies.firstOrNull { it.id == pool[content]?.id }
                ?: copies.firstOrNull { !it.isVaulted } ?: copies.first()
        }
        pool.clear()
        pool.putAll(updated)
        files = files.mapNotNull { updated[it.clipContentKey()] }.distinctBy { it.clipContentKey() }
        emitted.clear()
        emitted += files.map { it.clipContentKey() }
    }

    private fun refreshFolders(ids: Collection<String>) {
        val traversal = discovery ?: return
        pendingRefresh += ids
        if (pendingRefresh.isEmpty()) return
        val resumeOpening = loading && files.isEmpty()
        val previousJob = scanJob
        previousJob?.cancel()
        val request = ++generation
        loading = true
        val wasComplete = isComplete
        scanJob = scope.launch {
            val previousSize = files.size
            try {
                previousJob?.join()
                traversal.rebind(driveRepo, account ?: throw CancellationException("Account changed"))
                val batches = mutableListOf<VideoFolderBatch>()
                val targets = pendingRefresh.toList()
                // 先重列所有受影响层，再判断目录是否移出范围，避免同一子树在范围内移动时短暂消失。
                for (id in targets) {
                    batches += traversal.refreshFolder(id)
                }
                if (discovery !== traversal || request != generation) return@launch
                batches.forEach(::rememberBatch)
                pendingRefresh.removeAll(targets.toSet())
                folders = driveRepo.folderIndex.flow.value
                val rootId = root?.id
                val removed = traversal.folderIds.filterTo(hashSetOf()) { it != rootId && pathTo(it) == null }
                traversal.forgetFolders(removed)
                removed.forEach(folderVideos::remove)
                discoveredFolders.removeAll(removed)
                rebuildCandidates()
                // 更新元数据不重新挑已显示项；删除后的缺口补在尾部。原本已列完时，新内容也留在尾部。
                appendCandidates(if (wasComplete || previousSize == 0) FILES_PER_REQUEST else (previousSize - files.size).coerceAtLeast(0))
                updateProgress(traversal)
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (discovery === traversal && request == generation) {
                    loading = false
                    requestedAtSize = -1
                    onVisibleFilesChanged(visibleIds)
                    if (resumeOpening && files.isEmpty() && hasMore) requestMore()
                }
            }
        }
    }

    private fun updateProgress(traversal: VideoDiscovery) {
        failedFolders = traversal.failedFolders
        hasMore = traversal.hasPending || pool.values.any { it.clipContentKey() !in emitted }
        isComplete = !hasMore && failedFolders.isEmpty()
    }

    private companion object {
        const val FILES_PER_REQUEST = 48
        const val FOLDERS_PER_REQUEST = 16
        const val OPENING_FOLDERS = 4
        const val LOAD_AHEAD_FILES = 24
    }
}
