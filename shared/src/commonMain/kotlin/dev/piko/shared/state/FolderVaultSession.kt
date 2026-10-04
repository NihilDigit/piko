package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEdit
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.VaultWrite
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.reportFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

private const val LARGE_FILE_MIN_BYTES = 50L * 1024 * 1024

/**
 * 把文件夹里的真实文件换成归档条目，腾出网盘空间，以及反过来恢复到网盘。进程级：离开网盘页照常进行；
 * 归档与恢复同一时刻只做一件，免得两边同时改同一份清单。
 *
 * 八个目录并行处理，各自清单确认写成后才处置原文件。中途失败时已写成的引用与恢复依据保留。
 *
 * 默认将原文件移入回收站，也可选择永久删除。撤销时按实际处置方式恢复，再去掉清单条目。
 *
 * 全局十六个任务取样 CID，同内容只取一次；取样超时不挡归档。CID 用于日后的只读体检。
 */
class FolderVaultSession internal constructor(
    private val operations: FolderVaultOperations,
    private val scope: CoroutineScope,
    private val cidTimeoutMillis: Long = 5_000,
) {
    constructor(driveRepo: PikoDriveRepository, scope: CoroutineScope) : this(DriveFolderVaultOperations(driveRepo), scope)

    /**
     * 一次归档之前的清点，给确认框用。没有来源记录的（自己上传、秒传）单独计。
     */
    class Survey(
        val files: Int,
        val bytes: Long,
        val unsourcedFiles: Int,
        val unsourcedBytes: Long,
        val largeFiles: Survey? = null,
    )

    /** 正在归档的文件夹与进度。 */
    class Progress(val folderName: String, val done: Int, val total: Int, val prepared: Int = done)

    var progress by mutableStateOf<Progress?>(null)
        private set

    /** 恢复到网盘的进度。[total] 为 null 时还在扫描。 */
    data class RestoreProgress(
        val folderName: String,
        val stage: String = "正在扫描归档条目",
        val scannedFolders: Int = 0,
        val done: Int = 0,
        val total: Int? = null,
    )

    var restoreProgress by mutableStateOf<RestoreProgress?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    /** 已有一件在做时提示一句并返回 true。 */
    private fun busy(): Boolean {
        if (job?.isActive != true) return false
        val running = progress?.let { "「${it.folderName}」归档中" } ?: restoreProgress?.let { "「${it.folderName}」恢复中" } ?: "归档处理中"
        _messages.tryEmit("$running，请稍后再试")
        return true
    }

    /** 清点 [folder] 整棵树里能归档的文件。 */
    suspend fun survey(folder: PikoPathBreadcrumb): Result<Survey> = runSuspendCatching {
        val files = walk(folder.id).flatMap { it.second }
        val unsourced = files.filter { it.sourceUrl.isNullOrBlank() }
        val large = files.filter { it.sizeBytes >= LARGE_FILE_MIN_BYTES }
        val largeUnsourced = large.filter { it.sourceUrl.isNullOrBlank() }
        Survey(files.size, files.sumOf { it.sizeBytes }, unsourced.size, unsourced.sumOf { it.sizeBytes },
            Survey(large.size, large.sumOf { it.sizeBytes }, largeUnsourced.size, largeUnsourced.sumOf { it.sizeBytes }))
    }

    /** 开始归档 [folder]。[includeUnsourced] 为假时没有来源记录的文件原样留着。已有一个在做时不接。 */
    fun archive(folder: PikoPathBreadcrumb, includeUnsourced: Boolean, onlyLargeFiles: Boolean = false, moveToTrash: Boolean = true) {
        if (busy()) return
        job = scope.launch {
            val reverts = mutableMapOf<String, VaultEdit>()
            val trashed = mutableListOf<String>()
            val deleted = mutableMapOf<String, List<VaultEntry>>()
            val result = runSuspendCatching {
                val delete = !moveToTrash
                val levels = walk(folder.id).map { (folderId, files) ->
                    folderId to files.filter {
                        (includeUnsourced || !it.sourceUrl.isNullOrBlank()) && (!onlyLargeFiles || it.sizeBytes >= LARGE_FILE_MIN_BYTES)
                    }
                }.filter { it.second.isNotEmpty() }
                val total = levels.sumOf { it.second.size }
                var done = 0
                var prepared = 0
                val progressLock = Mutex()
                progress = Progress(folder.name, 0, total, 0)
                coroutineScope {
                    val sampler = VaultCidSampler(this, operations::sampleCid, cidTimeoutMillis)
                    val folders = Semaphore(8)
                    levels.map { (folderId, files) -> async {
                        folders.withPermit {
                            val addedAt = Clock.System.now().toEpochMilliseconds()
                            val entries = files.map { file -> async {
                                val cid = file.params["piko_vault_cid"] ?: sampler.sample(file)
                                progressLock.withLock {
                                    prepared++
                                    progress = Progress(folder.name, done, total, prepared)
                                }
                                VaultEntry.create(file.name, file.sizeBytes, file.hash, file.sourceUrl, addedAt, cid)
                            } }.awaitAll()
                            operations.write(folderId, entries)
                            // 删除请求的返回状态可能不明确，先记录恢复依据；撤销时跳过仍在原位的文件。
                            progressLock.withLock {
                                if (delete) deleted[folderId] = entries
                                else reverts[folderId] = VaultEdits.restore(entries.zip(files).associate { (entry, file) -> entry.id to file })
                            }
                            operations.remove(files.map { it.id }, delete)
                            progressLock.withLock {
                                if (!delete) trashed += files.map { it.id }
                                done += files.size
                                progress = Progress(folder.name, done, total, prepared)
                            }
                        }
                    } }.awaitAll()
                }
                total
            }
            progress = null
            // 做完的几层记成一条改动，哪怕后面失败了：撤销得回已经归档的那些
            if (reverts.isNotEmpty() || deleted.isNotEmpty()) {
                val count = result.getOrNull()?.let { "已归档 $it 个文件" }
                val summary = if (count == null) "部分归档记录已写入，原文件处理未全部完成"
                    else if (deleted.isNotEmpty()) "$count，原文件已删除" else "$count，原文件已移入回收站"
                operations.record(
                    DriveChangeJournal.Change.Vault(reverts, summary, untrashOnRevert = trashed, recreateOnRevert = deleted),
                )
            } else if (result.getOrNull() == 0) {
                _messages.tryEmit("无可归档的文件")
            }
            result.reportFailure(TAG, "归档") { _messages.tryEmit(it) }
            operations.refresh()
        }
    }

    /**
     * 中止正在做的归档，换号时用：再做下去，请求会发到新账号上。已做完的几层保持归档，不记撤销，
     * 换号本来也会清掉撤销记录；回到原账号后可逐项恢复到网盘。
     */
    fun cancel() {
        job?.cancel()
        progress = null
        restoreProgress = null
    }

    /** 把列表里的这几行归档条目恢复成网盘文件。 */
    fun restore(files: List<FileStat>) {
        val requested = files.filter { it.isVaulted }.groupBy { it.parentId }
            .mapValues { (_, rows) -> rows.mapNotNullTo(HashSet()) { VaultEntry.entryIdOf(it.id) } }
        if (requested.isEmpty() || busy()) return
        restoreProgress = RestoreProgress("恢复所选文件")
        job = scope.launch {
            try {
                runSuspendCatching {
                    // 现读清单，不用列表里的行：别的设备可能已恢复或移除了其中几条
                    val byFolder = coroutineScope {
                        requested.map { (folderId, ids) ->
                            async { folderId to operations.archived(folderId).entries.filter { it.id in ids } }
                        }.awaitAll()
                    }.filter { it.second.isNotEmpty() }.toMap()
                    restoreEntries(byFolder)
                }.reportFailure(TAG, "恢复归档") { _messages.tryEmit(it) }
            } finally { restoreProgress = null }
        }
    }

    /** 递归恢复 [folder] 中的归档条目，真实文件保持原样。清点失败时不开始恢复。 */
    fun restoreFolder(folder: PikoPathBreadcrumb) {
        if (busy()) return
        restoreProgress = RestoreProgress(folder.name)
        job = scope.launch {
            try {
                runSuspendCatching {
                    val byFolder = scanArchived(folder.id)
                    if (byFolder.isEmpty()) _messages.tryEmit("这个文件夹中没有归档条目")
                    else restoreEntries(byFolder)
                }.reportFailure(TAG, "取消文件夹归档") { _messages.tryEmit(it) }
            } finally { restoreProgress = null }
        }
    }

    private suspend fun scanArchived(rootId: String): Map<String, List<VaultEntry>> {
        val found = mutableMapOf<String, List<VaultEntry>>()
        val visited = mutableSetOf(rootId)
        var queue = listOf(rootId)
        var scanned = 0
        while (queue.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (chunk in queue.chunked(8)) {
                val levels = coroutineScope { chunk.map { id -> async { operations.archived(id) } }.awaitAll() }
                chunk.zip(levels).forEach { (folderId, level) ->
                    if (level.entries.isNotEmpty()) found[folderId] = level.entries
                    level.subfolders.forEach { if (visited.add(it.id)) next += it.id }
                }
                scanned += chunk.size
                restoreProgress = restoreProgress?.copy(scannedFolders = scanned)
            }
            queue = next
        }
        return found
    }

    /**
     * 回收站里还在的原文件优先，免占新空间；其余按 gcid 秒传，先比一次剩余空间。云端已不存的秒传不出来，
     * 也不扣额度，留在清单里。目录并发与归档相同，秒传全局十六个。
     */
    private suspend fun restoreEntries(byFolder: Map<String, List<VaultEntry>>) {
        val total = byFolder.values.sumOf { it.size }
        restoreProgress = restoreProgress?.copy(stage = "正在检查恢复空间", total = total)
        val trash = runSuspendCatching { operations.trash() }.logFailure(TAG, "读取回收站失败").getOrDefault(emptyList())
        val originals = trashOriginals(byFolder, trash)
        val needed = byFolder.values.flatten().filter { it.id !in originals }.sumOf { it.size }
        val remaining = operations.remainingBytes()
        if (remaining != null && needed > remaining) {
            _messages.tryEmit("网盘空间不足，放不下这 $total 项")
            return
        }
        restoreProgress = restoreProgress?.copy(stage = "正在恢复到网盘")
        val lock = Mutex()
        var missing = 0
        val created = mutableListOf<String>()
        val reverts = mutableMapOf<String, VaultEdit>()
        coroutineScope {
            val folders = Semaphore(8)
            val recreates = Semaphore(16)
            byFolder.map { (folderId, entries) -> async {
                folders.withPermit {
                    val restored = mutableMapOf<String, FileStat>()
                    suspend fun finish(entry: VaultEntry, fileId: String?) = lock.withLock {
                        if (fileId != null) {
                            restored[entry.id] = FileStat(id = fileId, name = entry.name, hash = entry.gcid, size = entry.size.toString())
                            created += fileId
                        }
                        restoreProgress = restoreProgress?.let { it.copy(done = it.done + 1) }
                    }
                    val (fromTrash, fromCloud) = entries.partition { it.id in originals }
                    if (fromTrash.isNotEmpty()) {
                        val untrashed = runSuspendCatching { operations.untrash(fromTrash.map { originals.getValue(it.id).id }) }
                            .logFailure(TAG, "从回收站恢复原文件失败").isSuccess
                        for (entry in fromTrash) {
                            val original = originals.getValue(entry.id)
                            // 条目归档后改过名时，原文件跟着改；改不成也算恢复了，只是名字是旧的
                            if (untrashed && original.name != entry.name) {
                                runSuspendCatching { operations.rename(original.id, entry.name) }.logFailure(TAG, "恢复后改名失败")
                            }
                            finish(entry, original.id.takeIf { untrashed })
                        }
                    }
                    fromCloud.map { entry -> async {
                        recreates.withPermit {
                            val id = runSuspendCatching { operations.recreate(entry, folderId) }
                                .onFailure { if (it is InstantContentUnavailableException) lock.withLock { missing++ } }
                                .logFailure(TAG, "恢复归档条目失败")
                                .getOrNull()
                            finish(entry, id)
                        }
                    } }.awaitAll()
                    if (restored.isEmpty()) return@withPermit
                    // 清单没改成的话，文件已恢复、条目还在，列表里会重复一行，不丢东西
                    runSuspendCatching { operations.markRestored(folderId, restored) }
                        .onSuccess { write -> lock.withLock { reverts[folderId] = VaultEdits.add(write.before.filter { it.id in restored }) } }
                        .logFailure(TAG, "恢复后改写归档清单失败")
                }
            } }.awaitAll()
        }
        val failed = total - created.size
        val summary = when {
            failed == 0 -> if (total == 1) "已恢复到网盘" else "已恢复 $total 项"
            missing == failed -> "已恢复 ${created.size} 项，$missing 项云端已无内容"
            else -> "已恢复 ${created.size} 项，$failed 项失败"
        }
        if (created.isNotEmpty()) {
            operations.record(DriveChangeJournal.Change.Vault(reverts, summary, trashOnRevert = created))
        } else {
            _messages.tryEmit(summary)
        }
        operations.refresh()
    }

    /**
     * 归档时移进回收站、还没清掉的原文件，按条目配对。只认所在目录、gcid 与大小，不认名字：条目归档后可能改过名，
     * 按名字配会错过原文件，转而秒传一份新的，原文件仍在回收站里占着空间。一份原文件只配一条，同名的优先。
     */
    private fun trashOriginals(byFolder: Map<String, List<VaultEntry>>, trash: List<FileStat>): Map<String, FileStat> {
        val paired = mutableMapOf<String, FileStat>()
        val used = mutableSetOf<String>()
        for ((folderId, entries) in byFolder) {
            val candidates = trash.filter { !it.isFolder && it.parentId == folderId }
            if (candidates.isEmpty()) continue
            for (entry in entries.sortedByDescending { entry -> candidates.any { it.name == entry.name } }) {
                val original = candidates
                    .filter { it.id !in used && it.hash.equals(entry.gcid, ignoreCase = true) && it.sizeBytes == entry.size }
                    .minByOrNull { if (it.name == entry.name) 0 else 1 } ?: continue
                used += original.id
                paired[entry.id] = original
            }
        }
        return paired
    }

    /**
     * [rootId] 整棵树，每层一项：目录 ID 与其中能归档的文件。已是归档条目的、清单文件、还在上传的、
     * 没有 gcid 的都不算；Piko-Temp 与同步设置的 .piko 不进去。
     */
    private suspend fun walk(rootId: String): List<Pair<String, List<FileStat>>> {
        val levels = mutableListOf<Pair<String, List<FileStat>>>()
        var queue = listOf(rootId)
        val visited = mutableSetOf(rootId)
        while (queue.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (chunk in queue.chunked(4)) {
                val listings = coroutineScope { chunk.map { id -> async { operations.list(id) } }.awaitAll() }
                chunk.zip(listings).forEach { (folderId, listing) ->
                    listing.filter { it.isFolder && it.name !in SKIPPED_FOLDERS && !it.trashed }
                        .forEach { if (visited.add(it.id)) next += it.id }
                    levels += folderId to listing.filter(::archivable)
                }
            }
            queue = next
        }
        return levels
    }

    private fun archivable(file: FileStat): Boolean =
        !file.isFolder && !file.trashed && !file.isVaulted && !VaultStore.looksLikeManifest(file) && !VaultStore.isRecoveryFile(file) &&
            file.phase == TaskPhase.COMPLETE && file.hash.isNotBlank()

    private companion object {
        const val TAG = "Vault"
    }
}

