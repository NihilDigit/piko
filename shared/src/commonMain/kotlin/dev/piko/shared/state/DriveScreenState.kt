package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ChildFile
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文件夹行在可见区域里停留这么久才预取其内容，见 [DriveScreenState.onFolderVisible]。 */
private const val PREFETCH_DWELL_MILLIS = 400L

/** 高亮的条目不在列表里时，每次静默重列之前等多久。合计约 2.7 秒，盖过实测的列表滞后。 */
private val HIGHLIGHT_RETRY_DELAYS = listOf(300L, 600L, 800L, 1000L)

private const val TAG = "Drive"

/**
 * 网盘浏览的全部状态与动作，两端共用。
 *
 * 这里只放与布局无关的东西：文件、选中、搜索、启发式折叠、防窥。列表还是网格、
 * 弹的是 BottomSheet 还是 ContentDialog、提示用 Snackbar 还是 InfoBar，都是各端
 * 自己的事，不进这个类。
 *
 * 状态用 Compose 的 State 而不是 StateFlow：两端的视图层都是 Compose，用 State
 * 可以省掉各写一遍 collectAsState，读取粒度也更细。提示消息反过来用事件流——它是
 * 一次性事件，用状态表达会在重组时重放。
 */
class DriveScreenState(
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    initialSortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
) {
    var files by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** 上次加载失败的原因，成功后清空。列表此时仍是旧数据，各端据此提示。 */
    var loadError by mutableStateOf<String?>(null)
        private set
    var sortOrder by mutableStateOf(initialSortOrder)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var isGlobalSearching by mutableStateOf(false)
        private set

    /**
     * 是否处于全盘搜索结果态。与命中数无关：搜完一无所获也要显示「全盘未找到」，
     * 而不是悄悄退回目录内过滤。
     */
    var isGlobalSearchActive by mutableStateOf(false)
        private set
    private val globalSearchHits = mutableStateListOf<SearchHit>()
    private var globalSearchJob: Job? = null

    var isSelectionMode by mutableStateOf(false)
        private set
    val selectedFileIds = mutableStateListOf<String>()

    val revealedFileIds = mutableStateListOf<String>()

    var highlightedFileIds by mutableStateOf(driveRepo.takePendingHighlight())
        private set

    /**
     * 「显示全部」按目录记住：规则仍可能误判，用户在某个目录里点开过，回到这里时应当还是展开的。
     * 记在进程内存里而不是偏好里：目录 id 会越积越多，重启后按默认折叠也说得过去。
     */
    val showAllFilesTemporarily: Boolean by derivedStateOf { DriveViewMemory.showAll[activeFolderId] == true }

    var isHeuristicFilterEnabled by mutableStateOf(true)
        private set

    var isNameParsing by mutableStateOf(true)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 面向用户的一次性提示，各端自己决定怎么呈现。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val folderStack get() = driveRepo.folderStackFlow
    val activeFolder: PikoPathBreadcrumb
        get() = driveRepo.folderStackFlow.value.lastOrNull() ?: PikoPathBreadcrumb("", "网盘")

    private var activeFolderId by mutableStateOf(driveRepo.folderStackFlow.value.lastOrNull()?.id.orEmpty())

    /**
     * [files] 的分析结果。只在 [analyzedFiles] 与 [files] 是同一个列表时可用：换目录后、新结果
     * 算出来之前，不能拿上一个目录的结构去排这一个目录的文件。
     */
    private var analyzedFiles by mutableStateOf<List<FileStat>?>(null)
    private var analysis by mutableStateOf<DriveStructure?>(null)

    private val currentAnalysis: DriveStructure? by derivedStateOf { analysis?.takeIf { analyzedFiles === files } }

    /** 按文件夹 id 的显示信息，后台算好逐个填入。解析关闭时界面不读它。 */
    val folderViews = mutableStateMapOf<String, DriveFolderView>()

    // 以下都用 derivedStateOf 而不是 getter：这些值每帧会被读到多次（列表、空态判断、
    // 全选、折叠提示各读一次），纯 getter 意味着同一帧内把千项目录过滤好几遍。
    // 整层都是次要项时不折叠（原盘的 CLIPINF/ 全是结构文件）：折光了列表为空，连折叠横幅也没处放
    private val isFoldingActive: Boolean by derivedStateOf {
        val folded = currentAnalysis?.foldedIds ?: return@derivedStateOf false
        isHeuristicFilterEnabled && isNameParsing && folded.size < files.size && isFoldingScope(files)
    }

    val potentialHiddenCount: Int by derivedStateOf {
        if (isFoldingActive) currentAnalysis?.foldedIds?.size ?: 0 else 0
    }

    /**
     * 按类型筛选，null 为不筛。作用于眼前这份列表：目录内容、目录内搜索或全盘搜索的结果。
     * 筛选时只留该类文件、去掉文件夹并平铺，与搜索一样不分作品与分区：分区是按整个目录算的，
     * 只剩一类文件时大半分区是空的。换目录时清掉，与搜索词一样只属于当前这一眼。
     */
    var typeFilter by mutableStateOf<FileCategory?>(null)
        private set

    private val isSearching: Boolean by derivedStateOf { isGlobalSearchActive || searchQuery.isNotBlank() || typeFilter != null }

    /** 筛选之前、搜索之后的文件，类型筛选与可选类型都从这一份算。 */
    private val searchedFiles: List<FileStat> by derivedStateOf {
        when {
            isGlobalSearchActive -> globalSearchHits.map { it.file }
            searchQuery.isNotBlank() -> files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
            else -> files
        }
    }

    /** 眼前这份列表里出现过的类型及其数量，按 [FileCategory] 的声明顺序。筛选菜单只列这些，免得选出空列表。 */
    val availableTypes: List<Pair<FileCategory, Int>> by derivedStateOf {
        val counts = searchedFiles.filterNot { it.isFolder }.groupingBy { it.fileCategory() }.eachCount()
        FileCategory.entries.mapNotNull { category -> counts[category]?.let { category to it } }
    }

    /**
     * 只列有缩略图的文件，文件夹照留，图库视图用，由界面按视图设置。放在这一层而不是只在界面上不画：
     * 全选、移动所选与图片翻页都读 [displayedFiles]，看不见的文件混在里面就会被一并移走或删掉。
     */
    var thumbnailsOnly by mutableStateOf(false)
        private set

    fun updateThumbnailsOnly(value: Boolean) {
        if (value == thumbnailsOnly) return
        thumbnailsOnly = value
        if (isSelectionMode) exitSelection()
    }

    private fun isHiddenByThumbnails(file: FileStat) = thumbnailsOnly && !file.isFolder && file.thumbnailLink.isEmpty()

    /** 列表项：作品头、分区标题与文件。搜索、筛选与解析关闭时照原样平铺，认不出任何作品时也平铺。 */
    val displayItems: List<DriveListItem> by derivedStateOf {
        if (thumbnailsOnly) hideFiles(unfilteredItems, ::isHiddenByThumbnails) else unfilteredItems
    }

    private val unfilteredItems: List<DriveListItem> by derivedStateOf {
        val structure = currentAnalysis
        val hideFolded = isFoldingActive && !showAllFilesTemporarily
        val filter = typeFilter
        when {
            filter != null -> searchedFiles.filter { !it.isFolder && it.fileCategory() == filter }.map { DriveListItem.File(it, null) }
            isGlobalSearchActive || searchQuery.isNotBlank() -> searchedFiles.map { DriveListItem.File(it, null) }
            structure == null -> files.map { DriveListItem.File(it, null) }
            !isNameParsing || structure.blocks.isEmpty() ->
                filterDriveFiles(files, structure.foldedIds, enabled = hideFolded, revealAll = false).map { DriveListItem.File(it, null) }
            else -> buildDriveItems(files, structure, hideFolded) { block -> isBlockExpanded(block) }
        }
    }

    /**
     * 当前可见的文件，顺序与界面一致。收起的分区与挂在视频下的附件也算在内：它们只是没单独占一行，
     * 全选、播放列表与图片翻页都该包括它们。
     */
    val displayedFiles: List<FileStat> by derivedStateOf {
        val structure = currentAnalysis
        if (isSearching || structure == null || !isNameParsing || structure.blocks.isEmpty()) {
            return@derivedStateOf displayItems.mapNotNull { (it as? DriveListItem.File)?.file }
        }
        val hideFolded = isFoldingActive && !showAllFilesTemporarily
        val shown = buildDriveItems(files, structure, hideFolded) { true }.mapNotNull { (it as? DriveListItem.File)?.file }
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val attachments = files.filter { file -> structure.attachedTo[file.id]?.let { it in shownIds } == true }
        (shown + attachments).filterNot(::isHiddenByThumbnails)
    }

    /** 列表项里的分区标题及其下标。顶栏副标题按首个可见项反查，分区菜单据此跳转。 */
    val sectionHeaders: List<IndexedValue<DriveListItem.SectionHeader>> by derivedStateOf {
        displayItems.withIndex().mapNotNull { (index, item) -> (item as? DriveListItem.SectionHeader)?.let { IndexedValue(index, it) } }
    }

    private fun isBlockExpanded(block: DriveBlock): Boolean =
        DriveViewMemory.expanded[expandKey(block.id)] ?: block.defaultExpanded

    private fun expandKey(blockId: String) = "$activeFolderId|$blockId"

    fun toggleSection(blockId: String) {
        val block = currentAnalysis?.blocks?.firstOrNull { it.id == blockId }
        val current = block?.let(::isBlockExpanded) ?: true
        DriveViewMemory.expanded[expandKey(blockId)] = !current
    }

    fun expandSection(blockId: String) {
        DriveViewMemory.expanded[expandKey(blockId)] = true
    }

    /** 文件的解析结果，详情面板用。解析关闭或未识别时为 null。 */
    fun fileView(fileId: String): DriveFileView? = if (!isNameParsing) null else currentAnalysis?.views?.get(fileId)

    /**
     * 全盘命中所在的目录路径。SDK 给的 parentPath 不含根，根目录下的命中拿到的是
     * 空串，这里补上，否则那一行整个不显示。
     */
    val hitLocations: Map<String, String> by derivedStateOf {
        if (isGlobalSearchActive) {
            globalSearchHits.associate { it.file.id to it.parentPath.ifEmpty { "网盘" } }
        } else {
            emptyMap()
        }
    }

    init {
        scope.launch {
            preferences.heuristicFilterFlow.collect { isHeuristicFilterEnabled = it }
        }
        scope.launch {
            preferences.nameParsingFlow.collect { isNameParsing = it }
        }
        // 目录一变就重新加载，不管是谁改的栈。只由这里负责：界面外的跳转（「在网盘中显示」）改栈时
        // 网盘页可能一直开着、不会重建，让各导航入口自己调加载的话，这条路就漏掉了
        scope.launch {
            driveRepo.folderStackFlow.collect { stack ->
                val id = stack.lastOrNull()?.id.orEmpty()
                if (id == activeFolderId) return@collect
                activeFolderId = id
                onFolderChanged()
            }
        }
        // 同理，高亮请求随时可能来，不只在网盘页建出来的那一刻
        scope.launch {
            driveRepo.pendingHighlights.collect { ids -> if (ids.isNotEmpty()) highlightedFileIds = driveRepo.takePendingHighlight() }
        }
        scope.launch {
            snapshotFlow { highlightedFileIds to activeFolderId }.collectLatest { (ids, folderId) ->
                if (ids.isNotEmpty()) catchUpWithHighlight(ids, folderId)
            }
        }
        // 要定位的条目若在收起的分区里、或被启发式折叠藏着，列表里就没有它可滚动：展开它所在的分区并显示全部
        scope.launch {
            snapshotFlow { highlightedFileIds to currentAnalysis }.collect { (ids, structure) ->
                if (ids.isEmpty() || structure == null) return@collect
                if (isFoldingActive && ids.any { it in structure.foldedIds }) setShowAllFiles(true)
                structure.blocks.filter { block -> block.fileIds.any { it in ids } }.forEach { expandSection(it.id) }
            }
        }
        // 解析放到后台：上千个文件的目录要算几秒。按内容缓存，重组、刷新与返回上级都不重算
        scope.launch {
            snapshotFlow { files }.collectLatest { list ->
                val key = DriveViewMemory.fingerprint(list)
                val structure = DriveViewMemory.structure(key)
                    ?: withContext(Dispatchers.Default) { analyzeDriveFolder(list) }.also { DriveViewMemory.putStructure(key, it) }
                analysis = structure
                analyzedFiles = list
            }
        }
        scope.launch {
            // 记下的文件夹内容启动后才从磁盘载入完，载入后再描述一遍
            combine(snapshotFlow { files }, driveRepo.childContentLoads) { list, _ -> list }
                .collectLatest { list -> describeFolders(list.filter(FileStat::isFolder)) }
        }
        // 回收站恢复这类界面外的改动由仓库层广播过来，订阅放在这里，
        // 免得每个平台的视图各订阅一遍
        scope.launch {
            driveRepo.refreshEvents.collect { load() }
        }
    }

    /**
     * 恢复上次退出时的目录栈。走这里而不是让视图直接调仓库，是因为恢复同样要
     * 触发一次加载；视图直接改栈会绕过加载，表现为进来是空列表。
     */
    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        // 栈顶没变时栈的监听不会触发，这一次加载由这里补上
        val unchanged = stack.last().id == activeFolderId
        driveRepo.updateFolderStack(stack)
        if (unchanged) onFolderChanged()
    }

    /**
     * [files] 当前是哪个目录的内容。视图据此恢复滚动位置：必须等列表真的换成目标目录的
     * 内容再恢复，否则会把旧列表的位置记到新目录名下。
     */
    var loadedFolderId by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null

    /**
     * 路径栈上的目录有缓存时先显示缓存，不进加载态，再在后台刷新；返回上级与切回网盘页
     * 因此是即时的。下拉刷新不用缓存。
     *
     * 新的加载取消旧的：进入 A 后未等返回就进了 B，A 晚到的结果不能盖掉 B。
     */
    fun load(refresh: Boolean = false) = load(useCache = !refresh, showRefreshing = refresh)

    /** [useCache] 与 [showRefreshing] 都为 false 是静默重列：不用缓存，也不出任何加载指示，见 [catchUpWithHighlight]。 */
    private fun load(useCache: Boolean, showRefreshing: Boolean) {
        val folderId = activeFolder.id
        val cached = if (useCache) driveRepo.cachedFiles(folderId, sortOrder) else null
        when {
            cached != null -> {
                files = cached
                loadedFolderId = folderId
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            useCache -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val listing = driveRepo.listAllFiles(parentId = folderId, sortOrder = sortOrder)
            // 空列表可能是目录已经不在了：上次退出时停在的目录后来被删，或在别的客户端进了回收站。
            // 这时退回上一级，而不是把一个不存在的目录画成「此文件夹为空」。上一级也不在的话，
            // 它的加载会再退一级。只在列表为空时才多查一次详情，平常的目录不多花请求
            val empty = listing.getOrNull()?.isEmpty() == true
            if (empty && folderId.isNotEmpty() && driveRepo.isFolderGone(folderId)) {
                isLoading = false
                isRefreshing = false
                if (navigateUp()) _messages.tryEmit("文件夹已不存在，已返回上一级")
                return@launch
            }
            listing
                .onSuccess {
                    files = it
                    loadedFolderId = folderId
                    loadError = null
                }
                .logFailure(TAG, "读取目录失败")
                .onFailure {
                    // 消息是一次性的，弹完就没了；而列表此刻显示的是上一次的内容，
                    // 界面需要一个持续的标记才能说明「这是陈旧数据」
                    loadError = it.message ?: "读取网盘失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    fun changeSortOrder(order: PikoFileSortOrder) {
        if (order == sortOrder) return
        sortOrder = order
        load()
    }

    // 以下几个导航只改栈，重新加载与清掉搜索、选中这些由栈的监听统一做，见 init

    fun openFolder(id: String, name: String) {
        driveRepo.pushFolder(id, name)
    }

    fun navigateUp(): Boolean = driveRepo.popFolder() != null

    fun navigateToBreadcrumb(index: Int) {
        driveRepo.popToBreadcrumb(index)
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        driveRepo.navigateToFolder(breadcrumb)
    }

    fun updateTypeFilter(value: FileCategory?) {
        typeFilter = value
        // 选中项可能已被筛掉，留着会让「移动所选」动到看不见的文件
        if (isSelectionMode) exitSelection()
    }

    /** 换了目录：搜索态、选中态、防窥揭示都不该跨目录留存。 */
    private fun onFolderChanged() {
        typeFilter = null
        searchQuery = ""
        stopGlobalSearch()
        exitSelection()
        revealedFileIds.clear()
        load()
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        stopGlobalSearch()
    }

    /**
     * 全盘搜索。PikPak 没有服务端按名搜索，只能逐层遍历，所以结果边走边到，
     * 中途可以停下并保留已找到的部分。
     */
    fun startGlobalSearch() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) return
        globalSearchHits.clear()
        isGlobalSearchActive = true
        isGlobalSearching = true
        globalSearchJob = scope.launch {
            try {
                driveRepo.searchRecursive(keyword).collect { globalSearchHits.add(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "全盘搜索失败，已找到 ${globalSearchHits.size} 项", e)
                _messages.tryEmit("全盘搜索失败")
            } finally {
                isGlobalSearching = false
            }
        }
    }

    /** 只停止遍历，已找到的结果留在列表里。 */
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

    fun setShowAllFiles(value: Boolean) {
        DriveViewMemory.showAll[activeFolderId] = value
    }

    private suspend fun describeFolders(folders: List<FileStat>) {
        folders.forEach { folder -> folderViews[folder.id] = folderView(folder, driveRepo.knownChildContents(folder.id)) }
    }

    private suspend fun folderView(folder: FileStat, content: List<ChildFile>?): DriveFolderView {
        val key = DriveViewMemory.folderKey(folder, content)
        return DriveViewMemory.folderView(key)
            ?: withContext(Dispatchers.Default) { describeDriveFolder(folder.name, content) }.also { DriveViewMemory.putFolderView(key, it) }
    }

    /**
     * 文件夹行在可见区域里时调用，挂起到预取完成；行离开可见区域，调用方的协程取消，请求随之作罢。
     *
     * 不知道里面有什么的文件夹补取一页文件，记下来描述文件夹行用：列表接口不给文件夹里的文件名，
     * 不预取的话文件夹要点进去一次才认得出作品名。记下的内容跨进程保留（FolderContentMemory），
     * 所以同一个文件夹只取一次。停留不到 [PREFETCH_DWELL_MILLIS] 的不取：快速滑过的一屏文件夹不该各发一个请求。
     * 搜索结果散在各处，不预取。
     */
    suspend fun onFolderVisible(folder: FileStat) {
        if (!isNameParsing || isSearching) return
        if (driveRepo.knownChildContents(folder.id) != null) return
        delay(PREFETCH_DWELL_MILLIS)
        val content = driveRepo.fetchChildContents(folder.id) ?: return
        folderViews[folder.id] = folderView(folder, content)
    }

    fun toggleSpoiler(fileId: String) {
        if (!revealedFileIds.remove(fileId)) revealedFileIds.add(fileId)
    }

    fun enterSelection(fileId: String? = null) {
        isSelectionMode = true
        if (fileId != null && fileId !in selectedFileIds) selectedFileIds.add(fileId)
    }

    fun exitSelection() {
        isSelectionMode = false
        selectedFileIds.clear()
        selectionAnchor = null
    }

    fun setSelected(fileId: String, selected: Boolean) {
        if (selected) {
            if (fileId !in selectedFileIds) selectedFileIds.add(fileId)
        } else {
            selectedFileIds.remove(fileId)
        }
    }

    /** Shift 点选的起点：最近一次单独点选的那一项。换目录、退出多选后作废。 */
    private var selectionAnchor: String? = null

    /** 桌面的 Ctrl（⌘）点选：切换这一项，不在多选时先进入多选。它成为 Shift 点选的起点。 */
    fun toggleSelected(fileId: String) {
        isSelectionMode = true
        setSelected(fileId, fileId !in selectedFileIds)
        selectionAnchor = fileId
        if (selectedFileIds.isEmpty()) exitSelection()
    }

    /**
     * 桌面的 Shift 点选：把起点到 [fileId] 之间（按眼前的顺序，含两端）全部选上，起点不动，
     * 连续 Shift 点选以同一个起点伸缩。没有起点时只选这一项，与文件管理器相同。
     */
    fun selectRange(fileId: String) {
        val order = displayedFiles.map { it.id }
        val anchor = selectionAnchor?.takeIf { it in order }
        isSelectionMode = true
        if (anchor == null) {
            setSelected(fileId, true)
            selectionAnchor = fileId
            return
        }
        val from = order.indexOf(anchor)
        val to = order.indexOf(fileId).takeIf { it >= 0 } ?: return
        order.subList(minOf(from, to), maxOf(from, to) + 1).forEach { setSelected(it, true) }
    }

    fun toggleSelectAll() {
        val visible = displayedFiles.map { it.id }
        if (selectedFileIds.size == visible.size) {
            selectedFileIds.clear()
        } else {
            selectedFileIds.clear()
            selectedFileIds.addAll(visible)
        }
    }

    fun highlight(ids: Set<String>) {
        highlightedFileIds = ids
    }

    fun clearHighlight() {
        highlightedFileIds = emptySet()
    }

    /**
     * 要高亮的条目常是刚写进网盘的（秒传、恢复），列表却还没有它们：存进眼前这个目录时栈没变，
     * 不会重新加载；存进别的目录时那一次加载先给缓存、再列一次，而列表接口比写入晚 0.1 到 0.8 秒
     * 才看得到新文件（2026-09-27 实测），那一次多半扑空。这里等手头的加载结束，仍缺就静默重列，
     * 按 [HIGHLIGHT_RETRY_DELAYS] 退避，齐了或换了目录（collectLatest 取消这里）即停。
     * 不改成保存后固定等一会儿再列：延迟因次而异，等短了照样扑空，等长了每次都白等。
     * 条目在子目录里（保留目录结构的秒传）时永远等不齐，重试有上限，只多花几次请求。
     */
    private suspend fun catchUpWithHighlight(ids: Set<String>, folderId: String) {
        for (wait in HIGHLIGHT_RETRY_DELAYS) {
            loadJob?.join()
            if (activeFolderId != folderId) return
            val present = files.mapTo(HashSet()) { it.id }
            if (ids.all { it in present }) return
            delay(wait)
            load(useCache = false, showRefreshing = false)
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        scope.launch {
            driveRepo.createFolder(activeFolder.id, trimmed)
                .onSuccess {
                    load()
                    _messages.tryEmit("已新建文件夹")
                }
                .logFailure(TAG, "新建文件夹失败")
                .onFailure { _messages.tryEmit("新建文件夹失败") }
        }
    }

    fun rename(fileId: String, newName: String) {
        if (newName.isBlank()) return
        val trimmed = newName.trim()
        scope.launch {
            driveRepo.rename(fileId, trimmed)
                .onSuccess {
                    load()
                    _messages.tryEmit("已重命名")
                }
                .logFailure(TAG, "重命名失败")
                .onFailure { _messages.tryEmit("重命名失败") }
        }
    }

    /** 加或去星标。星标只体现在列表条目的 tags 里，完成后重新列一次，这一项的状态才跟着变。 */
    fun setStarred(file: FileStat, starred: Boolean) {
        scope.launch {
            driveRepo.setStarred(listOf(file.id), starred)
                .onSuccess {
                    load()
                    _messages.tryEmit(if (starred) "已添加星标" else "已取消星标")
                }
                .logFailure(TAG, "修改星标失败")
                .onFailure { _messages.tryEmit(if (starred) "添加星标失败" else "取消星标失败") }
        }
    }

    fun moveToTrash(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.trash(ids)
                .onSuccess {
                    exitSelection()
                    load()
                    _messages.tryEmit(if (ids.size == 1) "已移入回收站" else "已将 ${ids.size} 项移入回收站")
                }
                .logFailure(TAG, "移入回收站失败")
                .onFailure { _messages.tryEmit("移入回收站失败") }
        }
    }

    fun move(ids: List<String>, targetId: String, targetName: String) {
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.move(ids, targetId)
                .onSuccess {
                    exitSelection()
                    load()
                    _messages.tryEmit("已移至 $targetName")
                }
                .logFailure(TAG, "移动失败")
                .onFailure { _messages.tryEmit("移动失败") }
        }
    }

    fun copy(ids: List<String>, targetId: String, targetName: String) {
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.copy(ids, targetId)
                .onSuccess {
                    exitSelection()
                    // 复制到当前目录时新副本就在眼前，要重新列一次
                    if (targetId == activeFolderId) load()
                    _messages.tryEmit("已复制到 $targetName")
                }
                .logFailure(TAG, "复制失败")
                .onFailure { _messages.tryEmit("复制失败") }
        }
    }
}
