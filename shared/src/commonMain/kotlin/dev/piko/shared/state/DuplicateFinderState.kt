package dev.piko.shared.state

import dev.piko.shared.i18n.tr
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScanStop
import dev.piko.shared.data.ScannedFile
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 查找重复：递归扫描 [root]，分出完全相同与同集不同版本两类。
 *
 * 创建即开始扫描。扫描在 [scope] 里进行，调用方结束这一次时取消 scope 即可一并停下。
 * 结果在网盘页的「查找重复」位置里按组列出，勾选与移入回收站都是网盘页的多选与删除，这里不另记勾选。
 *
 * 结果基于扫描那一刻的快照，并订阅仓库乐观投影（[PikoDriveRepository.optimisticRevision]）与目录变更
 * （[PikoDriveRepository.folderChanges]）：移入回收站的文件从结果里拿掉再重新分组，使代表版本组的那一行被移走后
 * 由相同副本接替；撤销恢复后自动补回。
 */
class DuplicateFinderState(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    override val root: PikoPathBreadcrumb,
) : FolderTask {
    enum class Phase { SCANNING, ANALYZING, DONE, FAILED }

    var phase by mutableStateOf(Phase.SCANNING)
        private set
    var scannedFolders by mutableStateOf(0)
        private set
    var scannedFiles by mutableStateOf(0)
        private set

    /** 扫描提前结束的原因，完整走完为 null。结果只覆盖已扫描的部分。 */
    var scanStop by mutableStateOf<ScanStop?>(null)
        private set

    /** 列不出来而跳过的目录数。 */
    var failedFolders by mutableStateOf(0)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    var report by mutableStateOf(DuplicateReport.EMPTY)
        private set

    private var scanned by mutableStateOf<List<ScannedFile>>(emptyList())

    /** 扫描后移入回收站的。不从 [scanned] 里删：撤销时要按原样补回。 */
    private var gone: Set<String> = emptySet()
    private val regroupLock = Mutex()
    private var groupedFiles: List<ScannedFile>? = null
    private var groupedRemoved: Set<String>? = null

    private val statsById: Map<String, FileStat> by derivedStateOf {
        val files = scanned
        if (files.isEmpty()) {
            emptyMap()
        } else {
            HashMap<String, FileStat>(files.size * 4 / 3 + 1).apply {
                for (i in 0 until files.size) {
                    val stat = files[i].file
                    put(stat.id, stat)
                }
            }
        }
    }

    /** 结果里出现的文件，按 ID 取回完整的 [FileStat]，网盘页据此画缩略图、打开与操作。 */
    fun fileStat(id: String): FileStat? = statsById[id]

    /** 建议移走的：完全相同的组里，默认保留的那份以外的。版本组不建议，挑哪一版由人定。 */
    val suggestedIds: Set<String> by derivedStateOf {
        val identical = report.identical
        if (identical.isEmpty()) {
            emptySet()
        } else {
            val ids = HashSet<String>(identical.size * 2)
            for (i in 0 until identical.size) {
                val group = identical[i]
                val keptId = group.keptId
                val rows = group.rows
                for (j in 0 until rows.size) {
                    val id = rows[j].file.id
                    if (id != keptId) ids.add(id)
                }
            }
            ids
        }
    }

    /** 还在扫描或比对，结果还没出来。 */
    val isScanning: Boolean get() = phase == Phase.SCANNING || phase == Phase.ANALYZING

    private var scanner: DuplicateScanner? = null
    private var scanJob: Job? = null

    private val rootName: String? get() = root.name.takeIf { root.id.isNotEmpty() }

    init {
        scope.launch {
            driveRepo.optimisticRevision.collect {
                if (phase == Phase.DONE) regroup()
            }
        }
        rescan()
        scope.launch {
            driveRepo.folderChanges.collect { change ->
                when (change) {
                    is DriveChange.Trashed -> updateGone(gone + change.ids)
                    is DriveChange.Restored -> updateGone(gone - change.ids.toSet())
                    else -> Unit
                }
            }
        }
    }

    fun rescan() {
        scanJob?.cancel()
        val scanner = DuplicateScanner(clients, driveRepo).also { scanner = it }
        phase = Phase.SCANNING
        scannedFolders = 0
        scannedFiles = 0
        scanStop = null
        failedFolders = 0
        errorMessage = null
        report = DuplicateReport.EMPTY
        scanned = emptyList()
        gone = emptySet()
        groupedFiles = null
        groupedRemoved = null
        scanJob = scope.launch {
            try {
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
                            phase = Phase.ANALYZING
                            scanned = withContext(Dispatchers.Default) { enrichScannedFiles(event.files) }
                            regroup()
                            phase = Phase.DONE
                            val summary = "Duplicate scan finished in ${logFolder(root.id)}: folders=$scannedFolders, files=$scannedFiles, " +
                                "identicalGroups=${report.identical.size}, versionGroups=${report.versions.size}, failedFolders=$failedFolders, stop=${scanStop ?: "none"}"
                            if (failedFolders > 0) PikoLog.w(TAG, summary) else PikoLog.i(TAG, summary)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(
                    TAG,
                    "Duplicate scan failed in ${logFolder(root.id)} (phase=$phase, scannedFolders=$scannedFolders, scannedFiles=$scannedFiles)",
                    e,
                )
                errorMessage = e.message ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류", "未知錯誤")
                phase = Phase.FAILED
            }
        }
    }

    /** 提前结束扫描，已扫描的部分照常比对。 */
    fun stopScan() {
        scanner?.stop()
    }

    private fun enrichScannedFiles(items: List<ScannedFile>): List<ScannedFile> {
        if (items.isEmpty()) return emptyList()
        val contentCache = HashMap<String, dev.piko.shared.data.ContentMetadata?>()
        val enriched = ArrayList<ScannedFile>(items.size)
        for (i in 0 until items.size) {
            val item = items[i]
            val file = item.file
            val params = file.params
            val missingWidth = (params["width"]?.toIntOrNull() ?: 0) <= 0
            val missingHeight = (params["height"]?.toIntOrNull() ?: 0) <= 0
            val missingDuration = (params["duration"]?.toDoubleOrNull() ?: 0.0) <= 0.0
            val missingCreated = params["piko_original_created_at"]?.toLongOrNull() == null

            val meta = if ((missingWidth || missingHeight || missingDuration) && file.hash.isNotBlank() && file.sizeBytes >= 0L) {
                val key = "${file.hash.uppercase()}:${file.sizeBytes}"
                if (contentCache.containsKey(key)) {
                    contentCache[key]
                } else {
                    driveRepo.contentMetadataOf(file).also { contentCache[key] = it }
                }
            } else {
                null
            }
            val originalCreated = if (missingCreated) driveRepo.originalCreatedAtMillis(file.id) else null

            val needsWidth = missingWidth && (meta?.width ?: 0) > 0
            val needsHeight = missingHeight && (meta?.height ?: 0) > 0
            val needsDuration = missingDuration && (meta?.durationMillis ?: 0L) > 0L
            val needsCreated = missingCreated && originalCreated != null
            if (!needsWidth && !needsHeight && !needsDuration && !needsCreated) {
                enriched.add(item)
                continue
            }
            val nextParams = LinkedHashMap<String, String>(params.size + 4)
            nextParams.putAll(params)
            if (needsWidth) nextParams["width"] = meta!!.width.toString()
            if (needsHeight) nextParams["height"] = meta!!.height.toString()
            if (needsDuration) nextParams["duration"] = (meta!!.durationMillis!! / 1000L).toString()
            if (needsCreated) nextParams["piko_original_created_at"] = originalCreated.toString()
            enriched.add(item.copy(file = file.copy(params = nextParams)))
        }
        return enriched
    }

    private suspend fun updateGone(next: Set<String>) {
        if (next == gone) return
        gone = next
        // 扫描还没出结果时只记下，出结果那一刻一并扣掉
        if (phase == Phase.DONE) regroup()
    }

    private suspend fun regroup() {
        regroupLock.withLock {
            val allScanned = scanned
            val removed = if (!driveRepo.hasPendingOptimisticWrites) {
                gone
            } else {
                val files = ArrayList<FileStat>(allScanned.size)
                for (i in 0 until allScanned.size) files.add(allScanned[i].file)
                val visible = driveRepo.projectOptimisticFiles(files).mapTo(HashSet(allScanned.size * 4 / 3 + 1)) { it.id }
                val acc = HashSet<String>(gone)
                for (i in 0 until allScanned.size) {
                    val id = allScanned[i].file.id
                    if (id !in visible) acc.add(id)
                }
                acc
            }
            if (groupedFiles === allScanned && groupedRemoved == removed) return@withLock
            report = withContext(Dispatchers.Default) {
                val present = if (removed.isEmpty()) {
                    allScanned
                } else {
                    val filtered = ArrayList<ScannedFile>(allScanned.size)
                    for (i in 0 until allScanned.size) {
                        val item = allScanned[i]
                        if (item.file.id !in removed) filtered.add(item)
                    }
                    filtered
                }
                findDuplicates(present, rootName)
            }
            groupedFiles = allScanned
            groupedRemoved = removed
        }
    }

    private companion object {
        const val TAG = "Duplicates"
    }
}
