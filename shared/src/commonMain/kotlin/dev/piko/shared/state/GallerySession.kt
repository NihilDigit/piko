package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isVaulted
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import dev.piko.shared.presentation.localizedName
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
    var includeImages by mutableStateOf(true)
        private set
    var selectedVideoCount by mutableStateOf<Int?>(null)
        private set
    private var selectedImageCount by mutableStateOf(0)
    private var selectedMediaTotal by mutableStateOf<Int?>(null)
    val scopeLabel: String
        get() {
            val total = selectedMediaTotal ?: return root?.localizedName.orEmpty()
            val videos = selectedVideoCount ?: 0
            val images = selectedImageCount
            return when {
                images == 0 -> tr("已选 $videos 个视频", "Selected ${enCount(videos, "video", "videos")}", "$videos 件の動画を選択", "동영상 ${videos}개 선택됨", "已選 $videos 部影片")
                videos == 0 -> tr("已选 $images 张图片", "Selected ${enCount(images, "image", "images")}", "$images 枚の画像を選択", "이미지 ${images}장 선택됨", "已選 $images 張圖片")
                else -> tr("已选 $total 项媒体", "Selected ${enCount(total, "media item", "media items")}", "$total 件のメディアを選択", "미디어 ${total}개 선택됨", "已選 $total 項媒體")
            }
        }
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
    private var selectedParentIds: Set<String> = emptySet()
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

    fun updateIncludeImages(enabled: Boolean) {
        if (includeImages == enabled) return
        includeImages = enabled
        if (root == null) return
        rebuildCandidates()
        val traversal = discovery
        if (enabled) {
            appendCandidates(if (selectedIds != null) Int.MAX_VALUE else FILES_PER_REQUEST)
        }
        if (traversal != null) {
            updateProgress(traversal)
            requestedAtSize = -1
            onVisibleFilesChanged(visibleIds)
            if (files.isEmpty() && hasMore && !loading) requestMore()
        }
    }

    fun open(path: List<PikoPathBreadcrumb>, selected: List<FileStat>? = null, showImages: Boolean = includeImages) {
        includeImages = showImages
        val folder = path.lastOrNull() ?: return close()
        val owner = driveRepo.currentAccount ?: return close()
        val selection = selected?.filter { it.isDiscoverableGalleryMedia(showImages) && it.hasGalleryCover() }?.distinctBy { it.id }?.toList()
        val ids = selection?.mapTo(HashSet()) { it.id }
        if (root?.id == folder.id && account == owner && selectedIds == ids) {
            rootPath = path
            root = folder
            shown = true
            return
        }
        close()
        includeImages = showImages
        shown = true
        rootPath = path
        root = folder
        account = owner
        selectedIds = ids
        selectedParentIds = selection?.mapTo(LinkedHashSet()) { it.parentId }.orEmpty()
        selectedMediaTotal = selection?.size
        selectedVideoCount = selection?.count { it.isDiscoverableVideo() }
        selectedImageCount = selection?.count { !it.isDiscoverableVideo() && it.isDiscoverableImage() } ?: 0
        if (selection != null) {
            discoveredFolders += selectedParentIds
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
        refreshFolders(if (selectedIds != null) selectedParentIds else folderVideos.keys + rootId)
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
                    val uniquePending = folderVideos.values.asSequence().flatten()
                        .filter { it.isDiscoverableGalleryMedia(includeImages) }
                        .map { it.clipContentKey() }.distinct().count { it !in emitted }
                    if (uniquePending >= FILES_PER_REQUEST && (files.isNotEmpty() || listed >= OPENING_FOLDERS)) break
                    val budget = if (files.isEmpty() && listed < OPENING_FOLDERS) {
                        (OPENING_FOLDERS - listed).coerceAtMost(DISCOVERY_CONCURRENCY)
                    } else {
                        (FOLDERS_PER_REQUEST - listed).coerceAtMost(DISCOVERY_CONCURRENCY)
                    }
                    val batches = traversal.nextBatch(budget, DISCOVERY_CONCURRENCY)
                    if (batches.isEmpty()) break
                    listed += batches.size
                    batches.forEach(::rememberBatch)
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
        val traversal = discovery
        if (traversal == null) {
            if (selectedIds != null && failedFolders.isNotEmpty()) refreshFolders(failedFolders.keys.toList())
            return
        }
        traversal.retryFailed()
        updateProgress(traversal)
        requestMore()
    }

    fun folderName(folderId: String): String? =
        driveRepo.knownLocalPath(folderId)?.lastOrNull()?.name ?: folders.nodes[folderId]?.name ?: root?.takeIf { it.id == folderId }?.name

    fun pathTo(folderId: String): List<PikoPathBreadcrumb>? {
        driveRepo.knownLocalPath(folderId)?.let { return it }
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
        selectedParentIds = emptySet()
        selectedVideoCount = null
        selectedImageCount = 0
        selectedMediaTotal = null
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
        folderVideos[batch.folderId] = batch.files.filter { it.isDiscoverableGalleryMedia(includeImages = true) && it.hasGalleryCover() }
        workOf.putAll(batch.workOf)
    }

    private fun rebuildCandidates() {
        val byContent = folderVideos.values.asSequence().flatten()
            .filter { it.isDiscoverableGalleryMedia(includeImages) && it.hasGalleryCover() }
            .distinctBy { it.id }.groupBy { it.clipContentKey() }
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
        val traversal = discovery
        val selectedVideoIds = selectedIds.orEmpty()
        if (traversal == null && selectedVideoIds.isEmpty()) return
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
                if (traversal == null) {
                    // 初始分析可能被刷新取消，尚未分析的父目录也必须补齐。
                    val targets = selectedParentIds.filter { it in pendingRefresh || it !in folderVideos }
                    for (parentId in targets) {
                        val listing = driveRepo.listBrowsable(parentId, PikoFileSortOrder.NAME_ASC)
                        if (request != generation) return@launch
                        listing.onSuccess { latest ->
                            val media = latest.filter {
                                it.id in selectedVideoIds && it.isDiscoverableGalleryMedia(includeImages = true) && it.hasGalleryCover()
                            }
                            val batch = analyzeVideoFolder(parentId, media)
                            if (request != generation) return@launch
                            folderVideos[parentId].orEmpty().forEach { workOf.remove(it.id) }
                            folderVideos[parentId] = media
                            workOf.putAll(batch.workOf)
                            failedFolders = failedFolders - parentId
                        }.logFailure("GallerySession", "画廊列目录失败，可重试")
                            .onFailure { failedFolders = failedFolders + (parentId to it) }
                    }
                    pendingRefresh.removeAll(targets.toSet())
                    rebuildCandidates()
                    appendCandidates(Int.MAX_VALUE)
                    hasMore = false
                    isComplete = failedFolders.isEmpty()
                    return@launch
                }
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
        const val OPENING_FOLDERS = 6
        const val DISCOVERY_CONCURRENCY = 6
        const val LOAD_AHEAD_FILES = 24
    }
}
