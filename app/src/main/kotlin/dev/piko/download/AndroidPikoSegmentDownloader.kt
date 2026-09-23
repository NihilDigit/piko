package dev.piko.download

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPikoSegmentDownloader(private val context: Context) : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val treeRoot = if (request.destinationPath.startsWith("content:")) {
            DocumentFile.fromTreeUri(context, Uri.parse(request.destinationPath))
                ?: return@withContext Result.failure(IllegalStateException("无法访问下载目录"))
        } else null
        // SAF 目录先抽到缓存再拷过去。中转文件不带 .part：抽取器自己会以 .part 写入、完成后改名
        val stagingFile = treeRoot?.let { context.cacheDir.resolve(request.fileName) }
        val outputFile = stagingFile ?: File(request.destinationPath)
        val randomAccess = request.openRandomAccess?.let { open ->
            runCatching { open() }.getOrElse { return@withContext Result.failure(it) }
        }
        val result = try {
            VideoSegmentExtractor.extractSegment(
                context = context,
                sourceUrlOrPath = request.sourceUrl,
                destinationFile = outputFile,
                startMs = request.startMillis,
                endMs = request.endMillis,
                onProgress = { progress ->
                    // The native extractor owns the write loop; this callback only crosses the platform boundary.
                    kotlinx.coroutines.runBlocking { onProgress(progress) }
                },
                dataSource = randomAccess?.let(::RandomAccessDataSource),
            )
        } finally {
            randomAccess?.close()
        }
        if (result.isFailure || treeRoot == null) return@withContext result.map { request.destinationPath }
        // 抽取成功后才在下载目录里建文件：先建的话，失败会在那里留下一个 0 字节的空文件
        try {
            val targetDocument = treeRoot.findFile(request.fileName) ?: treeRoot.createFile("video/mp4", request.fileName)
                ?: error("无法创建分段文件")
            val output = context.contentResolver.openOutputStream(targetDocument.uri, "wt")
                ?: error("无法打开目标文件")
            output.use { stream -> stagingFile!!.inputStream().use { input -> input.copyTo(stream) } }
            Result.success(targetDocument.uri.toString())
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            stagingFile?.delete()
        }
    }
}

/**
 * 把按偏移读取的网盘文件交给 MediaExtractor。它读 URL 时走受明文流量策略约束的
 * HttpURLConnection，读不了本机代理，见 RandomAccessMediaSource。
 * readAt 由抽取器在自己的线程上同步调用，这里阻塞等待挂起的读取。
 */
private class RandomAccessDataSource(private val source: RandomAccessMediaSource) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return runBlocking { source.readAt(position, buffer, offset, size) }
    }

    override fun getSize(): Long = source.size

    // 源由调用方在抽取结束后关闭；MediaExtractor.release 也会调到这里，不能在此重复关闭
    override fun close() = Unit
}
