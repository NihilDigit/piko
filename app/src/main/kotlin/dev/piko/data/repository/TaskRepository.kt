package dev.piko.data.repository

import dev.piko.data.client.PikPakClientManager
import dev.piko.util.runSuspendCatching
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskListResponse
import io.github.nihildigit.pikpak.listOfflineTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for managing cloud-side offline download tasks.
 *
 * Documentation References:
 * - Kotlin Coroutines & Cancellation: kotlin-docs-mirror/pages/docs/coroutines-cancellation.md
 * - Kotlin Coding Conventions: kotlin-docs-mirror/pages/docs/coding-conventions.md
 */
class TaskRepository(
    private val clientManager: PikPakClientManager,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    suspend fun getTasks(pageToken: String = ""): Result<TaskListResponse> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            client.listOfflineTasks(
                limit = 50,
                pageToken = pageToken.ifEmpty { null },
                phaseFilter = "PHASE_TYPE_PENDING,PHASE_TYPE_RUNNING,PHASE_TYPE_COMPLETE,PHASE_TYPE_ERROR",
            )
        }
    }
}
