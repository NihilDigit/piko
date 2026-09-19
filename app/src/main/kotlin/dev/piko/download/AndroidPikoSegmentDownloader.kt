package dev.piko.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPikoSegmentDownloader(private val context: Context) : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetDocument = if (request.destinationPath.startsWith("content:")) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(request.destinationPath))
                ?: return@withContext Result.failure(IllegalStateException("无法访问下载目录"))
            root.findFile(request.fileName) ?: root.createFile("video/mp4", request.fileName)
                ?: return@withContext Result.failure(IllegalStateException("无法创建分段文件"))
        } else null
        val temporaryFile = targetDocument?.let { context.cacheDir.resolve("${request.fileName}.part") }
        val outputFile = temporaryFile ?: File(request.destinationPath)
        val result = VideoSegmentExtractor.extractSegment(
            context = context,
            sourceUrlOrPath = request.sourceUrl,
            destinationFile = outputFile,
            startMs = request.startMillis,
            endMs = request.endMillis,
            onProgress = { progress ->
                // The native extractor owns the write loop; this callback only crosses the platform boundary.
                kotlinx.coroutines.runBlocking { onProgress(progress) }
            },
        )
        if (result.isFailure || targetDocument == null) return@withContext result.map { request.destinationPath }
        try {
            val output = context.contentResolver.openOutputStream(targetDocument.uri, "wt")
                ?: error("无法打开目标文件")
            output.use { stream -> temporaryFile!!.inputStream().use { input -> input.copyTo(stream) } }
            temporaryFile?.delete()
            Result.success(targetDocument.uri.toString())
        } catch (error: Throwable) {
            temporaryFile?.delete()
            Result.failure(error)
        }
    }
}
