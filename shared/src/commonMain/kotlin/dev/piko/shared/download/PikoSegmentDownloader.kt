package dev.piko.shared.download

data class PikoSegmentRequest(
    val sourceUrl: String,
    val destinationPath: String,
    val fileName: String,
    val startMillis: Long,
    val endMillis: Long,
)

interface PikoSegmentDownloader {
    suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String>
}
