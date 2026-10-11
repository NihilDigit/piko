package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.batchDelete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网盘根目录下的 Piko-Temp：解析面板里预览播放的文件秒传到这里。
 *
 * 各会话记录自己创建的文件，结束时只删除仍在临时目录中的那些 ID。共享目录留给其他预览和借对象，
 * 不随单个面板结束删除。进程内首次登录另清理上次退出后残留的目录。
 * 所有步骤固定使用接受请求时的 client；晚到的清理不能改向新登录的账号。
 */
class PreviewTempFolder(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val instantRepo: InstantMagnetRepository,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()

    // 按账号记：只记一个时，换到登录过的账号不会再清一次，借对象、预览拿到的仍是上一个账号的 Piko-Temp
    private val folderIds = HashMap<String, String>()

    private val sequence = MutableStateFlow(0L)
    private val owned = HashMap<Long, Pair<PikPakClient, MutableSet<String>>>()

    inner class Session internal constructor(private val client: PikPakClient?, private val id: Long) : AutoCloseable {
        private val closed = MutableStateFlow(false)
        suspend fun put(file: ResolvedFile): Result<String> = withContext(Dispatchers.Default) {
            lock.withLock {
                val owner = client ?: return@withLock Result.failure(IllegalStateException("Not logged in"))
                if (closed.value) return@withLock Result.failure(IllegalStateException("预览会话已结束"))
                val folder = ensureFolder(owner).getOrElse { return@withLock Result.failure(it) }
                if (closed.value) return@withLock Result.failure(IllegalStateException("预览会话已结束"))
                instantRepo.instantCreate(owner, file, folder).onSuccess { fileId ->
                    owned.getOrPut(id) { owner to HashSet() }.second.add(fileId)
                }
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val owner = client ?: return
            scope.launch(Dispatchers.Default) {
                lock.withLock {
                    val ids = owned.remove(id)?.second.orEmpty()
                    if (ids.isEmpty()) return@withLock
                    runSuspendCatching {
                        val folder = folderIds[owner.account] ?: return@runSuspendCatching
                        val remaining = owner.listFiles(folder).mapTo(HashSet()) { it.id }.intersect(ids)
                        if (remaining.isNotEmpty()) owner.batchDelete(remaining.toList())
                        if (clients.currentClient.value === owner) driveRepo.applyChange(DriveChange.ContentsChanged(setOf(folder)))
                    }.logFailure(TAG, "清理会话预览失败，留到下次登录时再清")
                }
            }
        }
    }

    fun session(): Session = Session(clients.currentClient.value, sequence.getAndUpdate { it + 1 } + 1)

    /** Piko-Temp 的 ID，不存在就先建。 */
    suspend fun folderId(owner: PikPakClient? = clients.currentClient.value): Result<String> = withContext(Dispatchers.Default) {
        lock.withLock { owner?.let { ensureFolder(it) } ?: Result.failure(IllegalStateException("Not logged in")) }
    }

    private suspend fun ensureFolder(owner: PikPakClient): Result<String> {
        val account = owner.account
        folderIds[account]?.let { return Result.success(it) }
        val found = runSuspendCatching {
            findFolders(owner).firstOrNull() ?: owner.createFolder("", FOLDER_NAME)
        }.getOrElse { return Result.failure(it) }
        folderIds[account] = found
        return Result.success(found)
    }

    /** 永久删除 Piko-Temp，找不到就什么也不做。 */
    suspend fun clear(owner: PikPakClient? = clients.currentClient.value): Result<Unit> = withContext(Dispatchers.Default) {
        lock.withLock {
            if (owner == null) return@withLock Result.success(Unit)
            if (owned.values.any { it.first.account == owner.account && it.second.isNotEmpty() }) return@withLock Result.success(Unit)
            runSuspendCatching {
                folderIds.remove(owner.account)
                val ids = findFolders(owner)
                if (ids.isNotEmpty()) owner.batchDelete(ids)
                if (clients.currentClient.value === owner) driveRepo.applyChange(DriveChange.ContentsChanged(null))
                PikoLog.d(TAG, "已清理 Piko-Temp（${ids.size} 个同名目录）")
            }
        }
    }

    // 按名字找而不是记住 id：上次运行建的目录 id 不在内存里。同名目录有多个时一并算上
    private suspend fun findFolders(owner: PikPakClient): List<String> =
        owner.listFiles("").filter { it.isFolder && it.name == FOLDER_NAME }.map { it.id }

    companion object {
        private const val TAG = "PikoTemp"
        const val FOLDER_NAME = "Piko-Temp"
    }
}
