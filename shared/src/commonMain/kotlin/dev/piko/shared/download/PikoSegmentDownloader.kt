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

interface PikoSegmentDownloader {
    suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String>
}
