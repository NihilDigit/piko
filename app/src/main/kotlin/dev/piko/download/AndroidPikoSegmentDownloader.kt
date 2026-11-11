package dev.piko.download

import dev.piko.shared.i18n.tr

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import dev.piko.shared.download.PikoRemuxRequest
import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPikoSegmentDownloader(private val context: Context) : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val safTarget = if (request.destinationPath.startsWith("content:")) {
            val parsed = Uri.parse(request.destinationPath)
            val rootId = treeRootDocumentId(parsed)
                ?: return@withContext Result.failure(IllegalStateException(tr("无法访问下载目录", "Cannot access download folder", "ダウンロード先にアクセスできません", "다운로드 폴더에 접근할 수 없습니다", "無法存取下載資料夾")))
            parsed to rootId
        } else null
        // SAF 目录先抽到缓存再拷过去。中转文件不带 .part：抽取器自己会以 .part 写入、完成后改名
        val stagingFile = safTarget?.let { context.cacheDir.resolve(request.fileName) }
        val outputFile = stagingFile ?: File(request.destinationPath)
        val randomAccess = request.openRandomAccess?.let { open ->
            try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "Failed to open random-access media source for ${logFile(null, request.fileName)}", e)
                return@withContext Result.failure(e)
            }
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
        if (result.isFailure || safTarget == null) {
            return@withContext result
                .logFailure(TAG, "Segment extraction failed for ${logFile(null, request.fileName)}")
                .map { request.destinationPath }
        }
        // 抽取成功后才在下载目录里建文件：先建的话，失败会在那里留下一个 0 字节的空文件
        try {
            val (treeUri, rootId) = safTarget
            val targetUri = findOrCreateChildFileUri(
                resolver = context.contentResolver,
                treeUri = treeUri,
                parentDocumentId = rootId,
                displayName = request.fileName,
                mimeType = "video/mp4",
            ) ?: error(tr("无法创建分段文件", "Cannot create segment file", "分割ファイルを作成できません", "분할 파일을 만들 수 없습니다", "無法建立片段檔案"))
            val written = exportFileToSafUri(context.contentResolver, stagingFile!!, targetUri)
            if (!written) {
                error(tr("无法打开目标文件", "Cannot open destination file", "出力ファイルを開けません", "대상 파일을 열 수 없습니다", "無法開啟目標檔案"))
            }
            Result.success(targetUri.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            PikoLog.w(TAG, "Failed to write extracted segment to SAF folder for ${logFile(null, request.fileName)}", error)
            Result.failure(error)
        } finally {
            stagingFile?.delete()
        }
    }

    // 目标是 PikoDownloadStorage.downloadTarget 给的本机路径（SAF 目录下是私有目录里的暂存），交付由调用方做
    override suspend fun remux(
        request: PikoRemuxRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = VideoSegmentExtractor.remux(
        context = context,
        sourcePath = request.sourcePath,
        destinationFile = File(request.destinationPath),
        durationHintMs = request.durationMillis,
        onProgress = { progress -> runBlocking { onProgress(progress) } },
    ).logFailure(TAG, "Remux failed for ${logFile(null, File(request.destinationPath).name)}")
        .map { request.destinationPath }

    private companion object {
        const val TAG = "SegmentDownload"
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
