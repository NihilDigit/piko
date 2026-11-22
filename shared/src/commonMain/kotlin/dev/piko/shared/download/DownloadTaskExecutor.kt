@file:JvmMultifileClass
@file:JvmName("PikoDownloadCoordinatorKt")

package dev.piko.shared.download

import dev.piko.data.auth.DownloadConcurrencyDefaults
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logFolder
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.TRANSCODE_GONE_MESSAGE
import dev.piko.shared.media.cache.PersistentFileStore
import dev.piko.shared.media.cache.PikoFileCachePool
import io.github.nihildigit.pikpak.BandwidthLimiter
import io.github.nihildigit.pikpak.StreamRole
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DownloadTaskExecutor(
    private val clientProvider: PikoClientProvider,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    private val segmentDownloader: PikoSegmentDownloader?,
    private val mediaRepository: PikoMediaRepository?,
    private val fileCachePool: PikoFileCachePool,
    private val store: DownloadQueueStore,
) {
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val limiter = BandwidthLimiter()
    private val queueLock = Mutex()
    private val partialPlaybackEpoch = MutableStateFlow(0L)
    private var lastGroup: String? = null
    @kotlin.concurrent.Volatile
    private var maxParallel: Int = DownloadConcurrencyDefaults.sanitize(preferences.currentSnapshot.maxConcurrentDownloads)

    fun isJobActive(taskId: String): Boolean = jobs.value[taskId]?.isActive == true

    fun notifyPartialPlaybackLookup(fileId: String): DownloadTask? =
        store.tasks.value.values.firstOrNull {
            it.fileId == fileId && store.belongsToCurrent(it) &&
                !it.isSegment && it.mediaId == null && it.status != DownloadStatus.COMPLETED && it.sparseCache
        }?.also { partialPlaybackEpoch.update { epoch -> epoch + 1L } }

    fun startBackgroundLoops() {
        launchProgressAndSpeedSampler()
        scope.launch {
            combine(preferences.snailModeFlow, clientProvider.currentClient) { mode, client -> mode to client }
                .collect { (mode, client) ->
                    limiter.bytesPerSecond = if (mode.enabled) mode.downloadKiBps * 1024L else null
                    client?.downloadLimiter?.bytesPerSecond = limiter.bytesPerSecond
                }
        }
        scope.launch {
            preferences.maxConcurrentDownloadsFlow
                .map(DownloadConcurrencyDefaults::sanitize)
                .distinctUntilChanged()
                .collect { limit ->
                    maxParallel = limit
                    rebalanceConcurrency(limit)
                }
        }
        scope.launch {
            try {
                store.restore()
            } finally {
                store.completeRestore()
            }
            store.resumeListings(::isJobActive, ::pumpQueue)
            runSuspendCatching { fileCachePool.prune() }
                .logFailure(DOWNLOAD_LOG_TAG, "Failed to prune orphan download caches on startup")
            store.persistOnStructuralChange()
        }
        scope.launch {
            clientProvider.currentClient.map { it?.account }.distinctUntilChanged().collect {
                store.awaitRestoreCompleted()
                val currentTasks = store.tasks.value.values
                val runningTasks = currentTasks.count { task -> task.taskId in jobs.value && !store.belongsToCurrent(task) }
                val foreignBatches = currentTasks
                    .filter { (it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING) && !store.belongsToCurrent(it) }
                    .mapNotNull { it.batch?.id }
                    .distinct()
                val deferredSinglePending = currentTasks.count {
                    it.status == DownloadStatus.PENDING && it.batch == null && !store.belongsToCurrent(it)
                }
                val pausedListings = store.pauseForeignListings(listingLeftAccountMessage)
                if (runningTasks > 0 || foreignBatches.isNotEmpty() || pausedListings > 0 || deferredSinglePending > 0) {
                    PikoLog.i(
                        DOWNLOAD_LOG_TAG,
                        "Account switched: pausing running=$runningTasks, batches=${foreignBatches.size}, listings=$pausedListings, deferredSinglePending=$deferredSinglePending",
                    )
                }
                foreignBatches.forEach { pauseBatchInternal(it, log = false) }
                store.tasks.value.values.filter { task -> task.taskId in jobs.value && !store.belongsToCurrent(task) }
                    .forEach { task -> pauseDownloadInternal(task.taskId, log = false) }
                store.resumeListings(::isJobActive, ::pumpQueue)
                resumeInterruptedTasks()
            }
        }
    }

    private fun launchProgressAndSpeedSampler() {
        scope.launch {
            val samples = mutableMapOf<String, ArrayDeque<Pair<TimeMark, Long>>>()
            var observedPlaybackEpoch = partialPlaybackEpoch.value
            while (true) {
                combine(store.tasks, partialPlaybackEpoch) { tasks, epoch ->
                    hasActiveWholeDownload(tasks) || epoch != observedPlaybackEpoch
                }.first { it }

                samples.clear()
                var previousTick = TimeSource.Monotonic.markNow()
                var logMark = previousTick
                for ((path, snapshot) in fileCachePool.progressSnapshot()) {
                    samples[path] = ArrayDeque<Pair<TimeMark, Long>>().apply {
                        addLast(previousTick to snapshot.deliveredBytes)
                    }
                }
                var playbackGraceTicks = if (partialPlaybackEpoch.value != observedPlaybackEpoch) {
                    observedPlaybackEpoch = partialPlaybackEpoch.value
                    PLAYBACK_WAKE_GRACE_TICKS
                } else {
                    0
                }

                while (true) {
                    delay(PROGRESS_INTERVAL_MS)
                    val latestEpoch = partialPlaybackEpoch.value
                    if (latestEpoch != observedPlaybackEpoch) {
                        observedPlaybackEpoch = latestEpoch
                        playbackGraceTicks = PLAYBACK_WAKE_GRACE_TICKS
                    } else if (playbackGraceTicks > 0) {
                        playbackGraceTicks--
                    }

                    val snapshots = fileCachePool.progressSnapshot()
                    val now = TimeSource.Monotonic.markNow()
                    val speeds = if (snapshots.isEmpty()) {
                        emptyMap()
                    } else {
                        snapshots.mapValues { (path, snapshot) ->
                            val window = samples.getOrPut(path) {
                                ArrayDeque<Pair<TimeMark, Long>>().apply { addLast(previousTick to 0L) }
                            }
                            window.addLast(now to snapshot.deliveredBytes)
                            while (window.size > 2 && window.first().first.elapsedNow().inWholeMilliseconds > SPEED_WINDOW_MS) {
                                window.removeFirst()
                            }
                            val (mark, bytes) = window.first()
                            (snapshot.deliveredBytes - bytes).coerceAtLeast(0) * 1000 / mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                        }
                    }
                    samples.keys.retainAll(snapshots.keys)
                    previousTick = now

                    if (snapshots.isNotEmpty()) {
                        store.updateTasks { tasks ->
                            val primary = HashMap<String, String>()
                            for (task in tasks.values) {
                                if (task.status == DownloadStatus.DOWNLOADING && task.sparseCache && !task.isSegment) {
                                    val id = task.cacheIdentity
                                    val existing = primary[id]
                                    if (existing == null || task.taskId < existing) {
                                        primary[id] = task.taskId
                                    }
                                }
                            }
                            var changed: MutableList<Pair<String, DownloadTask>>? = null
                            for (task in tasks.values) {
                                if (!task.sparseCache || task.isSegment || task.status == DownloadStatus.COMPLETED) continue
                                val identity = task.cacheIdentity
                                val snapshot = snapshots[identity] ?: continue
                                val speed = if (primary[identity] == task.taskId) speeds[identity] ?: 0L else 0L
                                if (snapshot.heldBytes != task.downloadedBytes || speed != task.speedBytesPerSec) {
                                    if (changed == null) changed = ArrayList()
                                    changed.add(task.taskId to task.copy(downloadedBytes = snapshot.heldBytes, speedBytesPerSec = speed))
                                }
                            }
                            if (changed == null) tasks else tasks + changed
                        }
                    }

                    if (logMark.elapsedNow().inWholeMilliseconds >= LOG_INTERVAL_MS) {
                        val activeIdentities = store.tasks.value.values
                            .filter { it.status == DownloadStatus.DOWNLOADING && it.sparseCache && !it.isSegment }
                            .mapTo(HashSet()) { it.cacheIdentity }
                        if (activeIdentities.isNotEmpty()) {
                            val activeSpeed = activeIdentities.sumOf { speeds[it] ?: 0L }
                            PikoLog.d(
                                DOWNLOAD_LOG_TAG,
                                "Active downloads: speed=$activeSpeed B/s, activeCaches=${activeIdentities.size}, poolEntries=${snapshots.size}",
                            )
                        }
                        logMark = TimeSource.Monotonic.markNow()
                    }

                    val currentTasks = store.tasks.value
                    if (
                        !hasActiveWholeDownload(currentTasks) &&
                        !hasOpenTrackedCache(currentTasks, snapshots) &&
                        playbackGraceTicks == 0 &&
                        partialPlaybackEpoch.value == observedPlaybackEpoch
                    ) {
                        samples.clear()
                        break
                    }
                }
            }
        }
    }

    private fun hasActiveWholeDownload(tasks: Map<String, DownloadTask>): Boolean =
        tasks.values.any { it.status == DownloadStatus.DOWNLOADING && !it.isSegment }

    private fun hasOpenTrackedCache(
        tasks: Map<String, DownloadTask>,
        snapshots: Map<String, PikoFileCachePool.Snapshot>,
    ): Boolean = snapshots.isNotEmpty() && tasks.values.any {
        it.sparseCache && !it.isSegment && it.status != DownloadStatus.COMPLETED && it.cacheIdentity in snapshots
    }

    private fun resumeInterruptedTasks() {
        val client = clientProvider.currentClient.value ?: return
        client.downloadLimiter.bytesPerSecond = limiter.bytesPerSecond
        val resumedCount = store.resumeInterruptedForAccount(client.account)
        if (resumedCount > 0) {
            PikoLog.i(DOWNLOAD_LOG_TAG, "Resumed $resumedCount interrupted download tasks for active account")
        }
        pumpQueue()
    }

    /** 所有批次与单文件共用并发上限 [maxParallel]，各组轮流获得位置，取消中的任务收尾后才腾出位置。 */
    fun pumpQueue() {
        scope.launch {
            queueLock.withLock {
                var slots = (maxParallel - jobs.value.size).coerceAtLeast(0)
                val pending = store.tasks.value.values.filter {
                    it.status == DownloadStatus.PENDING && store.belongsToCurrent(it) && it.taskId !in jobs.value
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
                    store.updateTask(next.taskId) {
                        claimed = it.status == DownloadStatus.PENDING && store.belongsToCurrent(it)
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

    private suspend fun rebalanceConcurrency(limit: Int) {
        val toRequeue = queueLock.withLock {
            val activeIds = jobs.value.keys
            val excess = activeIds.size - limit
            if (excess <= 0) return@withLock emptyList()
            val candidates = store.tasks.value.values
                .filter { it.taskId in activeIds && it.status == DownloadStatus.DOWNLOADING && !it.converting && !it.isSegment }
                .sortedByDescending { it.createdAtMs }
                .take(excess)
                .map { it.taskId }
            if (candidates.isNotEmpty()) {
                store.updateTasks { tasks ->
                    tasks + candidates.mapNotNull { id ->
                        tasks[id]?.takeIf { it.status == DownloadStatus.DOWNLOADING }?.let {
                            id to it.copy(status = DownloadStatus.PENDING, speedBytesPerSec = 0L)
                        }
                    }
                }
            }
            candidates
        }
        if (toRequeue.isNotEmpty()) {
            toRequeue.forEach { jobs.value[it]?.cancel() }
        } else {
            pumpQueue()
        }
    }

    fun startDownload(taskId: String) {
        val task = store.tasks.value[taskId] ?: return
        if (jobs.value[taskId]?.isActive == true) return
        if (!store.belongsToCurrent(task)) {
            store.updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, errorMessage = null) }
            return
        }
        store.updateTask(taskId) { it.copy(status = DownloadStatus.PENDING, errorMessage = null) }
        pumpQueue()
    }

    fun pauseBatchInternal(batchId: String, log: Boolean) {
        val ids = store.batchTaskIds(batchId)
        val batch = ids.firstNotNullOfOrNull { store.tasks.value[it]?.batch }
        var pausedCount = 0
        store.clearInterruptedTasks(ids.toSet())
        store.updateTasks { tasks ->
            pausedCount = 0
            tasks + ids.mapNotNull { id ->
                tasks[id]?.takeIf { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
                    ?.let {
                        pausedCount++
                        id to it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    }
            }
        }
        ids.forEach { id -> jobs.value[id]?.cancel() }
        if (log && pausedCount > 0) {
            PikoLog.i(DOWNLOAD_LOG_TAG, "Paused download batch: batch=$batchId, ${logFolder(batch?.sourceFolderId)}, paused=$pausedCount")
        }
    }

    fun resumeBatch(batchId: String) {
        val ids = store.batchTaskIds(batchId)
        val batch = ids.firstNotNullOfOrNull { store.tasks.value[it]?.batch }
        var resumedCount = 0
        store.updateTasks { tasks ->
            resumedCount = 0
            tasks + ids.mapNotNull { id ->
                tasks[id]?.takeIf { (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) && store.belongsToCurrent(it) }
                    ?.let {
                        resumedCount++
                        id to it.copy(status = DownloadStatus.PENDING, errorMessage = null)
                    }
            }
        }
        if (resumedCount > 0) {
            PikoLog.i(DOWNLOAD_LOG_TAG, "Resumed download batch: batch=$batchId, ${logFolder(batch?.sourceFolderId)}, resumed=$resumedCount")
        }
        pumpQueue()
    }

    fun cancelBatch(batchId: String) {
        store.dismissListing(batchId)
        val tasks = store.tasks.value.values.filter { it.batch?.id == batchId }
        val batch = tasks.firstOrNull()?.batch ?: return
        val folder = batch.folderName
        PikoLog.i(
            DOWNLOAD_LOG_TAG,
            "Cancelling download batch: batch=$batchId, ${logFolder(batch.sourceFolderId)}, " +
                "tasks=${tasks.size}, completed=${tasks.count { it.status == DownloadStatus.COMPLETED }}, deleteLocal=true",
        )
        pauseBatchInternal(batchId, log = false)
        val removals = tasks.mapNotNull { cancelDownloadInternal(it.taskId, log = false) }
        scope.launch(Dispatchers.IO) {
            removals.joinAll()
            if (batch.isFolder) {
                runSuspendCatching { store.storageFor(batch).pruneEmptyFolders(folder) }
                    .logFailure(DOWNLOAD_LOG_TAG, "Failed to prune empty folders after cancelling batch=$batchId (${logFolder(batch.sourceFolderId)})")
            }
        }
    }

    fun pauseDownloadInternal(taskId: String, log: Boolean) {
        store.clearInterruptedTask(taskId)
        val current = store.tasks.value[taskId]
        if (log && current != null && (current.status == DownloadStatus.PENDING || current.status == DownloadStatus.DOWNLOADING)) {
            val progressDetail = when {
                current.isSegment -> "range=${current.startMs}..${current.endMs}ms, progress=${((current.progressFraction ?: 0f) * 100).toInt()}%"
                current.converting -> "stage=remux, progress=${((current.progressFraction ?: 0f) * 100).toInt()}%"
                else -> "bytes=${current.downloadedBytes}/${current.totalBytes}"
            }
            PikoLog.d(DOWNLOAD_LOG_TAG, "Paused download: task=${logDownloadTask(taskId)}, ${logFile(current.fileId, current.fileName)}, $progressDetail")
        }
        jobs.value[taskId]?.cancel()
        store.updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
    }

    fun pauseAll() {
        store.clearAllInterruptedTasks()
        val ids = store.tasks.value.values
            .filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
            .map { it.taskId }
        if (ids.isNotEmpty()) PikoLog.i(DOWNLOAD_LOG_TAG, "Paused all active downloads: count=${ids.size}")
        store.updateTasks { tasks ->
            tasks + ids.mapNotNull { id -> tasks[id]?.let { id to it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) } }
        }
        ids.forEach { jobs.value[it]?.cancel() }
    }

    fun cancelDownloadInternal(taskId: String, log: Boolean): Job? {
        store.clearInterruptedTask(taskId)
        val job = jobs.value[taskId]
        val task = store.removeTask(taskId)
        if (task == null) {
            job?.cancel()
            return null
        }
        if (log) {
            PikoLog.d(DOWNLOAD_LOG_TAG, "Cancelled download: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, status=${task.status}")
        }
        return scope.launch(Dispatchers.IO) {
            suspend fun discardCache() {
                runSuspendCatching { fileCachePool.discard(task.account, task.gcid, task.cacheSize, task.taskId, task.cacheMediaId) }
                    .logFailure(DOWNLOAD_LOG_TAG, "Failed to discard sparse cache for cancelled task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}")
            }
            if (task.sparseCache) {
                discardCache()
            }
            runSuspendCatching { job?.cancelAndJoin() }
                .logFailure(DOWNLOAD_LOG_TAG, "Error while awaiting cancelled download job: task=${logDownloadTask(taskId)}")
            runSuspendCatching { fileCachePool.discardByOwner(taskId) }
                .logFailure(DOWNLOAD_LOG_TAG, "Failed to release owner cache pins for cancelled task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}")
            if (task.sparseCache || !task.isSegment) {
                discardCache()
            }
            if (task.status == DownloadStatus.COMPLETED && task.destinationPath.isNotBlank()) {
                runSuspendCatching {
                    store.storageFor(task).delete(task.destinationPath)
                }.logFailure(DOWNLOAD_LOG_TAG, "Failed to delete local target for cancelled task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}")
            }
        }
    }

    private fun launchDownload(taskId: String) {
        val task = store.tasks.value[taskId] ?: return
        if (!store.belongsToCurrent(task)) {
            store.updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, errorMessage = null) }
            return
        }
        if (task.isSegment) {
            PikoLog.d(DOWNLOAD_LOG_TAG, "Starting segment extraction: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, range=${task.startMs}..${task.endMs}ms")
        }
        if (task.isSegment) startSegment(task) else launchTracked(taskId) { runDownload(task) }
    }

    private suspend fun runDownload(queued: DownloadTask) {
        var task = queued
        val taskId = task.taskId
        val client = clientProvider.currentClient.value ?: run {
            PikoLog.w(DOWNLOAD_LOG_TAG, "Download failed: not signed in (task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)})")
            store.updateTask(taskId) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
            }
            return
        }
        var lease: PikoFileCachePool.Lease? = null
        val started = TimeSource.Monotonic.markNow()
        try {
            if (task.qualityCap > 0 || task.quality != null) task = chooseQuality(task)
            val isLeased = task.leasedSource || LeasedFile.isLeased(task.fileId)
            val leaseParentId = if (isLeased) {
                runSuspendCatching { mediaRepository?.leaseFolder?.invoke(client) }
                    .logFailure(DOWNLOAD_LOG_TAG, "Failed to resolve lease folder for task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}")
                    .getOrNull()
                    ?: task.parentId.takeIf { isDriveFolderId(it) }.orEmpty()
            } else {
                task.parentId
            }
            lease = fileCachePool.acquire(
                client = client,
                fileId = task.fileId,
                gcid = task.gcid,
                size = if (task.mediaId != null) task.fullFileSize else task.totalBytes,
                name = task.fileName,
                parentId = leaseParentId,
                leased = isLeased,
                retained = true,
                owner = taskId,
                mediaId = task.mediaId,
                streamSize = task.totalBytes,
            )
            val entry = lease.entry
            val importLegacyPrefix = !task.sparseCache && task.mediaId == null && task.downloadedBytes > 0L
            task = task.copy(
                account = client.account,
                sparseCache = true,
                downloadedBytes = entry.store.heldBytes.value,
                status = DownloadStatus.DOWNLOADING,
                errorMessage = null,
            )
            store.updateTask(taskId) {
                it.copy(
                    account = client.account,
                    sparseCache = true,
                    downloadedBytes = entry.store.heldBytes.value,
                    status = DownloadStatus.DOWNLOADING,
                    errorMessage = null,
                )
            }
            val taskStorage = store.storageFor(task)
            if (importLegacyPrefix) {
                val legacyPath = taskStorage.locate(task.fileName)?.takeUnless { it.startsWith("content:") }
                    ?: if (taskStorage.existingLength(task.fileName) > 0L) taskStorage.downloadTarget(task.fileName) else null
                if (legacyPath != null) entry.store.importPrefix(legacyPath)
            }
            val heldAtStart = entry.store.heldBytes.value
            val logEach = task.batch == null || task.totalBytes >= BATCH_LOG_MIN_BYTES
            if (logEach) {
                PikoLog.d(
                    DOWNLOAD_LOG_TAG,
                    "Download started: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, held=$heldAtStart/${task.totalBytes} B" +
                        (if (isLeased) ", leased=true" else "") +
                        (limiter.bytesPerSecond?.let { ", limit=${it / 1024} KiB/s" } ?: ""),
                )
            }
            coroutineScope {
                val networkStarted = TimeSource.Monotonic.markNow()
                val missingAll = entry.store.missing(entry.handle.contentKey, downloadOrder(task.totalBytes))
                val download = entry.cache.download(missingAll, StreamRole.BACKGROUND)
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                    if (it != null) download.cancel()
                }
                try {
                    download.await()
                } finally {
                    cancelHandle?.dispose()
                    download.cancel()
                }
                val networkMs = networkStarted.elapsedNow().inWholeMilliseconds
                check(entry.store.heldBytes.value == task.totalBytes) {
                    "Sparse cache still has missing blocks (${entry.store.heldBytes.value}/${task.totalBytes} B)"
                }
                val exportStarted = TimeSource.Monotonic.markNow()
                entry.store.flush()
                val target = taskStorage.downloadTarget(task.fileName)
                if (task.mediaId == null) entry.store.exportTo(target) else convertTranscode(task, entry.store, target)
                val destination = taskStorage.commit(task.fileName, target)
                val exportMs = exportStarted.elapsedNow().inWholeMilliseconds
                val savedBytes = if (task.mediaId == null) task.totalBytes else taskStorage.existingLength(task.fileName)
                fileCachePool.complete(lease, taskId)
                val totalMs = started.elapsedNow().inWholeMilliseconds
                val fetchedBytes = (task.totalBytes - heldAtStart).coerceAtLeast(0L)
                val netRateSummary = if (fetchedBytes > 0L) "${fetchedBytes * 1000L / networkMs.coerceAtLeast(1L) / 1024L} KiB/s" else "cached"
                if (logEach) {
                    PikoLog.i(
                        DOWNLOAD_LOG_TAG,
                        "Download completed: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                            "streamBytes=${task.totalBytes}, fetchedBytes=$fetchedBytes, networkMs=$networkMs ($netRateSummary), " +
                            "exportMs=$exportMs, totalMs=$totalMs" +
                            (task.quality?.let { ", remuxedQuality=$it, savedBytes=$savedBytes" } ?: ""),
                    )
                }
                store.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        totalBytes = savedBytes,
                        downloadedBytes = savedBytes,
                        speedBytesPerSec = 0L,
                        destinationPath = destination,
                        sparseCache = false,
                        converting = false,
                        progressFraction = null,
                    )
                }
                task.batch?.let { batch ->
                    val members = store.tasks.value.values.filter { it.batch?.id == batch.id }
                    if (members.all { it.status == DownloadStatus.COMPLETED }) {
                        PikoLog.i(
                            DOWNLOAD_LOG_TAG,
                            "Download batch completed: batch=${batch.id}, ${logFolder(batch.sourceFolderId)}, " +
                                "files=${members.size}, totalBytes=${members.sumOf { it.totalBytes }}",
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            store.updateTask(taskId) {
                it.copy(
                    status = if (it.status == DownloadStatus.PENDING) it.status else DownloadStatus.PAUSED,
                    downloadedBytes = lease?.entry?.store?.heldBytes?.value ?: it.downloadedBytes,
                    speedBytesPerSec = 0L,
                    converting = false,
                    progressFraction = null,
                )
            }
            throw e
        } catch (e: Throwable) {
            val stillCurrent = store.tasks.value[taskId]?.let(store::belongsToCurrent) ?: store.belongsToCurrent(task)
            if (!stillCurrent) {
                PikoLog.i(
                    DOWNLOAD_LOG_TAG,
                    "Download paused after account switch: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                        "held=${lease?.entry?.store?.heldBytes?.value ?: task.downloadedBytes}/${task.totalBytes} B (${e::class.simpleName})",
                )
                store.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.PAUSED,
                        speedBytesPerSec = 0L,
                        downloadedBytes = lease?.entry?.store?.heldBytes?.value ?: it.downloadedBytes,
                        errorMessage = null,
                        converting = false,
                        progressFraction = null,
                    )
                }
            } else {
                val converting = store.tasks.value[taskId]?.converting == true
                PikoLog.w(
                    DOWNLOAD_LOG_TAG,
                    "Download failed: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                        "held=${lease?.entry?.store?.heldBytes?.value ?: "?"}/${task.totalBytes} B, " +
                        "elapsedMs=${started.elapsedNow().inWholeMilliseconds}" +
                        (if (lease == null) ", stage=before_cache_lease" else if (converting) ", stage=remux" else ", stage=transfer"),
                    e,
                )
                store.updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        speedBytesPerSec = 0L,
                        downloadedBytes = lease?.entry?.store?.heldBytes?.value ?: it.downloadedBytes,
                        errorMessage = e.message,
                        converting = false,
                        progressFraction = null,
                    )
                }
            }
        } finally {
            val currentLease = lease
            if (currentLease != null) {
                val shouldPersistProgress = store.tasks.value[taskId] != null && currentLease.entry.retained
                currentLease.release()
                if (shouldPersistProgress && currentLease.entry.retained) {
                    withContext(NonCancellable) {
                        runCatching { currentLease.entry.store.flush() }
                            .logFailure(DOWNLOAD_LOG_TAG, "Failed to flush sparse cache bitmap on exit: task=${logDownloadTask(taskId)}, ${logFile(task.fileId, task.fileName)}")
                    }
                }
            }
        }
    }

    private suspend fun chooseQuality(task: DownloadTask): DownloadTask {
        val repo = mediaRepository
        if (repo == null) {
            if (task.quality != null && (task.mediaId == null || task.totalBytes <= 0L)) error(TRANSCODE_GONE_MESSAGE)
            val fallback = task.copy(qualityCap = 0)
            if (task.qualityCap != 0) store.updateTask(task.taskId) { it.copy(qualityCap = 0) }
            PikoLog.d(DOWNLOAD_LOG_TAG, "Media repository unavailable; using existing target for task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}")
            return fallback
        }
        val variant = repo.downloadVariant(task.fileId, task.quality, task.qualityCap).getOrThrow()
        var reVerifiedSameTarget = false
        val chosen = when {
            variant != null && variant.sizeBytes != null && variant.sizeBytes > 0 -> {
                reVerifiedSameTarget = task.quality != null && task.mediaId == variant.mediaId && task.totalBytes == variant.sizeBytes
                val name = if (task.quality == null) store.renamedForQuality(task, variant.name ?: ORIGINAL_QUALITY) else task.fileName
                task.copy(
                    qualityCap = 0,
                    quality = variant.name,
                    mediaId = variant.mediaId,
                    totalBytes = variant.sizeBytes,
                    downloadedBytes = if (reVerifiedSameTarget) task.downloadedBytes else 0L,
                    endMs = variant.durationMs,
                    fileName = name,
                    destinationPath = name,
                )
            }
            task.quality != null -> error(TRANSCODE_GONE_MESSAGE)
            else -> task.copy(qualityCap = 0)
        }
        val detail = "task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, chosen.fileName)}, " +
            "quality=${chosen.quality ?: "original"}${if (task.qualityCap > 0) " (cap=${task.qualityCap}p)" else ""}, streamBytes=${chosen.totalBytes}"
        if (reVerifiedSameTarget) {
            PikoLog.d(DOWNLOAD_LOG_TAG, "Verified transcode target: $detail")
        } else {
            PikoLog.i(DOWNLOAD_LOG_TAG, "Selected download quality: $detail")
        }
        store.updateTask(task.taskId) {
            it.copy(
                qualityCap = chosen.qualityCap,
                quality = chosen.quality,
                mediaId = chosen.mediaId,
                totalBytes = chosen.totalBytes,
                downloadedBytes = chosen.downloadedBytes,
                endMs = chosen.endMs,
                fileName = chosen.fileName,
                destinationPath = chosen.destinationPath,
            )
        }
        return chosen
    }

    private suspend fun chooseSegmentQuality(task: DownloadTask): DownloadTask {
        val repo = mediaRepository
        if (repo == null) {
            if (task.quality != null && task.mediaId == null) error(TRANSCODE_GONE_MESSAGE)
            val fallback = task.copy(qualityCap = 0)
            if (task.qualityCap != 0) store.updateTask(task.taskId) { it.copy(qualityCap = 0) }
            return fallback
        }
        val variant = repo.downloadVariant(task.fileId, task.quality, task.qualityCap).getOrThrow()
        if (variant == null && task.quality != null) error(TRANSCODE_GONE_MESSAGE)
        PikoLog.i(
            DOWNLOAD_LOG_TAG,
            "Selected segment quality: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                "quality=${variant?.name ?: "original"}${if (task.qualityCap > 0) " (cap=${task.qualityCap}p)" else ""}",
        )
        store.updateTask(task.taskId) { it.copy(qualityCap = 0, quality = variant?.name, mediaId = variant?.mediaId) }
        return task.copy(qualityCap = 0, quality = variant?.name, mediaId = variant?.mediaId)
    }

    private suspend fun convertTranscode(task: DownloadTask, fileStore: PersistentFileStore, target: String) {
        val converter = segmentDownloader ?: error(
            tr(
                "当前平台不支持转封装",
                "Remuxing is not supported on this platform",
                "このプラットフォームでは再多重化に対応していません",
                "이 플랫폼에서는 리먹싱을 지원하지 않습니다",
                "目前平台不支援轉封裝",
            ),
        )
        store.updateTask(task.taskId) { it.copy(converting = true, progressFraction = 0f, speedBytesPerSec = 0L) }
        val started = TimeSource.Monotonic.markNow()
        val logicalOpen = fileStore.logicalSourceFactory()?.let { open -> suspend { open() } }
        converter.remux(
            PikoRemuxRequest(
                sourcePath = fileStore.path,
                destinationPath = target,
                durationMillis = task.endMs,
                openRandomAccess = logicalOpen,
            ),
        ) { fraction ->
            store.updateTask(task.taskId) { it.copy(progressFraction = fraction) }
        }.getOrThrow()
        PikoLog.d(DOWNLOAD_LOG_TAG, "Remuxed transcode stream to MP4: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}, elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
    }

    private fun downloadOrder(size: Long): List<LongRange> =
        if (size > 0L) listOf(0L until size) else emptyList()

    private fun startSegment(task: DownloadTask) {
        val extractor = segmentDownloader ?: run {
            PikoLog.w(DOWNLOAD_LOG_TAG, "Segment extraction failed: platform extractor unavailable (task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)})")
            store.updateTask(task.taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = tr(
                        "当前平台不支持片段截取",
                        "Clip extraction is not supported on this platform",
                        "このプラットフォームでは区間切り出しに対応していません",
                        "이 플랫폼에서는 구간 추출을 지원하지 않습니다",
                        "目前平台不支援片段擷取",
                    ),
                )
            }
            return
        }
        launchTracked(task.taskId) {
            val repo = mediaRepository
            store.updateTask(task.taskId) { it.copy(status = DownloadStatus.DOWNLOADING, progressFraction = 0f, errorMessage = null) }
            val chosen = try {
                if (task.qualityCap > 0 || task.quality != null) chooseSegmentQuality(task) else task
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(DOWNLOAD_LOG_TAG, "Failed to select segment quality: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}", e)
                store.updateTask(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = e.message) }
                return@launchTracked
            }
            store.updateTask(task.taskId) { it.copy(sparseCache = it.sparseCache || repo != null) }
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
                                    store.updateTask(task.taskId) { current -> current.copy(fullFileSize = source.size) }
                                }
                            }
                        }
                    },
                ),
            ) { fraction ->
                store.updateTask(task.taskId) { it.copy(progressFraction = fraction) }
            }.onSuccess { path ->
                val size = runSuspendCatching { store.storageFor(task).existingLength(task.fileName) }
                    .logFailure(DOWNLOAD_LOG_TAG, "Failed to read extracted segment size: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}")
                    .getOrDefault(0L)
                PikoLog.i(
                    DOWNLOAD_LOG_TAG,
                    "Segment extraction completed: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                        "range=${task.startMs}..${task.endMs}ms, quality=${chosen.quality ?: "original"}, savedBytes=$size, elapsedMs=${started.elapsedNow().inWholeMilliseconds}",
                )
                if (repo != null) {
                    fileCachePool.discard(task.account, task.gcid, cacheSize, task.taskId, chosen.mediaId)
                    fileCachePool.discardByOwner(task.taskId)
                }
                store.updateTask(task.taskId) {
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
                PikoLog.w(
                    DOWNLOAD_LOG_TAG,
                    "Segment extraction failed: task=${logDownloadTask(task.taskId)}, ${logFile(task.fileId, task.fileName)}, " +
                        "range=${task.startMs}..${task.endMs}ms, elapsedMs=${started.elapsedNow().inWholeMilliseconds}",
                    error,
                )
                store.updateTask(task.taskId) { it.copy(status = DownloadStatus.FAILED, errorMessage = error.message) }
            }
        }
    }

    private fun launchTracked(taskId: String, block: suspend () -> Unit) {
        var previous: Job? = null
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                previous?.join()
                if (store.tasks.value[taskId]?.status != DownloadStatus.DOWNLOADING) return@launch
                block()
            } finally {
                jobs.update { current -> if (current[taskId] === self) current - taskId else current }
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

    companion object {
        private val listingLeftAccountMessage: String
            get() = tr("切换账号时中断", "Interrupted by account switch", "アカウント切り替えにより中断されました", "계정 전환으로 중단됨", "切換帳號時中斷")
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val PLAYBACK_WAKE_GRACE_TICKS = 10
        private const val LOG_INTERVAL_MS = 10_000L
        private const val SPEED_WINDOW_MS = 3_000L
        private const val BATCH_PARALLEL = 3
        private const val BATCH_LOG_MIN_BYTES = 64L * 1024 * 1024
    }
}
