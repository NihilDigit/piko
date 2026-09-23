package dev.piko.shared.data

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

class InstantMagnetRepository(private val clientManager: PikoClientProvider) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")
    private val _pendingMagnetFlow = MutableStateFlow<String?>(null)
    val pendingMagnetFlow: StateFlow<String?> = _pendingMagnetFlow.asStateFlow()

    fun onIncomingMagnet(magnet: String) {
        magnet.trim().takeIf { it.isNotEmpty() }?.let { _pendingMagnetFlow.value = it }
    }

    fun clearPendingMagnet() {
        _pendingMagnetFlow.value = null
    }

    suspend fun resolve(magnet: String): Result<MagnetResolutionResult?> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val resource = client.resolveMagnet(magnet) ?: return@withContext Result.success(null)
            val items = resource.files.map { InstantFileItem(it, it.gcid != null) }
            MagnetResolutionResult(resource, items, items.count { it.isInstantReady }, items.size)
        }
    }

    suspend fun instantSave(items: List<InstantFileItem>, targetParentId: String = ""): Result<List<String>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                items.filter { it.file.gcid != null }.map {
                    client.instantCreate(file = it.file, parentId = targetParentId, name = it.file.name)
                }
            }
        }

    suspend fun enqueueOfflineTask(magnet: String, targetParentId: String = ""): Result<CreateUrlResult> =
        withContext(Dispatchers.Default) { runSuspendCatching { client.createUrlFile(parentId = targetParentId, url = magnet) } }
}
