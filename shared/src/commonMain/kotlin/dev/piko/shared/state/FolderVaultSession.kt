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
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.reportFailure
import io.github.nihildigit.pikpak.FileStat
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
 * 把文件夹里的真实文件换成归档条目，腾出网盘空间。进程级：离开网盘页照常进行，一次归档一个文件夹。
 *
 * 八个目录并行处理，各自清单确认写成后才处置原文件。中途失败时已写成的引用与恢复依据保留。
 *
 * 原文件怎么处置看账号：会员移进回收站，出了岔子十五天内还能找回；免费账号直接删除，因为回收站里的文件
 * 照样占空间（2026-09-29 实测，移进回收站 45 秒用量不变，彻底删除 6 秒即还回），移进去等于没腾出来。
 * 整次归档记一条可撤销的改动：会员从回收站恢复，免费账号按 gcid 秒传回去，再去掉清单里的条目。
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
     * [deletesOriginals] 为真时原文件直接删除（免费账号），否则移进回收站。
     */
    class Survey(
        val files: Int,
        val bytes: Long,
        val unsourcedFiles: Int,
        val unsourcedBytes: Long,
        val deletesOriginals: Boolean,
        val largeFiles: Survey? = null,
    )

    /** 正在归档的文件夹与进度。 */
    class Progress(val folderName: String, val done: Int, val total: Int, val prepared: Int = done)

    var progress by mutableStateOf<Progress?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    /** 清点 [folder] 整棵树里能归档的文件。 */
    suspend fun survey(folder: PikoPathBreadcrumb): Result<Survey> = runSuspendCatching {
        val files = walk(folder.id).flatMap { it.second }
        val unsourced = files.filter { it.sourceUrl.isNullOrBlank() }
        val large = files.filter { it.sizeBytes >= LARGE_FILE_MIN_BYTES }
        val largeUnsourced = large.filter { it.sourceUrl.isNullOrBlank() }
        val delete = deletesOriginals()
        Survey(files.size, files.sumOf { it.sizeBytes }, unsourced.size, unsourced.sumOf { it.sizeBytes }, delete,
            Survey(large.size, large.sumOf { it.sizeBytes }, largeUnsourced.size, largeUnsourced.sumOf { it.sizeBytes }, delete))
    }

    private suspend fun deletesOriginals(): Boolean = operations.deletesOriginals()

    /** 开始归档 [folder]。[includeUnsourced] 为假时没有来源记录的文件原样留着。已有一个在做时不接。 */
    fun archive(folder: PikoPathBreadcrumb, includeUnsourced: Boolean, onlyLargeFiles: Boolean = false) {
        if (job?.isActive == true) {
            _messages.tryEmit("「${progress?.folderName}」归档中，请稍后再试")
            return
        }
        job = scope.launch {
            val reverts = mutableMapOf<String, VaultEdit>()
            val trashed = mutableListOf<String>()
            val deleted = mutableMapOf<String, List<VaultEntry>>()
            val result = runSuspendCatching {
                val delete = deletesOriginals()
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
                                val cid = sampler.sample(file)
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
                                else reverts[folderId] = VaultEdits.remove(entries.mapTo(HashSet()) { it.id })
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
        !file.isFolder && !file.trashed && !file.isVaulted && !VaultStore.looksLikeManifest(file) &&
            file.phase == TaskPhase.COMPLETE && file.hash.isNotBlank()

    private companion object {
        const val TAG = "Vault"
        val SKIPPED_FOLDERS = setOf("Piko-Temp", ".piko")
    }
}

internal interface FolderVaultOperations {
    suspend fun list(folderId: String): List<FileStat>
    suspend fun deletesOriginals(): Boolean
    suspend fun sampleCid(file: FileStat): String?
    suspend fun write(folderId: String, entries: List<VaultEntry>)
    suspend fun remove(ids: List<String>, permanently: Boolean)
    fun record(change: DriveChangeJournal.Change.Vault)
    fun refresh()
}

private class DriveFolderVaultOperations(private val drive: PikoDriveRepository) : FolderVaultOperations {
    override suspend fun list(folderId: String) = drive.listAllFiles(folderId).getOrThrow()
    override suspend fun deletesOriginals() = drive.isFreeAccount() == true
    override suspend fun sampleCid(file: FileStat) = drive.sampleCid(file.id).getOrNull()
    override suspend fun write(folderId: String, entries: List<VaultEntry>) {
        drive.vault.update(folderId, VaultEdits.add(entries)).getOrThrow()
    }
    override suspend fun remove(ids: List<String>, permanently: Boolean) {
        if (permanently) drive.delete(ids).getOrThrow() else drive.trash(ids).getOrThrow()
    }
    override fun record(change: DriveChangeJournal.Change.Vault) = drive.changes.record(change)
    override fun refresh() = drive.requestRefresh()
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
