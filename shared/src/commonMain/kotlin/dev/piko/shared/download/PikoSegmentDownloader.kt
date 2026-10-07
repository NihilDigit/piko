package dev.piko.shared.download

import dev.piko.shared.media.RandomAccessMediaSource

data class PikoSegmentRequest(
    val sourceUrl: String,
    val destinationPath: String,
    val fileName: String,
    val startMillis: Long,
    val endMillis: Long,
    /**
     * 按偏移读源文件的另一条路，调用时才打开，用完由调用方关闭。读不了 [sourceUrl] 的平台用它：
     * Android 的 MediaExtractor 读不了本机代理的 http 地址，见 RandomAccessMediaSource。
     */
    val openRandomAccess: (suspend () -> RandomAccessMediaSource)? = null,
)

/** 把本机一个完整的视频文件原样转封装成 MP4，不转码。用于转码档下载：PikPak 的转码只有 MPEG-TS。 */
data class PikoRemuxRequest(
    val sourcePath: String,
    /** 本机路径，不是 SAF 的 content: URI：交付到下载目录由调用方经 PikoDownloadStorage.commit 做。 */
    val destinationPath: String,
    /** 源的时长，读不出时长的平台据此报进度；不知道时为 0。 */
    val durationMillis: Long = 0L,
)

/** 片段截取与转封装的平台实现。两者都写目标旁的 .part，完成后替换目标，失败与取消不留下半截文件。 */
interface PikoSegmentDownloader {
    /** [onProgress] 报 0 到 1，按已复制到的时间相对片段长度。 */
    suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String>

    /** [onProgress] 报 0 到 1。成功返回 [PikoRemuxRequest.destinationPath]。 */
    suspend fun remux(
        request: PikoRemuxRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String>
}
