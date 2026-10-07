package dev.piko.shared.rename

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
import dev.piko.shared.data.VaultStore
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 批量重命名处理一棵树时另需的两样：每项所在的位置，供预览标出它在哪一层；以及扫描时已列全的目录里不参与改名的名字，
 * 冲突检查据此判断，不必再逐个目录列一遍（几千个目录就是几千次请求）。不在 [siblingNames] 里的目录（树根所在的那一层）
 * 仍由批量重命名自己去列。
 */
class RenameTree(
    /** 条目 ID 到所在目录，以树根的名字开头；树根自己不在里面。 */
    val locations: Map<String, String>,
    val siblingNames: Map<String, Set<String>>,
)

/**
 * 按番号规范命名一个文件夹之前的扫描：递归列出 [root] 下的文件与文件夹（上限、并发同查找重复，见 [DuplicateScanner]），
 * 连同树根本身交给批量重命名。树根查得到详情时也是一项：它本身就是一份资源时同样要改名。
 *
 * 状态随调用方的作用域走，作用域取消即放弃，没有进程级的会话：结果直接进批量重命名的预览，关掉对话框就不再有用。
 */
class CanonicalTreeScan(
    private val listFolder: suspend (folderId: String) -> List<FileStat>,
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val root: PikoPathBreadcrumb,
) {
    constructor(clients: PikoClientProvider, driveRepo: PikoDriveRepository, scope: CoroutineScope, root: PikoPathBreadcrumb) : this(
        listFolder = { folderId -> (clients.currentClient.value ?: error("Not logged in")).listFiles(folderId) },
        driveRepo = driveRepo,
        scope = scope,
        root = root,
    )

    enum class Phase { SCANNING, DONE, FAILED }

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

    /** 扫到的条目，树根在最前，其余按路径排，同一个文件夹里的挨在一起。扫完之前为空。 */
    var files by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var tree by mutableStateOf<RenameTree?>(null)
        private set

    private var scanner: DuplicateScanner? = null
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        // 扫描器只交出列全了的目录与其中要整理的条目；各目录列出的全部名字另记一份，作为冲突检查里不参与改名的名字
        val listings = HashMap<String, List<FileStat>>()
        val listingsLock = Mutex()
        val scanner = DuplicateScanner(listFolder = { folderId ->
            listFolder(folderId).also { entries -> listingsLock.withLock { listings[folderId] = entries } }
        }).also { scanner = it }
        phase = Phase.SCANNING
        scannedFolders = 0
        scannedFiles = 0
        scanStop = null
        failedFolders = 0
        errorMessage = null
        files = emptyList()
        tree = null
        job = scope.launch {
            try {
                val rootEntry = loadRoot()
                var scanned: List<ScannedFile> = emptyList()
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
                            scanned = (event.subfolders + event.files).filterNot { VaultStore.looksLikeManifest(it.file) }
                        }
                    }
                }
                val ordered = scanned.sortedBy { it.path }
                val included = ordered.mapTo(HashSet()) { it.file.id }
                files = listOfNotNull(rootEntry) + ordered.map { it.file }
                tree = RenameTree(
                    locations = ordered.associate { it.file.id to if (it.folderPath.isEmpty()) root.name else "${root.name}/${it.folderPath}" },
                    siblingNames = listingsLock.withLock {
                        listings.mapValues { (_, entries) -> entries.filter { it.id !in included && !it.trashed }.mapTo(HashSet()) { it.name } }
                    },
                )
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

    /** 提前结束扫描，已扫描的部分照常交给批量重命名。 */
    fun stop() {
        scanner?.stop()
    }

    // 网盘根目录没有自己的一项；查不到详情时同样不改树根，其下照常
    private suspend fun loadRoot(): FileStat? {
        if (root.id.isEmpty()) return null
        val detail = driveRepo.getFileDetail(root.id).getOrNull() ?: return null
        return FileStat(kind = detail.kind, id = detail.id, parentId = detail.parentId, name = detail.name)
    }

    private companion object {
        const val TAG = "CanonicalTreeScan"
    }
}
