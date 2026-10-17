package dev.piko.shared.presentation

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.FolderReferenceSource
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.RecentFolders
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.archive
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.library
import dev.piko.shared.data.outsideArchives
import dev.piko.shared.data.PikoDriveRepository.Companion.ROOT_BREADCRUMB
import dev.piko.shared.state.SaveTargetContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

class DriveWorkspace(
    private val repository: PikoDriveRepository,
    private val cacheStore: PikoCacheStore? = null,
    backgroundScope: CoroutineScope,
) : FileWorkspace, FolderReferenceSource {
    private val job = SupervisorJob(backgroundScope.coroutineContext[Job])
    private val workScope = CoroutineScope(backgroundScope.coroutineContext + job)
    override val clipboard = DriveClipboardState()
    private val folderIndex get() = repository.folderIndex
    private val scrollAnchors = MutableStateFlow<Map<Long, Map<String, ScrollAnchor>>>(emptyMap())
    private val navigations = MutableStateFlow<Set<TabNavigation>>(emptySet())
    private val _folderStackFlow = MutableStateFlow(listOf(ROOT_BREADCRUMB))
    override val folderStackFlow: StateFlow<List<PikoPathBreadcrumb>> = _folderStackFlow.asStateFlow()

    /**
     * 浏览历史，与资源管理器、Finder 的后退与前进同义：记的是去过的位置（整条路径），不是层级。
     * 「上一级」与它是两回事：从信息流或星标跳到很深的目录后，上一级只会一层层往上走，后退才回到跳之前在看的地方。
     * 每次改动路径栈都记一笔，只有启动时恢复上次的位置不记。不落盘，进程内有效。
     */
    private val _historyFlow = MutableStateFlow(FolderHistory())
    override val historyFlow: StateFlow<FolderHistory> = _historyFlow.asStateFlow()

    /**
     * 网盘页的标签，各有自己的路径栈与浏览历史。活动的那个就是 [folderStackFlow] 与 [historyFlow]：
     * 网盘页、定位、快捷访问、信息流都只认这两个，切标签时把它们换成目标标签存着的那一份，别处不用知道有标签。
     * 这里存的活动标签那一项是切走之前的旧值，读标签用 [tabsFlow]。不落盘，进程内有效。
     */
    private val _tabs = MutableStateFlow(listOf(DriveTab(FIRST_TAB_ID, listOf(ROOT_BREADCRUMB))))
    private val _activeTabId = MutableStateFlow(FIRST_TAB_ID)
    val activeTabId: StateFlow<Long> = _activeTabId.asStateFlow()
    private var selectingFileContext = false

    /** File tabs and transient task contexts share one ordered identity space. */
    val contexts = WorkspaceContexts(FIRST_TAB_ID).also { collection ->
        collection.onActivated = { context ->
            if (!selectingFileContext && context is WorkspaceContext.File) switchTab(context.id)
        }
    }
    val contextsFlow: StateFlow<List<WorkspaceContext>> get() = contexts.contextsFlow
    val activeContextId: StateFlow<Long> get() = contexts.activeContextId

    /** The owner uses this to stop a task when its context is actually discarded. */
    var onContextClosed: (WorkspaceContext) -> Unit = {}

    private val _tabsFlow = MutableStateFlow(_tabs.value)

    /** Retained file browsers. Visible work tabs are listed by contextsFlow; a closed final file browser remains as the return location. */
    val tabsFlow: StateFlow<List<DriveTab>> = _tabsFlow.asStateFlow()

    private fun newTabId(excluding: Set<Long> = emptySet()): Long {
        var id: Long
        do { id = Random.nextLong() } while (id == 0L || id == FIRST_TAB_ID || id in excluding || _tabs.value.any { it.id == id } || contextsFlow.value.any { it.id == id })
        return id
    }

    private fun publishTabs() {
        val active = _activeTabId.value
        val tabs = _tabs.value.map { if (it.id == active) it.copy(stack = _folderStackFlow.value, history = _historyFlow.value) else it }
        // 文件夹索引每变一次都会来这里对一遍，没变的不重写存盘
        if (tabs == _tabsFlow.value) return
        _tabsFlow.value = tabs
        navigations.value.forEach { navigation -> tabs.firstOrNull { it.id == navigation.tabId }?.let(navigation::update) }
        saveTabs()
    }

    fun navigationForTab(id: Long): TabNavigation = TabNavigation(this, id).also { navigation ->
        navigations.update { it + navigation }
    }

    internal fun releaseNavigation(navigation: TabNavigation) { navigations.update { it - navigation } }

    internal fun tabSnapshot(id: Long): DriveTab? = _tabs.value.firstOrNull { it.id == id }?.let { tab ->
        if (id == _activeTabId.value) tab.copy(stack = _folderStackFlow.value, history = _historyFlow.value) else tab
    }

    internal fun updateTabStack(id: Long, stack: List<PikoPathBreadcrumb>, restoring: Boolean = false) {
        if (stack.isEmpty()) return
        if (id == _activeTabId.value) {
            if (restoring) restoreFolderStack(stack) else updateFolderStack(stack)
            return
        }
        val next = folderIndex.resolve(stack)
        _tabs.update { tabs -> tabs.map { tab ->
            if (tab.id != id || tab.stack == next) tab
            else tab.copy(stack = next, history = if (restoring) tab.history else tab.history.visited(tab.stack))
        } }
        forgetFoldersOutsideStack()
        publishTabs()
    }

    internal fun moveTabHistory(id: Long, backwards: Boolean): Boolean {
        if (id == _activeTabId.value) return if (backwards) goBack() else goForward()
        val tab = tabSnapshot(id) ?: return false
        val target = (if (backwards) tab.history.back else tab.history.forward).lastOrNull() ?: return false
        val history = if (backwards) {
            FolderHistory(tab.history.back.dropLast(1), tab.history.forward + listOf(tab.stack))
        } else {
            FolderHistory(tab.history.back + listOf(tab.stack), tab.history.forward.dropLast(1))
        }
        _tabs.update { tabs -> tabs.map { if (it.id == id) it.copy(stack = target, history = history) else it } }
        publishTabs()
        return true
    }

    // 标签按账号存进缓存目录，重启后接着用。只存各自停在哪、哪个是活动的，历史不存
    private var tabsSave: Job? = null

    private fun saveTabs() {
        val store = cacheStore ?: return
        val account = navigationAccount.value ?: return
        if (account != repository.currentAccount) return
        val tabs = _tabsFlow.value
        val saved = SavedTabs(
            tabs = tabs.map { tab -> tab.stack.outsideArchives().map { SavedCrumb(it.id, it.name) } },
            active = tabs.indexOfFirst { it.id == _activeTabId.value }.coerceAtLeast(0),
            ids = tabs.map { it.id },
        )
        tabsSave?.cancel()
        tabsSave = workScope.launch {
            delay(TABS_SAVE_DELAY_MS)
            runCatching { store.write(tabsKey(account), tabsJson.encodeToString(SavedTabs.serializer(), saved)) }
        }
    }

    /**
     * 启动或换号后恢复这个账号上次的标签与位置，只在还停在初始状态（一个标签、在根目录）时做。没有存过的返回 false。
     * 恢复不记历史，与 [restoreFolderStack] 相同。
     */
    override suspend fun restoreLocation(): Boolean {
        val store = cacheStore ?: return false
        val account = repository.currentAccount ?: return false
        enterAccount(account)
        if (_tabs.value.size != 1 || _folderStackFlow.value.singleOrNull()?.id != ROOT_BREADCRUMB.id) return false
        val initialTabs = _tabsFlow.value
        val initialHighlightRevision = highlightRequests.value
        if (pendingByTab.value.values.any { it.isNotEmpty() }) return false
        val saved = runCatching { store.read(tabsKey(account))?.let { tabsJson.decodeFromString(SavedTabs.serializer(), it) } }.getOrNull()
            ?: return false
        // 读取缓存期间可能已经导航或换号，旧的恢复结果不能覆盖用户刚打开的位置。
        if (_tabsFlow.value !== initialTabs || highlightRequests.value != initialHighlightRevision || repository.currentAccount != account) return false
        // Older releases saved duplicate report positions as tabs. Their task state was never saved,
        // so only actual file positions can be restored into this workspace.
        val stacks = saved.tabs.mapIndexedNotNull { index, crumbs ->
            folderIndex.resolve(crumbs.map { PikoPathBreadcrumb(it.id, it.name) })
                .takeIf { it.isNotEmpty() && it.library?.isSession != true }?.let { index to it }
        }
        if (stacks.isEmpty()) return false
        val auxiliaryIds = contextsFlow.value.filterNot { it is WorkspaceContext.File }.mapTo(HashSet()) { it.id }
        val savedIds = saved.ids.takeIf { it.size == saved.tabs.size && it.distinct().size == it.size && it.none { id -> id == 0L || id in auxiliaryIds } }
        val allocated = HashSet<Long>()
        val tabs = stacks.map { (savedIndex, stack) ->
            val id = savedIds?.get(savedIndex) ?: newTabId(allocated)
            allocated += id
            DriveTab(id, stack)
        }
        val activeIndex = stacks.indexOfFirst { it.first >= saved.active }.takeIf { it >= 0 } ?: tabs.lastIndex
        val active = tabs[activeIndex]
        _tabs.value = tabs
        _activeTabId.value = active.id
        _historyFlow.value = FolderHistory()
        _folderStackFlow.value = active.stack

        contexts.restoreFilesPreservingAux(tabs.map { it.id }, active.id)
        forgetFoldersOutsideStack()
        publishTabs()
        return true
    }

    // 眼前的路径、标签与缓存属于哪个账号，见 enterAccount
    private val navigationAccount = MutableStateFlow<String?>(null)

    /**
     * 换号后把工作区的位置、标签、历史与滚动记忆换成新账号的一份：全回到根目录，
     * 由网盘页接着恢复新账号上次的标签。同一账号再调不做事，所以账号变化的收集者与网盘页都调它，谁先到谁做。
     * 不写回标签：写回会拿根目录盖掉新账号存着的那一份，恢复就没了。
     */
    fun enterAccount(account: String?) {
        repository.enterAccount(account)
        while (true) {
            val previous = navigationAccount.value
            if (previous == account) return
            if (!navigationAccount.compareAndSet(previous, account)) continue
            if (previous == null) return
            break
        }
        val root = DriveTab(newTabId(), listOf(ROOT_BREADCRUMB))
        _tabs.value = listOf(root)
        _activeTabId.value = root.id
        _historyFlow.value = FolderHistory()
        _folderStackFlow.value = root.stack

        contexts.resetFiles(listOf(root.id), root.id).forEach(onContextClosed)
        _tabsFlow.value = _tabs.value
        scrollAnchors.value = emptyMap()
        pendingHighlight.value = emptySet()
        pendingByTab.value = emptyMap()
        clipboard.clear()
    }

    private fun tabsKey(account: String) ="drive-tabs-" + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"

    /** 在活动标签后面开一个新标签，停在 [stack]。[activate] 为 false 是在后台开（中键点文件夹）。 */
    fun openTab(stack: List<PikoPathBreadcrumb>, activate: Boolean = true): Long {
        val tab = DriveTab(newTabId(), stack.ifEmpty { listOf(ROOT_BREADCRUMB) })
        val replaceReturnBrowser = contextsFlow.value.none { it is WorkspaceContext.File }
        _tabs.update { tabs ->
            if (replaceReturnBrowser) return@update listOf(tab)
            val at = tabs.indexOfFirst { it.id == _activeTabId.value }
            tabs.toMutableList().apply { add(at + 1, tab) }
        }
        val afterId = _activeTabId.value.takeIf { id -> contextsFlow.value.any { it.id == id } } ?: contexts.activeContextId.value
        contexts.openFile(tab.id, activate = false, afterId = afterId)
        if (activate || replaceReturnBrowser) switchFileTab(tab.id, activateContext = activate) else publishTabs()
        return tab.id
    }

    fun switchTab(id: Long) = switchFileTab(id, activateContext = true)

    private fun switchFileTab(id: Long, activateContext: Boolean) {
        val active = _activeTabId.value
        val target = _tabs.value.firstOrNull { it.id == id } ?: return
        if (activateContext) {
            if (contextsFlow.value.none { it.id == id }) contexts.openFile(id, activate = false)
            selectingFileContext = true
            try { contexts.activate(id) } finally { selectingFileContext = false }
        }
        if (id == active) return
        // 先把眼下的位置存回活动标签，再换成目标标签的
        _tabs.update { tabs -> tabs.map { if (it.id == active) it.copy(stack = _folderStackFlow.value, history = _historyFlow.value) else it } }
        _activeTabId.value = id
        _historyFlow.value = target.history
        _folderStackFlow.value = target.stack
        pendingHighlight.value = pendingForTab(id)
        forgetFoldersOutsideStack()
        publishTabs()
    }

    /** Close the visible tab while retaining a return location when it is the final file browser. */
    fun closeTab(id: Long) {
        val tabs = _tabs.value
        if (tabs.none { it.id == id }) return
        if (tabs.size <= 1) {
            contexts.close(id)?.let(onContextClosed)
            return
        }
        val index = tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        if (id == _activeTabId.value) {
            switchFileTab((tabs.getOrNull(index + 1) ?: tabs[index - 1]).id, activateContext = false)
        }
        _tabs.update { current -> current.filterNot { it.id == id } }
        contexts.close(id)?.let(onContextClosed)
        pendingByTab.update { it - id }
        forgetFoldersOutsideStack()
        publishTabs()
    }

    fun openDuplicateContext(root: PikoPathBreadcrumb, activate: Boolean = true): Long =
        contexts.openDuplicate(root, activate)

    fun openImportDraftContext(activate: Boolean = true): Long = contexts.openImportDraft(activate)

    fun switchContext(id: Long): Boolean {
        val context = contextsFlow.value.firstOrNull { it.id == id } ?: return false
        return if (context is WorkspaceContext.File) {
            switchTab(id)
            true
        } else contexts.activate(id)
    }

    fun moveContext(id: Long, targetIndex: Int): Boolean {
        if (!contexts.move(id, targetIndex)) return false
        val positions = contextsFlow.value.mapIndexed { index, context -> context.id to index }.toMap()
        _tabs.update { tabs -> tabs.sortedBy { positions[it.id] ?: Int.MAX_VALUE } }
        publishTabs()
        return true
    }

    fun closeContext(id: Long): WorkspaceContext? {
        val context = contextsFlow.value.firstOrNull { it.id == id } ?: return null
        if (context is WorkspaceContext.File) {
            closeTab(id)
            return context.takeIf { contextsFlow.value.none { it.id == id } }
        }
        if (contextsFlow.value.size == 1) {
            contexts.openFile(_activeTabId.value, activate = false)
        }
        return contexts.close(id)?.also(onContextClosed)
    }

    /** 眼前的位置：活动标签、它的路径栈与浏览历史。交给 [returnTo] 就能整个回到这里。 */
    class DriveLocation internal constructor(
        internal val owner: DriveWorkspace,
        internal val tabId: Long,
        internal val stack: List<PikoPathBreadcrumb>,
        internal val history: FolderHistory,
        internal val account: String?,
    ) : FileLocation

    override fun currentLocation() = DriveLocation(this, _activeTabId.value, _folderStackFlow.value, _historyFlow.value, navigationAccount.value)

    /**
     * 回到 [location]，连历史一起：之后的浏览整段丢掉，后退与前进都不留它的痕迹，像是从没离开过。
     * 用在信息流的「继续刷」：从信息流跳去看一个文件，看完回到刷之前在的地方。
     * 那个标签期间被关掉了，就在活动标签后面重新开一个。
     */
    override fun returnTo(location: FileLocation) {
        if (location !is DriveLocation || location.owner !== this || location.account != navigationAccount.value || location.account != repository.currentAccount) return
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

    private val recentFolders = RecentFolders(cacheStore, workScope)

    /** 最近去过的文件夹（整条路径），新的在前，命令面板用，见 [RecentFolders]。 */
    override val recentFoldersFlow: StateFlow<List<List<PikoPathBreadcrumb>>> get() = recentFolders.flow

    /**
     * 人在网盘里往别处走（进出文件夹、面包屑、后退前进、从别处跳进来）之前问一声，返回 false 即拦下、栈不动；
     * 拦下的一方问过人之后调 retry 把同一步再走一遍；普通导航不结束业务会话。
     * 在换栈之前拦，而不是换了再跳回去：那样历史里多出一步，画面也闪一下。
     * 换账号、启动时恢复、切标签与信息流的继续刷不经它：那不是人在这个位置里往外走。
     */
    fun interface LeaveGuard {
        fun allows(next: List<PikoPathBreadcrumb>, retry: () -> Unit): Boolean
    }

    var leaveGuard: LeaveGuard? = null

    private fun leaveAllowed(next: List<PikoPathBreadcrumb>, retry: () -> Unit): Boolean = leaveGuard?.allows(next, retry) ?: true

    /**
     * 换栈之外还有别的步骤的导航（先切到网盘页、跳过去后标出某一项）：按要去的 [next] 先问 [leaveGuard]，问的是整个 [action]。
     * 拦下后取消就什么都没发生，确认后重走的是整个动作；只靠换栈时的那一问，切页已经发生、标出那一步也丢了。
     */
    override fun navigateThen(next: List<PikoPathBreadcrumb>, action: () -> Unit) {
        if (leaveAllowed(next, action)) action()
    }

    /**
     * 活动标签停在会话的位置（查重，见 [DriveLibrary.isSession]）、那个会话还没结束时为真，由主界面装上。
     * 这时换栈改为另开一个普通标签：会话标签只是一个位置，改写它，会话还在、标签却没了，成了关不掉的孤儿。
     * 只在有标签栏时为真；移动端的返回不结束扫描或报告。
     */
    var sessionTabHeld: (DriveLibrary) -> Boolean = { false }

    /** 把栈换成 [requested]，换了才把原来的位置记进后退、清掉前进。 */
    private fun moveTo(requested: List<PikoPathBreadcrumb>) {
        // 调用方手上的栈可能是改名、移动之前拼的，进栈前先按索引改正，免得旧名字又写回来
        val next = folderIndex.resolve(requested)
        val previous = _folderStackFlow.value
        if (next == previous) return
        if (!leaveAllowed(next) { moveTo(requested) }) return
        val session = previous.library?.takeIf { it.isSession }
        if (session != null && next.library != session && sessionTabHeld(session)) {
            openTab(next)
            return
        }
        _folderStackFlow.value = next
        _historyFlow.update { it.visited(previous) }
        stackChanged()
    }

    /** 后退了返回 true。被 [leaveGuard] 拦下也返回 true：这一下已经有人接着处理，调用方不要再退到别处。 */
    override fun goBack(): Boolean {
        val history = _historyFlow.value
        val target = history.back.lastOrNull() ?: return false
        if (!leaveAllowed(target) { goBack() }) return true
        _historyFlow.value = FolderHistory(back = history.back.dropLast(1), forward = history.forward + listOf(_folderStackFlow.value))
        _folderStackFlow.value = target
        stackChanged()
        return true
    }

    override fun goForward(): Boolean {
        val history = _historyFlow.value
        val target = history.forward.lastOrNull() ?: return false
        if (!leaveAllowed(target) { goForward() }) return true
        _historyFlow.value = FolderHistory(back = history.back + listOf(_folderStackFlow.value), forward = history.forward.dropLast(1))
        _folderStackFlow.value = target
        stackChanged()
        return true
    }

    // 界面外发起的「跳到这个文件并标出它」。网盘页此时可能不在组合里，所以存成
    // 一次性的待办，由下一个 DriveScreenState 在初始化时取走
    private val pendingHighlight = MutableStateFlow<Set<String>>(emptySet())
    private val pendingByTab = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    private val highlightRequests = MutableStateFlow(0L)

    /** 待高亮的条目。网盘页开着时也会有新请求进来，所以给一个可订阅的流，取用仍走 [takePendingHighlight]。 */
    override val pendingHighlights: StateFlow<Set<String>> = pendingHighlight.asStateFlow()

    override fun requestHighlight(ids: Set<String>) {
        requestHighlightForTab(_activeTabId.value, ids)
    }

    override fun takePendingHighlight(): Set<String> = takePendingForTab(_activeTabId.value)

    internal fun pendingForTab(id: Long): Set<String> = pendingByTab.value[id].orEmpty()

    internal fun requestHighlightForTab(id: Long, ids: Set<String>) {
        highlightRequests.update { it + 1 }
        pendingByTab.update { it + (id to ids) }
        if (id == _activeTabId.value) pendingHighlight.value = ids
        navigations.value.filter { it.tabId == id }.forEach { it.updateHighlights(ids) }
    }

    internal fun takePendingForTab(id: Long): Set<String> {
        val previous = pendingByTab.getAndUpdate { it - id }[id].orEmpty()
        if (id == _activeTabId.value) pendingHighlight.value = emptySet()
        navigations.value.filter { it.tabId == id }.forEach { it.updateHighlights(emptySet()) }
        return previous
    }

    private fun locationStacks(): List<List<PikoPathBreadcrumb>> {
        val active = _activeTabId.value
        val kept = _tabs.value.filter { it.id != active }.map { it.stack to it.history } + (_folderStackFlow.value to _historyFlow.value)
        return kept.flatMap { (stack, history) -> listOf(stack) + history.back + history.forward }
    }

    override fun refreshFolderReferences() {
        if (navigationAccount.value != repository.currentAccount) return
        val index = folderIndex
        _folderStackFlow.update { index.resolve(it) }
        _historyFlow.update { it.resolved(index) }
        _tabs.update { tabs -> tabs.map { tab -> tab.copy(stack = index.resolve(tab.stack), history = tab.history.resolved(index)) } }
        // 截短了的（停在删掉的文件夹里）不留：那个文件夹已经去不了，留着的是它的上级，人并没有专门去过
        recentFolders.rewrite { stacks -> stacks.mapNotNull { stack -> index.resolve(stack).takeIf { it.last().id == stack.last().id } } }
        publishTabs()
    }

    override fun scrollAnchor(folderId: String): ScrollAnchor? = scrollAnchorForTab(_activeTabId.value, folderId)

    override fun saveScrollAnchor(folderId: String, anchor: ScrollAnchor) {
        saveScrollAnchorForTab(_activeTabId.value, folderId, anchor)
    }

    internal fun scrollAnchorForTab(id: Long, folderId: String): ScrollAnchor? = scrollAnchors.value[id]?.get(folderId)

    internal fun saveScrollAnchorForTab(id: Long, folderId: String, anchor: ScrollAnchor) {
        if (tabSnapshot(id)?.stack?.none { it.id == folderId } != false) return
        scrollAnchors.update { current -> current + (id to (current[id].orEmpty() + (folderId to anchor))) }
    }

    private fun stackChanged() {
        // 库不是文件夹，不进「最近去过」；库里的子文件夹的路径以库开头，从命令面板再打开时也回不到真实的上级
        if (_folderStackFlow.value.library == null && _folderStackFlow.value.archive == null) recentFolders.visited(_folderStackFlow.value)
        forgetFoldersOutsideStack()
        publishTabs()
    }

    // 历史里的位置也留着：后退回去时首帧就是原来的滚动位置。别的标签停着的位置与它们的历史同样留着，切回去是即时的。
    // 列表本身不在这里丢：出了路径的目录照最近列过的留着，由 rememberListing 按数量淘汰
    private fun forgetFoldersOutsideStack() {
        val kept = _tabs.value.associate { tab ->
            val current = requireNotNull(tabSnapshot(tab.id))
            tab.id to (listOf(current.stack) + current.history.back + current.history.forward).flatten().mapTo(HashSet()) { it.id }
        }
        scrollAnchors.update { tabs -> tabs.filterKeys { it in kept }.mapValues { (id, anchors) -> anchors.filterKeys { it in kept.getValue(id) } } }
    }

    override fun pushFolder(id: String, name: String) {
        moveTo(_folderStackFlow.value + PikoPathBreadcrumb(id, name))
    }

    /** 换到一条完整的路径，记进历史：在网盘中显示、从星标或传输跳过去，后退能回到跳之前的地方。 */
    override fun updateFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isNotEmpty()) moveTo(stack)
    }

    /** 启动时恢复上次退出时的位置。不记历史：后退不该退到恢复之前那一瞬的根目录。 */
    override fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        _folderStackFlow.value = stack
        stackChanged()
    }

    override fun popToBreadcrumb(index: Int): PikoPathBreadcrumb? {
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
    override fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        moveTo(if (breadcrumb.id.isEmpty()) listOf(ROOT_BREADCRUMB) else listOf(ROOT_BREADCRUMB, breadcrumb))
    }

    override fun popFolder(): PikoPathBreadcrumb? {
        val stack = _folderStackFlow.value
        if (stack.size <= 1) return null
        moveTo(stack.dropLast(1))
        return stack.last()
    }

    override fun removeRecentFolder(folderId: String) = recentFolders.remove(folderId)

    override val retainedPaths: List<List<PikoPathBreadcrumb>> get() = locationStacks()
    override val referencedPaths: List<List<PikoPathBreadcrumb>> get() = retainedPaths + recentFolders.flow.value

    override val saveTargetContext = SaveTargetContext(
        currentFolder = { saveFolder(folderStackFlow.value) },
        folders = folderStackFlow.map(::saveFolder).distinctUntilChanged(),
    )

    private fun saveFolder(stack: List<PikoPathBreadcrumb>) =
        stack.lastOrNull { isDriveFolderId(it.id) } ?: ROOT_BREADCRUMB

    init {
        repository.registerFolderReferences(this)
        job.invokeOnCompletion { repository.unregisterFolderReferences(this) }
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.accountChanges.collect { account ->
                if (account != null) enterAccount(account) else {
                    clipboard.clear()
                    pendingHighlight.value = emptySet()
                    pendingByTab.value = emptyMap()
                    navigations.value.forEach { it.updateHighlights(emptySet()) }
                }
                recentFolders.switchAccount(account)
            }
        }
    }

    fun close() {
        contextsFlow.value.filterNot { it is WorkspaceContext.File }.forEach(onContextClosed)
        clipboard.clear()
        job.cancel()
        tabsSave?.cancel()
        repository.unregisterFolderReferences(this)
    }

    companion object {
        private const val FIRST_TAB_ID = 1L
        private const val TABS_SAVE_DELAY_MS = 1_000L
    }
}

@Serializable
private class SavedCrumb(val id: String, val name: String)

@Serializable
private class SavedTabs(val tabs: List<List<SavedCrumb>>, val active: Int, val ids: List<Long> = emptyList())

private val tabsJson = Json { ignoreUnknownKeys = true }
