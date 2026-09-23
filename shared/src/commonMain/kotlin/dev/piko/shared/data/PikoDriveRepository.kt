package dev.piko.shared.data

import dev.piko.data.repository.NaturalOrder
import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.searchFiles
import io.github.nihildigit.pikpak.searchFilesRecursive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

/** 列表的滚动位置：首个可见项的下标与它已滚出顶端的像素数。 */
data class ScrollAnchor(val index: Int, val offset: Int)

/** 目录的递归统计。[progress] 区分仍在统计、统计完、因到达上限而中止三种。 */
data class FolderUsage(val fileCount: Int, val bytes: Long, val progress: Progress) {
    enum class Progress { COUNTING, COMPLETE, TRUNCATED }
}

private const val FOLDER_USAGE_CONCURRENCY = 4

open class PikoDriveRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    // 读写发生在 Dispatchers.Default 的多个线程上，普通 HashMap 并发写会丢项甚至破坏结构。
    // commonMain 没有 ConcurrentHashMap，借 MutableStateFlow.update 的 CAS 做原子替换。
    private val folderMeaninglessCacheFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    protected val folderMeaninglessCache: Map<String, Boolean> get() = folderMeaninglessCacheFlow.value
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()
    private val _folderStackFlow = MutableStateFlow(listOf(ROOT_BREADCRUMB))
    val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = _folderStackFlow.asStateFlow()

    // 回收站恢复这类改动发生在网盘界面之外，界面不会重建，也就不会重新拉取。
    // 用事件流而非 StateFlow：订阅方只需被动收到「该刷新了」，不需要初值，也不该在重组时重放。
    private val _refreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshEvents: SharedFlow<Unit> = _refreshEvents.asSharedFlow()

    fun requestRefresh() {
        _refreshEvents.tryEmit(Unit)
    }

    // 外部链接要求打开网盘页（官方「在 App 中打开」链接）。应用可能正冷启动、还没登录，
    // 所以是待办而不是事件，由主界面组合时取走。刷新只对已在组合里的网盘页有用；
    // 不在的那一页新建时本就会从网络重新拉取
    private val _openDriveRequested = MutableStateFlow(false)
    val openDriveRequested: StateFlow<Boolean> = _openDriveRequested.asStateFlow()

    fun requestOpenDrive() {
        _openDriveRequested.value = true
        requestRefresh()
    }

    fun consumeOpenDriveRequest() {
        _openDriveRequested.value = false
    }

    // 界面外发起的「跳到这个文件并标出它」。网盘页此时可能不在组合里，所以存成
    // 一次性的待办，由下一个 DriveScreenState 在初始化时取走
    private val pendingHighlight = MutableStateFlow<Set<String>>(emptySet())

    fun requestHighlight(ids: Set<String>) {
        pendingHighlight.value = ids
    }

    fun takePendingHighlight(): Set<String> = pendingHighlight.getAndUpdate { emptySet() }

    /**
     * 文件所在目录的完整路径栈（含根）。服务端没有按 id 取路径的接口，只能沿
     * parent_id 逐级上溯，每级一次请求。
     */
    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            var parentId = client.getFile(fileId).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB) + chain
                val folder = client.getFile(parentId)
                chain.addFirst(PikoPathBreadcrumb(folder.id, folder.name))
                parentId = folder.parentId
            }
            error("目录层级过深")
        }
    }

    /*
     * 路径栈上每一级的列表与滚动位置。放在仓库层而不是界面状态里，是因为网盘页切走
     * 再切回时界面状态会整个重建，而这两样要随路径栈一起留下：返回上级时先显示缓存、
     * 回到原来的位置，再在后台刷新。出栈的目录一并丢弃，所以重新进入某个目录总是从
     * 顶部开始、重新取数据；两张表的大小也就以路径深度为界。
     */
    private val listingCache = MutableStateFlow<Map<String, List<FileStat>>>(emptyMap())
    private val scrollAnchors = MutableStateFlow<Map<String, ScrollAnchor>>(emptyMap())

    /** 路径栈里某一级的缓存列表，按 [sortOrder] 排好。不在栈里或尚未取过时为 null。 */
    fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        listingCache.value[folderId]?.let { sortFiles(it, sortOrder, folderId) }

    fun scrollAnchor(folderId: String): ScrollAnchor? = scrollAnchors.value[folderId]

    fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) {
        if (folderStackFlow.value.none { it.id == folderId }) return
        scrollAnchors.update { it + (folderId to anchor) }
    }

    private fun forgetFoldersOutsideStack() {
        val inStack = folderStackFlow.value.mapTo(HashSet()) { it.id }
        listingCache.update { cache -> cache.filterKeys { it in inStack } }
        scrollAnchors.update { anchors -> anchors.filterKeys { it in inStack } }
    }

    fun pushFolder(id: String, name: String) {
        _folderStackFlow.update { it + PikoPathBreadcrumb(id, name) }
    }

    fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) _folderStackFlow.value = stack
        forgetFoldersOutsideStack()
    }

    fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        var child: PikoPathBreadcrumb? = null
        _folderStackFlow.update { stack ->
            if (index !in 0 until stack.lastIndex) return null
            child = stack[index + 1]
            stack.take(index + 1)
        }
        forgetFoldersOutsideStack()
        return child
    }

    /**
     * 直接跳到某个目录，中间层级不可知，栈只留根与目标两级。目标本身是根时只留根：
     * 秒传的保存目标可以是根目录，拼成两级会出现两个「网盘」，返回一次还停在原地。
     */
    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        _folderStackFlow.value = if (breadcrumb.id.isEmpty()) {
            listOf(ROOT_BREADCRUMB)
        } else {
            listOf(ROOT_BREADCRUMB, breadcrumb)
        }
        forgetFoldersOutsideStack()
    }

    fun popFolder(): PikoPathBreadcrumb? {
        var popped: PikoPathBreadcrumb? = null
        _folderStackFlow.update { stack ->
            if (stack.size <= 1) return null
            popped = stack.last()
            stack.dropLast(1)
        }
        forgetFoldersOutsideStack()
        return popped
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val page = client.listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            sortFiles(page.files, sortOrder, parentId) to page.nextPageToken
        }
    }

    /**
     * 列出目录下的全部条目，翻页由 SDK 负责。
     *
     * 按页取有两处坑：只取第一页会让超过一页的目录被静默截断；排序又发生在
     * 客户端，分页取回时每页各自有序、整体无序。两者都要求先取全再排。
     */
    suspend fun listAllFiles(
        parentId: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val files = client.listFiles(parentId)
            // 只缓存路径栈上的目录。目录选择器等其他调用方也走这里，它们的目录不该留在缓存里
            if (folderStackFlow.value.any { it.id == parentId }) listingCache.update { it + (parentId to files) }
            sortFiles(files, sortOrder, parentId)
        }
    }

    /**
     * 递归统计目录下的文件数与总大小，边统计边发出累计值。
     *
     * 服务端不提供目录大小：列表与详情里目录的 size 都是 "0"，也没有子项计数
     * （2026-09-23 实测），只能逐个目录列出来加总。SDK 的 searchFilesRecursive 走同样的
     * 遍历，但要求非空关键词，且到了上限静默结束，调用方分不清统计完没完。
     * 到达 [maxFolders] 或 [timeout] 时以 TRUNCATED 结束，界面据此标注「至少」。
     */
    fun folderUsage(
        folderId: String,
        maxFolders: Int = 2_000,
        timeout: Duration = 30.seconds,
    ): Flow<FolderUsage> = flow {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var files = 0
        var bytes = 0L
        var listed = 0
        var level = listOf(folderId)
        while (level.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (batch in level.chunked(FOLDER_USAGE_CONCURRENCY)) {
                if (deadline.hasPassedNow() || listed >= maxFolders) {
                    emit(FolderUsage(files, bytes, FolderUsage.Progress.TRUNCATED))
                    return@flow
                }
                val budgeted = batch.take(maxFolders - listed)
                listed += budgeted.size
                val listings = coroutineScope { budgeted.map { async { client.listFiles(it) } }.awaitAll() }
                for (entry in listings.flatten()) {
                    if (entry.isFolder) {
                        next += entry.id
                    } else {
                        files++
                        bytes += entry.sizeBytes
                    }
                }
                emit(FolderUsage(files, bytes, FolderUsage.Progress.COUNTING))
            }
            level = next
        }
        emit(FolderUsage(files, bytes, FolderUsage.Progress.COMPLETE))
    }.flowOn(Dispatchers.Default)

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getFile(fileId) }
    }

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            client.getQuota().also {
                _quotaFlow.value = it
                preferences?.saveQuotaSnapshot(it.quota.usageBytes, it.quota.limitBytes)
            }
        }
    }

    /**
     * 全屏查看图片用的原图直链。
     *
     * 列表里的 thumbnailLink 是压过的小图，放大到全屏就是一团马赛克；原图链接只有
     * getFileDetail 才带。链接是签过名的，过期后 CDN 直接 403，所以不缓存，每次打开现取。
     */
    suspend fun originalImageUrl(fileId: String): String? =
        getFileDetail(fileId).getOrNull()?.downloadUrl

    suspend fun createFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.createFolder(parentId, name) }
    }

    suspend fun rename(fileId: String, name: String): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.rename(fileId, name) }
    }

    suspend fun trash(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchTrash(ids) }
    }

    suspend fun restore(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchUntrash(ids) }
    }

    suspend fun delete(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchDelete(ids) }
    }

    suspend fun move(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        // SDK 0.6.7 的 batchTrash、batchDelete、batchUntrash 都按上限分批，唯独 batchMove 没有；
        // 一次移动整个目录的内容会超过服务端的 id 数上限（error_code 11）
        runSuspendCatching { ids.chunked(BATCH_MOVE_LIMIT).forEach { client.batchMove(it, parentId) } }
    }

    suspend fun search(query: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.searchFiles(query) }
    }

    /**
     * 全盘按名搜索。PikPak 没有服务端搜索接口，只能逐层遍历目录树，
     * 所以结果是流式的：大网盘走完一轮要几十秒，不能等遍历结束才给结果。
     * 遍历的深度、目录数与超时上限由 SDK 的默认值兜底。
     */
    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit> =
        client.searchFilesRecursive(query, parentId)

    suspend fun trashFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listTrash() }
    }

    fun folderMeaningless(folderId: String): Boolean? = folderMeaninglessCache[folderId]

    fun cacheFolderMeaningless(folderId: String, value: Boolean) {
        folderMeaninglessCacheFlow.update { it + (folderId to value) }
    }

    /**
     * 秒传与离线任务的默认保存目录。根目录里已有同类目录就沿用，没有才新建。
     *
     * 要列全根目录再找：只看第一页的话，根目录条目一多，已有的那个目录落在后面几页，
     * 每次都会再建一个同名目录。
     */
    suspend fun getOrCreateMyPacksFolder(): Result<PikoPathBreadcrumb> {
        val rootFiles = listAllFiles().getOrElse { return Result.failure(it) }
        val existing = rootFiles.firstOrNull { it.isMyPacksFolder() }
        return if (existing != null) {
            Result.success(PikoPathBreadcrumb(existing.id, existing.name))
        } else {
            createFolder("", MY_PACKS_FOLDER_NAME).map { PikoPathBreadcrumb(it, MY_PACKS_FOLDER_NAME) }
        }
    }

    suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            if (!forceRefresh) folderMeaninglessCache[folderId]?.let { return@withContext it }
            val result = listFiles(folderId, sortOrder = PikoFileSortOrder.TIME_DESC)
                .getOrDefault(emptyList<FileStat>() to "").first
                .isEmpty()
            cacheFolderMeaningless(folderId, result)
            result
        }

    /**
     * 文件夹在前，各自按 [order] 排。根目录的 My Pack 不论哪种排序都排第一：
     * 秒传与离线任务默认存进它，是根目录里最常进的目录。
     */
    private fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        val comparator = when (order) {
            // 自然顺序：第 2 集排在第 10 集前面
            PikoFileSortOrder.NAME_ASC -> compareBy(NaturalOrder, FileStat::name)
            PikoFileSortOrder.NAME_DESC -> compareBy(NaturalOrder, FileStat::name).reversed()
            // 按创建时间而非 modified_time：目录的 modified_time 不随其中内容变动而更新，
            // 与创建时间相同（2026-09-23 实测），按它排序只会让「修改时间」名不副实
            PikoFileSortOrder.TIME_DESC -> compareByDescending(FileStat::createdTime)
            PikoFileSortOrder.TIME_ASC -> compareBy(FileStat::createdTime)
            PikoFileSortOrder.SIZE_DESC -> compareByDescending(FileStat::sizeBytes)
            PikoFileSortOrder.SIZE_ASC -> compareBy(FileStat::sizeBytes)
        }
        val sortedFolders = folders.sortedWith(comparator)
        val pinned = if (parentId.isEmpty()) sortedFolders.firstOrNull { it.isMyPacksFolder() } else null
        val orderedFolders = if (pinned == null) sortedFolders else listOf(pinned) + (sortedFolders - pinned)
        return orderedFolders + regularFiles.sortedWith(comparator)
    }

    private fun FileStat.isMyPacksFolder() = isFolder && name.lowercase() in MY_PACKS_FOLDER_NAMES

    companion object {
        val ROOT_BREADCRUMB = PikoPathBreadcrumb("", "网盘")
        private const val MAX_LOCATE_DEPTH = 64

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 与 SDK 其余批量接口的分批大小一致。实测 200 可以、1000 被拒
        private const val BATCH_MOVE_LIMIT = 100

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
