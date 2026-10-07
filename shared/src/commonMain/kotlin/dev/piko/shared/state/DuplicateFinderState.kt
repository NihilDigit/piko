package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.log.PikoLog
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

/**
 * 查找重复：递归扫描 [root]，分出完全相同与同集不同版本两类。
 *
 * 创建即开始扫描。扫描在 [scope] 里进行，调用方结束这一次时取消 scope 即可一并停下。
 * 结果在网盘页的「查找重复」位置里按组列出，勾选与移入回收站都是网盘页的多选与删除，这里不另记勾选。
 *
 * 结果是扫描那一刻的快照。之后经撤销日志（[DriveChangeJournal]）移入回收站的，不管在哪一页删的，都从结果里拿掉
 * 再重新分组：代表版本组那一行的文件被移走后，应由它的相同副本接替，逐行删做不到。撤销回来的照样补回。
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

    private val statsById: Map<String, FileStat> by derivedStateOf { scanned.associate { it.file.id to it.file } }

    /** 结果里出现的文件，按 ID 取回完整的 [FileStat]，网盘页据此画缩略图、打开与操作。 */
    fun fileStat(id: String): FileStat? = statsById[id]

    /** 建议移走的：完全相同的组里，默认保留的那份以外的。版本组不建议，挑哪一版由人定。 */
    val suggestedIds: Set<String> by derivedStateOf {
        report.identical.flatMapTo(HashSet()) { group -> group.rows.map { it.file.id }.filter { it != group.keptId } }
    }

    /** 还在扫描或比对，结果还没出来。 */
    val isScanning: Boolean get() = phase == Phase.SCANNING || phase == Phase.ANALYZING

    private var scanner: DuplicateScanner? = null
    private var scanJob: Job? = null

    private val rootName: String? get() = root.name.takeIf { root.id.isNotEmpty() }

    init {
        rescan()
        scope.launch {
            driveRepo.changes.events.collect { event ->
                val trashed = (event.change as? DriveChangeJournal.Change.Trash)?.ids
                val restored = (event.undone as? DriveChangeJournal.Change.Trash)?.ids
                when {
                    trashed != null -> updateGone(gone + trashed)
                    restored != null -> updateGone(gone - restored.toSet())
                }
            }
        }
    }

    fun rescan() {
        scanJob?.cancel()
        val scanner = DuplicateScanner(clients).also { scanner = it }
        phase = Phase.SCANNING
        scannedFolders = 0
        scannedFiles = 0
        scanStop = null
        failedFolders = 0
        errorMessage = null
        report = DuplicateReport.EMPTY
        scanned = emptyList()
        gone = emptySet()
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
                            scanned = event.files
                            regroup()
                            phase = Phase.DONE
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w("Duplicates", "查找重复失败", e)
                errorMessage = e.message ?: "未知错误"
                phase = Phase.FAILED
            }
        }
    }

    /** 提前结束扫描，已扫描的部分照常比对。 */
    fun stopScan() {
        scanner?.stop()
    }

    private fun updateGone(next: Set<String>) {
        if (next == gone) return
        gone = next
        // 扫描还没出结果时只记下，出结果那一刻一并扣掉
        if (phase == Phase.DONE) scope.launch { regroup() }
    }

    private suspend fun regroup() {
        val present = gone.let { removed -> scanned.filterNot { it.file.id in removed } }
        report = withContext(Dispatchers.Default) { findDuplicates(present, rootName) }
    }
}
