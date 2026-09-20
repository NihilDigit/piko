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
import kotlinx.coroutines.withContext

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

open class PikoDriveRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")
    protected val folderMeaninglessCache = mutableMapOf<String, Boolean>()
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()
    private val _folderStackFlow = MutableStateFlow(listOf(PikoPathBreadcrumb("", "网盘")))
    val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = _folderStackFlow.asStateFlow()

    // 回收站恢复这类改动发生在网盘界面之外，界面不会重建，也就不会重新拉取。
    // 用事件流而非 StateFlow：订阅方只需被动收到「该刷新了」，不需要初值，也不该在重组时重放。
    private val _refreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshEvents: SharedFlow<Unit> = _refreshEvents.asSharedFlow()

    fun requestRefresh() {
        _refreshEvents.tryEmit(Unit)
    }

    fun pushFolder(id: String, name: String) {
        _folderStackFlow.value += PikoPathBreadcrumb(id, name)
    }

    fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) _folderStackFlow.value = stack
    }

    fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        if (index !in 0 until _folderStackFlow.value.lastIndex) return null
        val child = _folderStackFlow.value.getOrNull(index + 1)
        _folderStackFlow.value = _folderStackFlow.value.take(index + 1)
        return child
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        _folderStackFlow.value = listOf(PikoPathBreadcrumb("", "网盘"), breadcrumb)
    }

    fun popFolder(): PikoPathBreadcrumb? {
        if (_folderStackFlow.value.size <= 1) return null
        val result = _folderStackFlow.value.last()
        _folderStackFlow.value = _folderStackFlow.value.dropLast(1)
        return result
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
        runSuspendCatching { client.batchMove(ids, parentId) }
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
        folderMeaninglessCache[folderId] = value
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
            PikoFileSortOrder.NAME_ASC -> compareBy<FileStat> { it.name.lowercase() }
            PikoFileSortOrder.NAME_DESC -> compareBy<FileStat> { it.name.lowercase() }.reversed()
            PikoFileSortOrder.TIME_DESC -> compareBy<FileStat> { it.modifiedTime.toString() }.reversed()
            PikoFileSortOrder.TIME_ASC -> compareBy<FileStat> { it.modifiedTime.toString() }
            PikoFileSortOrder.SIZE_DESC -> compareBy<FileStat> { it.sizeBytes }.reversed()
            PikoFileSortOrder.SIZE_ASC -> compareBy<FileStat> { it.sizeBytes }
        }
        return folders.sortedWith(comparator) + regularFiles.sortedWith(comparator)
    }

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
