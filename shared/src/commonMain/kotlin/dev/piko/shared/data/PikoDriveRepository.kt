package dev.piko.shared.data

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

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

    fun pushFolder(id: String, name: String) {
        _folderStackFlow.update { it + PikoPathBreadcrumb(id, name) }
    }

    fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) _folderStackFlow.value = stack
    }

    fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        var child: PikoPathBreadcrumb? = null
        _folderStackFlow.update { stack ->
            if (index !in 0 until stack.lastIndex) return null
            child = stack[index + 1]
            stack.take(index + 1)
        }
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
    }

    fun popFolder(): PikoPathBreadcrumb? {
        var popped: PikoPathBreadcrumb? = null
        _folderStackFlow.update { stack ->
            if (stack.size <= 1) return null
            popped = stack.last()
            stack.dropLast(1)
        }
        return popped
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val page = client.listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            sortFiles(page.files, sortOrder) to page.nextPageToken
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
        runSuspendCatching { sortFiles(client.listFiles(parentId), sortOrder) }
    }

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
        val existing = rootFiles.firstOrNull { it.isFolder && it.name.lowercase() in MY_PACKS_FOLDER_NAMES }
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

    private fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        val comparator = when (order) {
            // 比较器每次比较都会调用，lowercase() 会为每次比较新建两个字符串，
            // 千项目录一次排序就是几万次分配
            PikoFileSortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER, FileStat::name)
            PikoFileSortOrder.NAME_DESC -> compareBy(String.CASE_INSENSITIVE_ORDER, FileStat::name).reversed()
            PikoFileSortOrder.TIME_DESC -> compareByDescending(FileStat::modifiedTime)
            PikoFileSortOrder.TIME_ASC -> compareBy(FileStat::modifiedTime)
            PikoFileSortOrder.SIZE_DESC -> compareByDescending(FileStat::sizeBytes)
            PikoFileSortOrder.SIZE_ASC -> compareBy(FileStat::sizeBytes)
        }
        return folders.sortedWith(comparator) + regularFiles.sortedWith(comparator)
    }

    companion object {
        val ROOT_BREADCRUMB = PikoPathBreadcrumb("", "网盘")

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 与 SDK 其余批量接口的分批大小一致。实测 200 可以、1000 被拒
        private const val BATCH_MOVE_LIMIT = 100

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
