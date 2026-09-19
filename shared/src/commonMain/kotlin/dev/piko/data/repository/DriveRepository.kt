package dev.piko.data.repository

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb

typealias FileSortOrder = PikoFileSortOrder
typealias PathBreadcrumb = PikoPathBreadcrumb

class DriveRepository(
    clientManager: PikoClientProvider,
    preferences: dev.piko.data.auth.PikoUserPreferences? = null,
) : PikoDriveRepository(clientManager, preferences) {
    fun getFolderMeaningless(folderId: String): Boolean? = folderMeaningless(folderId)
    fun getAllCachedFolderMeaningless(): Map<String, Boolean> = folderMeaninglessCache.toMap()

    suspend fun getOrCreateMyPacksFolder(): Result<PathBreadcrumb> {
        val existing = listFiles().getOrElse { return Result.failure(it) }.first
            .firstOrNull { it.isFolder && (it.name.equals("My Pack", true) || it.name.equals("My Packs", true) || it.name == "我的资源" || it.name == "我的离线") }
        return if (existing != null) {
            Result.success(PathBreadcrumb(existing.id, existing.name))
        } else {
            createFolder("", "My Packs").map { PathBreadcrumb(it, "My Packs") }
        }
    }

    suspend fun createNewFolder(parentId: String, name: String): Result<String> = createFolder(parentId, name)
    suspend fun renameItem(fileId: String, newName: String): Result<Unit> = rename(fileId, newName)
    suspend fun moveToTrash(ids: List<String>): Result<Unit> = trash(ids)
    suspend fun restoreFromTrash(ids: List<String>): Result<Unit> = restore(ids)
    suspend fun deletePermanently(ids: List<String>): Result<Unit> = delete(ids)
    suspend fun moveItems(ids: List<String>, toParentId: String): Result<Unit> = move(ids, toParentId)
    suspend fun getTrashFiles(): Result<List<io.github.nihildigit.pikpak.FileStat>> = trashFiles()
}
