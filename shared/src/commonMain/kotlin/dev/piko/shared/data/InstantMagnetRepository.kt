package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.PruneResult
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.deleteOfflineTasks
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.pruneOfflineOutput
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.getFile
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

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

internal class OfflinePruneBlocked(message: String) : IllegalStateException(message)

class InstantMagnetRepository(private val clientManager: PikoClientProvider) {
    private val savePermits = Semaphore(SAVE_CONCURRENCY)
    val offlineSubmissions = dev.piko.shared.state.OfflineSubmitSession(clientManager)
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")
    private val _pendingMagnetFlow = MutableStateFlow<String?>(null)
    val pendingMagnetFlow: StateFlow<String?> = _pendingMagnetFlow.asStateFlow()

    fun onIncomingMagnet(magnet: String) {
        magnet.trim().takeIf { it.isNotEmpty() }?.let { incoming ->
            _pendingMagnetFlow.update { pending -> listOfNotNull(pending, incoming).distinct().joinToString("\n") }
        }
    }

    fun clearPendingMagnet() {
        _pendingMagnetFlow.value = null
    }

    // 磁力链接与 infohash 能对上具体内容，日志里只记数量、大小与耗时
    suspend fun resolve(magnet: String): Result<MagnetResolutionResult?> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        runSuspendCatching {
            val resource = client.resolveMagnet(magnet) ?: run {
                PikoLog.i(TAG, "解析磁力：云端未收录，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
                return@withContext Result.success(null)
            }
            val items = resource.files.map { InstantFileItem(it, it.gcid != null) }
            MagnetResolutionResult(resource, items, items.count { it.isInstantReady }, items.size).also {
                PikoLog.i(TAG, "解析磁力：${it.totalCount} 个文件，可秒传 ${it.instantReadyCount} 个，共 ${resource.files.sumOf { f -> f.size }} 字节，" +
                    "历时 ${started.elapsedNow().inWholeMilliseconds} ms")
            }
        }.onFailure { PikoLog.d(TAG, "解析磁力失败，历时 ${started.elapsedNow().inWholeMilliseconds} ms，${it::class.simpleName}") }
    }

    /**
     * 秒传一批文件，返回落进网盘的文件 id。没有 gcid 的（云端未收录）跳过。
     *
     * [reuse] 是 gcid 到已在网盘里的文件 id，通常是刚预览过、还在 Piko-Temp 里的那份：
     * 移动过来而不是再秒传一次，秒传每次都按大小的 15% 扣上传额度，内容重复也照扣。
     *
     * [keepStructure] 为真时按种子里的相对路径建子目录。逐个平铺会把「正片」「SPs」
     * 这类分区压成一层，同名文件还会撞在一起。
     *
     * [names] 是种子内路径到存进网盘时的名字（按番号规范命名），没有的用原名。秒传本就带名字，不多发请求；
     * 移用的预览副本是以原名存进 Piko-Temp 的，移过来后另改一次名。
     */
    suspend fun instantSave(
        items: List<InstantFileItem>,
        targetParentId: String = "",
        reuse: Map<String, String> = emptyMap(),
        keepStructure: Boolean = false,
        names: Map<String, String> = emptyMap(),
        existingFolders: Map<String, String> = emptyMap(),
    ): Result<List<String>> {
        val owner = clientManager.currentClient.value ?: return Result.failure(IllegalStateException("Not logged in"))
        val accepted = items.toList()
        val previews = reuse.toMap()
        val acceptedNames = names.toMap()
        val folders = existingFolders.toMap()
        return withContext(Dispatchers.Default) { saveFiles(owner, accepted, targetParentId, previews, keepStructure, acceptedNames, folders) }
    }

    suspend fun saveReferences(items: List<InstantFileItem>, targetParentId: String, keepStructure: Boolean,
        names: Map<String, String>, existingFolders: Map<String, String>, vault: VaultStore, source: String?): Result<List<String>> {
        val owner = clientManager.currentClient.value ?: return Result.failure(IllegalStateException("未登录"))
        return withContext(Dispatchers.Default) { saveFiles(owner, items.toList(), targetParentId, emptyMap(), keepStructure,
            names.toMap(), existingFolders.toMap(), vault, source) }
    }

