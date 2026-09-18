package dev.piko.download

import android.content.Context
import android.os.Environment
import dev.piko.data.client.PikPakClientManager
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.RangeSource
import io.github.nihildigit.pikpak.downloadTo
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

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

class PikoDownloadManager(
    private val context: Context,
    private val clientManager: PikPakClientManager,
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val runningJobs = mutableMapOf<String, Job>()

    private val downloadDir: File by lazy {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun enqueue(file: FileStat) {
        val destFile = File(downloadDir, file.name)
        val initialDownloaded = if (destFile.exists()) destFile.length() else 0L

        val task = DownloadTask(
            taskId = file.id,
            fileId = file.id,
            fileName = file.name,
            gcid = file.hash,
            totalBytes = file.sizeBytes,
            downloadedBytes = initialDownloaded,
            destinationPath = destFile.absolutePath,
            status = if (initialDownloaded >= file.sizeBytes && file.sizeBytes > 0) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
            fullFileSize = file.sizeBytes,
            thumbnailLink = file.thumbnailLink,
        )

        _tasks.update { it + (file.id to task) }
        if (task.status != DownloadStatus.COMPLETED) {
            startDownload(file.id)
        }
    }

    /**
     * 段落下载：基于原生 MediaExtractor + MediaMuxer 无损流抽取，生成带标准 ftyp/moov 容器头的合规 MP4
     */
    fun enqueueSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String? = null,
        startByte: Long = 0L,
        lengthBytes: Long = 0L,
    ) {
        val ext = file.name.substringAfterLast(".", "")
        val baseName = file.name.substringBeforeLast(".")
        val safeLabel = timeRangeLabel.replace(":", "-").replace(" ", "")
        val segmentFileName = if (ext.isNotEmpty()) "${baseName}_[$safeLabel].$ext" else "${baseName}_[$safeLabel]"
        val destFile = File(downloadDir, segmentFileName)
        val initialDownloaded = if (destFile.exists()) destFile.length() else 0L
        val taskId = "${file.id}_seg_${startMs}_$endMs"

        val task = DownloadTask(
            taskId = taskId,
            fileId = file.id,
            fileName = segmentFileName,
            gcid = file.hash,
            totalBytes = if (lengthBytes > 0) lengthBytes else (destFile.length().coerceAtLeast(1024L)),
            downloadedBytes = initialDownloaded,
            destinationPath = destFile.absolutePath,
            status = if (initialDownloaded > 1024L) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
            isSegment = true,
            startByte = startByte,
            fullFileSize = file.sizeBytes,
            timeRangeLabel = timeRangeLabel,
            thumbnailLink = file.thumbnailLink,
            startMs = startMs,
            endMs = endMs,
            streamUrl = streamUrl,
        )

        _tasks.update { it + (taskId to task) }
        if (task.status != DownloadStatus.COMPLETED) {
            startDownload(taskId)
        }
    }

    fun startDownload(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        if (task.status == DownloadStatus.DOWNLOADING) return

        val destFile = File(task.destinationPath)

        // 若为段落下载，执行原生无损流抽取打包
        if (task.isSegment && task.endMs > task.startMs) {
            val job = scope.launch(Dispatchers.IO) {
                _tasks.update { it + (taskId to task.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)) }

                var url = task.streamUrl
                if (url.isNullOrBlank()) {
                    val mediaRepo = dev.piko.PikoApplication.instance.mediaRepository
                    val prep = mediaRepo.prepareMedia(task.fileId)
                    url = prep.getOrNull()?.currentUrl
                }

                if (url.isNullOrBlank()) {
                    _tasks.update { it + (taskId to task.copy(status = DownloadStatus.FAILED, errorMessage = "无法解析媒体直链")) }
                    return@launch
                }

                val result = VideoSegmentExtractor.extractSegment(
                    context = context,
                    sourceUrlOrPath = url,
                    destinationFile = destFile,
                    startMs = task.startMs,
                    endMs = task.endMs,
                    onProgress = { p ->
                        val bytes = (task.totalBytes * p).toLong()
                        _tasks.update { current ->
                            val c = current[taskId] ?: return@update current
                            current + (taskId to c.copy(
                                downloadedBytes = if (destFile.exists()) destFile.length() else bytes,
                            ))
                        }
                    }
                )

                if (result.isSuccess) {
                    val finalSize = destFile.length()
                    _tasks.update { current ->
                        val c = current[taskId] ?: return@update current
                        current + (taskId to c.copy(
                            status = DownloadStatus.COMPLETED,
                            downloadedBytes = finalSize,
                            totalBytes = finalSize,
                            speedBytesPerSec = 0L,
                        ))
                    }
                } else {
                    _tasks.update { current ->
                        val c = current[taskId] ?: return@update current
                        current + (taskId to c.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "提取切片失败",
                            speedBytesPerSec = 0L,
                        ))
                    }
                }
                runningJobs.remove(taskId)
            }
            runningJobs[taskId] = job
            return
        }

        val client = clientManager.currentClient.value ?: return

        val job = scope.launch(Dispatchers.IO) {
            _tasks.update { it + (taskId to task.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)) }

            val handle = PikPakFileHandle(
                client = client,
                gcid = task.gcid,
                size = if (task.isSegment) task.fullFileSize else task.totalBytes,
                name = task.fileName,
                initialFileId = task.fileId,
                connectionBudget = 8,
            )

            // 如果是段落下载，偏移 RangeSource 的访问基址
            val rangeSource: RangeSource = if (task.isSegment && task.startByte > 0L) {
                object : RangeSource {
                    override suspend fun <T> read(
                        start: Long,
                        length: Long,
                        priority: Int,
                        block: suspend (ByteReadChannel) -> T,
                    ): T = handle.read(task.startByte + start, length, priority, block)

                    override suspend fun readBytes(start: Long, length: Long, priority: Int): ByteArray =
                        handle.readBytes(task.startByte + start, length, priority)
                }
            } else {
                handle
            }

            val progressFlow = MutableStateFlow(destFile.length())
            // 进度与速度监听
            val progressJob = launch {
                var previousBytes = destFile.length()
                while (isActive) {
                    delay(1000)
                    val currentBytes = progressFlow.value
                    val delta = (currentBytes - previousBytes).coerceAtLeast(0L)
                    previousBytes = currentBytes
                    _tasks.update { current ->
                        val currentTask = current[taskId] ?: return@update current
                        current + (taskId to currentTask.copy(
                            downloadedBytes = currentBytes,
                            speedBytesPerSec = delta,
                        ))
                    }
                }
            }

            val destPath = Path(destFile.absolutePath)
            try {
                // 利用 SDK 的 8 连接并发分块滑动窗口下载，支持断点续传
                rangeSource.downloadTo(
                    dest = destPath,
                    totalSize = task.totalBytes,
                    concurrency = 8,
                    priority = 1,
                    progress = progressFlow,
                )
                progressJob.cancel()
                _tasks.update { current ->
                    val currentTask = current[taskId] ?: return@update current
                    current + (taskId to currentTask.copy(
                        downloadedBytes = task.totalBytes,
                        speedBytesPerSec = 0L,
                        status = DownloadStatus.COMPLETED,
                    ))
                }
            } catch (e: Exception) {
                progressJob.cancel()
                val isCancelled = !isActive
                _tasks.update { current ->
                    val currentTask = current[taskId] ?: return@update current
                    current + (taskId to currentTask.copy(
                        downloadedBytes = destFile.length(),
                        speedBytesPerSec = 0L,
                        status = if (isCancelled) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                        errorMessage = if (isCancelled) null else e.localizedMessage,
                    ))
                }
            } finally {
                handle.close()
                runningJobs.remove(taskId)
            }
        }

        runningJobs[taskId] = job
    }

    fun pauseDownload(taskId: String) {
        val job = runningJobs.remove(taskId)
        job?.cancel()
        _tasks.update { current ->
            val task = current[taskId] ?: return@update current
            current + (taskId to task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L))
        }
    }

    fun cancelDownload(taskId: String) {
        pauseDownload(taskId)
        val task = _tasks.value[taskId]
        if (task != null) {
            File(task.destinationPath).delete()
        }
        _tasks.update { it - taskId }
    }
}
