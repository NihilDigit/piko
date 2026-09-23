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
) {
    val progress: Float
        get() = progressFraction?.coerceIn(0f, 1f)
            ?: if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}
