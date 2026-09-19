package dev.piko.shared.data

import io.github.nihildigit.pikpak.TaskListResponse
import io.github.nihildigit.pikpak.listOfflineTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskRepository(private val clientManager: PikoClientProvider) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    suspend fun getTasks(pageToken: String = ""): Result<TaskListResponse> = withContext(Dispatchers.Default) {
        runCatching {
            client.listOfflineTasks(
                limit = 50,
                pageToken = pageToken.ifEmpty { null },
                phaseFilter = "PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_COMPLETE,PHASE_TYPE_ERROR",
            )
        }
    }
}
