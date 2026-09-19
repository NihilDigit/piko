package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakFileHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PikoDownloadCoordinator(
    private val clientProvider: PikoClientProvider,
    private val preferences: PikoUserPreferences,
    private val storage: PikoDownloadStorage,
    private val scope: CoroutineScope,
    private val segmentDownloader: PikoSegmentDownloader? = null,
    private val onDownloadStarted: (() -> Unit)? = null,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun enqueue(file: FileStat) {
        onDownloadStarted?.invoke()
        val name = FileNameSanitizer.sanitize(file.name)
        val existing = scope.launch(Dispatchers.Default) {
            val downloaded = storage.existingLength(name)
            val complete = downloaded >= file.sizeBytes && file.sizeBytes > 0L
            val task = DownloadTask(
                taskId = file.id,
                fileId = file.id,
                fileName = name,
                gcid = file.hash,
                totalBytes = file.sizeBytes,
                downloadedBytes = downloaded,
                destinationPath = name,
                status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
                fullFileSize = file.sizeBytes,
                thumbnailLink = file.thumbnailLink,
            )
            _tasks.update { it + (task.taskId to task) }
            if (!complete) startDownload(task.taskId)
        }
        existing.invokeOnCompletion { if (it != null) _tasks.update { tasks -> tasks - file.id } }
    }

    fun startDownload(taskId: String) {
        onDownloadStarted?.invoke()
        if (jobs[taskId]?.isActive == true) return
        val task = _tasks.value[taskId] ?: return
        jobs[taskId] = scope.launch(Dispatchers.Default) {
            val client = clientProvider.currentClient.value
            if (client == null) {
                update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "未登录") }
                return@launch
            }
            update(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null) }
            val concurrency = preferences.concurrentConnectionsFlow.first()
            val handle = PikPakFileHandle(
                client = client,
                gcid = task.gcid,
                size = task.totalBytes,
                name = task.fileName,
                initialFileId = task.fileId,
                connectionBudget = concurrency,
            )
            val reader = try {
                handle.openStream(task.totalBytes, concurrency, coroutineContext)
            } catch (e: Throwable) {
                handle.close()
                update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = e.message) }
                return@launch
            }
            try {
                val destinationPath = storage.download(task.fileName, reader, task.totalBytes) { bytes ->
                    update(taskId) { it.copy(downloadedBytes = bytes) }
                }
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = task.totalBytes,
                        destinationPath = destinationPath,
                    )
                }
            } catch (e: CancellationException) {
                update(taskId) { it.copy(status = DownloadStatus.PAUSED) }
                throw e
            } catch (e: Throwable) {
                update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = e.message) }
            } finally {
                reader.close()
                handle.close()
                jobs.remove(taskId)
            }
        }
    }

    fun enqueueSegment(
        file: FileStat,
        startMillis: Long,
        endMillis: Long,
        timeRangeLabel: String,
        sourceUrl: String,
    ) {
        onDownloadStarted?.invoke()
        val name = FileNameSanitizer.sanitize(
            "${file.name.substringBeforeLast('.', file.name)}_[$timeRangeLabel].mp4",
            fallbackExtension = "mp4",
            forceExtension = "mp4",
        )
        val taskId = "${file.id}_seg_${startMillis}_$endMillis"
        val task = DownloadTask(
            taskId = taskId,
            fileId = file.id,
            fileName = name,
            gcid = file.hash,
            totalBytes = 0L,
            destinationPath = storage.pathFor(name),
            isSegment = true,
            fullFileSize = file.sizeBytes,
            timeRangeLabel = timeRangeLabel,
            thumbnailLink = file.thumbnailLink,
            startMs = startMillis,
            endMs = endMillis,
            streamUrl = sourceUrl,
        )
        _tasks.update { it + (taskId to task) }
        startSegment(task)
    }

    fun enqueueSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
    ) = enqueueSegment(file, startMs, endMs, timeRangeLabel, streamUrl.orEmpty())

    private fun startSegment(task: DownloadTask) {
        val extractor = segmentDownloader ?: run {
            updateTask(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "当前平台不支持分段抽取") }
            return
        }
        jobs[task.taskId] = scope.launch(Dispatchers.Default) {
            updateTask(task.taskId) { it.copy(status = DownloadStatus.DOWNLOADING) }
            extractor.extract(
                PikoSegmentRequest(task.streamUrl ?: "", task.destinationPath, task.fileName, task.startMs, task.endMs),
            ) { progress ->
                updateTask(task.taskId) { it.copy(downloadedBytes = (it.totalBytes * progress).toLong()) }
            }.onSuccess { path ->
                updateTask(task.taskId) { it.copy(status = DownloadStatus.COMPLETED, destinationPath = path) }
            }.onFailure { error ->
                updateTask(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) }
            }
            jobs.remove(task.taskId)
        }
    }

    fun pauseDownload(taskId: String) {
        jobs[taskId]?.cancel()
        update(taskId) { it.copy(status = DownloadStatus.PAUSED) }
    }

    fun cancelDownload(taskId: String) {
        jobs.remove(taskId)?.cancel()
        val path = _tasks.value[taskId]?.destinationPath
        if (!path.isNullOrBlank()) scope.launch { storage.delete(path) }
        _tasks.update { it - taskId }
    }

    private fun update(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { tasks -> tasks[taskId]?.let { tasks + (taskId to transform(it)) } ?: tasks }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) = update(taskId, transform)
}
