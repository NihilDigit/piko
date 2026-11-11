package dev.piko.shared.state


import dev.piko.shared.i18n.tr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.log.PikoLog
import dev.piko.shared.data.PikoDriveRepository
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

internal class DriveSearchSession(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    var isGlobalSearching by mutableStateOf(false)
        private set
    var isGlobalSearchActive by mutableStateOf(false)
        private set
    val globalSearchHits = mutableStateListOf<SearchHit>()
    private var globalSearchJob: Job? = null
    fun startGlobalSearch(searchQuery: String) {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) return
        globalSearchJob?.cancel()
        globalSearchHits.clear()
        isGlobalSearchActive = true
        isGlobalSearching = true
        globalSearchJob = scope.launch {
            val buffer = ArrayList<SearchHit>(64)
            var lastFlush = TimeSource.Monotonic.markNow()
            fun flushBuffer() {
                if (buffer.isNotEmpty()) {
                    globalSearchHits.addAll(buffer)
                    buffer.clear()
                    lastFlush = TimeSource.Monotonic.markNow()
                }
            }
            try {
                driveRepo.searchRecursive(keyword).collect { hit ->
                    buffer.add(hit)
                    if (buffer.size >= 64 || lastFlush.elapsedNow().inWholeMilliseconds >= 60L) {
                        flushBuffer()
                    }
                }
                flushBuffer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                flushBuffer()
                PikoLog.w(TAG, "Global drive search failed (queryLength=${keyword.length}, hits=${globalSearchHits.size})", e)
                notify(tr("全盘搜索失败", "Drive search failed", "ドライブ全体の検索に失敗しました", "드라이브 전체 검색에 실패했습니다", "全碟搜尋失敗"))
            } finally {
                isGlobalSearching = false
            }
        }
    }

    fun cancelGlobalSearch() {
        globalSearchJob?.cancel()
    }

    fun stopGlobalSearch() {
        globalSearchJob?.cancel()
        globalSearchJob = null
        isGlobalSearching = false
        isGlobalSearchActive = false
        globalSearchHits.clear()
    }

    private companion object {
        const val TAG = "DriveSearch"
    }
}
