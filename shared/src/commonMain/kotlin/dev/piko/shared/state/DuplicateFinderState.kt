package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScanStop
import dev.piko.shared.data.ScannedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 查找重复：递归扫描 [root]，分出完全相同与同集不同版本两类，勾选后移入回收站。
 *
 * 创建即开始扫描。扫描在 [scope] 里进行，调用方关掉界面时取消 scope 即可一并停下。
 * 勾选以文件 id 记录，同一个文件既是完全相同组里保留的一份、又代表版本组里的一行时，两处的勾选是同一个。
 */
class DuplicateFinderState(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val root: PikoPathBreadcrumb,
) {
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
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var isTrashing by mutableStateOf(false)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val filesById: Map<String, DuplicateFile> by derivedStateOf {
        (report.identical + report.versions).flatMap { it.rows }.associate { it.file.id to it.file }
    }

    val selectedBytes: Long by derivedStateOf { selectedIds.sumOf { filesById[it]?.size ?: 0L } }

    /** 所有副本都被勾选的完全相同组数。移走后网盘里不再留有这份内容，确认时单独提醒。 */
    val fullyRemovedGroups: Int by derivedStateOf {
        report.identical.count { group -> group.rows.all { it.file.id in selectedIds } }
    }

    private var scanned: List<ScannedFile> = emptyList()
    private var scanner: DuplicateScanner? = null
    private var scanJob: Job? = null

    private val rootName: String? get() = root.name.takeIf { root.id.isNotEmpty() }

    init {
        rescan()
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
        selectedIds = emptySet()
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
                            val result = withContext(Dispatchers.Default) { findDuplicates(scanned, rootName) }
                            report = result
                            selectedIds = result.identical.flatMap { group ->
                                group.rows.map { it.file.id }.filter { it != group.keptId }
                            }.toSet()
                            phase = Phase.DONE
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = e.message ?: "未知错误"
                phase = Phase.FAILED
            }
        }
    }

    /** 提前结束扫描，已扫描的部分照常比对。 */
    fun stopScan() {
        scanner?.stop()
    }

    fun toggle(fileId: String) {
        selectedIds = if (fileId in selectedIds) selectedIds - fileId else selectedIds + fileId
    }

    fun keepAll(group: DuplicateGroup) {
        selectedIds = selectedIds - group.rows.map { it.file.id }.toSet()
    }

    /** 完全相同组回到默认：除保留的一份外全选。 */
    fun selectDefault(group: DuplicateGroup) {
        val keptId = group.keptId ?: return
        selectedIds = selectedIds + group.rows.map { it.file.id }.filter { it != keptId }
    }

    fun trashSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || isTrashing) return
        isTrashing = true
        scope.launch {
            driveRepo.trash(ids)
                .onSuccess {
                    val removed = ids.toSet()
                    scanned = scanned.filterNot { it.file.id in removed }
                    // 重新分组而不是逐行删：代表版本组那一行的文件被移走后，应由它的相同副本接替
                    report = withContext(Dispatchers.Default) { findDuplicates(scanned, rootName) }
                    // 组解散后留下的勾选看不见也取消不了，一并清掉
                    selectedIds = selectedIds.filter { it !in removed && it in filesById }.toSet()
                    driveRepo.requestRefresh()
                    _messages.tryEmit("已将 ${ids.size} 个文件移入回收站")
                }
                .onFailure { _messages.tryEmit("移入回收站失败") }
            isTrashing = false
        }
    }
}