private val SKIPPED_FOLDERS = setOf("Piko-Temp", ".piko")

internal interface FolderVaultOperations {
    suspend fun list(folderId: String): List<FileStat>
    suspend fun sampleCid(file: FileStat): String?
    suspend fun write(folderId: String, entries: List<VaultEntry>)
    suspend fun remove(ids: List<String>, permanently: Boolean)
    fun record(change: DriveChangeJournal.Change.Vault)
    fun refresh()

    /** [folderId] 的子文件夹与仍归档着的条目。清单读不出来就失败：当作没有条目会漏掉恢复。 */
    suspend fun archived(folderId: String): ArchivedLevel
    suspend fun trash(): List<FileStat>
    /** 剩余空间，不限量或查不到时为 null。 */
    suspend fun remainingBytes(): Long?
    suspend fun untrash(ids: List<String>)
    suspend fun rename(id: String, name: String)
    /** 按 gcid 秒传回 [folderId]，返回新文件的 ID。云端已不存时抛 InstantContentUnavailableException。 */
    suspend fun recreate(entry: VaultEntry, folderId: String): String
    suspend fun markRestored(folderId: String, files: Map<String, FileStat>): VaultWrite
}

internal class ArchivedLevel(val subfolders: List<FileStat>, val entries: List<VaultEntry>)

