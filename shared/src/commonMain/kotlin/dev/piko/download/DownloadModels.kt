package dev.piko.download

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus { PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Serializable
data class DownloadTask(
    val taskId: String,
    val fileId: String,
    val fileName: String,
    val gcid: String,
    val totalBytes: Long,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val errorMessage: String? = null,
    val destinationPath: String = "",
    val isSegment: Boolean = false,
    val startByte: Long = 0L,
    val fullFileSize: Long = 0L,
    val timeRangeLabel: String? = null,
    val thumbnailLink: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val streamUrl: String? = null,
    /** 源文件所在目录。云端文件对象失效时 SDK 在这里重建，缺省会落到网盘根目录。 */
    val parentId: String = "",
    /**
     * 按比例上报的进度。片段抽取事先不知道产物大小，totalBytes 为 0，
     * 按字节算的进度会一直停在 0。
     */
    val progressFraction: Float? = null,
    /** 加入队列的时刻，epoch 毫秒。与云端任务混排时按它排序。 */
    val createdAtMs: Long = 0L,
    /** 源文件所在的账号，只有它在用时才能继续。有多账号之前建的任务为空串，哪个账号都放行。 */
    val account: String = "",
    /**
     * 所属的文件夹下载。此时 [fileName] 是相对下载目录的路径，以 [DownloadBatch.folderName] 打头、以 / 分隔。
     * 单独下载的文件与旧版本存下的任务为 null。
     */
    val batch: DownloadBatch? = null,
) {
    val progress: Float
        get() = progressFraction?.coerceIn(0f, 1f)
            ?: if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    /** 列表里显示的名字：文件夹下载里的文件只写文件夹之内的路径，文件夹名已在组的那一行上。 */
    val displayName: String
        get() = batch?.let { fileName.removePrefix("${it.folderName}/") } ?: fileName
}

/** 一次文件夹下载。同一批的任务落在下载目录里同一个文件夹下，传输页收成一组。 */
@Serializable
data class DownloadBatch(
    val id: String,
    /** 本机上的文件夹名，已按文件名规则清理过。 */
    val folderName: String,
)
