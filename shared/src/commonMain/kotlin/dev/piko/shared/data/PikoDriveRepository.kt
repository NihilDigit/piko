package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.searchFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

open class PikoDriveRepository(private val clientManager: PikoClientProvider) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")
    protected val folderMeaninglessCache = mutableMapOf<String, Boolean>()
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()
    private val _folderStackFlow = MutableStateFlow(listOf(PikoPathBreadcrumb("", "网盘")))
    val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = _folderStackFlow.asStateFlow()

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

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getFile(fileId) }
    }

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getQuota().also { _quotaFlow.value = it } }
    }

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
