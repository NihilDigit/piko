package dev.piko.shared.data

import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskListResponse
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.createUrlFile
import io.github.nihildigit.pikpak.deleteOfflineTasks
import io.github.nihildigit.pikpak.listOfflineTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(
    private val clientManager: PikoClientProvider,
    private val driveRepository: PikoDriveRepository,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    /**
     * 最近一次拉到的首页任务，连同所属账号。传输页的状态随页面重建，每次进入都从空列表开始
     * 会先闪一下加载态；用它先铺底，再照常刷新。只放内存，换账号即失效。
     */
    private var lastTasks: Pair<String, List<OfflineTask>>? = null

    /** 当前账号上次拉到的任务，没有时为 null。 */
    fun cachedTasks(): List<OfflineTask>? {
        val account = clientManager.currentClient.value?.account ?: return null
        return lastTasks?.takeIf { it.first == account }?.second
    }

    /** 已完成任务产出的缩略图，按产出文件 id；查过而没有的记为空串。文件 id 不跨账号重复。 */
    val outputThumbnails: MutableMap<String, String> = mutableMapOf()

    suspend fun getTasks(pageToken: String = ""): Result<TaskListResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val current = client
            current.listOfflineTasks(
                limit = 50,
                pageToken = pageToken.ifEmpty { null },
                // SDK 的默认值只含 RUNNING 与 ERROR，排队中的任务会从列表里消失
                phaseFilter = ALL_PHASES,
            ).also { if (pageToken.isEmpty()) lastTasks = current.account to it.tasks }
        }
    }

    /**
     * 以原链接重新提交，成功后删掉旧记录，免得列表里一新一旧两条。
     *
     * 不用服务端的 RETRY：实测（2026-09-23）服务端接受后任务转入 RUNNING，约两秒内又落回
     * ERROR「Save failed, retry please」，真实的保存失败也是如此；按原链接重新提交则能正常完成。
     */
    suspend fun resubmitTask(task: OfflineTask): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val url = task.sourceUrl ?: error("任务缺少来源链接")
            client.createUrlFile(parentId = resubmitTarget(task), url = url)
            Unit
        }.onSuccess {
            // 新任务已经提交，旧记录删不掉只是多留一条，用户可以手动删，不算重新提交失败
            runSuspendCatching { client.deleteOfflineTasks(listOf(task.id), deleteFiles = false) }
        }
    }

    /**
     * 原任务的保存目录。缺省的 parent_folder_id 表示原先存在根目录，重新提交时改放 My Packs，
     * 与秒传与离线的默认目标一致，免得根目录里越积越多。取不到 My Packs 时退回根目录，
     * 重新提交本身不该因此失败。
     */
    private suspend fun resubmitTarget(task: OfflineTask): String =
        task.params["parent_folder_id"]?.takeIf { it.isNotEmpty() }
            ?: driveRepository.getOrCreateMyPacksFolder().getOrNull()?.id.orEmpty()

    /** 只删任务记录。已完成任务的文件留在网盘里，未完成任务的占位文件由服务端一并清掉。 */
    suspend fun deleteTasks(taskIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteOfflineTasks(taskIds, deleteFiles = false) }
    }

    private companion object {
        val ALL_PHASES = listOf(TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.COMPLETE, TaskPhase.ERROR)
            .joinToString(",")
    }
}

/** 任务的来源链接（磁力或 URL）。缺失时无法重新提交。 */
val OfflineTask.sourceUrl: String?
    get() = params["url"]?.takeIf { it.isNotEmpty() }
