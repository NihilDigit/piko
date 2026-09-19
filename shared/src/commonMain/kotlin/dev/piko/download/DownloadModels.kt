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
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}
