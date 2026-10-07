package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScanStop
import dev.piko.shared.data.ScannedFile
import dev.piko.shared.data.VaultStore
import dev.piko.shared.log.PikoLog
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.naming.av.AvTreeItem
import dev.piko.shared.naming.av.AvTreeName
import dev.piko.shared.naming.av.canonicalAvTree
import dev.piko.shared.naming.av.isAvContent
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.rename.RenameProblem
import dev.piko.shared.rename.RenameRun
import dev.piko.shared.rename.RenameSource
import dev.piko.shared.rename.planRenames
import dev.piko.shared.scrape.MetaTubeService
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条改名建议：网盘里的一项、它的规范名与改不了的原因。[folderPath] 是所在目录相对起点的路径，起点下为空串。 */
data class CanonicalSuggestion(val file: FileStat, val newName: String, val problem: RenameProblem?, val folderPath: String)

/** 一部作品的建议，按番号归在一起：资源文件夹、视频与跟着视频改的字幕。 */
data class CanonicalGroup(val code: String, val rows: List<CanonicalSuggestion>)

/**
 * 按番号规范命名一个文件夹：递归扫描 [root]，按资源文件夹的规则（canonicalAvTree）算出每一项的规范名，列出要改的。
 * 与查找重复同一种形态，结果在网盘页的「按番号规范命名」位置（DriveLibrary.CANONICAL_NAMES）里看，勾选是网盘页的多选。
 *
 * 创建即开始扫描；配了 MetaTube 时扫完先查片名，再出建议。应用的改名走 [RenameRun]，与批量重命名同一套执行与撤销。
 *
 * 建议是扫描那一刻的快照，之后经改动日志（[DriveChangeJournal]）改了名、移进回收站的，不管在哪一页做的，都在快照上跟着改，
 * 再重新算一遍：改成规范名的那几行就此消失，撤销回来的又出现。
 */
