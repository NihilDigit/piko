package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.data.runSuspendCatching
import io.github.nihildigit.pikpak.BandwidthLimiter
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeMark
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

    // 蜗牛模式：所有下载任务、所有连接共用这一个额度，总和不超过上限；改设置即时生效，不必重启任务
    private val limiter = BandwidthLimiter()

    private val _listings = MutableStateFlow<Map<String, FolderListing>>(emptyMap())

    /** 还没变成任务的文件夹下载，按批次 ID。见 [enqueueFolders]。 */
    val listings: StateFlow<Map<String, FolderListing>> = _listings.asStateFlow()

    // 列出中的文件夹下载的输入与协程，重试与放弃要用；列完等确认的还带着排好的任务
    private val listingWork = MutableStateFlow<Map<String, ListingWork>>(emptyMap())

    private class ListingWork(
        val folder: FileStat,
        val source: DownloadFolderSource,
        val job: Job? = null,
        val planned: List<DownloadTask> = emptyList(),
    )

    init {
        scope.launch {
            preferences.snailModeFlow.collect { mode ->
                limiter.bytesPerSecond = if (mode.enabled) mode.downloadKiBps * 1024L else null
            }
        }
        // 先恢复再开始写回：反过来的话，第一次写入的是构造时的空表，上次的记录就被抹掉了
        scope.launch {
            restore()
            persistOnStructuralChange()
        }
        // 换号时别的账号的任务转为暂停：它们手里的 client 随即关闭，放着不管会以失败告终
        scope.launch {
            clientProvider.currentClient.map { it?.account }.distinctUntilChanged().collect {
                // 文件夹下载的任务先整批停下：逐个暂停时，每停一个就会补上同一批里排着的下一个
                _tasks.value.values.mapNotNull { task -> task.batch?.id?.takeIf { !belongsToCurrent(task) } }
                    .distinct().forEach(::pauseBatch)
                _tasks.value.values.filter { task -> task.taskId in jobs.value && !belongsToCurrent(task) }
                    .forEach { task -> pauseDownload(task.taskId) }
                // 列到一半的文件夹换了账号就列不下去，停下等切回来重试
                _listings.value.values.filter { it.isListing && it.account != currentAccount() }.forEach { listing ->
                    listingWork.value[listing.batch.id]?.job?.cancel()
                    updateListing(listing.batch.id) { it.copy(error = OTHER_ACCOUNT) }
                }
            }
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
     *
     * 写完一次至少隔 [PERSIST_INTERVAL_MS] 再写，其间的变化并成一次：文件夹下载一批上千个小文件，
     * 每完成一个就是一次状态变化，逐次写就是上千次整表重写。晚写的那一段丢了也无妨，恢复时以磁盘为准核对。
     */
    private suspend fun persistOnStructuralChange() {
        _tasks
            .distinctUntilChangedBy { tasks -> tasks.mapValues { it.value.status } }
            .conflate()
            .collect { tasks ->
                val serialized = json.encodeToString(taskListSerializer, tasks.values.toList())
                // 写盘失败只影响下次启动能否恢复，不能让收集协程带着异常退出
                runSuspendCatching { preferences.saveDownloadTasks(serialized) }
                delay(PERSIST_INTERVAL_MS)
            }
    }

    /**
     * 这个文件在下载目录里有没有完整副本。
     *
     * 只查内存任务表会在 App 重启后失忆（表是空的），明明下好的片子又去云端取流。
     * 这里以磁盘为准：sanitize 后的文件名对上、长度落满才算数，暂停中的半截文件不算。
     */
    private fun localNameOf(file: FileStat): String = localFileNameOf(file)

    suspend fun findCompletedLocalPath(file: FileStat): String? = withContext(Dispatchers.IO) {
        if (file.sizeBytes <= 0L) return@withContext null
        // 随文件夹下载下来的落在子文件夹里，路径只有任务表知道；任务表是持久化的，重启后照样查得到
        val inFolders = _tasks.value.values.filter { it.fileId == file.id && it.batch != null }.map { it.fileName }
        val name = (inFolders + localNameOf(file)).firstOrNull { name ->
            storage.exists(name) && storage.existingLength(name) >= file.sizeBytes
        } ?: return@withContext null
        // SAF 目录返回的是 content: URI，播放器认不了，维持走云端（与之前行为一致）。
        storage.pathFor(name).takeUnless { it.startsWith("content:") }
    }

    fun enqueue(file: FileStat) {
        // 正在下载的同一个文件再点一次下载，不能用一份 PENDING 的新任务盖掉进行中的那份
        if (jobs.value[file.id]?.isActive == true) return
        onDownloadStarted?.invoke()
        val name = localNameOf(file)
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
                account = currentAccount(),
            )
            _tasks.update { it + (task.taskId to task) }
            if (!complete) startDownload(task.taskId)
        }
        existing.invokeOnCompletion { if (it != null) _tasks.update { tasks -> tasks - file.id } }
    }

    /**
     * 下载几个文件夹，每个一批：在后台列出其中全部文件，落在下载目录下同名的文件夹里、保持子文件夹结构。
     * 列出期间与列完等确认时见 [listings]；列完即变成任务表里的一批任务（[DownloadTask.batch]），
     * 同一批同时只下 [BATCH_PARALLEL] 个，其余排着。返回开始列出的批数：Piko 自己的文件夹不下载，见 [isPikoFolder]。
     */
    fun enqueueFolders(folders: List<FileStat>, source: DownloadFolderSource): Int {
        val accepted = folders.filter { it.isFolder && !isPikoFolder(it) }
        accepted.forEach { folder ->
            val now = Clock.System.now().toEpochMilliseconds()
            val batch = DownloadBatch(id = "${folder.id}@$now", folderName = FileNameSanitizer.sanitizeFolderName(folder.name))
            _listings.update { it + (batch.id to FolderListing(batch, createdAtMs = now, account = currentAccount())) }
            listingWork.update { it + (batch.id to ListingWork(folder, source)) }
            startListing(batch.id)
        }
        return accepted.size
    }

    /** 列出失败的重新列一遍。 */
    fun retryListing(batchId: String) {
        val listing = _listings.value[batchId] ?: return
        if (listing.account.isNotEmpty() && listing.account != currentAccount()) return
        updateListing(batchId) { it.copy(filesFound = 0, bytesFound = 0L, error = null, quotaExcess = null) }
        startListing(batchId)
    }

    /** 超出今日额度也照样下载。 */
    fun confirmListing(batchId: String) {
        val work = listingWork.value[batchId] ?: return
        if (work.planned.isNotEmpty()) addBatch(batchId, work.planned)
    }

    /** 不下载了：停下列出，或丢掉等确认的那一批。 */
    fun dismissListing(batchId: String) {
        listingWork.value[batchId]?.job?.cancel()
        listingWork.update { it - batchId }
        _listings.update { it - batchId }
    }

    private fun startListing(batchId: String) {
        val work = listingWork.value[batchId] ?: return
        val listing = _listings.value[batchId] ?: return
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val planned = try {
                planFolderDownload(work.folder, work.source) { files, bytes ->
                    updateListing(batchId) { it.copy(filesFound = files, bytesFound = bytes) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "列出文件夹失败：${logFile(work.folder.id, work.folder.name)}", e)
                updateListing(batchId) { it.copy(error = e.message?.takeIf { m -> m.isNotBlank() } ?: "网络中断") }
                return@launch
            }
            if (planned.isEmpty()) {
                updateListing(batchId) { it.copy(error = "文件夹里没有可下载的文件") }
                return@launch
            }
            // 已在本机的按长度认作完成，与单个文件的下载一样；上千个文件逐个查长度，放在 IO 线程上
            val tasks = withContext(Dispatchers.IO) { planned.map { plannedTask(it, listing) } }
            val needed = tasks.filter { it.status != DownloadStatus.COMPLETED }.sumOf { it.totalBytes - it.downloadedBytes }
            val remaining = runSuspendCatching { work.source.remainingDailyDownload() }.getOrNull()
            PikoLog.d(TAG, "列出文件夹：${logFile(work.folder.id, work.folder.name)}，${tasks.size} 个文件，待下载 $needed 字节，今日余量 $remaining")
            if (remaining != null && needed > remaining) {
                listingWork.update { current -> current[batchId]?.let { current + (batchId to ListingWork(it.folder, it.source, planned = tasks)) } ?: current }
                updateListing(batchId) { it.copy(quotaExcess = QuotaExcess(needed, remaining)) }
            } else {
                addBatch(batchId, tasks)
            }
        }
        listingWork.update { current -> current[batchId]?.let { current + (batchId to ListingWork(it.folder, it.source, job)) } ?: current }
        job.start()
    }

    private suspend fun plannedTask(planned: PlannedFile, listing: FolderListing): DownloadTask {
        val file = planned.file
        val downloaded = storage.existingLength(planned.path)
        val complete = downloaded >= file.sizeBytes && file.sizeBytes > 0L
        return DownloadTask(
            taskId = file.id,
            fileId = file.id,
            fileName = planned.path,
            gcid = file.hash,
            totalBytes = file.sizeBytes,
            downloadedBytes = downloaded.coerceAtMost(file.sizeBytes),
            destinationPath = if (complete) storage.locate(planned.path) ?: storage.pathFor(planned.path) else planned.path,
            status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
            fullFileSize = file.sizeBytes,
            thumbnailLink = file.thumbnailLink,
            parentId = file.parentId,
            createdAtMs = listing.createdAtMs,
            account = listing.account,
            batch = listing.batch,
        )
    }

    // 一次写进任务表：逐个加的话上千个文件就是上千次整表复制与重组
    private fun addBatch(batchId: String, tasks: List<DownloadTask>) {
        _tasks.update { current ->
            // 正在下的同一个文件不拿排队的新任务盖掉，理由同 enqueue
            current + tasks.filter { jobs.value[it.taskId]?.isActive != true }.associateBy { it.taskId }
        }
        listingWork.update { it - batchId }
        _listings.update { it - batchId }
        onDownloadStarted?.invoke()
        pumpBatch(batchId)
    }

    /**
     * 按顺序补上同一批里排着的任务，直到有 [BATCH_PARALLEL] 个在下。任务结束、暂停、整批继续时调用。
     * 在同一次 CAS 里把选中的转为下载中，几处同时补时不会多开。
     */
    private fun pumpBatch(batchId: String) {
        var claimed = emptyList<String>()
        _tasks.update { tasks ->
            val batch = tasks.values.filter { it.batch?.id == batchId }
            val running = batch.count { it.status == DownloadStatus.DOWNLOADING }
            val next = batch.filter { it.status == DownloadStatus.PENDING }
                .sortedBy { it.fileName }
                .take((BATCH_PARALLEL - running).coerceAtLeast(0))
            claimed = next.map { it.taskId }
            if (next.isEmpty()) tasks else tasks + next.associate { it.taskId to it.copy(status = DownloadStatus.DOWNLOADING) }
        }
        claimed.forEach(::startDownload)
    }

    fun pauseBatch(batchId: String) {
        val ids = batchTaskIds(batchId)
        // 先把排着的一起转为暂停，再停在下的：停一个会补下一个，补的时候已经没有排着的了
        _tasks.update { tasks ->
            tasks + ids.mapNotNull { id ->
                tasks[id]?.takeIf { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
                    ?.let { id to it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
            }
        }
        ids.forEach { id -> jobs.value[id]?.cancel() }
    }

    /** 整批继续：暂停与失败的重新排队。 */
    fun resumeBatch(batchId: String) {
        val ids = batchTaskIds(batchId)
        _tasks.update { tasks ->
            tasks + ids.mapNotNull { id ->
                tasks[id]?.takeIf { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
                    ?.let { id to it.copy(status = DownloadStatus.PENDING, errorMessage = null) }
            }
        }
        onDownloadStarted?.invoke()
        pumpBatch(batchId)
    }

    /** 取消整批并删掉已下载的文件，连同留下的空文件夹。 */
    fun cancelBatch(batchId: String) {
        dismissListing(batchId)
        val tasks = _tasks.value.values.filter { it.batch?.id == batchId }
        val folder = tasks.firstOrNull()?.batch?.folderName ?: return
        // 先整批转为暂停，免得逐个取消时补上同一批里排着的
        pauseBatch(batchId)
        val removals = tasks.mapNotNull { cancelDownload(it.taskId) }
        scope.launch(Dispatchers.IO) {
            removals.joinAll()
            runSuspendCatching { storage.pruneEmptyFolders(folder) }
        }
    }

    /** 这一批的文件夹在本机的位置，还没建出来时为 null。 */
    suspend fun batchFolderPath(batch: DownloadBatch): String? = storage.locate(batch.folderName)

    private fun batchTaskIds(batchId: String): List<String> =
        _tasks.value.values.filter { it.batch?.id == batchId }.map { it.taskId }

    private fun updateListing(batchId: String, transform: (FolderListing) -> FolderListing) {
        _listings.update { listings -> listings[batchId]?.let { listings + (batchId to transform(it)) } ?: listings }
    }

    private fun currentAccount(): String = clientProvider.currentClient.value?.account.orEmpty()

    private fun belongsToCurrent(task: DownloadTask): Boolean = task.account.isEmpty() || task.account == currentAccount()

    fun startDownload(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        // 文件 ID 与直链只在源账号里有效，换到别的账号上取不到
        if (!belongsToCurrent(task)) {
            update(taskId) { it.copy(status = DownloadStatus.PAUSED, errorMessage = OTHER_ACCOUNT) }
            return
        }
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
        // 归档条目没有常驻的文件：handle 按 gcid 造一份，取到直链就删，与播放时一样只借用。
        // 造在根目录而不是所在的文件夹，免得删之前在用户眼前的目录里闪一下
        val vaulted = VaultEntry.isVaulted(task.fileId)
        val handle = PikPakFileHandle(
            client = client,
            gcid = task.gcid,
            size = task.totalBytes,
            name = task.fileName,
            initialFileId = task.fileId.takeUnless { vaulted },
            parentId = if (vaulted) "" else task.parentId,
            connectionBudget = concurrency,
            leased = vaulted,
            onRangeAttempt = ::logRangeAttempt,
        )
        val progress = MutableStateFlow(task.downloadedBytes)
        val started = TimeSource.Monotonic.markNow()
        // 这一次跑了多少、平均多快，暂停、完成、失败时各记一笔，与信息流取流的速度对照
        fun session() = "本次 ${formatRate(progress.value - task.downloadedBytes, started.elapsedNow().inWholeMilliseconds)}，并发 $concurrency"
        try {
            coroutineScope {
                val reporter = launch { reportProgress(taskId, progress) }
                val target = storage.downloadTarget(task.fileName)
                handle.downloadTo(Path(target), task.totalBytes, concurrency = concurrency, progress = progress, limiter = limiter)
                reporter.cancel()
                val destinationPath = storage.commit(task.fileName, target)
                PikoLog.d(TAG, "完成：${logFile(task.fileId, task.fileName)}，${session()}")
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
            PikoLog.d(TAG, "暂停：${logFile(task.fileId, task.fileName)}，${progress.value}/${task.totalBytes}，${session()}")
            update(taskId) {
                it.copy(status = DownloadStatus.PAUSED, downloadedBytes = progress.value, speedBytesPerSec = 0L)
            }
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "下载失败：${logFile(task.fileId, task.fileName)}，已下载 ${progress.value}/${task.totalBytes}，${session()}", e)
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
        // 速度按最近 SPEED_WINDOW_MS 算，不按上一次采样：downloadTo 按顺序追加写盘，队头一块没到时
        // 先到的块都写不进去，0.5 秒的读数就在 0 与十几 MB/s 之间来回跳，而实际吞吐是稳的（2026-09-28）
        val samples = ArrayDeque<Pair<TimeMark, Long>>()
        samples.addLast(clock.markNow() to progress.value)
        // 日志另按 LOG_INTERVAL_MS 记一笔：界面的读数一秒一变，写进日志只要看得出走势
        var logMark = clock.markNow()
        var logBytes = progress.value
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val bytes = progress.value
            samples.addLast(clock.markNow() to bytes)
            while (samples.size > 2 && samples.first().first.elapsedNow().inWholeMilliseconds > SPEED_WINDOW_MS) samples.removeFirst()
            val (oldestMark, oldestBytes) = samples.first()
            val elapsedMs = oldestMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
            val speed = ((bytes - oldestBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            update(taskId) { it.copy(downloadedBytes = bytes, speedBytesPerSec = speed) }
            val logElapsed = logMark.elapsedNow().inWholeMilliseconds
            if (logElapsed >= LOG_INTERVAL_MS) {
                PikoLog.d(TAG, "进度 $taskId：$bytes 字节，近 ${logElapsed / 1000} 秒 ${formatRate(bytes - logBytes, logElapsed)}")
                logMark = clock.markNow()
                logBytes = bytes
            }
        }
    }

    private fun formatRate(bytes: Long, elapsedMs: Long): String {
        val kibPerSec = bytes.coerceAtLeast(0L) * 1000L / elapsedMs.coerceAtLeast(1L) / 1024
        return "${bytes.coerceAtLeast(0L) / 1024 / 1024} MiB / ${elapsedMs / 1000} 秒，$kibPerSec KiB/s"
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
            account = currentAccount(),
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

    /** 返回删文件的协程，整批取消时等它们删完再收拾空文件夹。 */
    fun cancelDownload(taskId: String): Job? {
        val job = jobs.value[taskId]
        val task = _tasks.value[taskId]
        _tasks.update { it - taskId }
        if (task == null) {
            job?.cancel()
            return null
        }
        // 先等下载协程真正退出再删文件：cancel 只是发出请求，协程可能还在写最后一块，
        // 抢先删掉的话它会把文件重新建出来
        return scope.launch(Dispatchers.IO) {
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
                // 腾出一个位置，同一批里排着的补上
                _tasks.value[taskId]?.batch?.let { pumpBatch(it.id) }
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
        const val OTHER_ACCOUNT = "需切换至所属账号后继续"
        const val PROGRESS_INTERVAL_MS = 500L
        const val LOG_INTERVAL_MS = 10_000L
        const val SPEED_WINDOW_MS = 3_000L
        const val PERSIST_INTERVAL_MS = 1_000L

        /**
         * 一批里同时下几个。每个任务各开「并发连接数」条连接，全放开的话一个上千文件的文件夹会同时开几千条；
         * 只下一个又太慢：字幕、图片这类小文件的耗时几乎全在取直链上。
         */
        const val BATCH_PARALLEL = 3
        val json = Json { ignoreUnknownKeys = true }
        val taskListSerializer = ListSerializer(DownloadTask.serializer())
    }
}

/**
 * 存到本机的文件名。离线下载进来的文件常常名字里不带扩展名（「…[繁日雙語MP4][1080P]」），扩展名单在
 * file_extension 里；照名字原样存，系统就不知道拿什么打开。名字里已经是这个扩展名的不重复补。
 */
internal fun localFileNameOf(file: FileStat): String {
    val extension = file.fileExtension.trim().removePrefix(".")
    val named = extension.isEmpty() || file.name.endsWith(".$extension", ignoreCase = true)
    return FileNameSanitizer.sanitize(if (named) file.name else "${file.name}.$extension")
}