private class DriveFolderVaultOperations(private val drive: PikoDriveRepository) : FolderVaultOperations {
    override suspend fun list(folderId: String) = drive.listBrowsable(folderId, dev.piko.shared.data.PikoFileSortOrder.TIME_DESC).getOrThrow()
    override suspend fun sampleCid(file: FileStat) = drive.sampleCid(file.id).getOrNull()
    override suspend fun write(folderId: String, entries: List<VaultEntry>) {
        drive.vault.update(folderId, VaultEdits.add(entries)).getOrThrow()
    }
    override suspend fun remove(ids: List<String>, permanently: Boolean) {
        if (permanently) drive.delete(ids).getOrThrow() else drive.trash(ids).getOrThrow()
    }
    override fun record(change: DriveChangeJournal.Change.Vault) = drive.changes.record(change)
    override fun refresh() = drive.requestRefresh()

    override suspend fun archived(folderId: String): ArchivedLevel {
        val listing = drive.listAllFiles(folderId).getOrThrow()
        val entries = drive.vault.read(folderId, listing).getOrThrow().filter { it.isArchived }
        val subfolders = listing.filter { it.isFolder && !it.trashed && it.name !in SKIPPED_FOLDERS }
        return ArchivedLevel(subfolders, entries)
    }
    override suspend fun trash() = drive.trashFiles().getOrThrow()
    override suspend fun remainingBytes() = drive.getQuota().getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.remainingBytes
    override suspend fun untrash(ids: List<String>) = drive.restore(ids).getOrThrow()
    override suspend fun rename(id: String, name: String) = drive.rename(id, name).getOrThrow()
    override suspend fun recreate(entry: VaultEntry, folderId: String) =
        drive.instantCreate(ResolvedFile(path = entry.name, size = entry.size, gcid = entry.gcid), folderId).getOrThrow()
    override suspend fun markRestored(folderId: String, files: Map<String, FileStat>) =
        drive.vault.update(folderId, VaultEdits.restore(files)).getOrThrow()
}

/** CID 是可选的体检资料；取样并发受限，同内容只取一次，慢节点超时不阻塞归档。 */
internal class VaultCidSampler(
    private val scope: CoroutineScope,
    private val sample: suspend (FileStat) -> String?,
    private val timeoutMillis: Long,
) {
    private val slots = Semaphore(16)
    private val lock = Mutex()
    private val samples = mutableMapOf<Pair<String, Long>, Deferred<String?>>()

    suspend fun sample(file: FileStat): String? {
        val key = file.hash.uppercase() to file.sizeBytes
        val result = lock.withLock {
            samples.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    slots.withPermit {
                        withTimeoutOrNull(timeoutMillis) {
                            try { sample.invoke(file) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        }
                    }
                }.also { it.start() }
            }
        }
        return result.await()
    }
}
