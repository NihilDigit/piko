package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import io.github.nihildigit.pikpak.FileStat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
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

    // 是 State：界面据它判断还能不能接着取，接完一页若仍停在底部就要再取一页
    private var nextPageToken by mutableStateOf("")
    val hasMore: Boolean get() = nextPageToken.isNotEmpty()

    /**
     * 下一页没取到。这时不再随滚动自动取，改由列表底部的「重试」接着取：人停在底部时滚动位置不变，
     * 自动取的话要么再也不触发，要么失败一次紧跟着再发一次。刷新后清掉。
     */
    var loadMoreFailed by mutableStateOf(false)
        private set

    /** 列表滚到底时是否该自动取下一页。 */
    val canAutoLoadMore: Boolean
        get() = hasMore && !loadMoreFailed && !isLoadingMore && !isLoading && !isRefreshing

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // 刷新时丢掉还在路上的下一页，免得旧页接在新列表后面
    private var pageJob: Job? = null

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        pageJob?.cancel()
        isLoadingMore = false
        loadMoreFailed = false
        pageJob = scope.launch {
            driveRepo.myShares()
                .logFailure(TAG, "读取我的分享失败")
                .onSuccess { page ->
                    shares = withoutCancelling(page.shares)
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

    /** 列表滚到底时调用，也是底部「重试」的动作。 */
    fun loadMore() {
        if (!hasMore || isLoadingMore || isLoading || isRefreshing) return
        isLoadingMore = true
        loadMoreFailed = false
        val token = nextPageToken
        pageJob = scope.launch {
            driveRepo.myShares(token)
                .logFailure(TAG, "读取我的分享的下一页失败")
                .onSuccess { page ->
                    val known = shares.mapTo(HashSet()) { it.shareId }
                    shares = shares + withoutCancelling(page.shares).filterNot { it.shareId in known }
                    nextPageToken = page.nextPageToken
                }
                .onFailure { loadMoreFailed = true }
            isLoadingMore = false
        }
    }

    /**
     * 每个分享打开后的第一项，按分享 ID；打不开的记为 null，不再重试。分享列表只给类型不给缩略图，
     * 多项的分享连源文件的 ID 也不给，这两样都要打开分享才拿得到。每张卡片进入视野时取一次。
     */
    val previews = mutableStateMapOf<String, FileStat?>()

    suspend fun loadPreview(share: ShareSummary) {
        if (!share.isOk || share.shareId in previews) return
        previews[share.shareId] = driveRepo.shareInfo(share.shareId, share.passCode)
            .logFailure(TAG, "打开分享取预览失败")
            .getOrNull()?.files?.firstOrNull()
    }

    /**
     * 分享在网盘里的源文件。只有一项的分享列表里直接给了 file_id；多项的取打开后第一项的 ID，
     * 它是否就是源文件的 ID 没有实测过，定位不到时由调用方提示。
     */
    fun sourceId(share: ShareSummary): String? = share.fileId.ifEmpty { previews[share.shareId]?.id.orEmpty() }.ifEmpty { null }

    /** 选中的几个分享，按分享 ID。框选（拖动拉框）与多选态下的点选进来，页头据此换成批量取消。 */
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 框选，照网盘页的 selectBoxed：选中的换成 [base] 加上框住的 [boxed]，拖动时每动一下调一次。 */
    fun selectBoxed(base: Set<String>, boxed: Collection<String>) {
        selectedIds = base + boxed
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clearSelection() {
        selectedIds = emptySet()
    }

    /** 一次取消几个分享，照单个的做法：先从列表里拿掉再请求，失败时放回。 */
    fun cancelSelected() {
        val ids = selectedIds
        if (ids.isEmpty()) return
        selectedIds = emptySet()
        cancelShares(ids, done = "已取消 ${ids.size} 个分享", logMessage = "批量取消分享失败")
    }

    fun cancel(share: ShareSummary) {
        cancelShares(setOf(share.shareId), done = "已取消分享", logMessage = "取消分享失败")
    }

    /**
     * 正在取消与已取消的分享 ID。取消请求发出之前取的列表（刷新、下一页）回来时还带着它们，
     * 不滤掉的话刚拿掉的一项又会冒出来。取消成功后也留着：那份旧列表可能在取消返回之后才到。
     * 分享 ID 不会复用，取消了的不会再合法地出现，留到页面离开即可，不必判断哪次请求发在取消之前。
     */
    private val cancellingIds = HashSet<String>()
    private val cancelledIds = HashSet<String>()

    private fun withoutCancelling(page: List<ShareSummary>) =
        page.filterNot { it.shareId in cancellingIds || it.shareId in cancelledIds }

    /**
     * 失败时只放回这一次拿掉的几项，不整份换回快照：几次取消先后进行、或期间刷新过时，
     * 快照里还有别的取消已成功的条目，也可能是刷新前的旧数据。
     */
    private fun cancelShares(ids: Set<String>, done: String, logMessage: String) {
        val before = shares
        cancellingIds += ids
        shares = shares.filterNot { it.shareId in ids }
        scope.launch {
            val result = driveRepo.cancelShares(ids.toList()).logFailure(TAG, "$logMessage（${ids.size} 个）")
            cancellingIds -= ids
            result
                .onSuccess {
                    PikoLog.i(TAG, "已取消 ${ids.size} 个分享")
                    cancelledIds += ids
                    _messages.tryEmit(done)
                }
                .onFailure {
                    shares = reinsertRemoved(shares, before, ids) { it.shareId }
                    _messages.tryEmit("取消分享失败")
                }
        }
    }

    private companion object {
        const val TAG = "Share"
    }
}
