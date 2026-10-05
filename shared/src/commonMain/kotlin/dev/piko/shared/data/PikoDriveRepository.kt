package dev.piko.shared.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Job
import kotlinx.io.Buffer
import io.ktor.utils.io.toByteArray
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.upload
import io.github.nihildigit.pikpak.PikPakHash
import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.EventPage
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.LeaseBudget
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.sampleCid
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.CreatedShare
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareListPage
import io.github.nihildigit.pikpak.createShare
import io.github.nihildigit.pikpak.deleteShares
import io.github.nihildigit.pikpak.listMyShares
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.TransferQuota
import io.github.nihildigit.pikpak.batchCopy
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.clearEvents
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.deleteEvents
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getShareInfo
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listShareFiles
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.getTransferQuota
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.listPlayHistory
import io.github.nihildigit.pikpak.listEvents
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.searchFiles
import io.github.nihildigit.pikpak.searchFilesRecursive
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.unstarFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

/** 列表的滚动位置：首个可见项的下标与它已滚出顶端的像素数。 */
data class ScrollAnchor(val index: Int, val offset: Int)

/** 目录的递归统计。[progress] 区分仍在统计、统计完、因到达上限而中止三种。 */
data class FolderUsage(val fileCount: Int, val bytes: Long, val progress: Progress) {
    enum class Progress { COUNTING, COMPLETE, TRUNCATED }
}

private const val FOLDER_USAGE_CONCURRENCY = 4

