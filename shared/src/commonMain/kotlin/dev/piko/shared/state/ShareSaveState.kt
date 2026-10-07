package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.naming.av.AvTreeItem
import dev.piko.shared.naming.av.canonicalAvName
import dev.piko.shared.naming.av.canonicalAvNameOf
import dev.piko.shared.naming.av.matchAvFolder
import dev.piko.shared.rename.RenameRun
import dev.piko.shared.rename.planCanonicalTree
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlin.time.TimeSource

/**
 * 转存一个 PikPak 分享：逐层浏览，勾选当前这一层的条目，转存到目标目录。
 *
 * 勾选只在当前层有效，换层即清空：转存要带上条目所在的各级分享目录，跨层勾选得按目录拆成几次请求，
 * 而分享多半顶层只有一个文件夹，勾它就是整个分享。转存后文件直接落在目标目录下，不带分享里的上级目录。
 */
class ShareSaveState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val shareId: String,
    /** 随链接一起转发来的提取码，有就直接用上，省得再问一遍。 */
    initialPassCode: String = "",
) {
    var info by mutableStateOf<ShareInfo?>(null)
        private set

    /** 分享设了提取码而还没给或给错了。给错时 [errorMessage] 另有说明。 */
    var needsPassCode by mutableStateOf(false)
        private set
    var passCode by mutableStateOf(initialPassCode)

    /** 进到的分享目录，不含顶层。 */
    val path = mutableStateListOf<PikoPathBreadcrumb>()
    var entries by mutableStateOf<List<FileStat>>(emptyList())
        private set
    val selectedIds = mutableStateListOf<String>()

    var isLoading by mutableStateOf(true)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 转存成功后的提示，界面显示后由它清空。 */
    var doneMessage by mutableStateOf<String?>(null)

    val selectedBytes: Long by derivedStateOf { entries.filter { it.id in selectedIds }.sumOf { it.sizeBytes } }

    private var loadJob: Job? = null

    init {
        open()
    }

    /** 读顶层。带着 [passCode]；没设提取码的分享传空串即可。 */
    fun open() {
        load {
            driveRepo.shareInfo(shareId, passCode.trim())
                .onSuccess { share ->
                    info = share
                    needsPassCode = false
                    path.clear()
                    entries = share.files
                }
                .onFailure { error ->
                    val unavailable = error as? ShareUnavailableException
                    // 分享 ID 与提取码合起来就能打开分享，都不进日志
                    if (unavailable?.needsPassCode == true) {
                        PikoLog.i(TAG, "打开分享：${if (passCode.isBlank()) "需要提取码" else "提取码错误"}")
                        needsPassCode = true
                        errorMessage = if (passCode.isBlank()) null else "提取码错误"
                    } else {
                        PikoLog.w(TAG, "打开分享失败${if (unavailable != null) "：分享已失效" else ""}", error)
                        // 服务端的 statusText 可能是英文，不直接显示；能回 200 却读不了的，是取消或过期了
                        errorMessage = if (unavailable != null) "分享已失效" else "无法读取分享"
                    }
                }
        }
    }

    fun enter(folder: FileStat) {
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    path += PikoPathBreadcrumb(folder.id, folder.name)
                    entries = it
                }
                .logFailure(TAG, "列出分享里的文件夹失败：${folder.id}")
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    /** 回到第 [depth] 层；0 是顶层。 */
    fun goTo(depth: Int) {
        if (depth >= path.size) return
        if (depth == 0) {
            val share = info ?: return
            path.clear()
            entries = share.files
            selectedIds.clear()
            return
        }
        val folder = path[depth - 1]
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    while (path.size > depth) path.removeAt(path.lastIndex)
                    entries = it
                }
                .logFailure(TAG, "列出分享里的文件夹失败：${folder.id}")
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    fun toggle(file: FileStat) {
        if (!selectedIds.remove(file.id)) selectedIds += file.id
    }

    fun toggleAll() {
        if (selectedIds.size == entries.size) selectedIds.clear() else {
            selectedIds.clear()
            selectedIds += entries.map { it.id }
        }
    }

    // region 按番号规范命名

    /**
     * 转存后这一项会改成的名字，不改时为 null，列表行据此预览。文件夹只看名字里的番号：里面有什么要转存后才列得出，
     * 实际以转存后按 [canonicalAvTree] 算出的为准。
     */
    fun canonicalPreview(file: FileStat): String? {
        val name = if (file.isFolder) {
            matchAvFolder(file.name)?.let { canonicalAvName(file.name, it, isFolder = true) }
        } else {
            canonicalAvNameOf(file.name)
        }
        return name?.takeIf { it != file.name }
    }

    /** 眼前这一层里有会改名的，面板据此给出「按番号规范命名」。 */
    val offersCanonicalNames: Boolean by derivedStateOf { entries.any { canonicalPreview(it) != null } }

    /**
     * 转存来的条目按番号规范命名。转存接口没有名称参数，也不返回新条目的 ID（见 PikoDriveRepository.restoreFromShare），
     * 只能比对转存前后目标目录的列表找出新来的，再改名。转存来的文件夹当作资源：名字里没有番号也照其中的番号命名。
     * 改成了的记一条可撤销的改动，返回改成的项数。
     */
    private suspend fun nameRestored(target: PikoPathBreadcrumb, before: Set<String>): Int {
        val after = driveRepo.listAllFiles(target.id).logFailure(TAG, "转存后列出目标目录失败，不改名").getOrElse { return 0 }
        val restored = after.filter { it.id !in before }
        if (restored.isEmpty()) return 0
        val items = restored.map { AvTreeItem(it.id, target.id, it.name, it.isFolder) }.toMutableList()
        for (folder in restored.filter { it.isFolder }) {
            val scanner = DuplicateScanner(listFolder = { id -> driveRepo.listAllFiles(id).getOrThrow() })
            val finished = runSuspendCatching { scanner.scan(folder.id).filterIsInstance<DuplicateScanEvent.Finished>().first() }
                .logFailure(TAG, "列出转存来的文件夹失败，不改其中的名字").getOrNull() ?: continue
            (finished.subfolders + finished.files).forEach { entry ->
                items += AvTreeItem(entry.file.id, entry.file.parentId, entry.file.name, entry.file.isFolder)
            }
        }
        val siblings = mapOf(target.id to after.filter { it.id in before }.map { it.name }.toSet())
        val plan = planCanonicalTree(items, siblings, resources = restored.filter { it.isFolder }.map { it.id }.toSet())
        if (plan.steps.isEmpty()) return 0
        val run = RenameRun(driveRepo, TAG)
        try {
            run.execute(plan)
        } finally {
            if (run.renamed.isNotEmpty()) {
                driveRepo.changes.record(DriveChangeJournal.Change.Rename(run.renamed.toList(), "已按番号规范命名 ${run.succeeded} 项"))
            }
        }
        PikoLog.i(TAG, "转存后按番号规范命名：${run.succeeded} 项，${plan.problemCount} 项有冲突未改，${run.failures.size} 项失败")
        return run.succeeded
    }

    // endregion

    /** [canonicalNames] 为真时转存后按番号规范命名，见 [nameRestored]。 */
    fun save(target: PikoPathBreadcrumb, canonicalNames: Boolean = false) {
        val token = info?.passCodeToken ?: return
        val ids = selectedIds.toList()
        if (ids.isEmpty() || isSaving) return
        isSaving = true
        errorMessage = null
        val bytes = selectedBytes
        val started = TimeSource.Monotonic.markNow()
        scope.launch {
            // 转存前的列表，转存后据此认出新来的。列不出来就照原名存，不为改名挡住转存
            val before = if (canonicalNames) {
                driveRepo.listAllFiles(target.id).logFailure(TAG, "转存前列出目标目录失败，不改名").getOrNull()?.map { it.id }?.toSet()
            } else {
                null
            }
            driveRepo.restoreFromShare(shareId, token, ids, target.id, ancestorIds = path.map { it.id })
                .logFailure(TAG, "转存分享失败：${ids.size} 项，$bytes 字节，到文件夹 ${target.id}，分享内第 ${path.size} 层")
                .onSuccess {
                    PikoLog.i(TAG, "已转存分享：${ids.size} 项，$bytes 字节，到文件夹 ${target.id}，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
                    selectedIds.clear()
                    val renamed = before?.let { nameRestored(target, it) } ?: 0
                    doneMessage = "已转存 ${ids.size} 项到 ${target.name}" + if (renamed > 0) "，按番号规范命名 $renamed 项" else ""
                }
                .onFailure { errorMessage = "转存失败：${it.message}" }
            isSaving = false
        }
    }

    private companion object {
        const val TAG = "ShareSave"
    }

    private fun load(block: suspend () -> Unit) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        selectedIds.clear()
        loadJob = scope.launch {
            block()
            isLoading = false
        }
    }
}
