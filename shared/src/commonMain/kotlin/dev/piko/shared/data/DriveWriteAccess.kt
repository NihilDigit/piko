package dev.piko.shared.data

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.batchCopy
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.unstarFiles
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.restoreShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次已接受操作固定使用同一个账号和 client，后续步骤不会改向新登录的账号。 */
class DriveWriteAccess internal constructor(
    val account: String,
    private val client: PikPakClient,
    private val repository: PikoDriveRepository,
) {
    private suspend fun <T> request(block: suspend () -> T): Result<T> = withContext(Dispatchers.Default) { runSuspendCatching { block() } }
    suspend fun detail(id: String) = request { client.getFile(id) }
    suspend fun listFolder(id: String) = request { client.listFiles(id) }
    suspend fun submitShare(shareId: String, token: String, sources: List<String>, target: String, ancestors: List<String>) =
        request { client.restoreShare(shareId, token, sources, toParentId = target, ancestorIds = ancestors) }
    suspend fun starredIds() = request { client.listStarred().mapTo(HashSet()) { it.id } }
    suspend fun createFolder(parent: String, name: String) = request { client.createFolder(parent, name) }
    suspend fun rename(id: String, name: String) = request { client.rename(id, name) }
    suspend fun move(ids: List<String>, parent: String) = request { client.batchMove(ids, parent) }
    suspend fun copy(ids: List<String>, parent: String) = request { client.batchCopy(ids, parent) }
    suspend fun restore(ids: List<String>) = request { client.batchUntrash(ids) }
    suspend fun delete(ids: List<String>) = request { client.batchDelete(ids) }
    suspend fun star(ids: List<String>, value: Boolean) = request { if (value) client.starFiles(ids) else client.unstarFiles(ids) }
    suspend fun task(id: String) = request { client.getTask(id) }
    fun publish(change: DriveChange) = repository.applyWriteChange(account, change)
}
