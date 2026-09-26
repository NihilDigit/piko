package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.data.runSuspendCatching
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakFileHandle
import io.github.nihildigit.pikpak.downloadTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeSource

class PikoDownloadCoordinator(
    private val clientProvider: PikoClientProvider,
    private val preferences: PikoUserPreferences,
    private val storage: PikoDownloadStorage,
    private val scope: CoroutineScope,
    private val segmentDownloader: PikoSegmentDownloader? = null,
    /** 片段抽取经它开本机代理会话读源文件。缺省时退回任务里存下的直链。 */
    private val mediaRepository: PikoMediaRepository? = null,
    private val onDownloadStarted: (() -> Unit)? = null,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    // 视图在主线程启停任务，任务协程在 IO 线程上结束时自己摘除，两边会同时改这张表。
    // 用 StateFlow.update 的 CAS 代替普通 Map，commonMain 里没有 ConcurrentHashMap。
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    init {
        // 先恢复再开始写回：反过来的话，第一次写入的是构造时的空表，上次的记录就被抹掉了
        scope.launch {
            restore()
            persistOnStructuralChange()
        }
    }

    /**
     * 读回上次保存的任务表。
     *
     * 上次进程结束时仍在下载或排队的任务，协程早已不在，一律转为暂停，由用户决定是否继续。
     * 已完成的任务以磁盘为准核对，文件被删或长度不足的丢弃，免得列表里挂着打不开的条目。
     */
    private suspend fun restore() {
        val saved = runSuspendCatching {
            json.decodeFromString(taskListSerializer, preferences.loadDownloadTasks())
        }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        val restored = withContext(Dispatchers.IO) { saved.mapNotNull { restoreTask(it) } }
        // 恢复期间用户可能已经加了新任务，同一任务以内存里的为准
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
    }

    // 片段任务的 destinationPath 与按 fileName 在下载目录里解析出的是同一个文件，存储层
    // 只提供按文件名查询，所以两类任务都按 fileName 核对
    private suspend fun restoreTask(task: DownloadTask): DownloadTask? {
        val stopped = task.copy(speedBytesPerSec = 0L)
        if (task.status == DownloadStatus.COMPLETED) {
            if (!storage.exists(task.fileName)) return null
            val length = storage.existingLength(task.fileName)
            // 片段的 totalBytes 在旧版本里一直是 0，「长度不小于 totalBytes」对空文件也成立，
            // 抽取失败留下的 0 字节文件会被当成已完成恢复回来。片段改为要求非空，并补上大小
            if (task.isSegment) {
                return stopped.copy(totalBytes = length, downloadedBytes = length).takeIf { length > 0 }
            }
            return stopped.takeIf { length >= task.totalBytes }
        }
        val status = when (task.status) {
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING -> DownloadStatus.PAUSED
            else -> task.status
        }
        // 保存只在状态变化时发生，记下的字节数可能落后；整文件下载的续传点就是文件长度
        val downloaded = if (task.isSegment) {
            task.downloadedBytes
        } else {
            storage.existingLength(task.fileName).coerceAtMost(task.totalBytes)
        }
        return stopped.copy(status = status, downloadedBytes = downloaded)
    }

    /**
     * 任务增删或状态变化时保存整张表。进度每 500 毫秒刷新一次，按它写盘的话，
     * Android 的 DataStore 每次都要整份重写文件。
     */
    private suspend fun persistOnStructuralChange() {
        _tasks
            .distinctUntilChangedBy { tasks -> tasks.mapValues { it.value.status } }
            .collect { tasks ->
                val serialized = json.encodeToString(taskListSerializer, tasks.values.toList())
                // 写盘失败只影响下次启动能否恢复，不能让收集协程带着异常退出
                runSuspendCatching { preferences.saveDownloadTasks(serialized) }
            }
    }

    /**
     * 这个文件在下载目录里有没有完整副本。
     *
     * 只查内存任务表会在 App 重启后失忆（表是空的），明明下好的片子又去云端取流。
     * 这里以磁盘为准：sanitize 后的文件名对上、长度落满才算数，暂停中的半截文件不算。
     */
    suspend fun findCompletedLocalPath(file: FileStat): String? = withContext(Dispatchers.IO) {
        if (file.sizeBytes <= 0L) return@withContext null
        val name = FileNameSanitizer.sanitize(file.name)
        if (!storage.exists(name)) return@withContext null
        if (storage.existingLength(name) < file.sizeBytes) return@withContext null
        // SAF 目录返回的是 content: URI，播放器认不了，维持走云端（与之前行为一致）。
        storage.pathFor(name).takeUnless { it.startsWith("content:") }
    }

    fun enqueue(file: FileStat) {
        // 正在下载的同一个文件再点一次下载，不能用一份 PENDING 的新任务盖掉进行中的那份
        if (jobs.value[file.id]?.isActive == true) return
        onDownloadStarted?.invoke()
        val name = FileNameSanitizer.sanitize(file.name)
        val existing = scope.launch(Dispatchers.IO) {
            val downloaded = storage.existingLength(name)
            val complete = downloaded >= file.sizeBytes && file.sizeBytes > 0L
            val task = DownloadTask(
                taskId = file.id,
                fileId = file.id,
                fileName = name,
                gcid = file.hash,
                totalBytes = file.sizeBytes,
                downloadedBytes = downloaded.coerceAtMost(file.sizeBytes),
                destinationPath = name,
                status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
                fullFileSize = file.sizeBytes,
                thumbnailLink = file.thumbnailLink,
                parentId = file.parentId,
                createdAtMs = Clock.System.now().toEpochMilliseconds(),
            )
            _tasks.update { it + (task.taskId to task) }
            if (!complete) startDownload(task.taskId)
        }
        existing.invokeOnCompletion { if (it != null) _tasks.update { tasks -> tasks - file.id } }
    }

    fun startDownload(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        onDownloadStarted?.invoke()
        PikoLog.d(TAG, "开始：${logFile(task.fileId, task.fileName)}，${task.downloadedBytes}/${task.totalBytes}${if (task.isSegment) "，片段" else ""}")
        // 片段任务要重新抽取，不能走整文件下载：它的 totalBytes 是 0，gcid 属于整个源文件
        if (task.isSegment) startSegment(task) else launchTracked(taskId) { runDownload(task) }
    }

    /**
     * 整文件下载走 SDK 的 downloadTo，而不是 openStream 逐块读。
     *
     * openStream 是给播放器的：请求带播放优先级，与正在播放的流抢同一份账号连接预算，
     * 还要为每个下载多占一份预读缓存。更要紧的是它不带续传点，旧实现暂停后再继续是
     * 从零读起：Android 截断重下，Desktop 追加写入把整份文件再接到尾部，文件直接写坏，
     * 而长度超过原文件又会被 findCompletedLocalPath 当成「已完成」。downloadTo 顺序追加，
     * 文件长度就是进度，取消即暂停，再调用一次从断点继续。
     */
    private suspend fun runDownload(task: DownloadTask) {
        val taskId = task.taskId
        val client = clientProvider.currentClient.value
        if (client == null) {
            update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "未登录") }
            return
        }
        update(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null) }
        val concurrency = preferences.concurrentConnectionsFlow.first()
        val handle = PikPakFileHandle(
            client = client,
            gcid = task.gcid,
            size = task.totalBytes,
            name = task.fileName,
            initialFileId = task.fileId,
            parentId = task.parentId,
            connectionBudget = concurrency,
        )
        val progress = MutableStateFlow(task.downloadedBytes)
        try {
            coroutineScope {
                val reporter = launch { reportProgress(taskId, progress) }
                val target = storage.downloadTarget(task.fileName)
                handle.downloadTo(Path(target), task.totalBytes, concurrency = concurrency, progress = progress)
                reporter.cancel()
                val destinationPath = storage.commit(task.fileName, target)
                PikoLog.d(TAG, "完成：${logFile(task.fileId, task.fileName)}")
                update(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = task.totalBytes,
                        speedBytesPerSec = 0L,
                        destinationPath = destinationPath,
                    )
                }
            }
        } catch (e: CancellationException) {
            update(taskId) {
                it.copy(status = DownloadStatus.PAUSED, downloadedBytes = progress.value, speedBytesPerSec = 0L)
            }
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "下载失败：${logFile(task.fileId, task.fileName)}，已下载 ${progress.value}/${task.totalBytes}", e)
            update(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    downloadedBytes = progress.value,
                    speedBytesPerSec = 0L,
                    errorMessage = e.message,
                )
            }
        } finally {
            handle.close()
        }
    }

    /**
     * 按固定间隔把字节进度与速度写进任务表。
     *
     * SDK 每写完一个块就更新一次进度，高速下每秒上百次。逐次写进 StateFlow 意味着每次都
     * 复制整张任务表、唤醒所有收集者：列表重组，前台服务重发通知，而系统对单个应用的
     * 通知更新本来就有频率上限，多出来的只会被丢弃。
     */
    private suspend fun reportProgress(taskId: String, progress: StateFlow<Long>) {
        val clock = TimeSource.Monotonic
        var lastMark = clock.markNow()
        var lastBytes = progress.value
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val bytes = progress.value
            val elapsedMs = lastMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
            val speed = ((bytes - lastBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            lastMark = clock.markNow()
            lastBytes = bytes
            update(taskId) { it.copy(downloadedBytes = bytes, speedBytesPerSec = speed) }
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
            parentId = file.parentId,
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
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
            update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "当前平台不支持分段抽取") }
            return
        }
        launchTracked(task.taskId) {
            update(task.taskId) { it.copy(status = DownloadStatus.DOWNLOADING, progressFraction = 0f) }
            // 源地址在抽取开始时现取，经本机代理读，不用入队时存下的直链。直链绕过 SDK 的账号
            // 连接预算，与代理、预览播放器抢连接，超出上限后 CDN 一律回 503（2026-09-23 实测），
            // 抽取器只会不停重试；存下的直链还会过期，恢复出来的任务续做时必然失败。
            val prepared = mediaRepository?.let { repo ->
                repo.preparePlayback(task.fileId).getOrElse { error ->
                    PikoLog.w(TAG, "片段抽取取源失败：${logFile(task.fileId, task.fileName)}", error)
                    update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) }
                    return@launchTracked
                }
            }
            val sourceUrl = prepared?.let { it.proxyUrl ?: it.info.currentUrl } ?: task.streamUrl.orEmpty()
            try {
                extractor.extract(
                    PikoSegmentRequest(
                        sourceUrl = sourceUrl,
                        destinationPath = task.destinationPath,
                        fileName = task.fileName,
                        startMillis = task.startMs,
                        endMillis = task.endMs,
                        openRandomAccess = mediaRepository?.let { repo -> { repo.openRandomAccess(task.fileId).getOrThrow() } },
                    ),
                ) { fraction ->
                    update(task.taskId) { it.copy(progressFraction = fraction) }
                }.onSuccess { path ->
                    // 片段入队时不知道产物大小，totalBytes 一直是 0，列表会显示 0 B，完成后按实际文件补上
                    val size = runSuspendCatching { storage.existingLength(task.fileName) }.getOrDefault(0L)
                    update(task.taskId) {
                        it.copy(
                            status = DownloadStatus.COMPLETED,
                            destinationPath = path,
                            progressFraction = 1f,
                            totalBytes = size,
                            downloadedBytes = size,
                        )
                    }
                }.onFailure { error ->
                    PikoLog.w(TAG, "片段抽取失败：${logFile(task.fileId, task.fileName)}，${task.startMs}–${task.endMs} ms", error)
                    update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) }
                }
            } finally {
                prepared?.close()
            }
        }
    }

    fun pauseDownload(taskId: String) {
        jobs.value[taskId]?.cancel()
        update(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
    }

    /** 把所有进行中的任务转为暂停。前台服务被系统叫停时用，之后可以逐个继续。 */
    fun pauseAll() {
        jobs.value.keys.forEach(::pauseDownload)
    }

    fun cancelDownload(taskId: String) {
        val job = jobs.value[taskId]
        val task = _tasks.value[taskId]
        _tasks.update { it - taskId }
        if (task == null) {
            job?.cancel()
            return
        }
        // 先等下载协程真正退出再删文件：cancel 只是发出请求，协程可能还在写最后一块，
        // 抢先删掉的话它会把文件重新建出来
        scope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            if (task.status == DownloadStatus.COMPLETED || task.isSegment) {
                if (task.destinationPath.isNotBlank()) storage.delete(task.destinationPath)
            } else {
                storage.delete(storage.downloadTarget(task.fileName))
            }
        }
    }

    /**
     * 启动一个登记在 [jobs] 里的任务协程。同一任务已有活跃协程时不再启动。
     *
     * 协程先以 LAZY 创建、登记成功后才启动：先启动再登记的话，它可能在登记前就跑完，
     * 结束时摘不掉自己，表里留下一个永远「在跑」的死条目。
     */
    private fun launchTracked(taskId: String, block: suspend () -> Unit) {
        var previous: Job? = null
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                // 暂停后马上继续时，被取消的旧协程可能还在写最后一块。两者同时追加同一个文件，
                // 续传点就对不上了，所以先等它彻底退出
                previous?.join()
                block()
            } finally {
                // 只摘自己：暂停后立刻继续时，表里已经是新协程，旧协程的收尾不能把它摘掉
                jobs.update { current -> if (current[taskId] === self) current - taskId else current }
            }
        }
        var registered = false
        jobs.update { current ->
            val existing = current[taskId]
            if (existing?.isActive == true) {
                registered = false
                current
            } else {
                registered = true
                previous = existing
                current + (taskId to job)
            }
        }
        if (registered) job.start() else job.cancel()
    }

    private fun update(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { tasks -> tasks[taskId]?.let { tasks + (taskId to transform(it)) } ?: tasks }
    }

    private companion object {
        const val TAG = "Download"
        const val PROGRESS_INTERVAL_MS = 500L
        val json = Json { ignoreUnknownKeys = true }
        val taskListSerializer = ListSerializer(DownloadTask.serializer())
    }
}
