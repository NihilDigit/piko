@file:JvmMultifileClass
@file:JvmName("PikoDownloadCoordinatorKt")

package dev.piko.shared.download

import androidx.compose.runtime.Stable
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.cache.PikoFileCachePool
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * 下载领域服务窄接口（ABI 防火墙）：隔离任务表 JSON 持久化、递归目录展开、并发限速与转封装实现细节。
 */
@Stable
interface DownloadService {
    val tasks: StateFlow<Map<String, DownloadTask>>
    val listings: StateFlow<Map<String, FolderListing>>
    val fileCachePool: PikoFileCachePool

    fun bindDefaultFolderSource(source: DownloadFolderSource)
    suspend fun flushPersistence()
    suspend fun findCompletedLocalPath(file: FileStat): String?

    fun enqueue(file: FileStat, targetDirectory: String? = null)
    fun enqueueFiles(files: List<FileStat>, maxHeight: Int? = null, targetDirectory: String? = null)
    fun enqueueQuality(file: FileStat, quality: DownloadQuality, targetDirectory: String? = null)
    fun enqueueResolved(files: List<ResolvedFile>, parentId: String = "", targetDirectory: String? = null)
    fun enqueueFolders(
        folders: List<FileStat>,
        source: DownloadFolderSource,
        maxHeight: Int? = null,
        targetDirectory: String? = null,
    ): Int

    fun retryListing(batchId: String)
    fun confirmListing(batchId: String)
    fun dismissListing(batchId: String)

    fun pauseBatch(batchId: String)
    fun resumeBatch(batchId: String)
    fun cancelBatch(batchId: String)
    suspend fun batchFolderPath(batch: DownloadBatch): String?

    fun startDownload(taskId: String)
    fun enqueueSegment(
        file: FileStat,
        startMillis: Long,
        endMillis: Long,
        timeRangeLabel: String,
        sourceUrl: String,
        quality: DownloadQuality? = null,
        targetDirectory: String? = null,
    )
    fun enqueueSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
        quality: DownloadQuality? = null,
        targetDirectory: String? = null,
    )
    fun pauseDownload(taskId: String)
    fun pauseAll()
    fun cancelDownload(taskId: String): Job?
}