class CanonicalNamingState(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val metaTube: MetaTubeService?,
    private val scope: CoroutineScope,
    override val root: PikoPathBreadcrumb,
) : FolderTask {
    enum class Phase { SCANNING, TITLES, DONE, FAILED }

    var phase by mutableStateOf(Phase.SCANNING)
        private set
    var scannedFolders by mutableStateOf(0)
        private set
    var scannedFiles by mutableStateOf(0)
        private set
    var scanStop by mutableStateOf<ScanStop?>(null)
        private set
    var failedFolders by mutableStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 查片名的进度（已完成，总数），不在查时为 null。 */
    var titlesProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 按作品分好的建议，番号的字母序。 */
    var groups by mutableStateOf<List<CanonicalGroup>>(emptyList())
        private set

    val isScanning: Boolean get() = phase == Phase.SCANNING || phase == Phase.TITLES

    /** 建议改名的项数，含改不了的。 */
    val suggestionCount: Int by derivedStateOf { groups.sumOf { it.rows.size } }

    /** 能直接应用的：没有冲突、不超长、不含 PikPak 不收的字符。 */
    val applicableIds: Set<String> by derivedStateOf {
        groups.flatMapTo(HashSet()) { group -> group.rows.filter { it.problem == null }.map { it.file.id } }
    }

    private val suggestionById: Map<String, CanonicalSuggestion> by derivedStateOf {
        groups.flatMap { it.rows }.associateBy { it.file.id }
    }

    fun suggestion(id: String): CanonicalSuggestion? = suggestionById[id]

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 应用后一项也没改成时的结果；改成了的经改动日志提示，带「撤销」。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 正在应用；进度在 [applyRun] 上。 */
    var isApplying by mutableStateOf(false)
        private set

    /** 最近一次应用的执行，界面读进度与失败项。 */
    var applyRun by mutableStateOf<RenameRun?>(null)
        private set

    // 扫到的条目，按 ID；改名、移进回收站时跟着改，见类注释
    private var entries: Map<String, ScannedFile> = emptyMap()
    private var gone: Set<String> = emptySet()

    // 起点自己也是一项：它本身就是一份资源时同样改名。起点是网盘根目录或查不到详情时为 null
    private var rootEntry: FileStat? = null
    private var rootSiblings: Set<String> = emptySet()
    private var titles: Map<String, String> = emptyMap()

    private var scanner: DuplicateScanner? = null
    private var scanJob: Job? = null
    private var titlesJob: Job? = null
    private var applyJob: Job? = null

    init {
        rescan()
        scope.launch {
            driveRepo.changes.events.collect { event ->
                val renamed = (event.change as? DriveChangeJournal.Change.Rename)?.renames
                val unrenamed = (event.undone as? DriveChangeJournal.Change.Rename)?.renames?.asReversed()?.map {
                    DriveChangeJournal.Renamed(it.id, it.newName, it.oldName)
                }
                val trashed = (event.change as? DriveChangeJournal.Change.Trash)?.ids
                val restored = (event.undone as? DriveChangeJournal.Change.Trash)?.ids
                when {
                    renamed != null -> applyRenames(renamed)
                    unrenamed != null -> applyRenames(unrenamed)
                    trashed != null -> updateGone(gone + trashed)
                    restored != null -> updateGone(gone - restored.toSet())
                }
            }
        }
    }

    fun rescan() {
        scanJob?.cancel()
        titlesJob?.cancel()
        val scanner = DuplicateScanner(clients).also { scanner = it }
        phase = Phase.SCANNING
        scannedFolders = 0
        scannedFiles = 0
        scanStop = null
        failedFolders = 0
        errorMessage = null
        titlesProgress = null
        groups = emptyList()
        entries = emptyMap()
        gone = emptySet()
        scanJob = scope.launch {
            try {
                loadRoot()
                scanner.scan(root.id).collect { event ->
                    when (event) {
                        is DuplicateScanEvent.Progress -> {
                            scannedFolders = event.folders
                            scannedFiles = event.files
                        }
                        is DuplicateScanEvent.Finished -> {
                            scannedFolders = event.folders
                            scannedFiles = event.files.size
                            scanStop = event.stop
                            failedFolders = event.failedFolders
                            // 归档清单与 .piko 里的同步文件是 Piko 自己的，不是要整理的内容
                            entries = (event.subfolders + event.files)
                                .filterNot { VaultStore.looksLikeManifest(it.file) }
                                .associateBy { it.file.id }
                        }
                    }
                }
                fetchTitles()
                replan()
                phase = Phase.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "按番号规范命名的扫描失败", e)
                errorMessage = e.message ?: "未知错误"
                phase = Phase.FAILED
            }
        }
    }

    /** 提前结束扫描，已扫描的部分照常出建议。 */
    fun stopScan() {
        scanner?.stop()
    }

    /** 不等片名查完，查到的照用，其余用原名里的片名。 */
    fun skipTitles() {
        titlesJob?.cancel()
    }

    /**
     * 应用 [ids] 里能应用的建议（有问题的跳过），批量串行，见 [RenameRun]。整批记一条改动，撤销即改回原名。
     * 只改所选的：没选的保持原名，冲突照这样重新判断。
     */
    fun apply(ids: Set<String>) {
        if (isApplying) return
        val chosen = ids intersect applicableIds
        if (chosen.isEmpty()) return
        val current = snapshot()
        val newNames = current.sources.mapIndexed { index, source -> if (source.id in chosen) current.names[index].name else source.name }
        val plan = planRenames(current.sources, newNames, siblingsOf())
        val run = RenameRun(driveRepo, TAG)
        applyRun = run
        isApplying = true
        PikoLog.i(TAG, "应用规范命名：${plan.changeCount} 项，${plan.steps.size} 步")
        applyJob = scope.launch {
            try {
                run.execute(plan) { id, name -> renameEntry(id, name) }
            } finally {
                isApplying = false
                val summary = when {
                    run.failures.isEmpty() -> "已按番号规范命名 ${run.succeeded} 项"
                    else -> "已按番号规范命名 ${run.succeeded} 项，${run.failures.size} 项失败"
                }
                // 改成了的记进改动记录，提示带「撤销」；一项也没改成的只报结果
                if (run.renamed.isNotEmpty()) {
                    driveRepo.changes.record(DriveChangeJournal.Change.Rename(run.renamed.toList(), summary))
                } else {
                    _messages.tryEmit(summary)
                }
                scope.launch { replan() }
            }
        }
    }

    /** 停在当前这一项之后。正在发出的请求可能已在服务端生效。 */
    fun stopApplying() {
        applyJob?.cancel()
    }

    private suspend fun loadRoot() {
        rootEntry = null
        rootSiblings = emptySet()
        if (root.id.isEmpty()) return
        val detail = driveRepo.getFileDetail(root.id).getOrNull() ?: return
        // 起点所在目录里别的条目叫什么，起点改名时据此判断重名；列不出来就不改起点
        val siblings = driveRepo.listAllFiles(detail.parentId).getOrNull() ?: return
        rootEntry = FileStat(kind = detail.kind, id = detail.id, parentId = detail.parentId, name = detail.name)
        rootSiblings = siblings.filter { it.id != detail.id }.map { it.name }.toSet()
    }

    private suspend fun fetchTitles() {
        val service = metaTube ?: return
        if (!service.enabled.first()) return
        val videos = entries.values.mapNotNull { entry ->
            if (entry.file.isFolder) return@mapNotNull null
            val parsed = parseMediaName(entry.file.name)
            parsed.av?.takeIf { parsed.fileKind.isAvContent }
        }
        if (videos.isEmpty()) return
        phase = Phase.TITLES
        val found = HashMap<String, String>()
        titlesProgress = 0 to videos.distinctBy(AvInfo::code).size
        val job = scope.launch {
            service.titles(videos, onProgress = { done, total -> titlesProgress = done to total }, onFound = { code, title -> found[code] = title })
        }
        titlesJob = job
        job.join()
        titlesProgress = null
        titles = found.toMap()
    }

    private fun applyRenames(renames: List<DriveChangeJournal.Renamed>) {
        val touched = renames.filter { it.id in entries || it.id == rootEntry?.id }
        if (touched.isEmpty()) return
        touched.forEach { renameEntry(it.id, it.newName) }
        if (phase == Phase.DONE && !isApplying) scope.launch { replan() }
    }

    private fun renameEntry(id: String, name: String) {
        entries[id]?.let { entry -> entries = entries + (id to entry.copy(file = entry.file.copy(name = name))) }
        rootEntry?.takeIf { it.id == id }?.let { rootEntry = it.copy(name = name) }
    }

    private fun updateGone(next: Set<String>) {
        if (next == gone) return
        gone = next
        if (phase == Phase.DONE && !isApplying) scope.launch { replan() }
    }

    /** 当前快照里的各项（[RenameSource]）与它们的规范名，一一对应。 */
    private class Snapshot(val sources: List<RenameSource>, val files: List<FileStat>, val names: List<AvTreeName>)

    private fun snapshot(): Snapshot {
        val files = listOfNotNull(rootEntry) + entries.values.map { it.file }.filter { it.id !in gone }
        val names = canonicalAvTree(files.map { AvTreeItem(it.id, it.parentId, it.name, it.isFolder) }, titles)
        return Snapshot(files.map { RenameSource(it.id, it.parentId, it.name, it.isFolder) }, files, names)
    }

    // 扫描范围里的目录都在快照里，只有起点所在的那一层要另给
    private fun siblingsOf(): Map<String, Set<String>> = rootEntry?.let { mapOf(it.parentId to rootSiblings) }.orEmpty()

    private suspend fun replan() {
        groups = withContext(Dispatchers.Default) { suggest() }
    }

    private fun suggest(): List<CanonicalGroup> {
        val current = snapshot()
        val plan = planRenames(current.sources, current.names.map { it.name }, siblingsOf())
        val rows = plan.rows.withIndex().filter { it.value.isChanged }.map { (index, row) ->
            val file = current.files[index]
            val folderPath = entries[file.id]?.folderPath.orEmpty()
            current.names[index].code.orEmpty() to CanonicalSuggestion(file, row.newName, row.problem, folderPath)
        }
        return rows.groupBy({ it.first }, { it.second }).map { (code, suggestions) ->
            // 文件夹排在前面，其余按新名字
            CanonicalGroup(code, suggestions.sortedWith(compareBy({ !it.file.isFolder }, { it.newName })))
        }.sortedBy { it.code }
    }

    private companion object {
        const val TAG = "CanonicalNaming"
    }
}
