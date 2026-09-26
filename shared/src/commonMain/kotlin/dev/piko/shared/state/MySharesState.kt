package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.ShareSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 我的分享：自己创建的分享链接，新的在前，与官方客户端共用。文件被删的分享仍列着，状态为失效。
 *
 * 取消分享照星标页的做法，先从列表里拿掉再请求，失败时放回。
 */
class MySharesState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    var shares by mutableStateOf<List<ShareSummary>>(emptyList())
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

    // 刷新时丢掉还在路上的下一页，免得旧页接在新列表后面
    private var pageJob: Job? = null

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        pageJob?.cancel()
        isLoadingMore = false
        pageJob = scope.launch {
            driveRepo.myShares()
                .logFailure(TAG, "读取我的分享失败")
                .onSuccess { page ->
                    shares = page.shares
                    nextPageToken = page.nextPageToken
                    loadError = null
                }
                .onFailure {
                    loadError = "读取分享失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    /** 列表滚到底时调用。 */
    fun loadMore() {
        if (!hasMore || isLoadingMore || isLoading || isRefreshing) return
        isLoadingMore = true
        val token = nextPageToken
        pageJob = scope.launch {
            driveRepo.myShares(token)
                .logFailure(TAG, "读取我的分享的下一页失败")
                .onSuccess { page ->
                    val known = shares.mapTo(HashSet()) { it.shareId }
                    shares = shares + page.shares.filterNot { it.shareId in known }
                    nextPageToken = page.nextPageToken
                }
                .onFailure { _messages.tryEmit("加载失败") }
            isLoadingMore = false
        }
    }

    fun cancel(share: ShareSummary) {
        val before = shares
        shares = shares.filterNot { it.shareId == share.shareId }
        scope.launch {
            driveRepo.cancelShares(listOf(share.shareId))
                .logFailure(TAG, "取消分享失败")
                .onSuccess { _messages.tryEmit("已取消分享") }
                .onFailure {
                    shares = before
                    _messages.tryEmit("取消分享失败")
                }
        }
    }

    private companion object {
        const val TAG = "Share"
    }
}
