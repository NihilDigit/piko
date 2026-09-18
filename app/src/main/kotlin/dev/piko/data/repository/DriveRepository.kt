package dev.piko.data.repository

import dev.piko.data.client.PikPakClientManager
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

enum class FileSortOrder {
    NAME_ASC, NAME_DESC,
    TIME_DESC, TIME_ASC,
    SIZE_DESC, SIZE_ASC,
}

data class PathBreadcrumb(
    val id: String,
    val name: String,
)

class DriveRepository(
    private val clientManager: PikPakClientManager,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    private val _folderStackFlow = MutableStateFlow<List<PathBreadcrumb>>(listOf(PathBreadcrumb("", "网盘")))
    val folderStackFlow: StateFlow<List<PathBreadcrumb>> = _folderStackFlow.asStateFlow()

    fun updateFolderStack(stack: List<PathBreadcrumb>) {
        if (stack.isNotEmpty()) {
            _folderStackFlow.value = stack
        }
    }

    fun pushFolder(id: String, name: String) {
        _folderStackFlow.value = _folderStackFlow.value + PathBreadcrumb(id, name)
    }

    fun popFolder(): Boolean {
        if (_folderStackFlow.value.size > 1) {
            _folderStackFlow.value = _folderStackFlow.value.dropLast(1)
            return true
        }
        return false
    }

    fun popToBreadcrumb(index: Int) {
        if (index >= 0 && index < _folderStackFlow.value.size) {
            _folderStackFlow.value = _folderStackFlow.value.take(index + 1)
        }
    }

    fun navigateToFolder(breadcrumb: PathBreadcrumb) {
        _folderStackFlow.value = listOf(PathBreadcrumb("", "网盘"), breadcrumb)
    }

    suspend fun getOrCreateMyPacksFolder(): Result<PathBreadcrumb> = withContext(Dispatchers.IO) {
        runCatching {
            val rootPage = client.listFilesPaged(parentId = "", pageSize = 100)
            val existing = rootPage.files.firstOrNull { file ->
                file.kind == "drive#folder" && (
                    file.name.equals("My Pack", ignoreCase = true) ||
                    file.name.equals("My Packs", ignoreCase = true) ||
                    file.name == "我的资源" ||
                    file.name == "我的离线"
                )
            }
            if (existing != null) {
                PathBreadcrumb(existing.id, existing.name)
            } else {
                val newFolderId = client.createFolder("", "My Packs")
                PathBreadcrumb(newFolderId, "My Packs")
            }
        }
    }

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.getQuota()
            _quotaFlow.value = res
            res
        }
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: FileSortOrder = FileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            val sortedFiles = sortFiles(response.files, sortOrder)
            Pair(sortedFiles, response.nextPageToken)
        }
    }

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.IO) {
        runCatching { client.getFile(fileId) }
    }

    suspend fun createNewFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { client.createFolder(parentId, name) }
    }

    suspend fun renameItem(fileId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.rename(fileId, newName) }
    }

    suspend fun moveToTrash(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.batchTrash(ids) }
    }

    suspend fun restoreFromTrash(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.batchUntrash(ids) }
    }

    suspend fun deletePermanently(ids: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.batchDelete(ids) }
    }

    suspend fun moveItems(ids: List<String>, toParentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.batchMove(ids, toParentId) }
    }

    suspend fun search(query: String): Result<List<FileStat>> = withContext(Dispatchers.IO) {
        runCatching { client.searchFiles(query) }
    }

    suspend fun getTrashFiles(): Result<List<FileStat>> = withContext(Dispatchers.IO) {
        runCatching { client.listTrash() }
    }

    suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.listFilesPaged(parentId = folderId, pageSize = 50)
            val subFiles = response.files
            if (subFiles.isEmpty()) return@runCatching true

            val maxInnerSize = subFiles.maxOfOrNull { it.sizeBytes } ?: 0L
            if (maxInnerSize >= thresholdBytes) return@runCatching false

            val hasSubfolder = subFiles.any { it.isFolder }
            if (hasSubfolder) return@runCatching false

            val totalInnerSize = subFiles.sumOf { it.sizeBytes }
            totalInnerSize < thresholdBytes
        }.getOrDefault(false)
    }

    private fun sortFiles(files: List<FileStat>, order: FileSortOrder): List<FileStat> {
        val (folders, nonFolders) = files.partition { it.isFolder }
        val sortComparator: Comparator<FileStat> = when (order) {
            FileSortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortOrder.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortOrder.TIME_DESC -> compareByDescending { it.modifiedTime }
            FileSortOrder.TIME_ASC -> compareBy { it.modifiedTime }
            FileSortOrder.SIZE_DESC -> compareByDescending { it.sizeBytes }
            FileSortOrder.SIZE_ASC -> compareBy { it.sizeBytes }
        }
        // 文件夹始终置顶展示，更符合文件管理系统的交互体验
        return folders.sortedWith(sortComparator) + nonFolders.sortedWith(sortComparator)
    }
}
