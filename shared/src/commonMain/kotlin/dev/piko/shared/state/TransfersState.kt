package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 传输列表中的一项：本地下载或云端离线任务。 */
sealed interface TransferItem {
    /** 列表 key。两类任务的 id 来自不同命名空间，加前缀防撞。 */
    val key: String
    val createdAtMs: Long

    data class Local(val task: DownloadTask) : TransferItem {
        override val key: String get() = "local:${task.taskId}"
        override val createdAtMs: Long get() = task.createdAtMs
    }

    data class Cloud(val task: OfflineTask) : TransferItem {
        override val key: String get() = "cloud:${task.id}"
        override val createdAtMs: Long get() = parseEpochMillis(task.createdTime) ?: 0L
    }

    /**
     * 整包离线：一个云端任务加上完成后的清理与改名。[task] 是传输页列表里同一任务的快照，
     * 可见期间它每 4 秒刷新一次，下载进度取它的；跟踪器按退避轮询，不够及时。
     */
    data class Pack(val job: OfflinePackJob, val task: OfflineTask?) : TransferItem {
        // 与 Cloud 同一命名空间：同一任务只该出现一次
        override val key: String get() = "cloud:${job.taskId}"
        override val createdAtMs: Long get() = job.createdAtMs

        val progress: Int get() = if (task?.phase == TaskPhase.RUNNING) task.progress else job.progress
    }
}

/**
 * 传输页：本地下载与云端离线任务合并为「进行中」「需要处理」「已完成」三段。
 *
 * 云端已完成的任务只列出最近 [COMPLETED_CLOUD_WINDOW] 内完成的，本地已完成的始终保留：
 * 后者对应磁盘上的文件，是用户找回下载的入口。
 *
 * 轮询挂在 [whileVisible] 上，由视图在可见期间调用。
 */
