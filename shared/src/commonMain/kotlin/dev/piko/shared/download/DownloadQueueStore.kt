@file:JvmMultifileClass
@file:JvmName("PikoDownloadCoordinatorKt")

package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logFolder
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class DownloadQueueStore(
    private val clientProvider: PikoClientProvider,
    private val preferences: PikoUserPreferences,
    private val storage: PikoDownloadStorage,
    private val scope: CoroutineScope,
    private val fileCachePool: PikoFileCachePool,
    private val cacheStore: PikoCacheStore?,
) {
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    private val _listings = MutableStateFlow<Map<String, FolderListing>>(emptyMap())
    val listings: StateFlow<Map<String, FolderListing>> = _listings.asStateFlow()

    private val listingWork = MutableStateFlow<Map<String, ListingWork>>(emptyMap())

    private data class ListingWork(
        val folder: FileStat,
        val source: DownloadFolderSource?,
        val maxHeight: Int?,
        val targetDirectory: String? = null,
        val job: Job? = null,
        val planned: List<DownloadTask> = emptyList(),
    )

    @Serializable
    private data class PersistedFolderListing(
        val batch: DownloadBatch,
        val folderId: String,
        val folderName: String,
        val maxHeight: Int? = null,
        val createdAtMs: Long = 0L,
        val account: String = "",
        val targetDirectory: String? = null,
    )

    private data class TaskPersistenceKey(
        val status: DownloadStatus,
        val sparseCache: Boolean,
        val fileName: String,
        val mediaId: String?,
        val fullFileSize: Long,
        val targetDirectory: String?,
    )

    private val restoreCompleted = CompletableDeferred<Unit>()

    /**
     * 仅记录上次应用进程退出时仍处于 `PENDING` 或 `DOWNLOADING`、在 [restoreTask] 中被暂置为 `PAUSED` 的任务 ID：
     * - 待所属账号变为当前活动账号后自动恢复为 `PENDING`；
     * - 若在切回所属账号前再次落盘，[flushPersistence] 仍按 `PENDING` 持久化以免丢失自动续传意图；
     * - 运行期手动暂停、运行期切号自动暂停的任务不加入此集合，显式暂停或取消任务时须从此集合移除。
     */
    private val interruptedTasks = MutableStateFlow<Set<String>>(emptySet())
    private val persistLock = Mutex()
    private val enqueueLock = Mutex()
    private val listingSlots = Semaphore(4)
    private var preserveUnreadTasks = false
    private var defaultFolderSource: DownloadFolderSource? = null
    private var batchSequence = 0L

    fun currentAccount(): String = clientProvider.currentClient.value?.account.orEmpty()

    fun belongsToCurrent(task: DownloadTask): Boolean = task.belongsTo(currentAccount())

    suspend fun defaultMaxHeight(): Int = preferences.downloadMaxHeightFlow.first() ?: 0

    fun normalizeTargetDirectory(directory: String?): String? =
        directory?.trim()?.takeIf { it.isNotEmpty() }

    fun storageFor(targetDirectory: String?): PikoDownloadStorage =
        storage.forDirectory(normalizeTargetDirectory(targetDirectory))

    fun storageFor(task: DownloadTask): PikoDownloadStorage =
        storageFor(task.targetDirectory)

    fun storageFor(batch: DownloadBatch): PikoDownloadStorage =
        storageFor(batch.targetDirectory)

    /**
     * 解析任务目标目录的规范化比较键，使「未指定目标目录（使用默认位置）」与「在选择器中显式确认当前默认目录」
     * 归入同一目录身份，避免重复入队、重名冲突或并发写入同一目标文件。
     */
    fun directoryKey(targetDirectory: String?): String {
        val raw = storageFor(targetDirectory).pathFor(DIRECTORY_KEY_PROBE)
        if (raw.startsWith("content:", ignoreCase = true)) return raw.trimEnd('/')
        val normalized = raw.replace('\\', '/').removeSuffix("/$DIRECTORY_KEY_PROBE").trimEnd('/')
        return if (normalized.length >= 2 && normalized[1] == ':') normalized.lowercase() else normalized
    }

    suspend fun awaitRestoreCompleted() {
        restoreCompleted.await()
    }

    fun completeRestore() {
        restoreCompleted.complete(Unit)
    }

    fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { currentMap ->
            val current = currentMap[taskId] ?: return@update currentMap
            val next = transform(current)
            if (next == current) currentMap else currentMap + (taskId to next)
        }
    }

    fun updateTasks(transform: (Map<String, DownloadTask>) -> Map<String, DownloadTask>) {
        _tasks.update(transform)
    }

    fun removeTask(taskId: String): DownloadTask? {
        val existing = _tasks.value[taskId]
        _tasks.update { it - taskId }
        return existing
    }

    fun batchTaskIds(batchId: String): List<String> =
        _tasks.value.values.filter { it.batch?.id == batchId }.map { it.taskId }

    fun clearInterruptedTask(taskId: String) {
        interruptedTasks.update { it - taskId }
    }

    fun clearInterruptedTasks(ids: Set<String>) {
        interruptedTasks.update { it - ids }
    }

    fun clearAllInterruptedTasks() {
        interruptedTasks.value = emptySet()
    }

    fun resumeInterruptedForAccount(account: String): Int {
        var resumedCount = 0
        _tasks.update { current ->
            resumedCount = 0
            current.mapValues { (id, task) ->
                if (id in interruptedTasks.value && task.belongsTo(account) && task.status == DownloadStatus.PAUSED) {
                    resumedCount++
                    task.copy(status = DownloadStatus.PENDING, errorMessage = null)
                } else {
                    task
                }
            }
        }
        interruptedTasks.update { ids ->
            ids.filterTo(HashSet()) { id ->
                _tasks.value[id]?.let { !it.belongsTo(account) } == true
            }
        }
        return resumedCount
    }

    /**
     * 读回上次保存的任务表与待处理文件夹枚举状态。
     */
    suspend fun restore() {
        val saved = runSuspendCatching {
            val serialized = preferences.loadDownloadTasks()
            preserveUnreadTasks = serialized.isNotBlank()
            val decoded = if (serialized.isBlank()) emptyList() else json.decodeFromString(taskListSerializer, serialized)
            preserveUnreadTasks = false
            decoded
        }.logFailure(DOWNLOAD_LOG_TAG, "Failed to load persisted download tasks, preserving raw data").getOrDefault(emptyList())
        runSuspendCatching {
            val serialized = cacheStore?.read("pending-folder-downloads.json")
            val pending = serialized?.takeIf { it.isNotBlank() }?.let {
                json.decodeFromString(ListSerializer(PersistedFolderListing.serializer()), it)
            }.orEmpty()
            pending.forEach { item ->
                val folder = FileStat(id = item.folderId, name = item.folderName, kind = FileKind.FOLDER)
                val targetDir = normalizeTargetDirectory(item.targetDirectory ?: item.batch.targetDirectory)
                val batch = if (item.batch.targetDirectory == targetDir) item.batch else item.batch.copy(targetDirectory = targetDir)
                _listings.update { current -> mapOf(batch.id to FolderListing(batch, item.createdAtMs, item.account)) + current }
                listingWork.update { current -> mapOf(batch.id to ListingWork(folder, null, item.maxHeight, targetDirectory = targetDir)) + current }
            }
        }.logFailure(DOWNLOAD_LOG_TAG, "Failed to load pending folder download listings")
        if (saved.isEmpty()) return
        val restored = withContext(Dispatchers.IO) { saved.mapNotNull { restoreTask(it) } }
        PikoLog.i(
            DOWNLOAD_LOG_TAG,
            "Restored download tasks: saved=${saved.size}, restored=${restored.size}, " +
                "incomplete=${restored.count { it.status != DownloadStatus.COMPLETED }}, droppedMissingOrIncomplete=${saved.size - restored.size}",
        )
        _tasks.update { current -> restored.associateBy { it.taskId } + current }
    }

    private suspend fun restoreTask(task: DownloadTask): DownloadTask? {
        if (task.status == DownloadStatus.PENDING || task.status == DownloadStatus.DOWNLOADING) {
            interruptedTasks.update { it + task.taskId }
        }
        val cachedBytes = if (task.status != DownloadStatus.COMPLETED && !task.isSegment && task.gcid.isNotBlank() && task.cacheSize > 0L) {
            fileCachePool.progress(task.account, task.gcid, task.cacheSize, task.cacheMediaId)
        } else {
            0L
        }
        val sparseCache = task.sparseCache || cachedBytes > 0L
        if (sparseCache && task.status != DownloadStatus.COMPLETED) {
            fileCachePool.remember(task.account, task.gcid, task.cacheSize, task.taskId, task.cacheMediaId)
        }
        val stopped = task.copy(
            speedBytesPerSec = 0L,
            converting = false,
            progressFraction = task.progressFraction.takeUnless { task.converting },
        )
        val taskStorage = storageFor(task)
        if (task.status == DownloadStatus.COMPLETED) {
            if (!taskStorage.exists(task.fileName)) return null
            val length = taskStorage.existingLength(task.fileName)
            if (task.isSegment) {
                return stopped.copy(totalBytes = length, downloadedBytes = length).takeIf { length > 0 }
            }
            return stopped.takeIf { length == task.totalBytes }
        }
        val status = when (task.status) {
            DownloadStatus.PENDING, DownloadStatus.DOWNLOADING -> DownloadStatus.PAUSED
            else -> task.status
        }
        val downloaded = if (task.isSegment) {
            task.downloadedBytes
        } else if (sparseCache) {
            cachedBytes
        } else {
            taskStorage.existingLength(task.fileName).coerceAtMost(task.totalBytes)
        }
        return stopped.copy(status = status, downloadedBytes = downloaded, sparseCache = sparseCache)
    }

    suspend fun persistOnStructuralChange() {
        _tasks
            .distinctUntilChangedBy { current ->
                current.mapValues { (_, task) ->
                    TaskPersistenceKey(task.status, task.sparseCache, task.fileName, task.mediaId, task.fullFileSize, task.targetDirectory)
                }
            }
            .conflate()
            .collect {
                flushPersistence()
                delay(PERSIST_INTERVAL_MS)
            }
    }

    suspend fun flushPersistence() {
        restoreCompleted.await()
        withContext(Dispatchers.IO) {
            persistLock.withLock {
                val current = _tasks.value
                if (!preserveUnreadTasks || current.isNotEmpty()) {
                    preserveUnreadTasks = false
                    runSuspendCatching {
                        val persisted = current.values.map { task ->
                            if (task.taskId in interruptedTasks.value && task.status == DownloadStatus.PAUSED) {
                                task.copy(status = DownloadStatus.PENDING)
                            } else {
                                task
                            }
                        }
                        preferences.saveDownloadTasks(json.encodeToString(taskListSerializer, persisted))
                    }.logFailure(DOWNLOAD_LOG_TAG, "Failed to save download task table")
                }
                val pending = _listings.value.values.mapNotNull { listing ->
                    listingWork.value[listing.batch.id]?.let { work ->
                        PersistedFolderListing(
                            batch = listing.batch,
                            folderId = work.folder.id,
                            folderName = work.folder.name,
                            maxHeight = work.maxHeight,
                            createdAtMs = listing.createdAtMs,
                            account = listing.account,
                            targetDirectory = work.targetDirectory ?: listing.batch.targetDirectory,
                        )
                    }
                }
                runSuspendCatching {
                    cacheStore?.write(
                        "pending-folder-downloads.json",
                        json.encodeToString(ListSerializer(PersistedFolderListing.serializer()), pending),
                    )
                }.logFailure(DOWNLOAD_LOG_TAG, "Failed to save pending folder download listings")
            }
        }
    }

    private fun persistListingsSoon() {
        scope.launch { flushPersistence() }
    }

    suspend fun findCompletedLocalPath(file: FileStat): String? = withContext(Dispatchers.IO) {
        restoreCompleted.await()
        if (file.sizeBytes <= 0L) return@withContext null
        val fromTasks = _tasks.value.values
            .filter { it.fileId == file.id && belongsToCurrent(it) && it.status == DownloadStatus.COMPLETED }
            .firstNotNullOfOrNull { task ->
                val taskStorage = storageFor(task)
                if (taskStorage.exists(task.fileName) && taskStorage.existingLength(task.fileName) == file.sizeBytes) {
                    taskStorage.pathFor(task.fileName).takeUnless { it.startsWith("content:") }
                } else {
                    null
                }
            }
        if (fromTasks != null) return@withContext fromTasks
        val defaultName = localFileNameOf(file)
        if (!storage.exists(defaultName) || storage.existingLength(defaultName) != file.sizeBytes) return@withContext null
        storage.pathFor(defaultName).takeUnless { it.startsWith("content:") }
    }

    suspend fun batchFolderPath(batch: DownloadBatch): String? =
        storageFor(batch).locate(if (batch.isFolder) batch.folderName else "")

    fun bindDefaultFolderSource(
        source: DownloadFolderSource,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        defaultFolderSource = source
        scope.launch {
            restoreCompleted.await()
            resumeListings(isJobActive, onBatchReady)
        }
    }

    fun resumeListings(
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        _listings.value.values.filter { it.account == currentAccount() }.forEach { listing ->
            val work = listingWork.value[listing.batch.id] ?: return@forEach
            if (work.job?.isActive != true && (work.source != null || defaultFolderSource != null)) {
                retryListing(listing.batch.id, isJobActive, onBatchReady)
            }
        }
    }

    fun pauseForeignListings(interruptedMessage: String): Int {
        val activeForeign = _listings.value.values.filter { it.isListing && it.account != currentAccount() }
        activeForeign.forEach { listing ->
            listingWork.value[listing.batch.id]?.job?.cancel()
            updateListing(listing.batch.id) { it.copy(error = interruptedMessage) }
        }
        return activeForeign.size
    }

    fun enqueueResolved(
        files: List<ResolvedFile>,
        parentId: String,
        targetDirectory: String?,
        isJobActive: (String) -> Boolean,
        onEnqueued: () -> Unit,
    ) {
        var missingHash = 0
        val resolved = files.mapNotNull { file ->
            val hash = file.gcid?.takeIf { it.isNotBlank() }?.uppercase() ?: run {
                missingHash++
                return@mapNotNull null
            }
            FileStat(
                id = "piko-content:$hash:${file.size}",
                name = file.name,
                hash = hash,
                size = file.size.toString(),
                parentId = parentId,
                phase = TaskPhase.COMPLETE,
            )
        }
        if (missingHash > 0) {
            PikoLog.w(DOWNLOAD_LOG_TAG, "Skipped $missingHash resolved file(s) missing GCID (${logFolder(parentId)})")
        }
        enqueueFiles(
            files = resolved,
            leasedSource = true,
            chosen = null,
            maxHeight = null,
            targetDirectory = targetDirectory,
            isJobActive = isJobActive,
            onEnqueued = onEnqueued,
        )
    }

    fun enqueueFiles(
        files: List<FileStat>,
        leasedSource: Boolean,
        chosen: DownloadQuality?,
        maxHeight: Int?,
        targetDirectory: String?,
        isJobActive: (String) -> Boolean,
        onEnqueued: () -> Unit,
    ) {
        val account = currentAccount()
        val accepted = files.filter { !it.isFolder && !it.isUploading }.distinctBy { it.id }
        if (accepted.isEmpty()) return
        val normalizedDir = normalizeTargetDirectory(targetDirectory)
        val targetDirKey = directoryKey(normalizedDir)
        val taskStorage = storageFor(normalizedDir)
        scope.launch(Dispatchers.IO) {
            restoreCompleted.await()
            enqueueLock.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                val cap = if (chosen == null && !leasedSource) maxHeight ?: defaultMaxHeight() else 0
                val batch = if (accepted.size > 1) {
                    DownloadBatch(
                        id = "files@$now-${++batchSequence}",
                        folderName = tr("批量下载", "Batch download", "一括ダウンロード", "일괄 다운로드", "批次下載"),
                        isFolder = false,
                        targetDirectory = normalizedDir,
                    )
                } else {
                    null
                }
                val taken = _tasks.value.values
                    .filter { directoryKey(it.targetDirectory) == targetDirKey }
                    .mapTo(mutableSetOf()) { it.fileName.lowercase() }
                val reused = accepted.count { existingTask(it, account, chosen, targetDirKey) != null }
                val added = accepted.map { file ->
                    val existing = existingTask(file, account, chosen, targetDirKey)
                    if (existing != null) {
                        existing.copy(
                            status = if (existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED) {
                                DownloadStatus.PENDING
                            } else {
                                existing.status
                            },
                        )
                    } else if (chosen?.name != null) {
                        transcodeTask(file, chosen, account, now, taken, normalizedDir, targetDirKey, taskStorage)
                    } else {
                        val wanted = localFileNameOf(file)
                        val matches = wanted.lowercase() !in taken &&
                            file.sizeBytes > 0L &&
                            taskStorage.exists(wanted) &&
                            taskStorage.existingLength(wanted) == file.sizeBytes
                        var name = if (matches) wanted.also { taken.add(it.lowercase()) } else uniqueDownloadName(wanted, taken)
                        if (!matches) while (taskStorage.exists(name)) name = uniqueDownloadName(wanted, taken)
                        val bytes = taskStorage.existingLength(name)
                        val complete = bytes == file.sizeBytes && file.sizeBytes > 0L
                        DownloadTask(
                            taskId = availableTaskId(file.id, account, targetDirKey),
                            fileId = file.id,
                            fileName = name,
                            gcid = file.hash,
                            totalBytes = file.sizeBytes,
                            downloadedBytes = bytes.coerceAtMost(file.sizeBytes),
                            destinationPath = if (complete) taskStorage.locate(name) ?: name else name,
                            status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
                            fullFileSize = file.sizeBytes,
                            thumbnailLink = file.thumbnailLink,
                            parentId = file.parentId,
                            createdAtMs = now,
                            account = account,
                            batch = batch,
                            leasedSource = leasedSource,
                            qualityCap = if (complete || leasedSource) 0 else qualityCapFor(file, name, cap),
                            targetDirectory = normalizedDir,
                        )
                    }
                }
                _tasks.update { current -> current + added.filter { !isJobActive(it.taskId) }.associateBy { it.taskId } }
                val targetSummary = batch?.let { "batch=${it.id}" }
                    ?: "task=${logDownloadTask(added.first().taskId)}, ${logFile(accepted.first().id, added.first().fileName)}"
                val qualitySummary = when {
                    chosen != null -> ", quality=${chosen.name ?: "original"}"
                    added.any { it.qualityCap > 0 } -> ", qualityCap=${cap}p"
                    else -> ""
                }
                val unprobed = added.count { it.totalBytes == 0L && it.quality != null }
                PikoLog.i(
                    DOWNLOAD_LOG_TAG,
                    "Enqueued ${accepted.size} file(s) ($targetSummary${if (leasedSource) ", leased=true" else ""}${if (normalizedDir != null) ", customDir=true" else ""}): " +
                        "reused=$reused, alreadyComplete=${added.count { it.status == DownloadStatus.COMPLETED }}, " +
                        "knownBytes=${added.sumOf { it.totalBytes }}${if (unprobed > 0) " ($unprobed transcode size pending probe)" else ""}$qualitySummary",
                )
            }
            onEnqueued()
        }
    }

    private suspend fun transcodeTask(
        file: FileStat,
        quality: DownloadQuality,
        account: String,
        now: Long,
        taken: MutableSet<String>,
        targetDirectory: String?,
        targetDirKey: String,
        taskStorage: PikoDownloadStorage,
    ): DownloadTask {
        val label = quality.name ?: error("Original quality is not a transcode variant")
        val wanted = transcodeFileName(localFileNameOf(file), label)
        val size = quality.sizeBytes ?: 0L
        val complete = wanted.lowercase() !in taken && size > 0L && taskStorage.exists(wanted) && taskStorage.existingLength(wanted) == size
        var name = if (complete) wanted.also { taken.add(it.lowercase()) } else uniqueDownloadName(wanted, taken)
        if (!complete) while (taskStorage.exists(name)) name = uniqueDownloadName(wanted, taken)
        return DownloadTask(
            taskId = availableTaskId("${file.id}_q_$label", account, targetDirKey),
            fileId = file.id,
            fileName = name,
            gcid = file.hash,
            totalBytes = size,
            downloadedBytes = if (complete) size else 0L,
            status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
            destinationPath = if (complete) taskStorage.locate(name) ?: name else name,
            fullFileSize = file.sizeBytes,
            thumbnailLink = file.thumbnailLink,
            parentId = file.parentId,
            createdAtMs = now,
            account = account,
            quality = label,
            mediaId = quality.mediaId,
            endMs = quality.durationMs,
            targetDirectory = targetDirectory,
        )
    }

    private fun qualityCapFor(file: FileStat, localName: String, cap: Int): Int =
        if (cap > 0 && localName.isPlayableVideo() && !LeasedFile.isLeased(file.id)) cap else 0

    fun enqueueFolders(
        folders: List<FileStat>,
        source: DownloadFolderSource,
        maxHeight: Int?,
        targetDirectory: String?,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ): Int {
        val normalizedDir = normalizeTargetDirectory(targetDirectory)
        val targetDirKey = directoryKey(normalizedDir)
        val eligible = folders.filter { it.isFolder && !isPikoFolder(it) }.distinctBy { it.id }
        val accepted = eligible.filter { folder ->
            _listings.value.values.none {
                it.account == currentAccount() && it.batch.sourceFolderId == folder.id &&
                    directoryKey(it.batch.targetDirectory) == targetDirKey
            } &&
                _tasks.value.values.none {
                    it.account == currentAccount() && it.batch?.sourceFolderId == folder.id &&
                        directoryKey(it.batch.targetDirectory) == targetDirKey &&
                        (it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING)
                }
        }
        val taken = (
            _listings.value.values
                .filter { directoryKey(it.batch.targetDirectory) == targetDirKey }
                .map { it.batch.folderName } +
                _tasks.value.values.mapNotNull {
                    it.batch?.takeIf { batch -> batch.isFolder && directoryKey(batch.targetDirectory) == targetDirKey }?.folderName
                }
            ).mapTo(mutableSetOf()) { it.lowercase() }
        accepted.forEach { folder ->
            val now = Clock.System.now().toEpochMilliseconds()
            val previous = _tasks.value.values.firstOrNull {
                it.account == currentAccount() && it.batch?.sourceFolderId == folder.id &&
                    directoryKey(it.batch.targetDirectory) == targetDirKey
            }?.batch
            val batch = previous ?: DownloadBatch(
                id = "${folder.id}@$now",
                folderName = uniqueDownloadName(FileNameSanitizer.sanitizeFolderName(folder.name), taken, false),
                sourceFolderId = folder.id,
                targetDirectory = normalizedDir,
            )
            _listings.update { it + (batch.id to FolderListing(batch, createdAtMs = now, account = currentAccount())) }
            listingWork.update { it + (batch.id to ListingWork(folder, source, maxHeight, targetDirectory = normalizedDir)) }
            PikoLog.d(DOWNLOAD_LOG_TAG, "Preparing folder download: batch=${batch.id}, ${logFolder(folder.id)}${if (normalizedDir != null) ", customDir=true" else ""}")
            persistListingsSoon()
            startListing(batch.id, isJobActive, onBatchReady)
        }
        return eligible.size
    }

    fun retryListing(
        batchId: String,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        val listing = _listings.value[batchId] ?: return
        if (listing.account.isNotEmpty() && listing.account != currentAccount()) return
        updateListing(batchId) { it.copy(filesFound = 0, bytesFound = 0L, error = null, quotaExcess = null) }
        startListing(batchId, isJobActive, onBatchReady)
    }

    fun confirmListing(
        batchId: String,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        val work = listingWork.value[batchId] ?: return
        if (_listings.value[batchId]?.account != currentAccount()) return
        if (work.planned.isNotEmpty()) {
            PikoLog.i(DOWNLOAD_LOG_TAG, "Confirmed folder download exceeding daily quota: batch=$batchId, ${logFolder(work.folder.id)}, files=${work.planned.size}")
            addBatch(batchId, work.planned, isJobActive, onBatchReady)
        }
    }

    fun dismissListing(batchId: String) {
        val dismissed = _listings.value[batchId]
        listingWork.value[batchId]?.job?.cancel()
        listingWork.update { it - batchId }
        _listings.update { it - batchId }
        if (dismissed != null) {
            PikoLog.d(DOWNLOAD_LOG_TAG, "Dismissed folder listing: batch=$batchId, ${logFolder(dismissed.batch.sourceFolderId)}")
        }
        persistListingsSoon()
    }

    private fun startListing(
        batchId: String,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        val work = listingWork.value[batchId] ?: return
        val listing = _listings.value[batchId] ?: return
        if (work.job?.isActive == true) return
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            restoreCompleted.await()
            if (_listings.value[batchId]?.account != currentAccount()) return@launch
            val source = work.source ?: defaultFolderSource ?: return@launch
            val planned = try {
                planFolderDownload(work.folder, object : DownloadFolderSource {
                    override suspend fun list(folderId: String) = listingSlots.withPermit { source.list(folderId) }
                    override suspend fun remainingDailyDownload() = source.remainingDailyDownload()
                }, rootName = listing.batch.folderName) { files, bytes ->
                    updateListing(batchId) { it.copy(filesFound = files, bytesFound = bytes) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(DOWNLOAD_LOG_TAG, "Failed to list folder for download: batch=$batchId, ${logFolder(work.folder.id)}", e)
                updateListing(batchId) {
                    it.copy(
                        error = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: tr("网络中断", "Network interrupted", "ネットワークが切断されました", "네트워크 연결이 끊어졌습니다", "網路中斷"),
                    )
                }
                return@launch
            }
            if (planned.isEmpty()) {
                PikoLog.d(DOWNLOAD_LOG_TAG, "Folder has no downloadable files: batch=$batchId, ${logFolder(work.folder.id)}")
                updateListing(batchId) {
                    it.copy(
                        error = tr(
                            "此文件夹中无可下载的文件",
                            "No downloadable files in this folder",
                            "このフォルダーにダウンロード可能なファイルはありません",
                            "이 폴더에 다운로드할 수 있는 파일이 없습니다",
                            "此資料夾中無可下載的檔案",
                        ),
                    )
                }
                return@launch
            }
            val batchStorage = storageFor(listing.batch)
            val lengths = batchStorage.existingLengths(planned.map { it.path })
            val cap = work.maxHeight ?: defaultMaxHeight()
            val plannedTasks = withContext(Dispatchers.IO) {
                planned.map { plannedTask(it, listing, lengths[it.path] ?: 0L, cap, batchStorage) }
            }
            val needed = plannedTasks.filter { it.status != DownloadStatus.COMPLETED }.sumOf { it.totalBytes - it.downloadedBytes }
            val remaining = runSuspendCatching { source.remainingDailyDownload() }
                .logFailure(DOWNLOAD_LOG_TAG, "Failed to query remaining daily download quota for batch=$batchId")
                .getOrNull()
            PikoLog.d(DOWNLOAD_LOG_TAG, "Listed folder for download: batch=$batchId, ${logFolder(work.folder.id)}, files=${plannedTasks.size}, neededBytes=$needed, remainingDailyQuota=$remaining")
            if (remaining != null && needed > remaining) {
                PikoLog.i(DOWNLOAD_LOG_TAG, "Folder download awaiting quota confirmation: batch=$batchId, ${logFolder(work.folder.id)}, neededBytes=$needed, remainingDailyQuota=$remaining")
                listingWork.update { current -> current[batchId]?.let { current + (batchId to it.copy(job = null, planned = plannedTasks)) } ?: current }
                updateListing(batchId) { it.copy(quotaExcess = QuotaExcess(needed, remaining)) }
            } else {
                addBatch(batchId, plannedTasks, isJobActive, onBatchReady)
            }
        }
        listingWork.update { current -> current[batchId]?.let { current + (batchId to it.copy(job = job, planned = emptyList())) } ?: current }
        job.start()
    }

    private suspend fun plannedTask(
        planned: PlannedFile,
        listing: FolderListing,
        length: Long,
        cap: Int,
        batchStorage: PikoDownloadStorage,
    ): DownloadTask {
        val file = planned.file
        val targetDir = normalizeTargetDirectory(listing.batch.targetDirectory)
        val targetDirKey = directoryKey(targetDir)
        existingTask(file, listing.account, targetDirectoryKey = targetDirKey)
            ?.takeIf { it.status != DownloadStatus.COMPLETED || (it.fileName == planned.path && length == file.sizeBytes) }
            ?.let {
                return it.copy(status = if (it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED) DownloadStatus.PENDING else it.status)
            }
        val complete = length == file.sizeBytes && file.sizeBytes > 0L
        return DownloadTask(
            taskId = availableTaskId(file.id, listing.account, targetDirKey),
            fileId = file.id,
            fileName = planned.path,
            gcid = file.hash,
            totalBytes = file.sizeBytes,
            downloadedBytes = length.coerceAtMost(file.sizeBytes),
            destinationPath = if (complete) batchStorage.locate(planned.path) ?: batchStorage.pathFor(planned.path) else planned.path,
            status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.PENDING,
            fullFileSize = file.sizeBytes,
            thumbnailLink = file.thumbnailLink,
            parentId = file.parentId,
            createdAtMs = listing.createdAtMs,
            account = listing.account,
            batch = listing.batch,
            qualityCap = if (complete) 0 else qualityCapFor(file, planned.path, cap),
            targetDirectory = targetDir,
        )
    }

    private fun addBatch(
        batchId: String,
        batchTasks: List<DownloadTask>,
        isJobActive: (String) -> Boolean,
        onBatchReady: () -> Unit,
    ) {
        val batch = batchTasks.firstOrNull()?.batch ?: _listings.value[batchId]?.batch
        _tasks.update { current ->
            current + batchTasks.filter { !isJobActive(it.taskId) }.associateBy { it.taskId }
        }
        listingWork.update { it - batchId }
        _listings.update { it - batchId }
        persistListingsSoon()
        PikoLog.i(
            DOWNLOAD_LOG_TAG,
            "Folder batch enqueued: batch=$batchId, ${logFolder(batch?.sourceFolderId)}, " +
                "files=${batchTasks.size}, alreadyComplete=${batchTasks.count { it.status == DownloadStatus.COMPLETED }}, totalBytes=${batchTasks.sumOf { it.totalBytes }}",
        )
        onBatchReady()
    }

    private fun updateListing(batchId: String, transform: (FolderListing) -> FolderListing) {
        _listings.update { current -> current[batchId]?.let { current + (batchId to transform(it)) } ?: current }
    }

    fun enqueueSegment(
        file: FileStat,
        startMillis: Long,
        endMillis: Long,
        timeRangeLabel: String,
        sourceUrl: String,
        quality: DownloadQuality?,
        targetDirectory: String?,
        onEnqueued: () -> Unit,
    ) {
        val account = currentAccount()
        val normalizedDir = normalizeTargetDirectory(targetDirectory)
        val targetDirKey = directoryKey(normalizedDir)
        val taskStorage = storageFor(normalizedDir)
        scope.launch {
            restoreCompleted.await()
            enqueueLock.withLock {
                val cap = if (quality == null) defaultMaxHeight() else 0
                val label = quality?.name?.let { "_[$it]" }.orEmpty()
                val name = FileNameSanitizer.sanitize(
                    "${file.name.substringBeforeLast('.', file.name)}_[$timeRangeLabel]$label.mp4",
                    fallbackExtension = "mp4",
                    forceExtension = "mp4",
                )
                val taskId = availableTaskId(
                    "${file.id}_seg_${startMillis}_$endMillis${quality?.name?.let { "_$it" }.orEmpty()}",
                    account,
                    targetDirKey,
                )
                val task = DownloadTask(
                    taskId = taskId,
                    fileId = file.id,
                    fileName = name,
                    gcid = file.hash,
                    totalBytes = 0L,
                    destinationPath = taskStorage.pathFor(name),
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
                    targetDirectory = normalizedDir,
                )
                _tasks.update { it + (taskId to task) }
                PikoLog.i(
                    DOWNLOAD_LOG_TAG,
                    "Enqueued segment download: task=${logDownloadTask(taskId)}, ${logFile(file.id, name)}, " +
                        "range=${startMillis}..${endMillis}ms, quality=${quality?.name ?: if (task.qualityCap > 0) "cap:${task.qualityCap}p" else "original"}" +
                        if (normalizedDir != null) ", customDir=true" else "",
                )
            }
            onEnqueued()
        }
    }

    /** 按上限挑到转码档的任务改名：「名字 [720P].mp4」，在原来的文件夹里，与已有的文件与任务都不重名。 */
    suspend fun renamedForQuality(task: DownloadTask, label: String): String = enqueueLock.withLock {
        val folder = task.fileName.substringBeforeLast('/', "")
        val wanted = (if (folder.isEmpty()) "" else "$folder/") + transcodeFileName(task.fileName.substringAfterLast('/'), label)
        val taskStorage = storageFor(task)
        val targetDirKey = directoryKey(task.targetDirectory)
        val taken = _tasks.value.values
            .filter { it.taskId != task.taskId && directoryKey(it.targetDirectory) == targetDirKey }
            .mapTo(mutableSetOf()) { it.fileName.lowercase() }
        var name = uniqueDownloadName(wanted, taken)
        while (taskStorage.exists(name)) name = uniqueDownloadName(wanted, taken)
        name
    }

    private fun existingTask(
        file: FileStat,
        account: String,
        chosen: DownloadQuality? = null,
        targetDirectoryKey: String = directoryKey(null),
    ): DownloadTask? = _tasks.value.values.firstOrNull {
        it.fileId == file.id && !it.isSegment && (it.account == account || it.account.isEmpty()) && it.gcid == file.hash &&
            directoryKey(it.targetDirectory) == targetDirectoryKey &&
            (chosen == null || (it.quality == chosen.name && it.qualityCap == 0))
    }

    private fun availableTaskId(fileId: String, account: String, targetDirectoryKey: String = directoryKey(null)): String {
        val scoped = if (_tasks.value[fileId]?.let { it.account.isNotEmpty() && it.account != account } == true) {
            "$account:$fileId"
        } else {
            fileId
        }
        val existing = _tasks.value[scoped]
        if (existing == null || directoryKey(existing.targetDirectory) == targetDirectoryKey) return scoped
        var suffix = 2
        while (_tasks.value.containsKey("$scoped#$suffix")) suffix++
        return "$scoped#$suffix"
    }

    companion object {
        private const val DIRECTORY_KEY_PROBE = "piko-dir-probe"
        private const val PERSIST_INTERVAL_MS = 1_000L
        private val json = Json { ignoreUnknownKeys = true }
        private val taskListSerializer = ListSerializer(DownloadTask.serializer())
    }
}

internal const val DOWNLOAD_LOG_TAG = "Download"

/** 多账号任务 ID 形如 `$account:$fileId`，落盘导出日志时对账号前缀做单向摘要以避免泄露原始账号名。 */
internal fun logDownloadTask(taskId: String): String =
    if (':' in taskId) {
        "acct-${taskId.substringBefore(':').hashCode().toUInt().toString(16)}:${taskId.substringAfter(':')}"
    } else {
        taskId
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
internal val DownloadTask.cacheSize: Long get() = if (isSegment) fullFileSize else totalBytes

internal val DownloadTask.cacheMediaId: String? get() = mediaId

internal val DownloadTask.cacheIdentity: String get() = PikoFileCachePool.identity(account, gcid, cacheSize, cacheMediaId)

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
