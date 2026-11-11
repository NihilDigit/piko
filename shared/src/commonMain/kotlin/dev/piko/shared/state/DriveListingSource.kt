package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface DriveListingResult {
    data object Loading : DriveListingResult
    data object Gone : DriveListingResult
    data class Content(val files: List<FileStat>, val cached: Boolean) : DriveListingResult
    data class Failure(val error: Throwable) : DriveListingResult
}

/** The late SQLite cache may paint first, but never replaces a network result. */
internal class DriveListingSource(private val repository: PikoDriveRepository) {
    fun observe(folder: String, order: PikoFileSortOrder, useCache: Boolean): Flow<DriveListingResult> = channelFlow {
        val cached = if (useCache) withContext(Dispatchers.Default) { repository.cachedBrowsable(folder, order) } else null
        if (cached != null) send(DriveListingResult.Content(cached, true)) else send(DriveListingResult.Loading)
        val settled = MutableStateFlow(cached != null)
        val watcher = if (useCache && !settled.value) launch {
            repository.observeTreeCacheReady().collect {
                if (!settled.value) {
                    val late = withContext(Dispatchers.Default) { repository.cachedBrowsable(folder, order) }
                    if (late != null && settled.compareAndSet(expect = false, update = true)) {
                        send(DriveListingResult.Content(late, true))
                    }
                }
            }
        } else null
        val result = try {
            repository.listBrowsable(folder, order)
        } finally {
            settled.value = true
            watcher?.cancel()
        }
        watcher?.cancelAndJoin()
        val files = result.getOrNull()
        send(when {
            files == null -> DriveListingResult.Failure(requireNotNull(result.exceptionOrNull()))
            files.isEmpty() && folder.isNotEmpty() && repository.isFolderGone(folder) -> DriveListingResult.Gone
            else -> DriveListingResult.Content(files, false)
        })
    }
}
