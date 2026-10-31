package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchCopy
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.sampleCid
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.unstarFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次已接受操作固定使用同一个账号和 client，后续步骤不会改向新登录的账号。 */
class DriveWriteAccess internal constructor(
    val account: String,
    private val client: PikPakClient,
    private val repository: PikoDriveRepository,
    val vault: VaultStore = repository.vaultForClient(client),
) {
    private val isCurrentClient: Boolean get() = repository.isCurrentClient(client)
    private suspend fun <T> request(ids: List<String> = emptyList(), block: suspend () -> T): Result<T> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            require(ids.none(LocalLocation::isLocal)) { "本地文件夹为只读" }
            block()
        }
    }
    suspend fun detail(id: String) = request(listOf(id)) { client.getFile(id) }
    suspend fun listFolder(id: String) = request(listOf(id)) { client.listFiles(id) }
    suspend fun browsableList(folderId: String): Result<List<FileStat>> {
        if (LocalLocation.isLocal(folderId)) return Result.failure(IllegalArgumentException("本地文件夹为只读"))
        return repository.browsableReaderForClient(client, vault, requireActiveClient = false).invoke(folderId)
            .map { files -> files.filterNot { LocalLocation.isLocal(it.id) } }
    }
    /** 恢复归档前必须用绑定 client 现查服务端确认真实文件仍在目标目录且未进回收站，禁止用可能滞后的本地缓存充当存在证据。 */
    suspend fun presentRealChildren(folderId: String): Result<List<FileStat>> =
        listFolder(folderId).map { list ->
            list.filter { !it.isFolder && !it.trashed && it.phase == TaskPhase.COMPLETE }
        }
    suspend fun submitShare(shareId: String, token: String, sources: List<String>, target: String, ancestors: List<String>) =
        request(sources + ancestors + target) { client.restoreShare(shareId, token, sources, toParentId = target, ancestorIds = ancestors) }
    suspend fun starredIds() = request { client.listStarred().mapTo(HashSet()) { it.id } }
    suspend fun createFolder(parent: String, name: String) = request(listOf(parent)) { client.createFolder(parent, name) }
    suspend fun rename(id: String, name: String) = request(listOf(id)) { client.rename(id, name) }
    suspend fun move(ids: List<String>, parent: String) = request(ids + parent) { client.batchMove(ids, parent) }
    suspend fun copy(ids: List<String>, parent: String) = request(ids + parent) { client.batchCopy(ids, parent) }
    suspend fun trash(ids: List<String>) = request(ids) { client.batchTrash(ids) }
    suspend fun restore(ids: List<String>) = request(ids) { client.batchUntrash(ids) }
    suspend fun delete(ids: List<String>) = request(ids) { client.batchDelete(ids) }
    suspend fun star(ids: List<String>, value: Boolean) = request(ids) { if (value) client.starFiles(ids) else client.unstarFiles(ids) }
    suspend fun task(id: String) = request { client.getTask(id) }
    suspend fun sampleCid(fileId: String) = request(listOf(fileId)) { client.sampleCid(client.getFile(fileId)) }
    suspend fun instantCreate(
        file: ResolvedFile,
        parentId: String,
        source: String? = null,
        notifyChange: Boolean = true,
    ): Result<String> = request(listOf(parentId)) { client.instantCreate(file, parentId) }.onSuccess { id ->
        if (isCurrentClient) {
            source?.let { repository.sourceLedger.record(it, listOf(file), expectedAccount = account) }
            repository.applyWriteChange(account, DriveChange.Created(parentId, id, file.path.substringAfterLast('/'), isFolder = false), notifyChange)
        }
    }
    suspend fun trashFiles(fresh: Boolean = false): Result<List<FileStat>> = repository.trashFilesForClient(client, fresh)
    suspend fun remainingQuotaBytes(): Result<Long?> =
        request { client.getQuota() }.map { it.quota.takeIf { q -> q.limitBytes > 0 }?.remainingBytes }
    fun knownVaultMetaOf(gcid: String, size: Long): VaultMediaMeta? =
        if (isCurrentClient) repository.knownVaultMetaOf(gcid, size) else vault.knownMetaOf(gcid, size)
    fun knownVaultParents(): Map<String, String> =
        if (isCurrentClient) repository.knownVaultParents() else emptyMap()
    fun localCleanChildren(folderId: String): List<FileStat>? =
        if (isCurrentClient) repository.localRealChildrenIfClean(folderId) else null
    fun recordSources(sources: Map<String, List<ResolvedFile>>) {
        if (isCurrentClient) {
            sources.forEach { (source, files) -> repository.sourceLedger.record(source, files, expectedAccount = account) }
        }
    }
    fun rememberTree(members: Map<String, Set<String>>) {
        if (isCurrentClient) repository.vaultTrees.merge(members, expectedAccount = account)
    }
    fun rememberFullyVaulted(folderIds: Set<String>) {
        if (isCurrentClient) repository.rememberFullyVaulted(folderIds)
    }
    fun starVaultEntries(folderId: String, entries: Collection<VaultEntry>) {
        if (isCurrentClient) repository.vaultStars.starEntries(folderId, entries, starred = true, expectedAccount = account)
    }
    fun isVaultEntryStarred(entryId: String): Boolean =
        isCurrentClient && repository.vaultStars.isStarred(entryId)
    fun unstarVaultEntries(entryIds: Collection<String>) {
        if (isCurrentClient) repository.vaultStars.unstarByEntryIds(entryIds, expectedAccount = account)
    }
    fun notifyFoldersRefreshed(folders: Set<String>) {
        if (isCurrentClient && folders.isNotEmpty()) repository.notifyFoldersRefreshed(folders)
    }
    fun recordChange(change: DriveChangeJournal.Change) {
        if (isCurrentClient) repository.changes.record(change)
    }
    fun publish(change: DriveChange) {
        if (isCurrentClient) repository.applyWriteChange(account, change)
    }
}
