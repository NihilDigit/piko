package dev.piko.shared.media.reader

import dev.piko.shared.media.MediaPlaybackService
import dev.piko.shared.memory.MemoryWorkKind
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.memory.cacheBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 原始文档字节读取结果。
 */
data class DocumentByteResult(
    val bytes: ByteArray,
    val totalBytes: Long,
    val truncated: Boolean,
)

/**
 * 跨平台文档加载与解析器：
 * - 受 [PikoMemoryGovernor] 内存水位约束，常态上限 16 MiB，受压时自动收窄至 8 MiB / 4 MiB；
 * - 读取完成后在 `Dispatchers.Default` 解码与构建虚拟化分块 AST，避免阻塞主线程。
 */
object DocumentLoader {
    const val DEFAULT_MAX_DOCUMENT_BYTES: Int = 16 * 1024 * 1024
    private const val MIN_DOCUMENT_CAP_BYTES: Int = 2 * 1024 * 1024

    fun effectiveMaxBytes(governor: PikoMemoryGovernor = PikoMemoryGovernor.Shared): Int {
        val budgeted = governor.policy.value.cacheBudget(DEFAULT_MAX_DOCUMENT_BYTES.toLong())
        return budgeted.coerceIn(MIN_DOCUMENT_CAP_BYTES.toLong(), DEFAULT_MAX_DOCUMENT_BYTES.toLong()).toInt()
    }

    suspend fun loadBytes(
        mediaRepository: MediaPlaybackService,
        fileId: String,
        localPath: String? = null,
        governor: PikoMemoryGovernor = PikoMemoryGovernor.Shared,
    ): Result<DocumentByteResult> {
        val cap = effectiveMaxBytes(governor)
        val permit = governor.acquire(MemoryWorkKind.SnapshotRead, estimatedPeakBytes = cap.toLong())
        return try {
            mediaRepository.readDocumentBytes(fileId = fileId, maxBytes = cap, localPath = localPath)
        } finally {
            permit.release()
        }
    }

    suspend fun decodeAndParse(
        fileName: String,
        raw: DocumentByteResult,
        forcedCharset: String? = null,
    ): ParsedDocument = withContext(Dispatchers.Default) {
        val decoded = DocumentTextDecoder.decode(raw.bytes, forcedCharset = forcedCharset)
        parseDocument(
            fileName = fileName,
            decoded = decoded,
            totalBytes = raw.totalBytes,
            loadedBytes = raw.bytes.size.toLong(),
            truncated = raw.truncated,
        )
    }
}