class TransfersState(
    private val coordinator: PikoDownloadCoordinator,
    private val taskRepo: TaskRepository,
    private val packTracker: OfflinePackTracker,
    private val scope: CoroutineScope,
    private val driveRepo: PikoDriveRepository,
) {
    private val cloud = OfflineTasksState(taskRepo, scope)

    private var localTasks by mutableStateOf(coordinator.tasks.value.values.toList())

    private var packJobs by mutableStateOf(packTracker.jobs.value)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 操作失败等提示。本地任务的失败体现在任务状态上，不走这里。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 云端列表尚未取回过。此时三段都空也不该显示空状态。 */
    val isLoading: Boolean get() = cloud.isLoading

    /** 云端列表最近一次拉取失败的原因。 */
    val cloudLoadError: String? get() = cloud.loadError

    val inProgress: List<TransferItem> by derivedStateOf {
        section(
            localFilter = { it.status in IN_PROGRESS_LOCAL },
            cloudFilter = { it.phase == TaskPhase.PENDING || it.phase == TaskPhase.RUNNING },
            packFilter = { it.isActive },
        )
    }

    val needsAttention: List<TransferItem> by derivedStateOf {
        section(
            localFilter = { it.status == DownloadStatus.FAILED },
            cloudFilter = { it.phase == TaskPhase.ERROR && !it.isOutputDeleted },
            packFilter = { it.stage == OfflinePackStage.FAILED },
        )
    }

    /** 已完成但产出文件后来被删的云端任务。不是失败，排在最后弱化显示。 */
    val outputDeleted: List<TransferItem> by derivedStateOf {
        section(localFilter = { false }, cloudFilter = { it.isOutputDeleted }, packFilter = { false })
    }

    // 窗口起点在重算时取当前时刻，不随时钟自行推进；任务表一变就会重算，
    // 刚跨出窗口的任务晚一会儿移出无妨
    val completed: List<TransferItem> by derivedStateOf {
        val windowStartMs = nowMs() - COMPLETED_CLOUD_WINDOW.inWholeMilliseconds
        section(
            localFilter = { it.status == DownloadStatus.COMPLETED },
            cloudFilter = { task ->
                val finishedAt = parseEpochMillis(task.updatedTime)
                task.phase == TaskPhase.COMPLETE && finishedAt != null && finishedAt >= windowStartMs
            },
            packFilter = { it.stage == OfflinePackStage.DONE && it.finishedAtMs >= windowStartMs },
        )
    }

    /**
     * 已完成任务产出的缩略图，按产出文件 id。查过但没有缩略图的记为空串，不再重查。
     *
     * 任务本身不带缩略图，产出的文件详情也靠不住：文件夹的 thumbnail_link 在详情里常为空，
     * 在父目录的列表里却有，网盘列表的文件夹封面用的就是后者。所以先查详情拿到父目录，
     * 再列父目录取缩略图；同一父目录只列一次，产出多半都落在同一个保存目录里。
     */
    private var thumbnails by mutableStateOf<Map<String, String>>(taskRepo.outputThumbnails.toMap())

    fun thumbnailOf(fileId: String): String? = thumbnails[fileId]?.ifEmpty { null }

    private val completedOutputIds: List<String> by derivedStateOf {
        completed.mapNotNull { item ->
            when (item) {
                is TransferItem.Cloud -> item.task.fileId
                is TransferItem.Pack -> item.job.outputId
                is TransferItem.Local -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }

    val isEmpty: Boolean by derivedStateOf {
        inProgress.isEmpty() && needsAttention.isEmpty() && completed.isEmpty() && outputDeleted.isEmpty()
    }

    init {
        scope.launch {
            coordinator.tasks.collect { localTasks = it.values.toList() }
        }
        scope.launch {
            packTracker.jobs.collect { packJobs = it }
        }
        scope.launch {
            cloud.messages.collect { _messages.emit(it) }
        }
        scope.launch {
            snapshotFlow { completedOutputIds }.collect { ids -> loadThumbnails(ids) }
        }
    }

    private suspend fun loadThumbnails(ids: List<String>) {
        val missing = ids.filter { it !in thumbnails }.take(THUMBNAIL_BATCH)
        if (missing.isEmpty()) return
        val parents = missing.associateWith { id -> driveRepo.getFileDetail(id).getOrNull()?.parentId }
        val found = mutableMapOf<String, String>()
        for (parentId in parents.values.filterNotNull().distinct()) {
            val listing = driveRepo.listAllFiles(parentId).getOrNull() ?: continue
            listing.filter { it.id in missing }.forEach { found[it.id] = it.thumbnailLink }
        }
        // 取不到的也记下，免得每次列表变动都重查同一批
        val loaded = missing.associateWith { found[it].orEmpty() }
        taskRepo.outputThumbnails.putAll(loaded)
        thumbnails = thumbnails + loaded
    }

    /** 可见期间轮询云端任务，挂起直到调用方的协程被取消。 */
    suspend fun whileVisible() = cloud.pollWhileVisible()

    fun pauseLocal(taskId: String) = coordinator.pauseDownload(taskId)

    /** 继续暂停的任务，或重试失败的任务。 */
    fun resumeLocal(taskId: String) = coordinator.startDownload(taskId)

    /** 取消并删除本地文件。已完成的任务删的是成品，未完成的删的是半截文件。 */
    fun removeLocal(taskId: String) = coordinator.cancelDownload(taskId)

    /** 以原链接重新提交，旧记录随之删除。任务缺少 sourceUrl 时视图应隐藏此操作。 */
    fun resubmitCloud(task: OfflineTask) = cloud.resubmit(task)

    /** 删除任务记录，也用作已完成任务的「移除」。已完成任务的文件保留在网盘里。 */
    fun deleteCloud(taskId: String) = cloud.delete(taskId)

    /** 清除全部已完成的云端任务记录，含列表时间窗之外的。文件保留在网盘里。 */
    fun clearCompletedCloud() = cloud.clear(listOf(TaskPhase.COMPLETE))

    /** 清除全部失败的云端任务记录。 */
    fun clearFailedCloud() = cloud.clear(listOf(TaskPhase.ERROR))

    /** 取消进行中的整包离线，或移除已结束的记录。已完成的文件保留在网盘里。 */
    fun discardPack(taskId: String) {
        scope.launch {
            packTracker.discard(taskId).onFailure { _messages.tryEmit("操作失败：${it.message}") }
        }
    }

    /** 清理失败的重做清理，下载失败的以原链接重新离线。 */
    fun retryPack(taskId: String) {
        scope.launch {
            packTracker.retry(taskId).onFailure { _messages.tryEmit("重试失败：${it.message}") }
        }
    }

    private fun section(
        localFilter: (DownloadTask) -> Boolean,
        cloudFilter: (OfflineTask) -> Boolean,
        packFilter: (OfflinePackJob) -> Boolean,
    ): List<TransferItem> {
        val localItems = localTasks.filter(localFilter).map { TransferItem.Local(it) }
        // 被整包离线跟踪的任务只以 Pack 出现：列表接口仍会返回它，不滤掉就是两行
        val packIds = packJobs.mapTo(HashSet()) { it.taskId }
        val cloudItems = cloud.tasks.filter { it.id !in packIds && cloudFilter(it) }.map { TransferItem.Cloud(it) }
        val tasksById = cloud.tasks.associateBy { it.id }
        val packItems = packJobs.filter(packFilter).map { TransferItem.Pack(it, tasksById[it.taskId]) }
        return (localItems + cloudItems + packItems).sortedByDescending { it.createdAtMs }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        /** 一次最多补查多少个产出，已完成列表只列最近一周，通常远不到这个数。 */
        const val THUMBNAIL_BATCH = 40

        /**
         * 云端已完成任务的展示窗口。按查看次数划界的话，看过一眼的完成项切页回来就消失了；
         * 按时间划界，一周内完成的都还算新近，窗口又有上限，不会倒出整份历史。
         */
        val COMPLETED_CLOUD_WINDOW = 7.days

        private val IN_PROGRESS_LOCAL = setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
    }
}

/**
 * 产出文件已被删除的任务。列表接口带 with=reference_resource 时，服务端把这类任务的 phase
 * 覆盖成 ERROR、message 写作「File deleted」，getTask 查同一任务却是 COMPLETE/Saved。
 * 2026-09-23 在真实账号上实测，按 message 精确匹配。
 */
val OfflineTask.isOutputDeleted: Boolean
    get() = phase == TaskPhase.ERROR && message == "File deleted"

/** 解析服务端的 RFC 3339 时间。缺失或格式不认识时返回 null。 */
private fun parseEpochMillis(rfc3339: String?): Long? =
    rfc3339?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
