package dev.piko.shared.data

import io.github.nihildigit.pikpak.TaskListResponse
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.listOfflineTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val clientManager: PikoClientProvider) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    suspend fun getTasks(pageToken: String = ""): Result<TaskListResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            client.listOfflineTasks(
                limit = 50,
                pageToken = pageToken.ifEmpty { null },
                // SDK 的默认值只含 RUNNING 与 ERROR，排队中的任务会从列表里消失
                phaseFilter = ALL_PHASES,
            )
        }
    }

    private companion object {
        val ALL_PHASES = listOf(TaskPhase.PENDING, TaskPhase.RUNNING, TaskPhase.COMPLETE, TaskPhase.ERROR)
            .joinToString(",")
    }
}
