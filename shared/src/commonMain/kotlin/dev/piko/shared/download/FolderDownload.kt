package dev.piko.shared.download

import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadBatch
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 展开文件夹下载要的两样网盘能力。调度器建在网盘仓库之前，由发起下载的一方传进来。 */
interface DownloadFolderSource {
    /** 文件夹里的条目，含归档条目（网盘页看到的那一份）。 */
    suspend fun list(folderId: String): List<FileStat>

    /** 今日还能下载的字节数。不限（会员）或查不到时为 null。 */
    suspend fun remainingDailyDownload(): Long?
}

class DriveDownloadFolderSource(private val driveRepo: PikoDriveRepository) : DownloadFolderSource {
    override suspend fun list(folderId: String): List<FileStat> =
        driveRepo.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()

    // 会员的 downloadDaily 上限是 0，见 TransferAllowances
    override suspend fun remainingDailyDownload(): Long? =
        driveRepo.getTransferQuota().getOrNull()?.account?.downloadDaily
            ?.takeIf { it.limitBytes > 0 }
            ?.let { (it.limitBytes - it.usedBytes).coerceAtLeast(0L) }
}

/** 正在列出、列出失败或列完等确认的文件夹下载。确认之后换成任务表里的一批任务，这一项随之撤掉。 */
data class FolderListing(
    val batch: DownloadBatch,
    val createdAtMs: Long,
    /** 发起时的账号。列目录与之后的下载都只在这个账号上做得了。 */
    val account: String,
    val filesFound: Int = 0,
    val bytesFound: Long = 0L,
    val error: String? = null,
    /** 列完了，要下载的部分超出今日剩余的下载额度，等用户决定。 */
    val quotaExcess: QuotaExcess? = null,
) {
    val isListing: Boolean get() = error == null && quotaExcess == null
}

class QuotaExcess(val neededBytes: Long, val remainingBytes: Long)

/** 文件夹里的一个文件与它落在下载目录里的相对路径。 */
internal class PlannedFile(val file: FileStat, val path: String)

/**
 * 列出 [folder] 下全部可下载的文件，路径以文件夹名打头、保持子文件夹结构。
 *
 * 逐层广度优先，每层几个文件夹并行列：一层一层等，上千个子文件夹的剧集库列起来太慢；全部并行又会撞上
 * 服务端的限流。上传中的文件不算（下载必然失败），同一文件夹里清理后撞名的加序号，不然两个任务写同一个文件。
 * 撞名按不分大小写判断：Windows 与 Android 的共享存储都不分。
 */
internal suspend fun planFolderDownload(
    folder: FileStat,
    source: DownloadFolderSource,
    onProgress: (files: Int, bytes: Long) -> Unit,
): List<PlannedFile> {
    val planned = mutableListOf<PlannedFile>()
    var bytes = 0L
    var level = listOf(folder.id to FileNameSanitizer.sanitizeFolderName(folder.name))
    while (level.isNotEmpty()) {
        val next = mutableListOf<Pair<String, String>>()
        for (chunk in level.chunked(LIST_CONCURRENCY)) {
            val listings = coroutineScope { chunk.map { (id, _) -> async { source.list(id) } }.awaitAll() }
            chunk.zip(listings).forEach { (dir, entries) ->
                val (_, path) = dir
                val taken = HashSet<String>()
                for (entry in entries.sortedBy { it.isFolder }) {
                    if (entry.trashed) continue
                    if (entry.isFolder) {
                        val name = unique(FileNameSanitizer.sanitizeFolderName(entry.name), taken, keepExtension = false)
                        next += entry.id to "$path/$name"
                    } else if (!entry.isUploading) {
                        planned += PlannedFile(entry, "$path/${unique(localFileNameOf(entry), taken, keepExtension = true)}")
                        bytes += entry.sizeBytes
                    }
                }
            }
            onProgress(planned.size, bytes)
        }
        level = next
    }
    return planned
}

/**
 * 不该整个下载下来的文件夹：网盘根目录下的 Piko-Temp（预览用的临时副本）与放同步设置的 .piko。
 * 只认根目录里的，别处同名的是用户自己的文件夹。
 */
internal fun isPikoFolder(folder: FileStat): Boolean =
    folder.parentId.isEmpty() && (folder.name == PreviewTempFolder.FOLDER_NAME || PikoSettingsSync.isSyncFolder(folder, folder.parentId))

private fun unique(name: String, taken: MutableSet<String>, keepExtension: Boolean): String {
    if (taken.add(name.lowercase())) return name
    val base = if (keepExtension) name.substringBeforeLast('.', name) else name
    val extension = name.removePrefix(base)
    var index = 2
    while (true) {
        val candidate = "$base ($index)$extension"
        if (taken.add(candidate.lowercase())) return candidate
        index++
    }
}

private const val LIST_CONCURRENCY = 4