@Stable
class PikoDownloadCoordinator(
    clientProvider: PikoClientProvider,
    preferences: PikoUserPreferences,
    storage: PikoDownloadStorage,
    scope: CoroutineScope,
    segmentDownloader: PikoSegmentDownloader? = null,
    /** 片段抽取经它开本机代理会话读源文件。缺省时退回任务里存下的直链。 */
    mediaRepository: PikoMediaRepository? = null,
    cacheStore: PikoCacheStore? = null,
) : DownloadService {
    override val fileCachePool: PikoFileCachePool =
        mediaRepository?.fileCachePool ?: PikoFileCachePool(scope) { storage.cacheTarget(it) }

    private val queueStore = DownloadQueueStore(
        clientProvider = clientProvider,
        preferences = preferences,
        storage = storage,
        scope = scope,
        fileCachePool = fileCachePool,
        cacheStore = cacheStore,
    )

    private val taskExecutor = DownloadTaskExecutor(
        clientProvider = clientProvider,
        preferences = preferences,
        scope = scope,
        segmentDownloader = segmentDownloader,
        mediaRepository = mediaRepository,
        fileCachePool = fileCachePool,
        store = queueStore,
    )

    override val tasks: StateFlow<Map<String, DownloadTask>> get() = queueStore.tasks

    /** 还没变成任务的文件夹下载，按批次 ID。见 [enqueueFolders]。 */
    override val listings: StateFlow<Map<String, FolderListing>> get() = queueStore.listings

    init {
        mediaRepository?.fileCachePool = fileCachePool
        mediaRepository?.partialDownload = taskExecutor::notifyPartialPlaybackLookup
        taskExecutor.startBackgroundLoops()
    }

    override fun bindDefaultFolderSource(source: DownloadFolderSource) {
        queueStore.bindDefaultFolderSource(source, taskExecutor::isJobActive, taskExecutor::pumpQueue)
    }

    /** 立即把当前下载任务表与待处理文件夹枚举状态写入持久化存储。 */
    override suspend fun flushPersistence() {
        queueStore.flushPersistence()
    }

    /**
     * 这个文件在下载目录里有没有完整副本。
     * 以磁盘为准核对 sanitize 后的文件名与完整字节长度，暂停中的半截文件不算。
     */
    override suspend fun findCompletedLocalPath(file: FileStat): String? =
        queueStore.findCompletedLocalPath(file)

    override fun enqueue(file: FileStat, targetDirectory: String?) =
        enqueueFiles(listOf(file), targetDirectory = targetDirectory)

    /**
     * 多选文件一次入队，共用全局并发上限；重复文件复用已有任务。视频按画质上限 [maxHeight] 挑档（0 是原画，
     * null 取设置里的默认下载画质，没设过取原画），开始下载时才挑，见 [DownloadTask.qualityCap]。
     */
    override fun enqueueFiles(files: List<FileStat>, maxHeight: Int?, targetDirectory: String?) {
        queueStore.enqueueFiles(
            files = files,
            leasedSource = false,
            chosen = null,
            maxHeight = maxHeight,
            targetDirectory = targetDirectory,
            isJobActive = taskExecutor::isJobActive,
            onEnqueued = taskExecutor::pumpQueue,
        )
    }

    /**
     * 单个视频按用户在下载对话框里选的那一档下载，不看上限。[quality] 是原画（name 为 null）时下原画；
     * 是转码档时下完转封装成 MP4，文件名里带上档位。
     */
    override fun enqueueQuality(file: FileStat, quality: DownloadQuality, targetDirectory: String?) {
        queueStore.enqueueFiles(
            files = listOf(file),
            leasedSource = false,
            chosen = quality,
            maxHeight = null,
            targetDirectory = targetDirectory,
            isJobActive = taskExecutor::isJobActive,
            onEnqueued = taskExecutor::pumpQueue,
        )
    }

    /** 分享或解析得到的内容直接下载，临时文件对象不随解析面板关闭。 */
    override fun enqueueResolved(files: List<ResolvedFile>, parentId: String, targetDirectory: String?) {
        queueStore.enqueueResolved(
            files = files,
            parentId = parentId,
            targetDirectory = targetDirectory,
            isJobActive = taskExecutor::isJobActive,
            onEnqueued = taskExecutor::pumpQueue,
        )
    }

    /**
     * 下载几个文件夹，每个一批：在后台列出其中全部文件，落在下载目录下同名的文件夹里并保持子文件夹结构。
     */
    override fun enqueueFolders(
        folders: List<FileStat>,
        source: DownloadFolderSource,
        maxHeight: Int?,
        targetDirectory: String?,
    ): Int = queueStore.enqueueFolders(
        folders = folders,
        source = source,
        maxHeight = maxHeight,
        targetDirectory = targetDirectory,
        isJobActive = taskExecutor::isJobActive,
        onBatchReady = taskExecutor::pumpQueue,
    )

    /** 列出失败的重新列一遍。 */
    override fun retryListing(batchId: String) {
        queueStore.retryListing(batchId, taskExecutor::isJobActive, taskExecutor::pumpQueue)
    }

    /** 超出今日额度也照样下载。 */
    override fun confirmListing(batchId: String) {
        queueStore.confirmListing(batchId, taskExecutor::isJobActive, taskExecutor::pumpQueue)
    }

    /** 不下载了：停下列出，或丢掉等确认的那一批。 */
    override fun dismissListing(batchId: String) {
        queueStore.dismissListing(batchId)
    }

    override fun pauseBatch(batchId: String) {
        taskExecutor.pauseBatchInternal(batchId, log = true)
    }

    /** 整批继续：暂停与失败的重新排队。 */
    override fun resumeBatch(batchId: String) {
        taskExecutor.resumeBatch(batchId)
    }

    /** 取消整批并删掉已下载的文件，连同留下的空文件夹。 */
    override fun cancelBatch(batchId: String) {
        taskExecutor.cancelBatch(batchId)
    }

    /** 这一批的文件夹在本机的位置，还没建出来时为 null。 */
    override suspend fun batchFolderPath(batch: DownloadBatch): String? =
        queueStore.batchFolderPath(batch)

    override fun startDownload(taskId: String) {
        taskExecutor.startDownload(taskId)
    }

    override fun enqueueSegment(
        file: FileStat,
        startMillis: Long,
        endMillis: Long,
        timeRangeLabel: String,
        sourceUrl: String,
        quality: DownloadQuality?,
        targetDirectory: String?,
    ) {
        queueStore.enqueueSegment(
            file = file,
            startMillis = startMillis,
            endMillis = endMillis,
            timeRangeLabel = timeRangeLabel,
            sourceUrl = sourceUrl,
            quality = quality,
            targetDirectory = targetDirectory,
            onEnqueued = taskExecutor::pumpQueue,
        )
    }

    override fun enqueueSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
        quality: DownloadQuality?,
        targetDirectory: String?,
    ) = enqueueSegment(file, startMs, endMs, timeRangeLabel, streamUrl.orEmpty(), quality, targetDirectory)

    override fun pauseDownload(taskId: String) {
        taskExecutor.pauseDownloadInternal(taskId, log = true)
    }

    /** 把所有进行中的任务转为暂停。前台服务被系统叫停时用，之后可以逐个继续。 */
    override fun pauseAll() {
        taskExecutor.pauseAll()
    }

    /** 返回删文件的协程，整批取消时等它们删完再收拾空文件夹。 */
    override fun cancelDownload(taskId: String): Job? =
        taskExecutor.cancelDownloadInternal(taskId, log = true)
}
