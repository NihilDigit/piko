package dev.piko.shared.data

import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.PruneResult
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.createUrlFile
import io.github.nihildigit.pikpak.deleteOfflineTasks
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.pruneOfflineOutput
import io.github.nihildigit.pikpak.resolveMagnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class InstantFileItem(
    val file: ResolvedFile,
    val isInstantReady: Boolean,
    val isSelected: Boolean = true,
)

data class MagnetResolutionResult(
    val resource: MagnetResource,
    val items: List<InstantFileItem>,
    val instantReadyCount: Int,
    val totalCount: Int,
)

class InstantMagnetRepository(private val clientManager: PikoClientProvider) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")
    private val _pendingMagnetFlow = MutableStateFlow<String?>(null)
    val pendingMagnetFlow: StateFlow<String?> = _pendingMagnetFlow.asStateFlow()

    fun onIncomingMagnet(magnet: String) {
        magnet.trim().takeIf { it.isNotEmpty() }?.let { _pendingMagnetFlow.value = it }
    }

    fun clearPendingMagnet() {
        _pendingMagnetFlow.value = null
    }

    suspend fun resolve(magnet: String): Result<MagnetResolutionResult?> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val resource = client.resolveMagnet(magnet) ?: return@withContext Result.success(null)
            val items = resource.files.map { InstantFileItem(it, it.gcid != null) }
            MagnetResolutionResult(resource, items, items.count { it.isInstantReady }, items.size)
        }
    }

    /**
     * 秒传一批文件，返回落进网盘的文件 id。没有 gcid 的（云端未收录）跳过。
     *
     * [reuse] 是 gcid 到已在网盘里的文件 id，通常是刚预览过、还在 Piko-Temp 里的那份：
     * 移动过来而不是再秒传一次，秒传每次都按大小的 15% 扣上传额度，内容重复也照扣。
     *
     * [keepStructure] 为真时按种子里的相对路径建子目录。逐个平铺会把「正片」「SPs」
     * 这类分区压成一层，同名文件还会撞在一起。
     */
    suspend fun instantSave(
        items: List<InstantFileItem>,
        targetParentId: String = "",
        reuse: Map<String, String> = emptyMap(),
        keepStructure: Boolean = false,
    ): Result<List<String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val files = items.map { it.file }.filter { it.gcid != null }
            val folderIds = if (keepStructure) createFolders(files, targetParentId) else emptyMap()
            // 逐个串行时 84 个文件约 20 秒。并发数取得保守：SDK 自带限流，再高也快不了多少
            val permits = Semaphore(SAVE_CONCURRENCY)
            coroutineScope {
                files.map { file ->
                    async {
                        permits.withPermit {
                            val parentId = folderIds[file.path.substringBeforeLast('/', "")] ?: targetParentId
                            val existing = reuse[file.gcid]
                            if (existing != null) {
                                client.batchMove(listOf(existing), parentId)
                                existing
                            } else {
                                client.instantCreate(file = file, parentId = parentId, name = file.name)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** 按路径由浅到深建目录，返回相对目录到 id。根（空串）即 [rootId]。 */
    private suspend fun createFolders(files: List<ResolvedFile>, rootId: String): Map<String, String> {
        val ids = mutableMapOf("" to rootId)
        val dirs = files.flatMap { ancestorsOf(it.path) }.distinct().sortedBy { it.count { c -> c == '/' } }
        for (dir in dirs) {
            val parent = ids.getValue(dir.substringBeforeLast('/', ""))
            ids[dir] = client.createFolder(parent, dir.substringAfterLast('/'))
        }
        return ids
    }

    /** 秒传单个文件到 [parentId]，给预览用。 */
    suspend fun instantCreate(file: ResolvedFile, parentId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.instantCreate(file = file, parentId = parentId, name = file.name) }
    }

    suspend fun enqueueOfflineTask(magnet: String, targetParentId: String = ""): Result<CreateUrlResult> =
        withContext(Dispatchers.Default) { runSuspendCatching { client.createUrlFile(parentId = targetParentId, url = magnet) } }

    suspend fun getTask(taskId: String): Result<OfflineTask> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getTask(taskId) }
    }

    /** 永久删除已完成任务产出里 [keep] 以外的文件，见 SDK 的 pruneOfflineOutput。 */
    suspend fun pruneOfflineOutput(task: OfflineTask, keep: Set<String>): Result<PruneResult> =
        withContext(Dispatchers.Default) { runSuspendCatching { client.pruneOfflineOutput(task, keep) } }

    /** 删除任务记录。未完成任务的占位文件由服务端一并清掉，已完成任务的文件保留。 */
    suspend fun deleteOfflineTask(taskId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteOfflineTasks(listOf(taskId), deleteFiles = false) }
    }

    private companion object {
        const val SAVE_CONCURRENCY = 4
    }
}

/** 路径上的各级目录，由浅到深，不含文件本身。「a/b/c.mkv」得到 a、a/b。 */
internal fun ancestorsOf(path: String): List<String> {
    val parts = path.split('/').dropLast(1)
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}
