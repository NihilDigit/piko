package dev.piko.shared.data

import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网盘根目录下的 Piko-Temp：解析面板里预览播放的文件秒传到这里。
 *
 * 不逐个记录放进去的 id，清理时整个目录永久删除。面板关闭时清一次，每个账号在进程内
 * 首次登录时再清一次：进程被杀时面板来不及清理，残留留到下次启动收拾。
 *
 * 建目录、放文件与清理共用一把锁。上一个面板关闭时发起的清理与下一个面板的首次预览
 * 可能挨得很近，锁先来先得，清理排在前面就不会删掉新放进去的文件。
 */
class PreviewTempFolder(
    private val driveRepo: PikoDriveRepository,
    private val instantRepo: InstantMagnetRepository,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var folderId: String? = null

    /** 秒传到 Piko-Temp，目录不存在就先建。返回新文件的 id。 */
    suspend fun put(file: ResolvedFile): Result<String> = lock.withLock {
        val parentId = folderId
            ?: findFolders().getOrElse { return Result.failure(it) }.firstOrNull()
            ?: driveRepo.createFolder("", FOLDER_NAME).getOrElse { return Result.failure(it) }
        folderId = parentId
        instantRepo.instantCreate(file, parentId)
    }

    /** 永久删除 Piko-Temp，找不到就什么也不做。 */
    suspend fun clear(): Result<Unit> = lock.withLock {
        folderId = null
        val ids = findFolders().getOrElse { return Result.failure(it) }
        if (ids.isEmpty()) return Result.success(Unit)
        driveRepo.delete(ids).onSuccess { driveRepo.requestRefresh() }
    }

    /** 调用方的作用域结束之后仍要跑完的清理，例如面板关闭时。 */
    fun clearInBackground() {
        scope.launch { clear() }
    }

    // 按名字找而不是记住 id：上次运行建的目录 id 不在内存里。同名目录有多个时一并算上
    private suspend fun findFolders(): Result<List<String>> =
        driveRepo.listAllFiles("").map { files ->
            files.filter { it.isFolder && it.name == FOLDER_NAME }.map { it.id }
        }

    companion object {
        const val FOLDER_NAME = "Piko-Temp"
    }
}
