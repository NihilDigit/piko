package dev.piko.shared.data

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
     * [subfolders] 是起点之下列出过内容的文件夹，没来得及列或列失败的不在里面：按番号规范命名据此看文件夹里有什么，
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
 * PikPak 没有按哈希查询的接口，只能逐个目录列出来在本地比对，一个目录一次请求（SDK 负责翻页）。
 * 按层遍历、每批并发 [concurrency] 个目录，与仓库层的 folderUsage 相同。
 * 到达目录数、文件数或时间上限时提前结束并注明原因，结果仍可用，只是不全。
 *
 * 不走 PikoDriveRepository.listAllFiles：它会把每个目录的文件名记进一张不随路径栈清理的表，
 * 扫一遍全盘就是几千项。
 */
class DuplicateScanner(
    private val listFolder: suspend (folderId: String) -> List<FileStat>,
    private val maxFolders: Int = 5_000,
    private val maxFiles: Int = 200_000,
    private val timeout: Duration = 5.minutes,
    private val concurrency: Int = 4,
) {
    constructor(clients: PikoClientProvider) : this(
        listFolder = { folderId ->
            val client = clients.currentClient.value ?: error("Not logged in")
            client.listFiles(folderId)
        },
    )

    // 停止不取消协程：取消会连同已经列出的结果一起丢掉，而用户按停止多半是想看已有的部分
    @Volatile
    private var stopRequested = false

    fun stop() {
        stopRequested = true
    }

    /** 列不出起点目录时抛出异常；其下的目录列不出来只计数跳过。 */
    fun scan(rootId: String): Flow<DuplicateScanEvent> = flow {
        val deadline = TimeSource.Monotonic.markNow() + timeout
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
                    listed >= maxFolders -> ScanStop.FOLDER_LIMIT
                    files.size >= maxFiles -> ScanStop.FILE_LIMIT
                    else -> null
                }
                if (stop != null) break
                val budgeted = batch.take(maxFolders - listed)
                val listings = coroutineScope {
                    budgeted.map { folder ->
                        async {
                            if (isRoot) listFolder(folder.id) else runSuspendCatching { listFolder(folder.id) }.getOrNull()
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
        emit(DuplicateScanEvent.Finished(files.toList(), listed, failed, stop, subfolders.toList()))
    }.flowOn(Dispatchers.Default)

    /** [entry] 是这个目录在上一层里的那一项，起点为 null。 */
    private class ScanFolder(val id: String, val path: String, val entry: ScannedFile? = null) {
        fun childPath(name: String) = if (path.isEmpty()) name else "$path/$name"
    }
}
