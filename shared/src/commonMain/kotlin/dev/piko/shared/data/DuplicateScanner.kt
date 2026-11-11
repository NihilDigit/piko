package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** 扫描到的一个文件。[folderPath] 是所在目录相对扫描起点的路径，起点下的文件为空串。 */
data class ScannedFile(val file: FileStat, val folderPath: String) {
    val path: String get() = if (folderPath.isEmpty()) file.name else "$folderPath/${file.name}"
}

/** 扫描提前结束的原因。 */
enum class ScanStop { FOLDER_LIMIT, FILE_LIMIT, TIMEOUT, CANCELLED }

sealed interface DuplicateScanEvent {
    data class Progress(val folders: Int, val files: Int) : DuplicateScanEvent

    /**
     * [stop] 为 null 表示完整走完。[failedFolders] 是列不出来而跳过的目录数：
     * 几千个目录里偶有一个超时，不值得让整轮扫描作废。
     * [subfolders] 是起点之下列出过内容的文件夹，没来得及列或列失败的不在里面：按番号规范命名（CanonicalTreeScan）据此看文件夹里有什么，
     * 内容不明的文件夹不能拿来判断。
     */
    data class Finished(
        val files: List<ScannedFile>,
        val folders: Int,
        val failedFolders: Int,
        val stop: ScanStop?,
        val subfolders: List<ScannedFile> = emptyList(),
    ) : DuplicateScanEvent
}

/**
 * 递归列出一个目录下的全部文件与文件夹，供查找重复与按番号规范命名用。
 *
 * 注入 [PikoDriveRepository] 时优先走 [PikoDriveRepository.scanRealChildren]：本地 SQLite 树已覆盖且干净的目录直接从本地读取，
 * 整棵子树干净（[isSubtreeClean]）时自动解除目录数与文件数上限（`Int.MAX_VALUE`）；未覆盖或脏目录回退远端按层并发拉取（每批 [concurrency] 个）。
 * 远端扫描到达目录数、文件数或时间上限时提前结束并注明 [ScanStop]，已收集结果仍可用。
 */
class DuplicateScanner(
    private val listFolder: suspend (folderId: String) -> List<FileStat>,
    private val maxFolders: Int = 5_000,
    private val maxFiles: Int = 200_000,
    private val timeout: Duration = 5.minutes,
    private val concurrency: Int = 12,
    private val isSubtreeClean: (rootId: String) -> Boolean = { false },
) {
    private companion object {
        const val TAG = "DuplicateScanner"
    }

    constructor(clients: PikoClientProvider) : this(
        listFolder = { folderId ->
            val client = clients.currentClient.value ?: error("Not logged in")
            client.listFiles(folderId)
        },
    )

    constructor(clients: PikoClientProvider, driveRepo: PikoDriveRepository) : this(
        listFolder = { folderId ->
            val client = clients.currentClient.value ?: error("Not logged in")
            driveRepo.scanRealChildren(folderId, client)
        },
        isSubtreeClean = driveRepo::isSubtreeClean,
    )

    // 停止不取消协程：取消会连同已经列出的结果一起丢掉，而用户按停止多半是想看已有的部分
    @Volatile
    private var stopRequested = false

    fun stop() {
        stopRequested = true
    }

    /** 列不出起点目录时抛出异常；其下的目录列不出来只计数跳过。 */
    fun scan(rootId: String): Flow<DuplicateScanEvent> = flow {
        val started = TimeSource.Monotonic.markNow()
        val deadline = started + timeout
        val unlimited = isSubtreeClean(rootId)
        val effectiveMaxFolders = if (unlimited) Int.MAX_VALUE else maxFolders
        val effectiveMaxFiles = if (unlimited) Int.MAX_VALUE else maxFiles
        val files = mutableListOf<ScannedFile>()
        val subfolders = mutableListOf<ScannedFile>()
        var listed = 0
        var failed = 0
        var stop: ScanStop? = null

        // 起点单独列：它失败就没有结果可言，应当报错而不是给出空的「没有重复」
        var level = listOf(ScanFolder(rootId, ""))
        var isRoot = true
        while (level.isNotEmpty() && stop == null) {
            val next = mutableListOf<ScanFolder>()
            for (batch in level.chunked(concurrency)) {
                stop = when {
                    stopRequested -> ScanStop.CANCELLED
                    deadline.hasPassedNow() -> ScanStop.TIMEOUT
                    listed >= effectiveMaxFolders -> ScanStop.FOLDER_LIMIT
                    files.size >= effectiveMaxFiles -> ScanStop.FILE_LIMIT
                    else -> null
                }
                if (stop != null) break
                val budgeted = if (unlimited) batch else batch.take(effectiveMaxFolders - listed)
                val listings = coroutineScope {
                    budgeted.map { folder ->
                        async {
                            if (isRoot) {
                                runSuspendCatching { listFolder(folder.id) }
                                    .logFailure(TAG, "Duplicate scan failed to list root ${logFolder(folder.id)}")
                                    .getOrThrow()
                            } else {
                                runSuspendCatching { listFolder(folder.id) }.getOrNull()
                            }
                        }
                    }.awaitAll()
                }
                listed += budgeted.size
                budgeted.zip(listings).forEach { (folder, entries) ->
                    if (entries == null) {
                        failed++
                        return@forEach
                    }
                    folder.entry?.let { subfolders += it }
                    for (entry in entries) {
                        // Piko-Temp 里是秒传预览的临时副本，本就与原文件相同，且会被自动清理，列出来只是噪声
                        when {
                            entry.isFolder -> if (!(isRoot && rootId.isEmpty() && entry.name == PreviewTempFolder.FOLDER_NAME)) {
                                next += ScanFolder(entry.id, folder.childPath(entry.name), ScannedFile(entry, folder.path))
                            }
                            entry.isFile && !entry.trashed -> files += ScannedFile(entry, folder.path)
                        }
                    }
                }
                emit(DuplicateScanEvent.Progress(folders = listed, files = files.size))
            }
            isRoot = false
            level = next
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        val summary = "root=${logFolder(rootId)}, folders=$listed, files=${files.size}, " +
            "failedFolders=$failed, stop=${stop ?: "NONE"}, cleanFastPath=$unlimited, elapsedMs=$elapsedMs"
        if ((stop != null && stop != ScanStop.CANCELLED) || failed > 0) {
            PikoLog.w(TAG, "Tree scan finished with incomplete coverage ($summary)")
        } else {
            PikoLog.i(TAG, "Tree scan finished ($summary)")
        }
        emit(DuplicateScanEvent.Finished(files.toList(), listed, failed, stop, subfolders.toList()))
    }.flowOn(Dispatchers.Default)

    /** [entry] 是这个目录在上一层里的那一项，起点为 null。 */
    private class ScanFolder(val id: String, val path: String, val entry: ScannedFile? = null) {
        fun childPath(name: String) = if (path.isEmpty()) name else "$path/$name"
    }
}