    private suspend fun saveFiles(owner: PikPakClient, items: List<InstantFileItem>, targetParentId: String,
        reuse: Map<String, String>, keepStructure: Boolean, names: Map<String, String>, existingFolders: Map<String, String>, referenceVault: VaultStore? = null, source: String? = null): Result<List<String>> {
        val started = TimeSource.Monotonic.markNow()
        val files = items.map { it.file }.filter { it.gcid != null }
        val reused = files.count { it.gcid in reuse }
        val renamed = files.count { names[it.path].let { name -> name != null && name != it.name } }
        val summary = "${files.size} 个文件（跳过无 gcid 的 ${items.size - files.size} 个，移用预览副本 $reused 个，按番号改名 $renamed 个），" +
            "${files.sumOf { it.size }} 字节，到文件夹 ${targetParentId.ifEmpty { "根目录" }}${if (keepStructure) "，保留目录结构" else ""}"
        val folderIds = existingFolders.toMutableMap().apply { put("", targetParentId) }
        var creatingFolder: String? = null
        val directories = if (keepStructure) files.flatMap { ancestorsOf(it.path) }.distinct().sortedBy { it.count { c -> c == '/' } } else emptyList()
        val prepared = runSuspendCatching {
            for (dir in directories) {
                if (dir in folderIds) continue
                val parent = folderIds.getValue(dir.substringBeforeLast('/', ""))
                creatingFolder = dir
                folderIds[dir] = owner.createFolder(parent, dir.substringAfterLast('/'))
                creatingFolder = null
            }
        }
        if (prepared.isFailure) {
            val error = prepared.exceptionOrNull()!!
            val receipt = InstantSaveReceipt(owner.account, targetParentId,
                notAttempted = files.mapTo(HashSet()) { it.path }, folders = folderIds.toMap(),
                unconfirmedFolder = creatingFolder.takeUnless { definitelyNotSaved(error) })
            return Result.failure(InstantSaveIncomplete(receipt, error))
        }
        if (referenceVault != null) {
            val confirmed = linkedMapOf<String, String>()
            val unknown = linkedSetOf<String>()
            val remaining = files.mapTo(linkedSetOf()) { it.path }
            var failure: Throwable? = null
            for ((directory, group) in files.groupBy { if (keepStructure) it.path.substringBeforeLast('/', "") else "" }) {
                val folderId = folderIds[directory] ?: targetParentId
                val entries = group.map { file -> VaultEntry.create(names[file.path] ?: file.name, file.size, requireNotNull(file.gcid), source,
                    kotlin.time.Clock.System.now().toEpochMilliseconds()) }
                val write = referenceVault.update(folderId, VaultEdits.add(entries))
                val actual = write.getOrNull()?.after ?: referenceVault.committed(folderId).getOrNull()
                group.zip(entries).forEach { (file, entry) ->
                    if(actual?.any { it.id == entry.id } == true) confirmed[file.path] = entry.virtualId
                    else if(write.exceptionOrNull() is VaultWriteUnconfirmed) unknown += file.path
                }
                remaining.removeAll(confirmed.keys + unknown)
                if(write.isFailure) { failure = write.exceptionOrNull(); break }
            }
            val receipt = InstantSaveReceipt(owner.account, targetParentId, confirmed = confirmed, unconfirmed = unknown,
                notAttempted = remaining, folders = folderIds.toMap())
            return if(failure == null && remaining.isEmpty() && unknown.isEmpty()) Result.success(files.map { confirmed.getValue(it.path) })
                else Result.failure(InstantSaveIncomplete(receipt, failure ?: IllegalStateException("引用保存待确认")))
        }
        val consumedPreviews = HashSet<String>()
        val previews = files.associate { file ->
            file.path to reuse[file.gcid]?.takeIf { consumedPreviews.add(it) }
        }
        val outcomes = runSuspendCatching {
            // 逐个串行时 84 个文件约 20 秒。并发数取得保守：SDK 自带限流，再高也快不了多少
            coroutineScope {
                files.map { file ->
                    async {
                        savePermits.withPermit {
                            val parentId = folderIds[file.path.substringBeforeLast('/', "")] ?: targetParentId
                            val existing = previews[file.path]
                            val name = names[file.path] ?: file.name
                            if (existing == null) {
                                val created = runSuspendCatching { owner.instantCreate(file = file, parentId = parentId, name = name) }
                                InstantFileOutcome(file.path, created.getOrNull(), created.exceptionOrNull())
                            } else {
                                val moved = runSuspendCatching { owner.batchMove(listOf(existing), parentId) }
                                val delivery = if (moved.isSuccess) true else runSuspendCatching { owner.getFile(existing) }
                                    .getOrNull()?.let { !it.trashed && it.parentId == parentId } == true
                                if (!delivery) InstantFileOutcome(file.path, null, moved.exceptionOrNull()) else {
                                    val renamed = if (name == file.name) Result.success(Unit) else runSuspendCatching { owner.rename(existing, name) }
                                    InstantFileOutcome(file.path, existing, renamed.exceptionOrNull(), namingUnconfirmed = renamed.isFailure)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        val saved = outcomes.getOrElse { return Result.failure(it) }
        val errors = saved.mapNotNull { it.error }
        val receipt = InstantSaveReceipt(owner.account, targetParentId,
            confirmed = saved.mapNotNull { outcome -> outcome.id?.let { outcome.path to it } }.toMap(),
            unconfirmed = saved.filter { it.id == null && !definitelyNotSaved(it.error) }.mapTo(HashSet()) { it.path },
            failed = saved.filter { it.id == null && definitelyNotSaved(it.error) }.mapTo(HashSet()) { it.path },
            folders = folderIds.toMap(), namingUnconfirmed = saved.filter { it.namingUnconfirmed }.mapTo(HashSet()) { it.path })
        val result = when {
            errors.isEmpty() -> Result.success(saved.mapNotNull { it.id })
            saved.size == 1 && receipt.confirmed.isEmpty() && receipt.failed.size == 1 && errors.single() is InstantContentUnavailableException -> Result.failure(errors.single())
            else -> Result.failure(InstantSaveIncomplete(receipt, errors.first()))
        }
        return result.onSuccess { PikoLog.i(TAG, "秒传完成：$summary，历时 ${started.elapsedNow().inWholeMilliseconds} ms") }
            // 堆栈由调用方连同给用户的提示一起记
            .onFailure { PikoLog.w(TAG, "秒传失败：$summary，${it::class.simpleName}") }
    }

    private data class InstantFileOutcome(val path: String, val id: String?, val error: Throwable?, val namingUnconfirmed: Boolean = false)

    private fun definitelyNotSaved(error: Throwable?): Boolean = error is InstantContentUnavailableException ||
        error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)

    /** 秒传单个文件到 [parentId]，给预览用。 */
    suspend fun instantCreate(file: ResolvedFile, parentId: String): Result<String> = withContext(Dispatchers.Default) {
        instantCreate(client, file, parentId)
    }

    internal suspend fun instantCreate(owner: PikPakClient, file: ResolvedFile, parentId: String): Result<String> =
        withContext(Dispatchers.Default) { runSuspendCatching { owner.instantCreate(file = file, parentId = parentId, name = file.name) } }

    suspend fun enqueueOfflineTask(magnet: String, targetParentId: String = "", targetName: String = "网盘目录",
        title: String = "提交云端离线", expectedAccount: String? = clientManager.currentClient.value?.account,
        requiresTask: Boolean = false): Result<CreateUrlResult> =
            offlineSubmissions.submit(magnet, PikoPathBreadcrumb(targetParentId, targetName), title, expectedAccount, requiresTask)
                .onSuccess { result ->
                    val kind = if (magnet.startsWith("magnet:", ignoreCase = true)) "磁力" else magnet.substringBefore("://", "其他").lowercase()
                    when (result) {
                        is CreateUrlResult.Queued -> PikoLog.i(TAG, "已提交离线任务 ${result.task.id}（$kind），到文件夹 ${targetParentId.ifEmpty { "根目录" }}")
                        is CreateUrlResult.InstantComplete -> PikoLog.i(TAG, "离线提交即完成，未建任务（$kind），产出 ${result.file?.id ?: "未知"}")
                    }
                }
                .onFailure { PikoLog.w(TAG, "提交离线任务失败，到文件夹 ${targetParentId.ifEmpty { "根目录" }}，${it::class.simpleName}") }
    suspend fun getTask(taskId: String): Result<DriveTask> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getTask(taskId) }
    }

    /** 永久删除已完成任务产出里 [keep] 以外的文件，见 SDK 的 pruneOfflineOutput。 */
    suspend fun pruneOfflineOutput(task: DriveTask, keep: Set<String>, sourceUrl: String): Result<PruneResult> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val owner = client
                // Persisted jobs may predate the completeness guard. Revalidate before any deletion.
                val resource = owner.resolveMagnet(sourceUrl)
                if (resource == null || resource.truncated) throw OfflinePruneBlocked("文件清单不完整，已保留全部离线产出")
                val paths = resource.files.mapTo(HashSet()) { it.path }
                if (keep.any { it !in paths }) throw OfflinePruneBlocked("保留路径与来源清单不一致，已保留全部离线产出")
                check(clientManager.currentClient.value === owner) { "账号已改变，未裁剪离线产出" }
                owner.pruneOfflineOutput(task, keep)
            }
                // 结果里是种子内的路径，只记条数
                .onSuccess { PikoLog.i(TAG, "裁剪离线产出：任务 ${task.id}，保留 ${keep.size} 个，删除 ${it.deleted.size} 项，保留项缺失 ${it.missing.size} 个") }
        }

    /** 删除任务记录。未完成任务的占位文件由服务端一并清掉，已完成任务的文件保留。 */
    suspend fun deleteOfflineTask(taskId: String, expectedAccount: String? = clientManager.currentClient.value?.account): Result<Unit> {
        val owner = clientManager.currentClient.value?.takeIf { it.account == expectedAccount }
            ?: return Result.failure(IllegalStateException("账号已改变"))
        return withContext(Dispatchers.Default) { runSuspendCatching { owner.deleteOfflineTasks(listOf(taskId), deleteFiles = false) } }
    }

    private companion object {
        const val TAG = "Instant"
        const val SAVE_CONCURRENCY = 4
    }
}

/** 路径上的各级目录，由浅到深，不含文件本身。「a/b/c.mkv」得到 a、a/b。 */
internal fun ancestorsOf(path: String): List<String> {
    val parts = path.split('/').dropLast(1)
    return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
}