open class PikoDriveRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
    private val cacheStore: PikoCacheStore? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    // 与进程同寿：仓库本身就是进程级的
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 读写发生在 Dispatchers.Default 的多个线程上，普通 HashMap 并发写会丢项甚至破坏结构。
    // commonMain 没有 ConcurrentHashMap，借 MutableStateFlow.update 的 CAS 做原子替换。
    private val folderMeaninglessCacheFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    protected val folderMeaninglessCache: Map<String, Boolean> get() = folderMeaninglessCacheFlow.value
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    // 带上取的是哪个账号：取回来之前可能已经换了号，按 quotaFlow 的当下值记会记到新账号头上
    private val _quotaUpdates = MutableSharedFlow<Pair<String, QuotaResponse>>(extraBufferCapacity = 4)
    val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>> = _quotaUpdates.asSharedFlow()
    // 不落盘：与存储配额不同，官方也没有把它算进「上次已知值」这类离线展示的必要
    private val _transferQuotaFlow = MutableStateFlow<TransferQuota?>(null)
    val transferQuotaFlow: StateFlow<TransferQuota?> = _transferQuotaFlow.asStateFlow()

    /**
     * 当前账号是不是免费账号，未取到时为 null。取自流量额度的 vip_status，登录与换号时各取一次。
     * 免费账号的保存与离线规则不同，见 SDK 的 FreeAccountProbeTest 实测结论。
     */
    val isFreeAccountFlow: StateFlow<Boolean?> =
        _transferQuotaFlow.map { it?.account?.isPremium?.not() }.stateIn(backgroundScope, SharingStarted.Eagerly, null)
    private val _folderStackFlow = MutableStateFlow(listOf(ROOT_BREADCRUMB))
    val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = _folderStackFlow.asStateFlow()

    /**
     * 浏览历史，与资源管理器、Finder 的后退与前进同义：记的是去过的位置（整条路径），不是层级。
     * 「上一级」与它是两回事：从信息流或星标跳到很深的目录后，上一级只会一层层往上走，后退才回到跳之前在看的地方。
     * 每次改动路径栈都记一笔，只有启动时恢复上次的位置不记。不落盘，进程内有效。
     */
    private val _historyFlow = MutableStateFlow(FolderHistory())
    val historyFlow: StateFlow<FolderHistory> = _historyFlow.asStateFlow()

    /**
     * 网盘页的标签，各有自己的路径栈与浏览历史。活动的那个就是 [folderStackFlow] 与 [historyFlow]：
     * 网盘页、定位、快捷访问、信息流都只认这两个，切标签时把它们换成目标标签存着的那一份，别处不用知道有标签。
     * 这里存的活动标签那一项是切走之前的旧值，读标签用 [tabsFlow]。不落盘，进程内有效。
     */
    private val _tabs = MutableStateFlow(listOf(DriveTab(FIRST_TAB_ID, listOf(ROOT_BREADCRUMB))))
    private val _activeTabId = MutableStateFlow(FIRST_TAB_ID)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()
    private var nextTabId = FIRST_TAB_ID + 1

    private val _tabsFlow = MutableStateFlow(_tabs.value)

    /** 全部标签，按显示的顺序；活动的那个带着眼下的栈与历史。每次换栈、开关标签后当场更新，读到的不会落后一步。 */
    val tabsFlow: StateFlow<List<DriveTab>> = _tabsFlow.asStateFlow()

    private fun publishTabs() {
        val active = _activeTabId.value
        _tabsFlow.value = _tabs.value.map { if (it.id == active) it.copy(stack = _folderStackFlow.value, history = _historyFlow.value) else it }
        saveTabs()
    }

    // 标签按账号存进缓存目录，重启后接着用。只存各自停在哪、哪个是活动的，历史不存
    private var tabsSave: Job? = null

    private fun saveTabs() {
        val store = cacheStore ?: return
        val account = clientManager.currentClient.value?.account ?: return
        val tabs = _tabsFlow.value
        val saved = SavedTabs(
            tabs = tabs.map { tab -> tab.stack.outsideArchives().map { SavedCrumb(it.id, it.name) } },
            active = tabs.indexOfFirst { it.id == _activeTabId.value }.coerceAtLeast(0),
        )
        tabsSave?.cancel()
        tabsSave = backgroundScope.launch {
            delay(TABS_SAVE_DELAY_MS)
            runCatching { store.write(tabsKey(account), tabsJson.encodeToString(SavedTabs.serializer(), saved)) }
        }
    }

    /**
     * 启动或换号后恢复这个账号上次的标签与位置，只在还停在初始状态（一个标签、在根目录）时做。没有存过的返回 false。
     * 恢复不记历史，与 [restoreFolderStack] 相同。
     */
    suspend fun restoreTabs(): Boolean {
        val store = cacheStore ?: return false
        val account = clientManager.currentClient.value?.account ?: return false
        enterAccount(account)
        if (_tabs.value.size > 1 || _folderStackFlow.value.size > 1) return false
        val saved = runCatching { store.read(tabsKey(account))?.let { tabsJson.decodeFromString(SavedTabs.serializer(), it) } }.getOrNull()
            ?: return false
        val stacks = saved.tabs.map { stack -> stack.map { PikoPathBreadcrumb(it.id, it.name) } }.filter { it.isNotEmpty() }
        if (stacks.isEmpty()) return false
        val tabs = stacks.map { DriveTab(nextTabId++, it) }
        val active = tabs[saved.active.coerceIn(0, tabs.lastIndex)]
        _tabs.value = tabs
        _activeTabId.value = active.id
        _historyFlow.value = FolderHistory()
        _folderStackFlow.value = active.stack
        forgetFoldersOutsideStack()
        publishTabs()
        return true
    }

    // 眼前的路径、标签与缓存属于哪个账号，见 enterAccount
    private val navigationAccount = MutableStateFlow<String?>(null)

    /**
     * 换号后把网盘页的位置、标签、历史、剪贴板、撤销记录与列表缓存换成新账号的一份：全回到根目录，
     * 由网盘页接着恢复新账号上次的标签。同一账号再调不做事，所以账号变化的收集者与网盘页都调它，谁先到谁做。
     * 不写回标签：写回会拿根目录盖掉新账号存着的那一份，恢复就没了。
     */
    fun enterAccount(account: String?) {
        while (true) {
            val previous = navigationAccount.value
            if (previous == account) return
            if (!navigationAccount.compareAndSet(previous, account)) continue
            if (previous == null) return
            break
        }
        val root = DriveTab(nextTabId++, listOf(ROOT_BREADCRUMB))
        _tabs.value = listOf(root)
        _activeTabId.value = root.id
        _historyFlow.value = FolderHistory()
        _folderStackFlow.value = root.stack
        _tabsFlow.value = _tabs.value
        _clipboard.value = null
        listingCache.value = emptyMap()
        recentListings.value = emptyMap()
        scrollAnchors.value = emptyMap()
        subfolderCache.value = emptyMap()
        folderMeaninglessCacheFlow.value = emptyMap()
        _folderEmptiness.value = emptyMap()
        changes.clear()
    }

    private fun tabsKey(account: String) ="drive-tabs-" + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"

    /** 在活动标签后面开一个新标签，停在 [stack]。[activate] 为 false 是在后台开（中键点文件夹）。 */
    fun openTab(stack: List<PikoPathBreadcrumb>, activate: Boolean = true): Long {
        val tab = DriveTab(nextTabId++, stack.ifEmpty { listOf(ROOT_BREADCRUMB) })
        _tabs.update { tabs ->
            val at = tabs.indexOfFirst { it.id == _activeTabId.value }
            tabs.toMutableList().apply { add(at + 1, tab) }
        }
        if (activate) switchTab(tab.id) else publishTabs()
        return tab.id
    }

    fun switchTab(id: Long) {
        val active = _activeTabId.value
        if (id == active) return
        val target = _tabs.value.firstOrNull { it.id == id } ?: return
        // 先把眼下的位置存回活动标签，再换成目标标签的
        _tabs.update { tabs -> tabs.map { if (it.id == active) it.copy(stack = _folderStackFlow.value, history = _historyFlow.value) else it } }
        _activeTabId.value = id
        _historyFlow.value = target.history
        _folderStackFlow.value = target.stack
        forgetFoldersOutsideStack()
        publishTabs()
    }

    /** 关掉一个标签。关的是活动标签时先切到右边那个，没有就左边。只剩一个时不关。 */
    fun closeTab(id: Long) {
        val tabs = _tabs.value
        if (tabs.size <= 1) return
        val index = tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        if (id == _activeTabId.value) switchTab((tabs.getOrNull(index + 1) ?: tabs[index - 1]).id)
        _tabs.update { current -> current.filterNot { it.id == id } }
        forgetFoldersOutsideStack()
        publishTabs()
    }

    /** 眼前的位置：活动标签、它的路径栈与浏览历史。交给 [returnTo] 就能整个回到这里。 */
    class DriveLocation internal constructor(
        internal val tabId: Long,
        internal val stack: List<PikoPathBreadcrumb>,
        internal val history: FolderHistory,
    )

    fun currentLocation() = DriveLocation(_activeTabId.value, _folderStackFlow.value, _historyFlow.value)

    /**
     * 回到 [location]，连历史一起：之后的浏览整段丢掉，后退与前进都不留它的痕迹，像是从没离开过。
     * 用在信息流的「继续刷」：从信息流跳去看一个文件，看完回到刷之前在的地方。
     * 那个标签期间被关掉了，就在活动标签后面重新开一个。
     */
    fun returnTo(location: DriveLocation) {
        val tabId = if (_tabs.value.any { it.id == location.tabId }) location.tabId else openTab(location.stack)
        switchTab(tabId)
        _historyFlow.value = location.history
        _folderStackFlow.value = location.stack
        forgetFoldersOutsideStack()
        publishTabs()
    }

    /** 按显示顺序切到后一个（[step] 为 1）或前一个（-1），两头相接。 */
    fun cycleTab(step: Int) {
        val tabs = _tabs.value
        if (tabs.size <= 1) return
        val index = tabs.indexOfFirst { it.id == _activeTabId.value }
        switchTab(tabs[(index + step).mod(tabs.size)].id)
    }

    private val recentFolders = RecentFolders(cacheStore, backgroundScope)

    /** 最近去过的文件夹（整条路径），新的在前，命令面板用，见 [RecentFolders]。 */
    val recentFoldersFlow: StateFlow<List<List<PikoPathBreadcrumb>>> get() = recentFolders.flow

    private val _clipboard = MutableStateFlow<DriveClipboard?>(null)

    /** 剪切或复制、等着粘贴的条目。放在仓库而不是网盘页里：换标签页、进「我的」再回来，都还在。 */
    val clipboardFlow: StateFlow<DriveClipboard?> = _clipboard.asStateFlow()

    fun setClipboard(clip: DriveClipboard?) {
        _clipboard.value = clip
    }

    private val pinnedFolders = PinnedFolders(preferences, backgroundScope)

    /** 固定到快速访问的文件夹，按固定的先后排，见 [PinnedFolders]。 */
    val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>> get() = pinnedFolders.flow

    fun pinFolder(folder: PikoPathBreadcrumb) = pinnedFolders.pin(folder)

    fun unpinFolder(folderId: String) = pinnedFolders.unpin(folderId)

    /** 做过的改动，能撤销的记在这里，见 [DriveChangeJournal]。 */
    val changes = DriveChangeJournal(this, backgroundScope)

    /** 把栈换成 [next]，换了才把原来的位置记进后退、清掉前进。 */
    private fun moveTo(next: List<PikoPathBreadcrumb>) {
        val previous = _folderStackFlow.value
        if (next == previous) return
        _folderStackFlow.value = next
        _historyFlow.update { it.visited(previous) }
        stackChanged()
    }

    fun goBack(): Boolean {
        val history = _historyFlow.value
        val target = history.back.lastOrNull() ?: return false
        _historyFlow.value = FolderHistory(back = history.back.dropLast(1), forward = history.forward + listOf(_folderStackFlow.value))
        _folderStackFlow.value = target
        stackChanged()
        return true
    }

    fun goForward(): Boolean {
        val history = _historyFlow.value
        val target = history.forward.lastOrNull() ?: return false
        _historyFlow.value = FolderHistory(back = history.back + listOf(_folderStackFlow.value), forward = history.forward.dropLast(1))
        _folderStackFlow.value = target
        stackChanged()
        return true
    }

    // 回收站恢复这类改动发生在网盘界面之外，界面不会重建，也就不会重新拉取。
    // 用事件流而非 StateFlow：订阅方只需被动收到「该刷新了」，不需要初值，也不该在重组时重放。
    private val _refreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshEvents: SharedFlow<Unit> = _refreshEvents.asSharedFlow()

    fun requestRefresh() {
        _refreshEvents.tryEmit(Unit)
    }

    // 外部链接要求打开网盘页（官方「在 App 中打开」链接）。应用可能正冷启动、还没登录，
    // 所以是待办而不是事件，由主界面组合时取走。刷新只对已在组合里的网盘页有用；
    // 不在的那一页新建时本就会从网络重新拉取
    private val _openDriveRequested = MutableStateFlow(false)
    val openDriveRequested: StateFlow<Boolean> = _openDriveRequested.asStateFlow()

    fun requestOpenDrive() {
        _openDriveRequested.value = true
        requestRefresh()
    }

    fun consumeOpenDriveRequest() {
        _openDriveRequested.value = false
    }

    // 界面外发起的「跳到这个文件并标出它」。网盘页此时可能不在组合里，所以存成
    // 一次性的待办，由下一个 DriveScreenState 在初始化时取走
    private val pendingHighlight = MutableStateFlow<Set<String>>(emptySet())

    /** 待高亮的条目。网盘页开着时也会有新请求进来，所以给一个可订阅的流，取用仍走 [takePendingHighlight]。 */
    val pendingHighlights: StateFlow<Set<String>> = pendingHighlight.asStateFlow()

    fun requestHighlight(ids: Set<String>) {
        pendingHighlight.value = ids
    }

    fun takePendingHighlight(): Set<String> = pendingHighlight.getAndUpdate { emptySet() }

    /**
     * 文件所在目录的完整路径栈（含根）。服务端没有按 id 取路径的接口，只能沿
     * parent_id 逐级上溯，每级一次请求。
     */
    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            var parentId = client.getFile(fileId).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB) + chain
                val folder = client.getFile(parentId)
                chain.addFirst(PikoPathBreadcrumb(folder.id, folder.name))
                parentId = folder.parentId
            }
            error("目录层级过深")
        }
    }

    /**
     * 地址栏输入的路径：从根起按名字逐层找文件夹，返回完整路径栈（含根）。
     * 服务端没有按路径取文件夹的接口，只能每层列一次目录。与眼前路径栈重合的前缀直接沿用，
     * 从当前位置往下走一两层时只多列那一两层。
     * 名字先比完全相同，没有再忽略大小写；同名的文件夹 PikPak 允许有几个，取第一个。
     */
    suspend fun resolveFolderPath(names: List<String>): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            // 人在库里时路径栈的第一级不是根，地址栏里写的路径仍从根起
            val current = folderStackFlow.value.takeIf { it.library == null } ?: listOf(ROOT_BREADCRUMB)
            val shared = current.drop(1).zip(names).takeWhile { (crumb, name) -> crumb.name == name }.size
            val stack = current.take(1 + shared).toMutableList()
            for (name in names.drop(shared)) {
                val parentId = stack.last().id
                // 记下的表可能早于刚建、刚移进来的文件夹，找不到时重列一次再下结论
                stack += findFolder(subfolders(parentId).getOrThrow(), name)
                    ?: findFolder(subfolders(parentId, fresh = true).getOrThrow(), name)
                    ?: throw FolderNotFoundException(name)
            }
            stack
        }
    }

    private fun findFolder(folders: List<PikoPathBreadcrumb>, name: String) =
        folders.firstOrNull { it.name == name } ?: folders.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** [resolveFolderPath] 在某一层找不到 [name]。 */
    class FolderNotFoundException(val name: String) : Exception("找不到文件夹「$name」")

    /*
     * 地址栏补全与路径解析用的子文件夹表，按文件夹 ID 记。补全每敲一个字都要问「这一层有哪些文件夹」，
     * 列目录是一次请求，所以记下来，至多 SUBFOLDER_CACHE_SIZE 层，过 SUBFOLDER_TTL 重取。
     * listingCache 只留路径栈上的目录，补全常常走到栈外，不能只靠它。
     * 按插入先后淘汰：Map 的 + 保留插入顺序，命中时挪到末尾，最久没用的排在最前。
     */
    private class SubfolderEntry(val folders: List<PikoPathBreadcrumb>, val listedAt: TimeSource.Monotonic.ValueTimeMark)

    private val subfolderCache = MutableStateFlow<Map<String, SubfolderEntry>>(emptyMap())

    /**
     * [folderId] 下的文件夹，按名字自然排序，根目录下不含 `.piko`。先看路径栈上已列出的目录，再看记下的，
     * 都没有或 [fresh] 时才请求。
     */
    suspend fun subfolders(folderId: String, fresh: Boolean = false): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (!fresh) {
                listingCache.value[folderId]?.let { return@runSuspendCatching foldersIn(folderId, it) }
                subfolderCache.value[folderId]?.takeIf { it.listedAt.elapsedNow() < SUBFOLDER_TTL }?.let { entry ->
                    subfolderCache.update { (it - folderId) + (folderId to entry) }
                    return@runSuspendCatching entry.folders
                }
            }
            val folders = foldersIn(folderId, client.listFiles(folderId))
            val entry = SubfolderEntry(folders, TimeSource.Monotonic.markNow())
            subfolderCache.update { cache ->
                val next = (cache - folderId) + (folderId to entry)
                if (next.size <= SUBFOLDER_CACHE_SIZE) next else next.entries.drop(next.size - SUBFOLDER_CACHE_SIZE).associate { it.toPair() }
            }
            folders
        }
    }

    /**
     * 目录图（见 FolderMap）里的一层：文件夹与文件，文件夹在前、各自按名字自然排序。
     * 路径栈上的目录直接用列表缓存。不进 [subfolders] 的表：那张表只记文件夹，补全也在用。
     */
    suspend fun folderMapLevel(folderId: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            // 与网盘页共用缓存：路径栈上的由网盘页一直在刷新，拿来就用；最近列过的不超过 RECENT_LISTING_FRESH 也直接用。
            // 再展开、换位置后重新展开到眼前的文件夹时，各层因此是当场出来的，不再每层一个请求
            val recent = recentListings.value[folderId]?.takeIf { it.listedAt.elapsedNow() < RECENT_LISTING_FRESH }?.files
            val files = listingCache.value[folderId] ?: recent ?: listAllFiles(folderId).getOrThrow()
            files.filter { !it.trashed && !isPikoInternalFolder(it, folderId) && !VaultStore.looksLikeManifest(it) }
                .sortedWith(compareBy<FileStat> { !it.isFolder }.thenBy(NaturalOrder) { it.name })
        }
    }

    private fun foldersIn(parentId: String, files: List<FileStat>) = files.asSequence()
        .filter { it.isFolder && !it.trashed && !isPikoInternalFolder(it, parentId) }
        .sortedWith(compareBy(NaturalOrder) { it.name })
        .map { PikoPathBreadcrumb(it.id, it.name) }
        .toList()

    // 建、改名、移走或删掉文件夹之后，记下的哪一层变了说不清，整张表丢掉，下次补全重列。
    // 最近列过的目录同理，目录图不然会照旧显示改动前的结构
    private fun forgetSubfolders() {
        subfolderCache.value = emptyMap()
        recentListings.value = emptyMap()
    }

    /** 从最近去过的文件夹里删掉一条，地址栏的历史用。 */
    fun removeRecentFolder(folderId: String) = recentFolders.remove(folderId)

    /*
     * 路径栈上每一级的列表与滚动位置。放在仓库层而不是界面状态里，是因为网盘页切走
     * 再切回时界面状态会整个重建，而这两样要随路径栈一起留下：返回上级时先显示缓存、
     * 回到原来的位置，再在后台刷新。出栈的目录一并丢弃，所以重新进入某个目录总是从
     * 顶部开始、重新取数据；两张表的大小也就以路径深度为界。
     */
    private val listingCache = MutableStateFlow<Map<String, List<FileStat>>>(emptyMap())
    private val scrollAnchors = MutableStateFlow<Map<String, ScrollAnchor>>(emptyMap())

    private class RecentListing(val files: List<FileStat>, val listedAt: TimeSource.Monotonic.ValueTimeMark)

    /**
     * 不在路径栈上、但最近列过的目录，按最近列出的先后至多留 [RECENT_LISTINGS] 个，不跨进程。目录图展开的各层、
     * 目录选择器列过的、刚出栈的都在这里：网盘页进到这样的目录时先显示它再刷新，与返回上级一样快；
     * 目录图再展开时不重取。[listingCache] 照旧只管路径栈，两张表分开是因为那张还要随出栈丢弃、以深度为界。
     */
    private val recentListings = MutableStateFlow<Map<String, RecentListing>>(emptyMap())

    private fun rememberListing(folderId: String, files: List<FileStat>) {
        recentListings.update { cache ->
            val next = (cache - folderId) + (folderId to RecentListing(files, TimeSource.Monotonic.markNow()))
            if (next.size <= RECENT_LISTINGS) next else next.entries.drop(next.size - RECENT_LISTINGS).associate { it.toPair() }
        }
    }

    private fun cachedListing(folderId: String): List<FileStat>? = listingCache.value[folderId] ?: recentListings.value[folderId]?.files

    /** 某个目录的缓存列表，按 [sortOrder] 排好：路径栈上的，或最近列过的。没有时为 null。 */
    fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        cachedListing(folderId)?.let { sortFiles(it, sortOrder, folderId) }

    /** 各目录的归档清单，见 [VaultStore]。 */
    open val vault = VaultStore(this)

    /**
     * 网盘页看到的列表：清单文件不列，换成其中的归档条目。清单读不出来时照样列出真实文件，
     * 只少了归档的那几行，不让整个目录打不开。
     */
    suspend fun listBrowsable(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>> =
        listAllFiles(parentId, sortOrder).map { listing ->
            val entries = vault.read(parentId, listing).logFailure(TAG, "读取归档清单失败")
                .onSuccess { vaultEntriesKnown(parentId, it) }
                .getOrDefault(emptyList())
            withVaulted(parentId, listing, entries, sortOrder)
        }

    /** [cachedFiles] 的 [listBrowsable] 版本。清单还没读过时先不列归档条目，等随后的刷新补上。 */
    fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        cachedListing(folderId)?.let { listing ->
            withVaulted(folderId, listing, vault.cached(folderId, listing).orEmpty(), sortOrder)
        }

    private fun withVaulted(
        folderId: String,
        listing: List<FileStat>,
        entries: List<VaultEntry>,
        sortOrder: PikoFileSortOrder,
    ): List<FileStat> {
        val visible = listing.filterNot { isPikoInternalFolder(it, folderId) }
        if (entries.isEmpty() && visible.none(VaultStore::looksLikeManifest)) return sortFiles(visible, sortOrder, folderId)
        val real = visible.filterNot(VaultStore::looksLikeManifest)
        val restoredMetadata = entries.filterNot { it.isArchived }
        val enriched = real.map { file -> restoredMetadata.firstOrNull { it.matches(file) }?.enrich(file) ?: file }
        return sortFiles(enriched + entries.filter { it.isArchived }.map { it.toFileStat(folderId) }, sortOrder, folderId)
    }

    /**
     * 文件夹里的文件，只供文件夹行解析作品名（describeFolder）。列表接口只给文件夹的缩略图，
     * 不给其中的文件名，所以来源只有两处：列过的目录，以及 [fetchChildContents] 补取的一页。
     * 与 [listingCache] 不同，不随路径栈出栈丢弃：返回上级时正要用它描述刚离开的目录。跨进程保留，见 [FolderContentMemory]。
     */
    private val childContents = FolderContentMemory(cacheStore, backgroundScope)
    private val childNameFetches = Semaphore(CHILD_NAME_CONCURRENCY)

    /** 整棵归档过的文件夹，见 [VaultTrees]。跨设备的同步在 VaultTreeSync。 */
    val vaultTrees = VaultTrees(cacheStore, backgroundScope)

    init {
        // 记下的目录内容按账号存：换号时换一份，退出登录只清内存
        backgroundScope.launch {
            clientManager.currentClient.collect {
                childContents.switchAccount(it?.account)
                recentFolders.switchAccount(it?.account)
                vaultTrees.switchAccount(it?.account)
            }
        }
        // 额度与账号类型属于账号：换号时先清掉，否则新账号在取到之前沿用上一个账号的，
        // 免费与会员的规则会用反。断线重连换的是同一账号的新 client，不清
        backgroundScope.launch {
            var account: String? = null
            clientManager.currentClient.collectLatest { client ->
                if (client?.account != account) {
                    account = client?.account
                    _quotaFlow.value = null
                    _transferQuotaFlow.value = null
                    // 退出到登录页不算换号：同一账号登回来时位置还在，别的账号登进来时再换
                    if (client != null) enterAccount(client.account)
                }
                if (client != null && _transferQuotaFlow.value == null) {
                    getTransferQuota().logFailure(TAG, "取账号类型失败")
                }
                // 所有账号打开归档条目都受剩余空间约束，同时借出的总量不能超过剩余，
                // 否则信息流一批并行核对时后面的秒传直接失败。留一成余量给清单这类小文件
                if (client != null && client.leaseBudget == null) {
                    getQuota().getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.let { quota ->
                        client.leaseBudget = LeaseBudget(quota.remainingBytes.coerceAtLeast(0) * 9 / 10)
                    }
                }
            }
        }
    }

    /** 从磁盘载入完成一次就加一，文件夹行据此重新描述。 */
    val childContentLoads: StateFlow<Int> get() = childContents.loads

    fun knownChildContents(folderId: String): List<ChildFile>? = childContents.get(folderId)

    /** [complete] 为假时 [files] 只是一页：其中没有清单不说明文件夹里没有，只加标记、不去掉。 */
    private fun rememberChildContents(folderId: String, files: List<FileStat>, complete: Boolean = true) {
        // 清单文件不是用户的文件，不拿来解析作品名；有没有它说明这个文件夹里有没有归档条目。
        // 清单读过、确认已经空了的不算
        val (manifests, real) = files.partition(VaultStore::looksLikeManifest)
        childContents.put(folderId, real.filterNot(FileStat::isFolder).take(MAX_REMEMBERED_CHILD_NAMES).map(ChildFile::of))
        val hasEntries = manifests.isNotEmpty() && vault.cached(folderId, files)?.any { it.isArchived } != false
        if (hasEntries || complete) childContents.markVaulted(folderId, hasEntries)
        _folderEmptiness.update { it + (folderId to files.isEmpty()) }
    }

    /** 挂归档标记的文件夹：直接放着归档条目的，加上归档时选的那一层到它们之间的各层，见 VaultTrees.marked。 */
    val vaultedFolders: StateFlow<Set<String>> by lazy {
        combine(childContents.vaultedFolders, childContents.listedFolders, vaultTrees.flow, VaultTrees::marked)
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    /** 清单读到或写成之后，按其中还有没有条目更新文件夹的标记。 */
    internal fun vaultEntriesKnown(folderId: String, entries: List<VaultEntry>) {
        childContents.markVaulted(folderId, entries.any { it.isArchived })
    }

    /*
     * 列过的文件夹是不是空的，连子文件夹在内，按 ID；没列过的不在表里。海报墙给空文件夹画空的封面用。
     * 不从 childContents 推：那里只记文件，只有子文件夹的与真空的都是一张空表，分不开；它还跨进程保留，
     * 这里只在这一次运行里有效，重启后按需再探（probeFolderEmptiness）。
     */
    private val _folderEmptiness = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val folderEmptiness: StateFlow<Map<String, Boolean>> = _folderEmptiness.asStateFlow()

    /**
     * 只取一项，看文件夹空不空。记下的内容是空表、而这一次运行里还没列过它时才需要：那张空表可能是真空，
     * 也可能只有子文件夹。一项的页不记进 childContents，免得把只取了一项当成全部内容。
     */
    suspend fun probeFolderEmptiness(folderId: String) = withContext(Dispatchers.Default) {
        if (folderId in _folderEmptiness.value) return@withContext
        childNameFetches.withPermit {
            if (folderId in _folderEmptiness.value) return@withPermit
            val files = runSuspendCatching { client.listFilesPaged(parentId = folderId, pageSize = 1).files }.getOrNull() ?: return@withPermit
            _folderEmptiness.update { it + (folderId to files.isEmpty()) }
        }
    }

    /** 往文件夹里放了东西或挪走了东西，空不空说不准了，等下次列到它再记。 */
    private fun forgetEmptiness(vararg folderIds: String) {
        _folderEmptiness.update { it - folderIds.toSet() }
    }

    /**
     * 补取一页文件。只取一页、至多 [CHILD_NAME_PAGE] 项，并发至多 [CHILD_NAME_CONCURRENCY]。
     * 失败返回 null 且不记下：记下的内容跨进程保留，一次网络失败不该让这个文件夹从此被当成空的。
     */
    suspend fun fetchChildContents(folderId: String): List<ChildFile>? = withContext(Dispatchers.Default) {
        childContents.get(folderId)?.let { return@withContext it }
        childNameFetches.withPermit {
            // 排队期间可能已有同一目录的请求完成
            childContents.get(folderId)?.let { return@withPermit it }
            val files = runSuspendCatching { client.listFilesPaged(parentId = folderId, pageSize = CHILD_NAME_PAGE).files }
                .getOrNull() ?: return@withPermit null
            rememberChildContents(folderId, files, complete = files.size < CHILD_NAME_PAGE)
            childContents.get(folderId)
        }
    }

    fun scrollAnchor(folderId: String): ScrollAnchor? = scrollAnchors.value[folderId]

    fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) {
        if (folderStackFlow.value.none { it.id == folderId }) return
        scrollAnchors.update { it + (folderId to anchor) }
    }

    private fun stackChanged() {
        // 库不是文件夹，不进「最近去过」；库里的子文件夹的路径以库开头，从命令面板再打开时也回不到真实的上级
        if (_folderStackFlow.value.library == null && _folderStackFlow.value.archive == null) recentFolders.visited(_folderStackFlow.value)
        forgetFoldersOutsideStack()
        publishTabs()
    }

    // 历史里的位置也留着：后退回去时首帧就是原来的列表与滚动位置。别的标签停着的位置与它们的历史同样留着，切回去是即时的
    private fun forgetFoldersOutsideStack() {
        val active = _activeTabId.value
        val kept = _tabs.value.filter { it.id != active }.map { it.stack to it.history } + (folderStackFlow.value to _historyFlow.value)
        val inStack = kept.asSequence()
            .flatMap { (stack, history) -> sequenceOf(stack) + history.back.asSequence() + history.forward.asSequence() }
            .flatten()
            .mapTo(HashSet()) { it.id }
        listingCache.update { cache -> cache.filterKeys { it in inStack } }
        scrollAnchors.update { anchors -> anchors.filterKeys { it in inStack } }
    }

    fun pushFolder(id: String, name: String) {
        moveTo(_folderStackFlow.value + PikoPathBreadcrumb(id, name))
    }

    /** 换到一条完整的路径，记进历史：在网盘中显示、从星标或传输跳过去，后退能回到跳之前的地方。 */
    fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) moveTo(stack)
    }

    /** 启动时恢复上次退出时的位置。不记历史：后退不该退到恢复之前那一瞬的根目录。 */
    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        _folderStackFlow.value = stack
        stackChanged()
    }

    fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
        val stack = _folderStackFlow.value
        if (index !in 0 until stack.lastIndex) return null
        val child = stack[index + 1]
        moveTo(stack.take(index + 1))
        return child
    }

    /**
     * 直接跳到某个目录，中间层级不可知，栈只留根与目标两级。目标本身是根时只留根：
     * 秒传的保存目标可以是根目录，拼成两级会出现两个「网盘」，返回一次还停在原地。
     */
    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        moveTo(if (breadcrumb.id.isEmpty()) listOf(ROOT_BREADCRUMB) else listOf(ROOT_BREADCRUMB, breadcrumb))
    }

    fun popFolder(): PikoPathBreadcrumb? {
        val stack = _folderStackFlow.value
        if (stack.size <= 1) return null
        moveTo(stack.dropLast(1))
        return stack.last()
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val page = client.listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            sortFiles(page.files, sortOrder, parentId) to page.nextPageToken
        }
    }

    /**
     * 列出目录下的全部条目，翻页由 SDK 负责。
     *
     * 按页取有两处坑：只取第一页会让超过一页的目录被静默截断；排序又发生在
     * 客户端，分页取回时每页各自有序、整体无序。两者都要求先取全再排。
     */
    suspend fun listAllFiles(
        parentId: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val files = client.listFiles(parentId)
            // 只缓存路径栈上的目录。目录选择器等其他调用方也走这里，它们的目录不该留在缓存里
            if (folderStackFlow.value.any { it.id == parentId }) listingCache.update { it + (parentId to files) }
            rememberListing(parentId, files)
            rememberChildContents(parentId, files)
            sortFiles(files, sortOrder, parentId)
        }
    }

    /**
     * 递归统计目录下的文件数与总大小，边统计边发出累计值。
     *
     * 服务端不提供目录大小：列表与详情里目录的 size 都是 "0"，也没有子项计数
     * （2026-09-23 实测），只能逐个目录列出来加总。SDK 的 searchFilesRecursive 走同样的
     * 遍历，但要求非空关键词，且到了上限静默结束，调用方分不清统计完没完。
     * 到达 [maxFolders] 或 [timeout] 时以 TRUNCATED 结束，界面据此标注「至少」。
     */
    fun folderUsage(
        folderId: String,
        maxFolders: Int = 2_000,
        timeout: Duration = 30.seconds,
    ): Flow<FolderUsage> = flow {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var files = 0
        var bytes = 0L
        var listed = 0
        var level = listOf(folderId)
        while (level.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (batch in level.chunked(FOLDER_USAGE_CONCURRENCY)) {
                if (deadline.hasPassedNow() || listed >= maxFolders) {
                    emit(FolderUsage(files, bytes, FolderUsage.Progress.TRUNCATED))
                    return@flow
                }
                val budgeted = batch.take(maxFolders - listed)
                listed += budgeted.size
                val listings = coroutineScope { budgeted.map { async { client.listFiles(it) } }.awaitAll() }
                for (entry in listings.flatten()) {
                    if (entry.isFolder) {
                        next += entry.id
                    } else {
                        files++
                        bytes += entry.sizeBytes
                    }
                }
                emit(FolderUsage(files, bytes, FolderUsage.Progress.COUNTING))
            }
            level = next
        }
        emit(FolderUsage(files, bytes, FolderUsage.Progress.COMPLETE))
    }.flowOn(Dispatchers.Default)

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getFile(fileId) }
    }

    /**
     * 目录已被删除或移入回收站。列一个不存在的目录不报错，只回一个空列表，与空目录分不开，
     * 只能另查详情：彻底删除的查不到，回收站里的回 file_in_recycle_bin，也可能查到但带着 trashed。
     * 只认服务端明确的拒绝，网络失败说不准，按还在处理，免得断一下网就把人退出目录。
     */
    suspend fun isFolderGone(folderId: String): Boolean =
        getFileDetail(folderId).fold(onSuccess = { it.trashed }, onFailure = { it is PikPakException })

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            asked.getQuota().also {
                _quotaFlow.value = it
                _quotaUpdates.tryEmit(asked.account to it)
            }
        }
    }

    /** [isFreeAccountFlow] 的当前值，登录时那一次还没取到就现取。取不到时为 null。 */
    suspend fun isFreeAccount(): Boolean? =
        isFreeAccountFlow.value ?: getTransferQuota().getOrNull()?.account?.isPremium?.not()

    /** 离线下载、下载、上传三项月度流量额度，见 [TransferQuota] 上的计费实测结论。 */
    suspend fun getTransferQuota(): Result<TransferQuota> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getTransferQuota().also { _transferQuotaFlow.value = it } }
    }

    /**
     * 全屏查看图片用的原图直链。
     *
     * 列表里的 thumbnailLink 是压过的小图，放大到全屏就是一团马赛克；原图链接只有
     * getFileDetail 才带。链接是签过名的，过期后 CDN 直接 403，所以不缓存，每次打开现取。
     */
    suspend fun originalImageUrl(fileId: String): String? =
        getFileDetail(fileId).getOrNull()?.downloadUrl

    /**
     * 把一小段内容传成网盘里的文件，返回新文件的 ID。只给配置同步这类几 KB 的东西用：整段在内存里，
     * 算 gcid 与上传各读一遍。同名文件不会被覆盖，调用方自己删旧的。
     */
    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val size = bytes.size.toLong()
            val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
            client.upload(parentId, name, size, gcid, { Buffer().apply { write(bytes) } }, {}).fileId
        }
    }

    /**
     * 读出一个小文件的全部内容，与 [uploadBytes] 配对。
     *
     * 刚传完的文件查详情有时还没有直链，稍后再查就有（设置同步与归档清单都撞到过，SDK 的 instantCreate 也记着同样的现象），
     * 所以没有直链时隔一会儿再查，几次都没有才算失败。
     */
    suspend fun readBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            var detail = client.getFile(fileId)
            for (wait in LINK_RETRY_DELAYS) {
                if (detail.downloadUrl != null) break
                delay(wait)
                detail = client.getFile(fileId)
            }
            val url = detail.downloadUrl ?: error("没有下载链接")
            val size = detail.size.toLongOrNull() ?: error("大小未知")
            if (size == 0L) return@runSuspendCatching ByteArray(0)
            client.streamRangeFromUrl(url, start = 0L, length = size, {}) { stream -> stream.channel.toByteArray() }
        }
    }

    /** [parentId] 下名为 [name] 的文件夹，没有就新建。返回它的 ID。 */
    suspend fun folderNamed(parentId: String, name: String): Result<String> {
        val existing = listAllFiles(parentId).getOrElse { return Result.failure(it) }
            .firstOrNull { it.isFolder && it.name == name && !it.trashed }
        return existing?.let { Result.success(it.id) } ?: createFolder(parentId, name)
    }

    /** [fileId] 的取样 CID，读 60 KB。归档时记下，日后只读体检用，见 [VaultEntry.cid]。 */
    suspend fun sampleCid(fileId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.sampleCid(client.getFile(fileId)) }
    }

    /** 按 gcid 秒传出一个文件，恢复归档条目用。 */
    suspend fun instantCreate(file: ResolvedFile, parentId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.instantCreate(file, parentId) }.onSuccess { forgetEmptiness(parentId) }
    }

    suspend fun createFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.createFolder(parentId, name) }.onSuccess {
            forgetSubfolders()
            forgetEmptiness(parentId)
        }
    }

    suspend fun rename(fileId: String, name: String): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.rename(fileId, name) }.onSuccess {
            recentFolders.forget(fileId)
            pinnedFolders.renamed(fileId, name)
            forgetSubfolders()
        }
    }

    suspend fun trash(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchTrash(ids) }.onSuccess {
            ids.forEach(recentFolders::forget)
            forgetSubfolders()
        }
    }

    suspend fun restore(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchUntrash(ids) }
    }

    suspend fun delete(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchDelete(ids) }
    }

    suspend fun move(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchMove(ids, parentId) }.onSuccess {
            ids.forEach(recentFolders::forget)
            forgetSubfolders()
            forgetEmptiness(parentId)
        }
    }

    /**
     * 复制到 [parentId]。服务端按任务执行，小批量在返回时已完成；目标里有同名项时自动改名为「名字(1)」。
     * 复制到自身或自己的子目录里会被拒绝（file_move_or_copy_to_cur）。SDK 已按 id 上限分批。
     */
    suspend fun copy(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.batchCopy(ids, parentId); Unit }.onSuccess { forgetEmptiness(parentId) }
    }

    suspend fun search(query: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.searchFiles(query) }
    }

    /**
     * 全盘按名搜索。PikPak 没有服务端搜索接口，只能逐层遍历目录树，
     * 所以结果是流式的：大网盘走完一轮要几十秒，不能等遍历结束才给结果。
     * 遍历的深度、目录数与超时上限由 SDK 的默认值兜底。
     */
    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit> =
        client.searchFilesRecursive(query, parentId).filter { hit ->
            !isPikoInternalFolder(hit.file) && !VaultStore.looksLikeManifest(hit.file) &&
                !(parentId.isEmpty() && hit.breadcrumb.firstOrNull()?.let(::isPikoInternalFolderName) == true)
        }

    suspend fun trashFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listTrash() }
    }

    /**
     * 读分享的顶层与提取码令牌。没带提取码、提取码错、分享已取消时服务端仍回 200，
     * SDK 据状态抛 [io.github.nihildigit.pikpak.ShareUnavailableException]，这里原样交给调用方分辨。
     */
    suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getShareInfo(shareId, passCode) }
    }

    /** 分享里某个文件夹的内容。子目录只能经它打开，GET /share 不认 parent_id。 */
    suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching { client.listShareFiles(shareId, passCodeToken, parentId = parentId).files }
        }

    /**
     * 把分享里的条目转存到 [toParentId]，等任务结束才返回。[ancestorIds] 是条目所在的各级分享目录，
     * 转存子目录里的条目时要带上。实测 2026-09-25：文件直接落在目标目录下，不带上级目录；
     * 秒级完成；任务 params 里没有新旧 id 的映射，要找新文件只能列目标目录。
     */
    suspend fun restoreFromShare(
        shareId: String,
        passCodeToken: String,
        fileIds: List<String>,
        toParentId: String,
        ancestorIds: List<String>,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val restore = client.restoreShare(shareId, passCodeToken, fileIds, toParentId = toParentId, ancestorIds = ancestorIds)
            if (restore.restoreTaskId.isEmpty()) return@runSuspendCatching
            repeat(RESTORE_POLL_LIMIT) {
                val task = client.getTask(restore.restoreTaskId)
                if (task.phase == TaskPhase.COMPLETE) return@runSuspendCatching
                if (task.phase == TaskPhase.ERROR) error(task.message.ifBlank { "转存失败" })
                delay(RESTORE_POLL_INTERVAL_MILLIS)
            }
            error("转存超时")
        }
    }

    /**
     * 把 [fileIds] 分享为一条链接，文件与文件夹可以混在一起、不必同目录。
     * [passCode] 为 null 是公开链接，空串由服务端生成四位提取码，否则用它。[expirationDays] 为 -1 永久有效。
     */
    suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                client.createShare(
                    fileIds = fileIds,
                    requirePassCode = passCode != null,
                    customPassCode = passCode.orEmpty(),
                    expirationDays = expirationDays,
                )
            }
        }

    /** 自己的分享，一页，新的在前。取消了的不在里面；文件被删的仍在，状态是 DELETED。 */
    suspend fun myShares(pageToken: String = ""): Result<ShareListPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listMyShares(pageToken = pageToken) }
    }

    /** 取消分享，文件留在网盘里。 */
    suspend fun cancelShares(shareIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteShares(shareIds) }
    }

    /** 全盘的星标文件与文件夹。服务端按 parent_id=* 一次返回全部，不分页。 */
    suspend fun starredFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listStarred() }
    }

    suspend fun setStarred(ids: List<String>, starred: Boolean): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { if (starred) client.starFiles(ids) else client.unstarFiles(ids) }
    }

    /** 播放历史的一页，按最近播放倒序，与官方客户端共用同一份。 */
    suspend fun playHistory(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listPlayHistory(pageToken = pageToken) }
    }

    /**
     * 最近添加的一页：上传与离线、秒传加进网盘的文件，按时间倒序，与官方客户端「最近添加」同一份。
     * 明写这两种类型，不用不带过滤的查询：不带过滤时服务端恰好只给这两种（SDK 的 listEvents 实测），
     * 但那是服务端眼下的默认，以后多一种事件就会混进来。
     */
    suspend fun recentlyAdded(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listEvents(listOf(EventType.UPLOAD, EventType.RESTORE), pageToken = pageToken) }
    }

    /** 删掉几条事件记录（播放历史或最近添加里的一行），文件本身不动。 */
    suspend fun deleteEvents(eventIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteEvents(eventIds) }
    }

    suspend fun clearPlayHistory(): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.clearEvents(listOf(EventType.PLAY)) }
    }

    fun folderMeaningless(folderId: String): Boolean? = folderMeaninglessCache[folderId]

    fun cacheFolderMeaningless(folderId: String, value: Boolean) {
        folderMeaninglessCacheFlow.update { it + (folderId to value) }
    }

    /**
     * 秒传与离线任务的默认保存目录。根目录里已有同类目录就沿用，没有才新建。
     *
     * 要列全根目录再找：只看第一页的话，根目录条目一多，已有的那个目录落在后面几页，
     * 每次都会再建一个同名目录。
     */
    /** 根目录里的 My Pack，没有时为 null，不新建：侧边栏只为显示它不该往网盘里添一个文件夹。 */
    suspend fun findMyPacksFolder(): Result<PikoPathBreadcrumb?> =
        listAllFiles().map { files -> files.firstOrNull { it.isMyPacksFolder() }?.let { PikoPathBreadcrumb(it.id, it.name) } }

    suspend fun getOrCreateMyPacksFolder(): Result<PikoPathBreadcrumb> {
        val rootFiles = listAllFiles().getOrElse { return Result.failure(it) }
        val existing = rootFiles.firstOrNull { it.isMyPacksFolder() }
        return if (existing != null) {
            Result.success(PikoPathBreadcrumb(existing.id, existing.name))
        } else {
            createFolder("", MY_PACKS_FOLDER_NAME).map { PikoPathBreadcrumb(it, MY_PACKS_FOLDER_NAME) }
        }
    }

    suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            if (!forceRefresh) folderMeaninglessCache[folderId]?.let { return@withContext it }
            val result = listFiles(folderId, sortOrder = PikoFileSortOrder.TIME_DESC)
                .getOrDefault(emptyList<FileStat>() to "").first
                .isEmpty()
            cacheFolderMeaningless(folderId, result)
            result
        }

    /**
     * 文件夹在前，各自按 [order] 排。根目录的 My Pack 不论哪种排序都排第一：
     * 秒传与离线任务默认存进它，是根目录里最常进的目录。
     */
    private fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        val comparator = when (order) {
            // 自然顺序：第 2 集排在第 10 集前面
            PikoFileSortOrder.NAME_ASC -> compareBy(NaturalOrder, FileStat::name)
            PikoFileSortOrder.NAME_DESC -> compareBy(NaturalOrder, FileStat::name).reversed()
            // 按创建时间而非 modified_time：目录的 modified_time 不随其中内容变动而更新，
            // 与创建时间相同（2026-09-23 实测），按它排序只会让「修改时间」名不副实
            PikoFileSortOrder.TIME_DESC -> compareByDescending(FileStat::createdTime)
            PikoFileSortOrder.TIME_ASC -> compareBy(FileStat::createdTime)
            PikoFileSortOrder.SIZE_DESC -> compareByDescending(FileStat::sizeBytes)
            PikoFileSortOrder.SIZE_ASC -> compareBy(FileStat::sizeBytes)
        }
        val sortedFolders = folders.sortedWith(comparator)
        val pinned = if (parentId.isEmpty()) sortedFolders.firstOrNull { it.isMyPacksFolder() } else null
        val orderedFolders = if (pinned == null) sortedFolders else listOf(pinned) + (sortedFolders - pinned)
        return orderedFolders + regularFiles.sortedWith(comparator)
    }

    private fun FileStat.isMyPacksFolder() = isFolder && name.lowercase() in MY_PACKS_FOLDER_NAMES

    companion object {
        val ROOT_BREADCRUMB = PikoPathBreadcrumb("", "网盘")
        private const val TAG = "DriveRepository"
        private val LINK_RETRY_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds)
        private const val FIRST_TAB_ID = 1L
        private const val TABS_SAVE_DELAY_MS = 1_000L
        private const val MAX_LOCATE_DEPTH = 64
        private const val CHILD_NAME_PAGE = 20
        private const val CHILD_NAME_CONCURRENCY = 2
        private const val MAX_REMEMBERED_CHILD_NAMES = 200
        private const val SUBFOLDER_CACHE_SIZE = 32

        // 目录图一次展开十几层、每层几十个文件夹，再加网盘页走过的，一百多个够覆盖一次浏览
        private const val RECENT_LISTINGS = 128

        // 目录图直接用最近列过的而不重取的期限。网盘页不看它：网盘页总是先显示缓存、随即刷新
        private val RECENT_LISTING_FRESH = 60.seconds
        private val SUBFOLDER_TTL = 60.seconds

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 转存任务实测一秒内完成；给大目录留到一分钟
        private const val RESTORE_POLL_LIMIT = 60
        private const val RESTORE_POLL_INTERVAL_MILLIS = 1_000L

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}

