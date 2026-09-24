package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import io.github.nihildigit.pikpak.DriveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 播放历史页。数据是 PikPak 服务端的播放事件，与官方客户端共用，按最近播放倒序，一页至多 100 条，
 * 滚到底再取下一页。每个文件只有一条，再次播放会把它顶到最前。
 *
 * 文件已被删除的记录服务端照样返回，只是不再内嵌文件；列表里保留它，让用户自己删掉。
 */
class PlayHistoryScreenState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    var events by mutableStateOf<List<DriveEvent>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set

    /** 上次加载失败的原因，成功后清空。列表此时仍是旧数据。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    private var nextPageToken = ""
    val hasMore: Boolean get() = nextPageToken.isNotEmpty()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pageJob: Job? = null

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        pageJob?.cancel()
        pageJob = scope.launch {
            driveRepo.playHistory()
                .onSuccess { page ->
                    events = page.events
                    nextPageToken = page.nextPageToken
                    loadError = null
                }
                .onFailure {
                    loadError = it.message ?: "读取播放历史失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    /** 滚到接近底部时调用；已在加载或没有下一页时不做事。 */
    fun loadMore() {
        if (!hasMore || isLoadingMore || isLoading) return
        isLoadingMore = true
        val token = nextPageToken
        pageJob = scope.launch {
            driveRepo.playHistory(token)
                .onSuccess { page ->
                    // 两页之间有新的播放时，那条会被顶到第一页，下一页里可能再出现一次旧位置的它
                    val known = events.mapTo(HashSet()) { it.id }
                    events = events + page.events.filterNot { it.id in known }
                    nextPageToken = page.nextPageToken
                }
                .onFailure { _messages.tryEmit("加载更多失败") }
            isLoadingMore = false
        }
    }

    fun delete(event: DriveEvent) {
        val before = events
        events = events.filterNot { it.id == event.id }
        scope.launch {
            driveRepo.deletePlayEvents(listOf(event.id)).onFailure {
                events = before
                _messages.tryEmit("删除失败")
            }
        }
    }

    fun clearAll() {
        val before = events
        events = emptyList()
        nextPageToken = ""
        scope.launch {
            driveRepo.clearPlayHistory()
                .onSuccess { _messages.tryEmit("已清空播放历史") }
                .onFailure {
                    events = before
                    _messages.tryEmit("清空失败")
                }
        }
    }
}
