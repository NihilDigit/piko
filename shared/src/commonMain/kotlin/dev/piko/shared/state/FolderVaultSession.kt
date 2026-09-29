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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * 把文件夹里的真实文件换成归档条目，腾出网盘空间。进程级：离开网盘页照常进行，一次归档一个文件夹。
 *
 * 逐层做：一层的条目写进这一层的清单、写成之后，才处置这一层的原文件。清单没写成就不动文件，
 * 中途失败时已做完的几层保持归档，其余原样。
 *
 * 原文件怎么处置看账号：会员移进回收站，出了岔子十五天内还能找回；免费账号直接删除，因为回收站里的文件
 * 照样占空间（2026-09-29 实测，移进回收站 45 秒用量不变，彻底删除 6 秒即还回），移进去等于没腾出来。
 * 整次归档记一条可撤销的改动：会员从回收站恢复，免费账号按 gcid 秒传回去，再去掉清单里的条目。
 *
 * 每个文件归档时读 60 KB 算出 CID，日后只读体检用（gcidByCid 只收 CID）；算不出就不记，不挡归档。
 */
class FolderVaultSession(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
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
    )

    /** 正在归档的文件夹与进度。 */
    class Progress(val folderName: String, val done: Int, val total: Int)

    var progress by mutableStateOf<Progress?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    /** 清点 [folder] 整棵树里能归档的文件。 */
    suspend fun survey(folder: PikoPathBreadcrumb): Result<Survey> = runSuspendCatching {
        val files = walk(folder.id).flatMap { it.second }
        val unsourced = files.filter { it.sourceUrl.isNullOrBlank() }
        Survey(files.size, files.sumOf { it.sizeBytes }, unsourced.size, unsourced.sumOf { it.sizeBytes }, deletesOriginals())
    }

    private suspend fun deletesOriginals(): Boolean = driveRepo.isFreeAccount() == true

    /** 开始归档 [folder]。[includeUnsourced] 为假时没有来源记录的文件原样留着。已有一个在做时不接。 */
    fun archive(folder: PikoPathBreadcrumb, includeUnsourced: Boolean) {
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
                    folderId to files.filter { includeUnsourced || !it.sourceUrl.isNullOrBlank() }
                }.filter { it.second.isNotEmpty() }
                val total = levels.sumOf { it.second.size }
                var done = 0
                progress = Progress(folder.name, 0, total)
                for ((folderId, files) in levels) {
                    val addedAt = Clock.System.now().toEpochMilliseconds()
                    val entries = files.map { file ->
                        val cid = driveRepo.sampleCid(file.id).getOrNull()
                        progress = Progress(folder.name, ++done, total)
                        VaultEntry.create(file.name, file.sizeBytes, file.hash, file.sourceUrl, addedAt, cid)
                    }
                    driveRepo.vault.update(folderId, VaultEdits.add(entries)).getOrThrow()
                    if (delete) {
                        driveRepo.delete(files.map { it.id }).getOrThrow()
                        deleted[folderId] = entries
                    } else {
                        reverts[folderId] = VaultEdits.remove(entries.mapTo(HashSet()) { it.id })
                        driveRepo.trash(files.map { it.id }).getOrThrow()
                        trashed += files.map { it.id }
                    }
                }
                total
            }
            progress = null
            // 做完的几层记成一条改动，哪怕后面失败了：撤销得回已经归档的那些
            if (reverts.isNotEmpty() || deleted.isNotEmpty()) {
                val count = result.getOrNull()?.let { "已归档 $it 个文件" } ?: "部分文件已归档，其余未变动"
                val summary = if (deleted.isNotEmpty()) "$count，原文件已删除" else "$count，原文件已移入回收站"
                driveRepo.changes.record(
                    DriveChangeJournal.Change.Vault(reverts, summary, untrashOnRevert = trashed, recreateOnRevert = deleted),
                )
            } else if (result.getOrNull() == 0) {
                _messages.tryEmit("无可归档的文件")
            }
            result.reportFailure(TAG, "归档") { _messages.tryEmit(it) }
            driveRepo.requestRefresh()
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
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            val folderId = queue.removeFirst()
            val listing = driveRepo.listAllFiles(folderId).getOrThrow()
            listing.filter { it.isFolder && it.name !in SKIPPED_FOLDERS }.forEach { queue.addLast(it.id) }
            levels += folderId to listing.filter(::archivable)
        }
        return levels
    }

    private fun archivable(file: FileStat): Boolean =
        !file.isFolder && !file.isVaulted && !VaultStore.looksLikeManifest(file) &&
            file.phase == TaskPhase.COMPLETE && file.hash.isNotBlank()

    private companion object {
        const val TAG = "Vault"
        val SKIPPED_FOLDERS = setOf("Piko-Temp", ".piko")
    }
}
