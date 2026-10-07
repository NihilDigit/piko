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
    /** 源文件所在的账号，只有它在用时才能继续，见 [belongsTo]。有多账号之前建的任务为空串，哪个账号都放行。 */
    val account: String = "",
    /**
     * 所属的文件夹下载。此时 [fileName] 是相对下载目录的路径，以 [DownloadBatch.folderName] 打头、以 / 分隔。
     * 单独下载的文件与旧版本存下的任务为 null。
     */
    val batch: DownloadBatch? = null,
    /**
     * 写在共享的稀疏暂存里，续传与进度读有效块记录。为假的未完成任务是 1.1.0 及更早的顺序下载，续传时把已写的前缀导入暂存。
     * 只记有没有、不记位置：位置由内容身份推出，见 PikoFileCachePool。
     */
    val sparseCache: Boolean = false,
    /** 只有内容哈希、没有当前账号文件 ID 的来源，下载时由 SDK 临时借出文件对象。 */
    val leasedSource: Boolean = false,
    /**
     * 下的是哪一档转码（如 720P），原画为 null。转码档只有 MPEG-TS，下完在本机转封装成 MP4：下载时 [totalBytes]
     * 是转码流的长度，完成后换成 MP4 的长度；[endMs] 是视频时长，转封装按它报进度。
     */
    val quality: String? = null,
    /** 所下转码档的 media ID，稀疏暂存据此与原画分开。 */
    val mediaId: String? = null,
    /**
     * 按画质上限挑档（下载时选的级别，或设置里的下载画质），画面高度。大于 0 表示还没挑：开始下载时才查这个视频
     * 有哪些档，按 downloadQualityOrder 挑定后写进 [quality] 并归零，挑到原画则 [quality] 仍为 null。
     */
    val qualityCap: Int = 0,
    /** 转码档已经下完、正在本机转封装，进度在 [progressFraction]。 */
    val converting: Boolean = false,
) {
    val progress: Float
        get() = progressFraction?.coerceIn(0f, 1f)
            ?: if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    /** 列表里显示的名字：文件夹下载里的文件只写文件夹之内的路径，文件夹名已在组的那一行上。 */
    val displayName: String
        get() = batch?.takeIf { it.isFolder }?.let { fileName.removePrefix("${it.folderName}/") } ?: fileName

    fun belongsTo(account: String): Boolean = this.account.isEmpty() || this.account == account
}

/** 一次文件夹下载。同一批的任务落在下载目录里同一个文件夹下，传输页收成一组。 */
@Serializable
data class DownloadBatch(
    val id: String,
    /** 本机上的文件夹名，已按文件名规则清理过。 */
    val folderName: String,
    val isFolder: Boolean = true,
    val sourceFolderId: String? = null,
)