@Serializable
private class SavedCrumb(val id: String, val name: String)

@Serializable
private class SavedTabs(val tabs: List<List<SavedCrumb>>, val active: Int)

private val tabsJson = Json { ignoreUnknownKeys = true }

/** 网盘页的一个标签：停在哪（[stack]）与它自己的后退、前进。 */
data class DriveTab(val id: Long, val stack: List<PikoPathBreadcrumb>, val history: FolderHistory = FolderHistory()) {
    val title: String get() = stack.lastOrNull()?.name.orEmpty()

    /** 停在查重结果里。结果只活在这一次会话里，标签随它关掉。 */
    val isDuplicates: Boolean get() = stack.firstOrNull()?.id == DriveLibrary.DUPLICATES.id
}

/**
 * 网盘页的浏览历史。[back] 与 [forward] 的末尾是离眼下最近的一步，各存一条完整路径。
 * 最多记 [LIMIT] 步，再早的丢掉。
 */
data class FolderHistory(
    val back: List<List<PikoPathBreadcrumb>> = emptyList(),
    val forward: List<List<PikoPathBreadcrumb>> = emptyList(),
) {
    val canGoBack: Boolean get() = back.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    /** 从 [previous] 走开了：它进后退，前进作废。 */
    fun visited(previous: List<PikoPathBreadcrumb>) = FolderHistory(back = (back + listOf(previous)).takeLast(LIMIT), forward = emptyList())

    companion object {
        const val LIMIT = 50
    }
}
