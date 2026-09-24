package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.TaskRepository
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 云端离线任务列表，两端共用。
 *
 * 轮询不在构造时启动，而是由视图在可见期间调用 [pollWhileVisible]：Android 挂在
 * repeatOnLifecycle(STARTED) 里，切到后台即停；Desktop 挂在 LaunchedEffect 里，
 * 离开这一页即停。状态类自己开一个常驻循环的话，谁也关不掉它。
 */
class OfflineTasksState(
    private val taskRepo: TaskRepository,
    private val scope: CoroutineScope,
) {
    // 上次的列表先铺底；有缓存就不算首次加载，不显示整页加载态
    var tasks by mutableStateOf<List<OfflineTask>>(taskRepo.cachedTasks().orEmpty())
        private set

    /** 首次加载。之后的刷新与轮询都不再切回整页加载态，列表保持可见。 */
    var isLoading by mutableStateOf(taskRepo.cachedTasks() == null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** 最近一次拉取失败的原因，成功后清空。此时列表仍是上一次的数据。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 还在云端排队或下载中的任务，用于徽标与抽屉。 */
    val activeTasks: List<OfflineTask> by derivedStateOf {
        tasks.filter { it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.PENDING }
    }

    private var refreshJob: Job? = null

    /** 用户主动刷新。失败时弹一次提示；轮询失败只更新 [loadError]，不刷屏。 */
    fun refresh() {
        if (!isLoading) isRefreshing = true
        launchFetch(notifyFailure = true)
    }

    /**
     * 可见期间周期性拉取。挂起直到调用方的协程被取消。
     *
     * 每轮先拉取再等待，所以进入页面立刻有一次加载，不必另调 [refresh]。
     */
    suspend fun pollWhileVisible(intervalMs: Long = DEFAULT_POLL_INTERVAL_MS) {
        while (true) {
            fetch(notifyFailure = isLoading)
            delay(intervalMs)
        }
    }

    /** 以原链接重新提交，旧记录随之删除。成功后先从列表里摘掉旧记录，新任务由重新拉取带回。 */
    fun resubmit(task: OfflineTask) {
        scope.launch {
            taskRepo.resubmitTask(task)
                .onSuccess {
                    tasks = tasks.filterNot { it.id == task.id }
                    launchFetch(notifyFailure = false)
                }
                .onFailure { err -> _messages.tryEmit("重新提交失败") }
        }
    }

    /** 删除任务记录，不删已产出的文件。 */
    fun delete(taskId: String) {
        scope.launch {
            taskRepo.deleteTasks(listOf(taskId))
                .onSuccess {
                    tasks = tasks.filterNot { it.id == taskId }
                    launchFetch(notifyFailure = false)
                }
                .onFailure { err -> _messages.tryEmit("删除任务失败") }
        }
    }

    /** 清除某几个阶段的任务记录，不删产出的文件。先从列表里摘掉，失败再由重新拉取带回。 */
    fun clear(phases: List<String>) {
        val cleared = tasks.count { it.phase in phases }
        scope.launch {
            taskRepo.clearTasks(phases)
                .onSuccess {
                    tasks = tasks.filterNot { it.phase in phases }
                    _messages.tryEmit("已清除 $cleared 条记录")
                    launchFetch(notifyFailure = false)
                }
                .onFailure { _messages.tryEmit("清除记录失败") }
        }
    }

    private fun launchFetch(notifyFailure: Boolean) {
        // 连点刷新只保留最后一次，旧请求晚到的结果不能盖掉新的
        refreshJob?.cancel()
        refreshJob = scope.launch { fetch(notifyFailure) }
    }

    private suspend fun fetch(notifyFailure: Boolean) {
        try {
            taskRepo.getTasks()
                .onSuccess { response ->
                    tasks = response.tasks
                    loadError = null
                }
                .onFailure { err ->
                    val reason = err.message ?: "网络错误"
                    loadError = reason
                    if (notifyFailure) _messages.tryEmit("加载离线任务失败")
                }
        } finally {
            isLoading = false
            isRefreshing = false
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 4_000L
    }
}
