package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
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
    taskRepo: TaskRepository,
    private val scope: CoroutineScope,
) {
    private val cloud = OfflineTasksState(taskRepo, scope)

    private var localTasks by mutableStateOf(coordinator.tasks.value.values.toList())

    /** 操作失败等提示。目前只有云端操作会失败，本地任务的失败体现在任务状态上。 */
    val messages: SharedFlow<String> get() = cloud.messages

    /** 云端列表尚未取回过。此时三段都空也不该显示空状态。 */
    val isLoading: Boolean get() = cloud.isLoading

    /** 云端列表最近一次拉取失败的原因。 */
    val cloudLoadError: String? get() = cloud.loadError

    val inProgress: List<TransferItem> by derivedStateOf {
        section(
            localFilter = { it.status in IN_PROGRESS_LOCAL },
            cloudFilter = { it.phase == TaskPhase.PENDING || it.phase == TaskPhase.RUNNING },
        )
    }

    val needsAttention: List<TransferItem> by derivedStateOf {
        section(
            localFilter = { it.status == DownloadStatus.FAILED },
            cloudFilter = { it.phase == TaskPhase.ERROR && !it.isOutputDeleted },
        )
    }

    /** 已完成但产出文件后来被删的云端任务。不是失败，排在最后弱化显示。 */
    val outputDeleted: List<TransferItem> by derivedStateOf {
        section(localFilter = { false }, cloudFilter = { it.isOutputDeleted })
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
        )
    }

    val isEmpty: Boolean by derivedStateOf {
        inProgress.isEmpty() && needsAttention.isEmpty() && completed.isEmpty() && outputDeleted.isEmpty()
    }

    init {
        scope.launch {
            coordinator.tasks.collect { localTasks = it.values.toList() }
        }
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

    private fun section(
        localFilter: (DownloadTask) -> Boolean,
        cloudFilter: (OfflineTask) -> Boolean,
    ): List<TransferItem> {
        val localItems = localTasks.filter(localFilter).map { TransferItem.Local(it) }
        val cloudItems = cloud.tasks.filter(cloudFilter).map { TransferItem.Cloud(it) }
        return (localItems + cloudItems).sortedByDescending { it.createdAtMs }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
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
