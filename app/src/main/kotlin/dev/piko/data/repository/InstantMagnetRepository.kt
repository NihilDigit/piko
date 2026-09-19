package dev.piko.data.repository

import dev.piko.data.client.PikPakClientManager
import dev.piko.util.runSuspendCatching
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.MagnetResource
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.createUrlFile
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.resolveMagnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class InstantFileItem(
    val file: ResolvedFile,
    val isInstantReady: Boolean,
    val isSelected: Boolean = true,
)

data class MagnetResolutionResult(
    val resource: MagnetResource,
    val items: List<InstantFileItem>,
    val instantReadyCount: Int,
    val totalCount: Int,
)

/**
 * Repository for resolving magnet URIs, triggering instant cloud saves, and creating offline download tasks.
 *
 * Documentation References:
 * - Kotlin Structured Concurrency & Cancellation: kotlin-docs-mirror/pages/docs/coroutines-cancellation.md
 * - Kotlin Coroutines & Channels: kotlin-docs-mirror/pages/docs/flow.md
 */
class InstantMagnetRepository(
    private val clientManager: PikPakClientManager,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    private val _pendingMagnetFlow = MutableStateFlow<String?>(null)
    val pendingMagnetFlow: StateFlow<String?> = _pendingMagnetFlow.asStateFlow()

    fun onIncomingMagnet(magnet: String) {
        val trimmed = magnet.trim()
        if (trimmed.isNotEmpty()) {
            _pendingMagnetFlow.value = trimmed
        }
    }

    fun clearPendingMagnet() {
        _pendingMagnetFlow.value = null
    }

    suspend fun resolve(magnet: String): Result<MagnetResolutionResult?> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val resource = client.resolveMagnet(magnet) ?: return@runSuspendCatching null

            // 建任务时保留全部真实文件与原始命名，启发式筛选移至展示层
            val items = resource.files.map { file ->
                InstantFileItem(
                    file = file,
                    isInstantReady = file.gcid != null,
                    isSelected = true,
                )
            }

            val readyCount = items.count { it.isInstantReady }
            MagnetResolutionResult(
                resource = resource,
                items = items,
                instantReadyCount = readyCount,
                totalCount = items.size,
            )
        }
    }

    suspend fun instantSave(
        items: List<InstantFileItem>,
        targetParentId: String = "",
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val createdIds = mutableListOf<String>()
            for (item in items) {
                if (item.file.gcid != null) {
                    val fileId = client.instantCreate(
                        file = item.file,
                        parentId = targetParentId,
                        name = item.file.name,
                    )
                    createdIds.add(fileId)
                }
            }
            createdIds
        }
    }

    suspend fun enqueueOfflineTask(magnet: String, targetParentId: String = ""): Result<CreateUrlResult> =
        withContext(Dispatchers.IO) {
            runSuspendCatching {
                client.createUrlFile(parentId = targetParentId, url = magnet)
            }
        }
}
