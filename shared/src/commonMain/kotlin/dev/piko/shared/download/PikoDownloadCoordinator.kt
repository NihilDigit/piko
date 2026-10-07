package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logRangeAttempt
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.TRANSCODE_GONE_MESSAGE
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.runSuspendCatching
import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.FileStat
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.PikPakFileHandle
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.cache.copyCachedFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.PikPakStreamReader
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

    private data class ListingWork(
        val folder: FileStat,
        val source: DownloadFolderSource,
        /** 下载时选的画质上限，null 取设置里的。重新列出时照旧用它。 */
        val maxHeight: Int?,
        val job: Job? = null,
        val planned: List<DownloadTask> = emptyList(),
    )

    val fileCachePool = mediaRepository?.fileCachePool ?: PikoFileCachePool(scope) { storage.cacheTarget(it) }
    private val queueLock = Mutex()
    private val enqueueLock = Mutex()
    private val listingSlots = Semaphore(4)
    private var lastGroup: String? = null
    private var batchSequence = 0L

    init {
        mediaRepository?.fileCachePool = fileCachePool
        mediaRepository?.partialDownload = { fileId ->
            _tasks.value.values.firstOrNull { it.fileId == fileId && belongsToCurrent(it) &&
                !it.isSegment && it.mediaId == null && it.status != DownloadStatus.COMPLETED && it.sparseCache }
        }
        // 播放也会补齐暂停的下载。进度与网络速度统一采样，同一缓存的网络字节只统计一次。
        scope.launch {
            val samples = mutableMapOf<String, ArrayDeque<Pair<TimeMark, Long>>>()
            var previousTick = TimeSource.Monotonic.markNow()
            var logMark = TimeSource.Monotonic.markNow()
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                val snapshots = fileCachePool.progressSnapshot()
                val speeds = snapshots.mapValues { (path, snapshot) ->
                    val window = samples.getOrPut(path) { ArrayDeque(listOf(previousTick to 0L)) }
                    window.addLast(TimeSource.Monotonic.markNow() to snapshot.deliveredBytes)
                    while (window.size > 2 && window.first().first.elapsedNow().inWholeMilliseconds > SPEED_WINDOW_MS) window.removeFirst()
                    val (mark, bytes) = window.first()
                    (snapshot.deliveredBytes - bytes).coerceAtLeast(0) * 1000 / mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                }
                samples.keys.retainAll(snapshots.keys)
                previousTick = TimeSource.Monotonic.markNow()
                _tasks.update { tasks ->
                    // 片段也占着暂存，但它的进度按截到的时间算，字节数是整个源文件的，不往它身上记
                    val primary = tasks.values.filter { it.status == DownloadStatus.DOWNLOADING && it.sparseCache && !it.isSegment }
                        .groupBy { it.cacheIdentity }.mapValues { it.value.minBy { task -> task.taskId }.taskId }
                    val changed = tasks.values.mapNotNull { task ->
                        if (!task.sparseCache || task.isSegment) return@mapNotNull null
                        val identity = task.cacheIdentity
                        val snapshot = snapshots[identity] ?: return@mapNotNull null
                        if (task.status == DownloadStatus.COMPLETED) return@mapNotNull null
                        val speed = if (primary[identity] == task.taskId) speeds[identity] ?: 0L else 0L
                        if (snapshot.heldBytes == task.downloadedBytes && speed == task.speedBytesPerSec) null
                        else task.taskId to task.copy(downloadedBytes = snapshot.heldBytes, speedBytesPerSec = speed)
                    }
                    if (changed.isEmpty()) tasks else tasks + changed
                }
                if (logMark.elapsedNow().inWholeMilliseconds >= LOG_INTERVAL_MS && snapshots.isNotEmpty()) {
                    PikoLog.d(TAG, "下载：${speeds.values.sum()} 字节/秒，${snapshots.size} 个共享文件缓存")
                    logMark = TimeSource.Monotonic.markNow()
                }
            }
        }
        scope.launch {
            preferences.snailModeFlow.collect { mode ->
                limiter.bytesPerSecond = if (mode.enabled) mode.downloadKiBps * 1024L else null
            }
        }
        // 先恢复再开始写回：反过来的话，第一次写入的是构造时的空表，上次的记录就被抹掉了
        scope.launch {
            restore()
            runSuspendCatching { fileCachePool.prune() }.logFailure(TAG, "清理暂存下载失败")
            persistOnStructuralChange()
        }
        // 换号时别的账号的任务转为暂停：它们手里的 client 随即关闭，放着不管会以失败告终
        scope.launch {
            clientProvider.currentClient.map { it?.account }.distinctUntilChanged().collect {
                val running = _tasks.value.values.count { task -> task.taskId in jobs.value && !belongsToCurrent(task) }
                if (running > 0) PikoLog.i(TAG, "换号：暂停另一账号进行中的 $running 个任务")
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
            val serialized = preferences.loadDownloadTasks()
            // 空串是从未保存过（新装、新的数据目录），不是坏数据
            if (serialized.isBlank()) emptyList() else json.decodeFromString(taskListSerializer, serialized)
        }.logFailure(TAG, "读回下载任务表失败，按空表处理").getOrDefault(emptyList())
        if (saved.isEmpty()) return
        val restored = withContext(Dispatchers.IO) { saved.mapNotNull { restoreTask(it) } }
        PikoLog.i(TAG, "恢复下载任务：保存 ${saved.size} 个，恢复 ${restored.size} 个，" +
            "其中未完成 ${restored.count { it.status != DownloadStatus.COMPLETED }} 个；本机文件缺失或不完整丢弃 ${saved.size - restored.size} 个")
        // 恢复期间用户可能已经加了新任务，同一任务以内存里的为准
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
    }

    // 片段任务的 destinationPath 与按 fileName 在下载目录里解析出的是同一个文件，存储层
    // 只提供按文件名查询，所以两类任务都按 fileName 核对
    private suspend fun restoreTask(task: DownloadTask): DownloadTask? {
        if (task.sparseCache && task.status != DownloadStatus.COMPLETED) {
            fileCachePool.remember(task.account, task.gcid, task.cacheSize, task.taskId, task.cacheMediaId)
        }
        // 转封装做到一半退出的，下次从头再转：转码流还整个在暂存里，只是本机的活
        val stopped = task.copy(speedBytesPerSec = 0L, converting = false,
            progressFraction = task.progressFraction.takeUnless { task.converting })
        if (task.status == DownloadStatus.COMPLETED) {
            if (!storage.exists(task.fileName)) return null
            val length = storage.existingLength(task.fileName)
            // 片段的 totalBytes 在旧版本里一直是 0，「长度不小于 totalBytes」对空文件也成立，
            // 抽取失败留下的 0 字节文件会被当成已完成恢复回来。片段改为要求非空，并补上大小
            if (task.isSegment) {
                return stopped.copy(totalBytes = length, downloadedBytes = length).takeIf { length > 0 }
            }
            return stopped.takeIf { length == task.totalBytes }
        }
        val status = when (task.status) {
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING -> DownloadStatus.PAUSED
            else -> task.status
        }
        // 保存只在状态变化时发生，记下的字节数可能落后；整文件下载的续传点就是文件长度
        val downloaded = if (task.isSegment) {
            task.downloadedBytes
        } else {
            if (task.sparseCache) fileCachePool.progress(task.account, task.gcid, task.cacheSize, task.cacheMediaId)
            else storage.existingLength(task.fileName).coerceAtMost(task.totalBytes)
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
            // 暂存的身份（档位与长度）变了也要写：重启时按它登记暂存，记错了暂存会被当成没人要的删掉
            .distinctUntilChangedBy { tasks ->
                tasks.mapValues { (_, task) -> listOf(task.status, task.sparseCache, task.fileName, task.mediaId, task.fullFileSize) }
            }
            .conflate()
            .collect { tasks ->
                val serialized = json.encodeToString(taskListSerializer, tasks.values.toList())
                // 写盘失败只影响下次启动能否恢复，不能让收集协程带着异常退出
                runSuspendCatching { preferences.saveDownloadTasks(serialized) }.logFailure(TAG, "保存下载任务表失败")
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
        val inFolders = _tasks.value.values.filter { it.fileId == file.id && belongsToCurrent(it) && it.status == DownloadStatus.COMPLETED }.map { it.fileName }
        val name = (inFolders + localNameOf(file)).firstOrNull { name ->
            storage.exists(name) && storage.existingLength(name) == file.sizeBytes
        } ?: return@withContext null
        // SAF 目录返回的是 content: URI，播放器认不了，维持走云端（与之前行为一致）。
        storage.pathFor(name).takeUnless { it.startsWith("content:") }
    }

    fun enqueue(file: FileStat) = enqueueFiles(listOf(file))

    /**
     * 多选文件一次入队，共用全局并发上限；重复文件复用已有任务。视频按画质上限 [maxHeight] 挑档（0 是原画，
     * null 取设置里的默认下载画质，没设过取原画），开始下载时才挑，见 [DownloadTask.qualityCap]。
     */
    fun enqueueFiles(files: List<FileStat>, maxHeight: Int? = null) {
        enqueueFiles(files, leasedSource = false, maxHeight = maxHeight)
    }

    /**
     * 单个视频按用户在下载对话框里选的那一档下载，不看上限。[quality] 是原画（name 为 null）时下原画；
     * 是转码档时下完转封装成 MP4，文件名里带上档位。
     */
    fun enqueueQuality(file: FileStat, quality: DownloadQuality) {
        enqueueFiles(listOf(file), leasedSource = false, chosen = quality)
    }

    /** 分享或解析得到的内容直接下载，临时文件对象不随解析面板关闭。 */
    fun enqueueResolved(files: List<io.github.nihildigit.pikpak.ResolvedFile>, parentId: String = "") {
        enqueueFiles(files.mapNotNull { file ->
            val hash = file.gcid?.takeIf { it.isNotBlank() }?.uppercase() ?: return@mapNotNull null
            FileStat(id = "piko-content:$hash:${file.size}", name = file.name, hash = hash,
                size = file.size.toString(), parentId = parentId, phase = io.github.nihildigit.pikpak.TaskPhase.COMPLETE)
        }, leasedSource = true)
    }

    /** [chosen] 为 null 时按上限 [maxHeight] 挑档，它也为 null 时取设置里的下载画质。 */
    private fun enqueueFiles(files: List<FileStat>, leasedSource: Boolean, chosen: DownloadQuality? = null, maxHeight: Int? = null) {
        val account = currentAccount()
        val accepted = files.filter { !it.isFolder && !it.isUploading }.distinctBy { it.id }
        if (accepted.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            enqueueLock.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                val cap = if (chosen == null && !leasedSource) maxHeight ?: defaultMaxHeight() else 0
                val batch = if (accepted.size > 1) DownloadBatch("files@$now-${++batchSequence}", "批量下载", isFolder = false) else null
                val taken = _tasks.value.values.mapTo(mutableSetOf()) { it.fileName.lowercase() }
                val reused = accepted.count { existingTask(it, account, chosen) != null }
                val added = accepted.map { file ->
                    val existing = existingTask(file, account, chosen)
                    if (existing != null) {
                        existing.copy(status = if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) DownloadStatus.PENDING else existing.status)
                    } else if (chosen?.name != null) {
                        transcodeTask(file, chosen, account, now, taken)
                    } else {
                        var name = uniqueDownloadName(localNameOf(file), taken)
                        while (storage.exists(name)) name = uniqueDownloadName(localNameOf(file), taken)
                        val bytes = storage.existingLength(name)
                        val complete = bytes == file.sizeBytes && file.sizeBytes > 0L
                        DownloadTask(
                            taskId = availableTaskId(file.id, account), fileId = file.id, fileName = name, gcid = file.hash,
                            totalBytes = file.sizeBytes, downloadedBytes = bytes.coerceAtMost(file.sizeBytes),
                            destinationPath = if (complete) storage.locate(name) ?: name else name,
                            status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
                            fullFileSize = file.sizeBytes, thumbnailLink = file.thumbnailLink, parentId = file.parentId,
                            createdAtMs = now, account = account, batch = batch, leasedSource = leasedSource,
                            qualityCap = if (complete || leasedSource) 0 else qualityCapFor(file, name, cap),
                        )
                    }
                }
                _tasks.update { current -> current + added.filter { jobs.value[it.taskId]?.isActive != true }.associateBy { it.taskId } }
                PikoLog.i(TAG, "入队 ${accepted.size} 个文件${if (leasedSource) "（解析内容，借出取流）" else ""}：" +
                    "复用已有任务 $reused 个，本机已完整 ${added.count { it.status == DownloadStatus.COMPLETED }} 个，共 ${accepted.sumOf { it.sizeBytes }} 字节" +
                    (chosen?.let { "，指定画质 ${it.name ?: ORIGINAL_QUALITY}" } ?: cap.takeIf { it > 0 }?.let { "，画质上限 ${it}P" } ?: ""))
            }
            pumpQueue()
        }
    }

    /** 指定转码档的任务。文件名带上档位，与原画的下载分开；大小没探到时开始下载前再探。 */
    private suspend fun transcodeTask(file: FileStat, quality: DownloadQuality, account: String, now: Long, taken: MutableSet<String>): DownloadTask {
        val label = quality.name ?: error("原画不是转码档")
        val wanted = transcodeFileName(localNameOf(file), label)
        var name = uniqueDownloadName(wanted, taken)
        while (storage.exists(name)) name = uniqueDownloadName(wanted, taken)
        return DownloadTask(
            taskId = availableTaskId("${file.id}_q_$label", account), fileId = file.id, fileName = name, gcid = file.hash,
            totalBytes = quality.sizeBytes ?: 0L, destinationPath = name, fullFileSize = file.sizeBytes,
            thumbnailLink = file.thumbnailLink, parentId = file.parentId, createdAtMs = now, account = account,
            quality = label, mediaId = quality.mediaId, endMs = quality.durationMs,
        )
    }

    // 只有视频挑档。归档条目经借出的对象取流（leaseDetail），转码档的下载没有接这条路，只下原画
    private fun qualityCapFor(file: FileStat, localName: String, cap: Int): Int =
        if (cap > 0 && localName.isPlayableVideo() && !LeasedFile.isLeased(file.id)) cap else 0

    /**
     * 下载几个文件夹，每个一批：在后台列出其中全部文件，落在下载目录下同名的文件夹里、保持子文件夹结构。
     * 列出期间与列完等确认时见 [listings]；列完即变成任务表里的一批任务（[DownloadTask.batch]），
     * 同一批同时只下 [BATCH_PARALLEL] 个，其余排着。返回开始列出的批数：Piko 自己的文件夹不下载，见 [isPikoFolder]。
     * 其中的视频按上限 [maxHeight] 挑档，同 [enqueueFiles]。
     */
    fun enqueueFolders(folders: List<FileStat>, source: DownloadFolderSource, maxHeight: Int? = null): Int {
        val eligible = folders.filter { it.isFolder && !isPikoFolder(it) }.distinctBy { it.id }
        val accepted = eligible
            .filter { folder ->
                _listings.value.values.none { it.account == currentAccount() && it.batch.sourceFolderId == folder.id } &&
                    _tasks.value.values.none { it.account == currentAccount() && it.batch?.sourceFolderId == folder.id &&
                        (it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING) }
            }
        val taken = (_listings.value.values.map { it.batch.folderName } +
            _tasks.value.values.mapNotNull { it.batch?.takeIf { batch -> batch.isFolder }?.folderName }).mapTo(mutableSetOf()) { it.lowercase() }
        accepted.forEach { folder ->
            val now = Clock.System.now().toEpochMilliseconds()
            val previous = _tasks.value.values.firstOrNull {
                it.account == currentAccount() && it.batch?.sourceFolderId == folder.id
            }?.batch
            val batch = previous ?: DownloadBatch(id = "${folder.id}@$now", folderName = uniqueDownloadName(FileNameSanitizer.sanitizeFolderName(folder.name), taken, false), sourceFolderId = folder.id)
            _listings.update { it + (batch.id to FolderListing(batch, createdAtMs = now, account = currentAccount())) }
            listingWork.update { it + (batch.id to ListingWork(folder, source, maxHeight)) }
            startListing(batch.id)
        }
        return eligible.size
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
        if (_listings.value[batchId]?.account != currentAccount()) return
        if (work.planned.isNotEmpty()) {
            PikoLog.i(TAG, "超出今日下载额度仍下载：文件夹 ${work.folder.id}，${work.planned.size} 个文件")
            addBatch(batchId, work.planned)
        }
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
                planFolderDownload(work.folder, object : DownloadFolderSource {
                    override suspend fun list(folderId: String) = listingSlots.withPermit { work.source.list(folderId) }
                    override suspend fun remainingDailyDownload() = work.source.remainingDailyDownload()
                }, rootName = listing.batch.folderName) { files, bytes ->
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
                PikoLog.d(TAG, "列出文件夹：${logFile(work.folder.id, work.folder.name)}，没有可下载的文件")
                updateListing(batchId) { it.copy(error = "文件夹里没有可下载的文件") }
                return@launch
            }
            // 已在本机的按长度认作完成，与单个文件的下载一样；上千个文件逐个查长度，放在 IO 线程上
            val lengths = storage.existingLengths(planned.map { it.path })
            val cap = work.maxHeight ?: defaultMaxHeight()
            val tasks = withContext(Dispatchers.IO) { planned.map { plannedTask(it, listing, lengths[it.path] ?: 0L, cap) } }
            val needed = tasks.filter { it.status != DownloadStatus.COMPLETED }.sumOf { it.totalBytes - it.downloadedBytes }
            val remaining = runSuspendCatching { work.source.remainingDailyDownload() }.getOrNull()
            PikoLog.d(TAG, "列出文件夹：${logFile(work.folder.id, work.folder.name)}，${tasks.size} 个文件，待下载 $needed 字节，今日余量 $remaining")
            if (remaining != null && needed > remaining) {
                listingWork.update { current -> current[batchId]?.let { current + (batchId to it.copy(job = null, planned = tasks)) } ?: current }
                updateListing(batchId) { it.copy(quotaExcess = QuotaExcess(needed, remaining)) }
            } else {
                addBatch(batchId, tasks)
            }
        }
        listingWork.update { current -> current[batchId]?.let { current + (batchId to it.copy(job = job, planned = emptyList())) } ?: current }
        job.start()
    }

    private suspend fun plannedTask(planned: PlannedFile, listing: FolderListing, length: Long, cap: Int): DownloadTask {
        val file = planned.file
        existingTask(file, listing.account)?.takeIf {
            (it.status != DownloadStatus.COMPLETED || (it.fileName == planned.path && length == file.sizeBytes)) }?.let {
                return it.copy(status = if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) DownloadStatus.PENDING else it.status)
            }
        val downloaded = length
        val complete = downloaded == file.sizeBytes && file.sizeBytes > 0L
        return DownloadTask(
            taskId = availableTaskId(file.id, listing.account),
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
            qualityCap = if (complete) 0 else qualityCapFor(file, planned.path, cap),
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
        PikoLog.i(TAG, "文件夹下载入队：${tasks.size} 个文件，本机已完整 ${tasks.count { it.status == DownloadStatus.COMPLETED }} 个，" +
            "共 ${tasks.sumOf { it.totalBytes }} 字节")
        pumpBatch(batchId)
    }

    /**
     * 批次操作交给全局队列，多个文件夹不会各自叠加并发。
     */
    private fun pumpBatch(batchId: String) = pumpQueue()

    /** 所有批次与单文件共用三个位置，各组轮流获得位置，取消中的任务收尾后才腾出位置。 */
    private fun pumpQueue() {
        scope.launch {
            queueLock.withLock {
                var slots = (BATCH_PARALLEL - jobs.value.size).coerceAtLeast(0)
                val pending = _tasks.value.values.filter {
                    it.status == DownloadStatus.PENDING && belongsToCurrent(it) && it.taskId !in jobs.value
                }.sortedWith(compareBy({ it.createdAtMs }, { it.fileName }))
                    .groupBy { it.batch?.id ?: "single" }.mapValues { it.value.toMutableList() }.toMutableMap()
                while (slots > 0 && pending.isNotEmpty()) {
                    val keys = pending.keys.toList()
                    val previous = keys.indexOf(lastGroup)
                    val key = keys[(previous + 1) % keys.size]
                    val next = pending.getValue(key).removeAt(0)
                    if (pending.getValue(key).isEmpty()) pending.remove(key)
                    lastGroup = key
                    var claimed = false
                    update(next.taskId) {
                        claimed = it.status == DownloadStatus.PENDING && belongsToCurrent(it)
                        if (claimed) it.copy(status = DownloadStatus.DOWNLOADING) else it
                    }
                    if (claimed) {
                        launchDownload(next.taskId)
                        slots--
                    }
                }
            }
        }
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
                tasks[id]?.takeIf { (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) && belongsToCurrent(it) }
                    ?.let { id to it.copy(status = DownloadStatus.PENDING, errorMessage = null) }
            }
        }
        pumpBatch(batchId)
    }

    /** 取消整批并删掉已下载的文件，连同留下的空文件夹。 */
    fun cancelBatch(batchId: String) {
        dismissListing(batchId)
        val tasks = _tasks.value.values.filter { it.batch?.id == batchId }
        val folder = tasks.firstOrNull()?.batch?.folderName ?: return
        PikoLog.i(TAG, "取消整批：${tasks.size} 个任务，其中已完成 ${tasks.count { it.status == DownloadStatus.COMPLETED }} 个，一并删除本机文件")
        // 先整批转为暂停，免得逐个取消时补上同一批里排着的
        pauseBatch(batchId)
        val removals = tasks.mapNotNull { cancelDownload(it.taskId) }
        scope.launch(Dispatchers.IO) {
            removals.joinAll()
            if (tasks.firstOrNull()?.batch?.isFolder == true) {
                runSuspendCatching { storage.pruneEmptyFolders(folder) }.logFailure(TAG, "取消整批后清理空文件夹失败")
            }
        }
    }

    /** 这一批的文件夹在本机的位置，还没建出来时为 null。 */
    suspend fun batchFolderPath(batch: DownloadBatch): String? = storage.locate(if (batch.isFolder) batch.folderName else "")

    private fun batchTaskIds(batchId: String): List<String> =
        _tasks.value.values.filter { it.batch?.id == batchId }.map { it.taskId }

    private fun updateListing(batchId: String, transform: (FolderListing) -> FolderListing) {
        _listings.update { listings -> listings[batchId]?.let { listings + (batchId to transform(it)) } ?: listings }
    }

    /** 同一个文件已有的整文件任务。指定了画质（[chosen]）时只认那一档的，原画不认还没挑档的任务。 */
    private fun existingTask(file: FileStat, account: String, chosen: DownloadQuality? = null): DownloadTask? = _tasks.value.values.firstOrNull {
        it.fileId == file.id && !it.isSegment && (it.account == account || it.account.isEmpty()) && it.gcid == file.hash &&
            (chosen == null || (it.quality == chosen.name && it.qualityCap == 0))
    }

    private fun availableTaskId(fileId: String, account: String): String =
        if (_tasks.value[fileId]?.let { it.account.isNotEmpty() && it.account != account } == true) "$account:$fileId" else fileId

    private fun currentAccount(): String = clientProvider.currentClient.value?.account.orEmpty()

    /** 没给上限时取设置里的默认下载画质；没设过默认的，经对话框来的都已带上上限，其余入口下原画。 */
    private suspend fun defaultMaxHeight(): Int = preferences.downloadMaxHeightFlow.first() ?: 0

    private fun belongsToCurrent(task: DownloadTask): Boolean = task.account.isEmpty() || task.account == currentAccount()

    fun startDownload(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        if (jobs.value[taskId]?.isActive == true) return
        if (!belongsToCurrent(task)) {
            update(taskId) { it.copy(status = DownloadStatus.PAUSED, errorMessage = OTHER_ACCOUNT) }
            return
        }
        update(taskId) { it.copy(status = DownloadStatus.PENDING, errorMessage = null) }
        pumpQueue()
    }

    private fun launchDownload(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        // 文件 ID 与直链只在源账号里有效，换到别的账号上取不到
        if (!belongsToCurrent(task)) {
            update(taskId) { it.copy(status = DownloadStatus.PAUSED, errorMessage = OTHER_ACCOUNT) }
            return
        }
        // 整文件下载的开始在取得缓存之后记，那时才知道续传点
        if (task.isSegment) PikoLog.d(TAG, "开始片段：${logFile(task.fileId, task.fileName)}，${task.startMs}–${task.endMs} ms")
        // 片段任务要重新抽取，不能走整文件下载：它的 totalBytes 是 0，gcid 属于整个源文件
        if (task.isSegment) startSegment(task) else launchTracked(taskId) { runDownload(task) }
    }

    /** 播放、预取与下载共用 SDK 缓存；续传只请求位图尚未持有的块。 */
    private suspend fun runDownload(queued: DownloadTask) {
        var task = queued
        val taskId = task.taskId
        val client = clientProvider.currentClient.value ?: run {
            PikoLog.w(TAG, "下载失败：未登录，${logFile(task.fileId, task.fileName)}")
            update(taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "未登录") }
            return
        }
        var lease: PikoFileCachePool.Lease? = null
        val started = TimeSource.Monotonic.markNow()
        try {
            if (task.qualityCap > 0 || task.quality != null) task = chooseQuality(task)
            val concurrency = preferences.concurrentConnectionsFlow.first().coerceIn(1, 8)
            // 转码档的 handle 要原画的大小（重建文件对象时用），暂存的大小是转码流的长度
            lease = fileCachePool.acquire(client, task.fileId, task.gcid,
                if (task.mediaId != null) task.fullFileSize else task.totalBytes, task.fileName,
                parentId = task.parentId, leased = task.leasedSource || LeasedFile.isLeased(task.fileId),
                retained = true, concurrency = concurrency, owner = taskId,
                mediaId = task.mediaId, streamSize = task.totalBytes)
            val entry = lease.entry
            update(taskId) { it.copy(account = client.account, sparseCache = true, downloadedBytes = entry.store.heldBytes.value,
                status = DownloadStatus.DOWNLOADING, errorMessage = null) }
            // 旧版本顺序下载的前缀只可能是原画；转码档的文件名指的是转封装的产物
            if (!task.sparseCache && task.mediaId == null) entry.store.importPrefix(storage.downloadTarget(task.fileName))
            val heldAtStart = entry.store.heldBytes.value
            // 文件夹下载里的小文件成百上千，逐个记会把日志刷满，它们只进整批完成的那一行；失败照样逐个记
            val logEach = task.batch == null || task.totalBytes >= BATCH_LOG_MIN_BYTES
            if (logEach) PikoLog.d(TAG, "开始：${logFile(task.fileId, task.fileName)}，已有 $heldAtStart/${task.totalBytes} 字节，$concurrency 条连接" +
                (if (task.leasedSource || LeasedFile.isLeased(task.fileId)) "，借出取流" else "") +
                (limiter.bytesPerSecond?.let { "，限速 ${it / 1024} KiB/s" } ?: ""))
            coroutineScope {
                // 分批提交范围，在占用连接之前等限速额度；播放读取不经过限速器。
                for (range in downloadOrder(task.totalBytes)) {
                    var offset = range.first
                    while (offset <= range.last) {
                        currentCoroutineContext().ensureActive()
                        val blockSize = PikPakStreamReader.DEFAULT_BLOCK_SIZE
                        val maximum = minOf(DOWNLOAD_WINDOW_BYTES, concurrency * blockSize)
                        val window = limiter.bytesPerSecond?.coerceIn(blockSize, maximum)
                            ?.let { (it / blockSize) * blockSize } ?: maximum
                        val end = minOf(range.last, offset + window - 1)
                        val missing = entry.store.missing(entry.handle.contentKey, listOf(offset..end))
                        val needed = missing.sumOf { it.last - it.first + 1 }
                        if (needed > 0) {
                            limiter.acquire(needed)
                            val download = entry.cache.download(missing, StreamRole.BACKGROUND)
                            try { download.await() } finally { download.cancel() }
                        }
                        offset = end + 1
                    }
                }
                check(entry.store.heldBytes.value == task.totalBytes) { "文件仍有未完成的块" }
                entry.store.flush()
                val target = storage.downloadTarget(task.fileName)
                if (task.mediaId == null) copyCachedFile(entry.store.path, target) else convertTranscode(task, entry.store.path, target)
                val destination = storage.commit(task.fileName, target)
                // 转码档存下的是转封装出的 MP4，长度与转码流不同；完成的任务按文件长度核对，见 restoreTask
                val savedBytes = if (task.mediaId == null) task.totalBytes else storage.existingLength(task.fileName)
                fileCachePool.complete(lease, taskId)
                val elapsed = started.elapsedNow().inWholeMilliseconds
                if (logEach) PikoLog.i(TAG, "完成：${logFile(task.fileId, task.fileName)}，${task.totalBytes} 字节，本次下载 ${task.totalBytes - heldAtStart} 字节，" +
                    "历时 $elapsed ms（${(task.totalBytes - heldAtStart) * 1000 / elapsed.coerceAtLeast(1) / 1024} KiB/s）" +
                    (task.quality?.let { "，转码档 $it 转封装为 $savedBytes 字节" } ?: ""))
                update(taskId) { it.copy(status = DownloadStatus.COMPLETED, totalBytes = savedBytes, downloadedBytes = savedBytes,
                    speedBytesPerSec = 0L, destinationPath = destination, sparseCache = false, converting = false, progressFraction = null) }
                task.batch?.let { batch ->
                    val members = _tasks.value.values.filter { it.batch?.id == batch.id }
                    if (members.all { it.status == DownloadStatus.COMPLETED }) {
                        PikoLog.i(TAG, "整批完成：${members.size} 个文件，共 ${members.sumOf { it.totalBytes }} 字节")
                    }
                }
            }
        } catch (e: CancellationException) {
            update(taskId) { it.copy(status = if (it.status == DownloadStatus.PENDING) it.status else DownloadStatus.PAUSED,
                downloadedBytes = lease?.entry?.store?.heldBytes?.value ?: it.downloadedBytes, speedBytesPerSec = 0L,
                converting = false, progressFraction = null) }
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "下载失败：${logFile(task.fileId, task.fileName)}，已有 ${lease?.entry?.store?.heldBytes?.value ?: "?"}/${task.totalBytes} 字节，" +
                "历时 ${started.elapsedNow().inWholeMilliseconds} ms${if (lease == null) "，未取得缓存" else ""}" +
                (if (_tasks.value[taskId]?.converting == true) "，失败在转封装" else ""), e)
            // 转封装失败时转码流仍完整留在暂存里，重试只重做转封装
            update(taskId) { it.copy(status = if (belongsToCurrent(it)) DownloadStatus.FAILED else DownloadStatus.PAUSED, speedBytesPerSec = 0L,
                downloadedBytes = lease?.entry?.store?.heldBytes?.value ?: it.downloadedBytes, errorMessage = e.message,
                converting = false, progressFraction = null) }
        } finally {
            lease?.release()
        }
    }

    /**
     * 定下这个任务下哪一档：按上限挑（[DownloadTask.qualityCap]，规则见 downloadQualityOrder），或补齐用户选的那一档的
     * media ID 与大小。挑到转码档时文件名换成带档位的 .mp4，挑到原画照旧。用户选的档已经没有了、或读不出字节则失败，
     * 不悄悄换成原画。转码档的任务每次开始都经这里，读不出的档在开始时就以能看懂的提示失败，而不是下到第一个块才报错。
     */
    private suspend fun chooseQuality(task: DownloadTask): DownloadTask {
        val variant = mediaRepository?.downloadVariant(task.fileId, task.quality, task.qualityCap)?.getOrThrow()
        val chosen = when {
            variant != null && variant.sizeBytes != null && variant.sizeBytes > 0 -> {
                val name = if (task.quality == null) renamedForQuality(task, variant.name ?: ORIGINAL_QUALITY) else task.fileName
                task.copy(qualityCap = 0, quality = variant.name, mediaId = variant.mediaId, totalBytes = variant.sizeBytes,
                    downloadedBytes = 0L, endMs = variant.durationMs, fileName = name, destinationPath = name)
            }
            task.quality != null -> error(TRANSCODE_GONE_MESSAGE)
            else -> task.copy(qualityCap = 0)
        }
        PikoLog.i(TAG, "定下画质：${logFile(task.fileId, chosen.fileName)}，" +
            (chosen.quality?.let { "转码档 $it，${chosen.totalBytes} 字节" } ?: "上限 ${task.qualityCap}P，下原画"))
        update(task.taskId) {
            it.copy(qualityCap = chosen.qualityCap, quality = chosen.quality, mediaId = chosen.mediaId, totalBytes = chosen.totalBytes,
                downloadedBytes = chosen.downloadedBytes, endMs = chosen.endMs, fileName = chosen.fileName, destinationPath = chosen.destinationPath)
        }
        return chosen
    }

    /**
     * 片段从哪一档截：按上限挑或补齐所选档的 media ID。按上限挑到的不改名（名字入队时就定了）；
     * 转码流的长度在截取开始打开来源时记下。用户选的档已经没有了则失败。
     */
    private suspend fun chooseSegmentQuality(task: DownloadTask): DownloadTask {
        val variant = mediaRepository?.downloadVariant(task.fileId, task.quality, task.qualityCap)?.getOrThrow()
        if (variant == null && task.quality != null) error(TRANSCODE_GONE_MESSAGE)
        PikoLog.i(TAG, "片段定下画质：${logFile(task.fileId, task.fileName)}，${variant?.name ?: "上限 ${task.qualityCap}P，截原画"}")
        update(task.taskId) { it.copy(qualityCap = 0, quality = variant?.name, mediaId = variant?.mediaId) }
        return task.copy(qualityCap = 0, quality = variant?.name, mediaId = variant?.mediaId)
    }

    /** 按上限挑到转码档的任务改名：「名字 [720P].mp4」，在原来的文件夹里，与已有的文件与任务都不重名。 */
    private suspend fun renamedForQuality(task: DownloadTask, label: String): String = enqueueLock.withLock {
        val folder = task.fileName.substringBeforeLast('/', "")
        val wanted = (if (folder.isEmpty()) "" else "$folder/") + transcodeFileName(task.fileName.substringAfterLast('/'), label)
        val taken = _tasks.value.values.filter { it.taskId != task.taskId }.mapTo(mutableSetOf()) { it.fileName.lowercase() }
        var name = uniqueDownloadName(wanted, taken)
        while (storage.exists(name)) name = uniqueDownloadName(wanted, taken)
        name
    }

    /** 把下完的转码流（MPEG-TS）在本机转封装成 MP4 写到 [target]，任务显示为「转换中」。 */
    private suspend fun convertTranscode(task: DownloadTask, source: String, target: String) {
        val converter = segmentDownloader ?: error("当前平台不支持转封装")
        update(task.taskId) { it.copy(converting = true, progressFraction = 0f, speedBytesPerSec = 0L) }
        val started = TimeSource.Monotonic.markNow()
        converter.remux(PikoRemuxRequest(source, target, durationMillis = task.endMs)) { fraction ->
            update(task.taskId) { it.copy(progressFraction = fraction) }
        }.getOrThrow()
        PikoLog.d(TAG, "转封装完成：${logFile(task.fileId, task.fileName)}，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
    }

    private fun downloadOrder(size: Long): List<LongRange> {
        val head = minOf(size, 2L * 1024 * 1024)
        val tail = maxOf(head, ((size - 512L * 1024).coerceAtLeast(0) / PikPakStreamReader.DEFAULT_BLOCK_SIZE) * PikPakStreamReader.DEFAULT_BLOCK_SIZE)
        return listOf(0L until head, tail until size, head until tail).filterNot { it.isEmpty() }
    }

    fun enqueueSegment(
        file: FileStat,
        startMillis: Long,
        endMillis: Long,
        timeRangeLabel: String,
        sourceUrl: String,
        /** 从哪一档截，原画的 name 为 null；为 null 时按设置里的下载画质上限，开始截取时再挑。 */
        quality: DownloadQuality? = null,
    ) {
        val account = currentAccount()
        scope.launch {
            val cap = if (quality == null) defaultMaxHeight() else 0
            // 选定了转码档时名字里带上档位；按上限挑的开始时才知道，名字不改
            val label = quality?.name?.let { "_[$it]" }.orEmpty()
            val name = FileNameSanitizer.sanitize(
                "${file.name.substringBeforeLast('.', file.name)}_[$timeRangeLabel]$label.mp4",
                fallbackExtension = "mp4",
                forceExtension = "mp4",
            )
            val taskId = availableTaskId("${file.id}_seg_${startMillis}_$endMillis${quality?.name?.let { "_$it" }.orEmpty()}", account)
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
                account = account,
                quality = quality?.name,
                mediaId = quality?.mediaId,
                qualityCap = if (LeasedFile.isLeased(file.id)) 0 else cap,
            )
            _tasks.update { it + (taskId to task) }
            pumpQueue()
        }
    }

    fun enqueueSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
        quality: DownloadQuality? = null,
    ) = enqueueSegment(file, startMs, endMs, timeRangeLabel, streamUrl.orEmpty(), quality)

    private fun startSegment(task: DownloadTask) {
        val extractor = segmentDownloader ?: run {
            PikoLog.w(TAG, "片段抽取失败：本平台没有抽取器")
            update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = "当前平台不支持分段抽取") }
            return
        }
        launchTracked(task.taskId) {
            // 源经 SDK 的 handle 按偏移读（openRandomAccess），不用入队时存下的直链：直链绕过 SDK 的账号连接预算，
            // 与代理、预览播放器抢连接，超出上限后 CDN 一律回 503（2026-09-23 实测）；存下的直链还会过期。
            // 读过的块记在这个任务名下留在下载暂存里（sparseCache），暂停或失败后再来不必重下，完成或取消时放手
            val repo = mediaRepository
            update(task.taskId) { it.copy(status = DownloadStatus.DOWNLOADING, progressFraction = 0f, errorMessage = null) }
            val chosen = try {
                // 选定了转码档的也要经这里：读不出字节的档在开始时就以能看懂的提示失败
                if (task.qualityCap > 0 || task.quality != null) chooseSegmentQuality(task) else task
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "片段定画质失败：${logFile(task.fileId, task.fileName)}", e)
                update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = e.message) }
                return@launchTracked
            }
            update(task.taskId) { it.copy(sparseCache = it.sparseCache || repo != null) }
            // 转码档的暂存按转码流的长度认，打开时才知道，记进 fullFileSize，放手与重启后登记都按它
            var cacheSize = chosen.fullFileSize
            val started = TimeSource.Monotonic.markNow()
            extractor.extract(
                PikoSegmentRequest(
                    sourceUrl = task.streamUrl.orEmpty(),
                    destinationPath = task.destinationPath,
                    fileName = task.fileName,
                    startMillis = task.startMs,
                    endMillis = task.endMs,
                    openRandomAccess = repo?.let {
                        {
                            it.openRandomAccess(task.fileId, retainedBy = task.taskId, mediaId = chosen.mediaId).getOrThrow().also { source ->
                                if (chosen.mediaId != null && source.size != cacheSize) {
                                    cacheSize = source.size
                                    update(task.taskId) { current -> current.copy(fullFileSize = source.size) }
                                }
                            }
                        }
                    },
                ),
            ) { fraction ->
                update(task.taskId) { it.copy(progressFraction = fraction) }
            }.onSuccess { path ->
                // 片段入队时不知道产物大小，totalBytes 一直是 0，列表会显示 0 B，完成后按实际文件补上
                val size = runSuspendCatching { storage.existingLength(task.fileName) }.getOrDefault(0L)
                PikoLog.i(TAG, "片段抽取完成：${logFile(task.fileId, task.fileName)}，${task.startMs}–${task.endMs} ms，" +
                    "${chosen.quality ?: ORIGINAL_QUALITY}，$size 字节，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
                if (repo != null) fileCachePool.discard(task.account, task.gcid, cacheSize, task.taskId, chosen.mediaId)
                update(task.taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        destinationPath = path,
                        progressFraction = 1f,
                        totalBytes = size,
                        downloadedBytes = size,
                        sparseCache = false,
                    )
                }
            }.onFailure { error ->
                PikoLog.w(TAG, "片段抽取失败：${logFile(task.fileId, task.fileName)}，${task.startMs}–${task.endMs} ms", error)
                update(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) }
            }
        }
    }

    fun pauseDownload(taskId: String) {
        // 任务 ID 在多账号时带着账号名，日志里只写文件 ID
        _tasks.value[taskId]?.let { PikoLog.d(TAG, "暂停：${logFile(it.fileId, it.fileName)}，${it.downloadedBytes}/${it.totalBytes} 字节") }
        jobs.value[taskId]?.cancel()
        update(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
    }

    /** 把所有进行中的任务转为暂停。前台服务被系统叫停时用，之后可以逐个继续。 */
    fun pauseAll() {
        val ids = _tasks.value.values.filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }.map { it.taskId }
        if (ids.isNotEmpty()) PikoLog.i(TAG, "全部暂停：${ids.size} 个任务")
        _tasks.update { tasks -> tasks + ids.mapNotNull { id -> tasks[id]?.let { id to it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) } } }
        ids.forEach { jobs.value[it]?.cancel() }
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
            if (task.sparseCache) fileCachePool.discard(task.account, task.gcid, task.cacheSize, task.taskId, task.cacheMediaId)
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
                if (_tasks.value[taskId]?.status != DownloadStatus.DOWNLOADING) return@launch
                block()
            } finally {
                // 只摘自己：暂停后立刻继续时，表里已经是新协程，旧协程的收尾不能把它摘掉
                jobs.update { current -> if (current[taskId] === self) current - taskId else current }
                // 腾出一个位置，同一批里排着的补上
                pumpQueue()
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
        const val BATCH_LOG_MIN_BYTES = 64L * 1024 * 1024
        private const val DOWNLOAD_WINDOW_BYTES = 4L * 1024 * 1024
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

/**
 * 片段读的是它那一档的整条流，占的是那一档的暂存，长度记在 fullFileSize（转码档开始截取时换成转码流的长度）；
 * 整文件任务的是它自己下的那一档，长度即 totalBytes。
 */
private val DownloadTask.cacheSize: Long get() = if (isSegment) fullFileSize else totalBytes

private val DownloadTask.cacheMediaId: String? get() = mediaId

private val DownloadTask.cacheIdentity: String get() = PikoFileCachePool.identity(account, gcid, cacheSize, cacheMediaId)

/** 转码档存下来的名字：「名字 [720P].mp4」。[name] 是不带文件夹的文件名。 */
internal fun transcodeFileName(name: String, quality: String): String = FileNameSanitizer.sanitize(
    "${name.substringBeforeLast('.', name)} [$quality].mp4",
    fallbackExtension = "mp4",
    forceExtension = "mp4",
)

private fun uniqueDownloadName(name: String, taken: MutableSet<String>, extension: Boolean = true): String {
    if (taken.add(name.lowercase())) return name
    val base = if (extension) name.substringBeforeLast('.', name) else name
    val suffix = name.removePrefix(base)
    var number = 2
    while (true) {
        val candidate = "$base ($number)$suffix"
        if (taken.add(candidate.lowercase())) return candidate
        number++
    }
}
