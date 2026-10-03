package dev.piko.shared.state

import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.data.VaultEdit
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import dev.piko.shared.data.DriveClipboard
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ChildFile
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.library
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.EventPage
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.reportFailure
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

/**
 * 乐观移除失败后，把 [removedIds] 这几项按它们在 [snapshot] 里的位置放回 [current]。
 *
 * 不能直接换回 [snapshot]：几次移除先后进行时，快照里还留着别的操作已经移除成功的条目，
 * 整份换回会让它们重新出现。所以每一项接在快照里它前面、眼下仍在列表中的那一项之后；
 * 眼下已在列表里的（例如期间重列过）不重复放。网盘页的库与我的分享都用它。
 */
internal fun <T> reinsertRemoved(current: List<T>, snapshot: List<T>, removedIds: Set<String>, idOf: (T) -> String): List<T> {
    val result = current.toMutableList()
    val present = current.mapTo(HashSet(), idOf)
    var anchorId: String? = null
    for (item in snapshot) {
        val id = idOf(item)
        if (id in removedIds && id !in present) {
            val at = anchorId?.let { anchor -> result.indexOfFirst { idOf(it) == anchor } + 1 } ?: 0
            result.add(at, item)
            present += id
        }
        if (id in present) anchorId = id
    }
    return result
}

internal fun reinsertRemoved(current: List<FileStat>, snapshot: List<FileStat>, removedIds: Set<String>): List<FileStat> =
    reinsertRemoved(current, snapshot, removedIds) { it.id }

/** 文件夹行在可见区域里停留这么久才预取其内容，见 [DriveScreenState.onFolderVisible]。 */
private const val PREFETCH_DWELL_MILLIS = 400L

/** 高亮的条目不在列表里时，每次静默重列之前等多久。合计约 2.7 秒，盖过实测的列表滞后。 */
private val HIGHLIGHT_RETRY_DELAYS = listOf(300L, 600L, 800L, 1000L)

private const val TAG = "Drive"

/** 对归档条目做只有网盘文件才能做的事时的提示。 */
private const val VAULTED_NEEDS_RESTORE = "已归档的条目需先恢复到网盘"

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
    var highlightRevision by mutableStateOf(0)
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
     * 眼前列的是哪个库（见 [DriveLibrary]），列的是文件夹时为 null。从库里进了子文件夹就是文件夹：
     * 那里的内容与操作都与网盘里无异。
     */
    val libraryView: DriveLibrary? by derivedStateOf { DriveLibrary.of(activeFolderId) }

    /**
     * 最近添加与播放历史里每个文件对应的那条记录，按文件 ID。列表的副文本（何时添加、看到哪里）
     * 与「从列表中移除」都要它。
     */
    var libraryEvents by mutableStateOf<Map<String, DriveEvent>>(emptyMap())
        private set

    /**
     * [files] 的分析结果。只在 [analyzedFiles] 与 [files] 是同一个列表时可用：换目录后、新结果
     * 算出来之前，不能拿上一个目录的结构去排这一个目录的文件。
     */
    private var analyzedFiles by mutableStateOf<List<FileStat>?>(null)
    private var analysis by mutableStateOf<DriveStructure?>(null)

    private val currentAnalysis: DriveStructure? by derivedStateOf { analysis?.takeIf { analyzedFiles === files } }
    val isDisplayStructureReady: Boolean by derivedStateOf { isSearching || libraryView != null || currentAnalysis != null }

    /** 列过的文件夹空不空，见 PikoDriveRepository.folderEmptiness。 */
    val folderEmptiness get() = driveRepo.folderEmptiness

    /** 直接放着归档条目的文件夹，见 PikoDriveRepository.vaultedFolders。 */
    val vaultedFolders get() = driveRepo.vaultedFolders

    /** 按文件夹 id 的显示信息，后台算好逐个填入。解析关闭时界面不读它。 */
    val folderViews = mutableStateMapOf<String, DriveFolderView>()

    // 以下都用 derivedStateOf 而不是 getter：这些值每帧会被读到多次（列表、空态判断、
    // 全选、折叠提示各读一次），纯 getter 意味着同一帧内把千项目录过滤好几遍。
    // 整层都是次要项时不折叠（原盘的 CLIPINF/ 全是结构文件）：折光了列表为空，连折叠横幅也没处放
    private val isFoldingActive: Boolean by derivedStateOf {
        val folded = currentAnalysis?.foldedIds ?: return@derivedStateOf false
        isHeuristicFilterEnabled && isNameParsing && libraryView == null && folded.size < files.size && isFoldingScope(files)
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
            // 库里的条目散在全盘各处，按作品与分区归拢的是一个目录里的东西，这里照原来的先后平铺
            structure == null || libraryView != null -> files.map { DriveListItem.File(it, null) }
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
        if (isSearching || libraryView != null || structure == null || !isNameParsing || structure.blocks.isEmpty()) {
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
        // 先看记下的状态，再看块的默认值：「次要文件」块由 buildDriveItems 临时拼出，不在 blocks 里，
        // 只按 blocks 查的话它永远当作展开，每点一次都写成收起，收起后就再也展不开
        val key = expandKey(blockId)
        val current = DriveViewMemory.expanded[key]
            ?: currentAnalysis?.blocks?.firstOrNull { it.id == blockId }?.defaultExpanded
            ?: true
        DriveViewMemory.expanded[key] = !current
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
            driveRepo.pendingHighlights.collect { ids -> if (ids.isNotEmpty()) highlight(driveRepo.takePendingHighlight()) }
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
                revealHighlightedItems(structure, ids)
            }
        }
        // 解析放到后台：上千个文件的目录要算几秒。按内容缓存，重组、刷新与返回上级都不重算
        scope.launch {
            snapshotFlow { files }.collectLatest { list ->
                val key = DriveViewMemory.fingerprint(list)
                val structure = DriveViewMemory.structure(key)
                    ?: withContext(Dispatchers.Default) { analyzeDriveFolder(list) }.also { DriveViewMemory.putStructure(key, it) }
                Snapshot.withMutableSnapshot {
                    revealHighlightedItems(structure, highlightedFileIds)
                    analysis = structure
                    analyzedFiles = list
                }
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

    /** 恢复上次退出时开着的几个标签，见 [PikoDriveRepository.restoreTabs]。恢复了返回 true。 */
    suspend fun restoreTabs(): Boolean {
        val unchangedBefore = activeFolderId
        if (!driveRepo.restoreTabs()) return false
        // 活动标签就停在根目录时栈顶没变，栈的监听不会触发，加载由这里补上
        if (driveRepo.folderStackFlow.value.lastOrNull()?.id.orEmpty() == unchangedBefore) onFolderChanged()
        return true
    }

    /**
     * 恢复上次退出时的目录栈。走这里而不是让视图直接调仓库，是因为恢复同样要
     * 触发一次加载；视图直接改栈会绕过加载，表现为进来是空列表。
     */
    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        // 栈顶没变时栈的监听不会触发，这一次加载由这里补上
        val unchanged = stack.last().id == activeFolderId
        driveRepo.restoreFolderStack(stack)
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

    // 根目录里的同步与临时文件夹不进入浏览视图。
    private fun withoutInternalFolders(folderId: String, listing: List<FileStat>): List<FileStat> =
        listing.filterNot { isPikoInternalFolder(it, folderId) }

    /** [useCache] 与 [showRefreshing] 都为 false 是静默重列：不用缓存，也不出任何加载指示，见 [catchUpWithHighlight]。 */
    private fun load(useCache: Boolean, showRefreshing: Boolean) {
        val folderId = activeFolder.id
        DriveLibrary.of(folderId)?.let { library ->
            loadLibrary(library, useCache, showRefreshing)
            return
        }
        val cached = if (useCache) driveRepo.cachedBrowsable(folderId, sortOrder) else null
        when {
            cached != null -> {
                files = withoutInternalFolders(folderId, cached)
                loadedFolderId = folderId
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            useCache -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val listing = driveRepo.listBrowsable(parentId = folderId, sortOrder = sortOrder)
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
                    files = withoutInternalFolders(folderId, it)
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

    /** 库上一次列出的内容，返回库时先显示它再刷新，与路径栈上的目录一样。只在这个网盘页的寿命里有效。 */
    private class LibraryListing(val files: List<FileStat>, val events: Map<String, DriveEvent>)

    private val libraryListings = mutableMapOf<DriveLibrary, LibraryListing>()

    private fun loadLibrary(library: DriveLibrary, useCache: Boolean, showRefreshing: Boolean) {
        val cached = if (useCache) libraryListings[library] else null
        when {
            cached != null -> {
                showLibrary(library, cached)
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            useCache -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            fetchLibrary(library)
                .onSuccess { listing ->
                    libraryListings[library] = listing
                    showLibrary(library, listing)
                    loadError = null
                }
                .logFailure(TAG, "读取${library.title}失败")
                .onFailure {
                    loadError = it.message ?: "读取${library.title}失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    private fun showLibrary(library: DriveLibrary, listing: LibraryListing) {
        files = listing.files
        libraryEvents = listing.events
        loadedFolderId = library.id
    }

    // 事件记录只取第一页，至多 100 条，按时间倒序：再往前的播放与添加，到这里来找的人不多
    private suspend fun fetchLibrary(library: DriveLibrary): Result<LibraryListing> = when (library) {
        DriveLibrary.STARRED -> driveRepo.starredFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.TRASH -> driveRepo.trashFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.RECENT -> driveRepo.recentlyAdded().map(::eventListing)
        DriveLibrary.HISTORY -> driveRepo.playHistory().map(::eventListing)
    }

    /**
     * 文件已删除的记录服务端照样返回，只是不再内嵌文件；移进回收站的仍内嵌着。两种都不列：
     * 网盘页的条目要能打开、能操作，一行打不开的记录放在这里只会被当成坏了。同一文件的几条记录只留最新的。
     */
    private fun eventListing(page: EventPage): LibraryListing {
        val live = page.events.mapNotNull { event -> event.file?.takeIf { !it.trashed }?.let { it to event } }.distinctBy { it.first.id }
        return LibraryListing(live.map { it.first }, live.associate { (file, event) -> file.id to event })
    }

    /** 在网盘里打开条目所在的文件夹并标出它，库里的条目用。文件夹也是在上级里标出，而不是进去。 */
    fun revealInDrive(file: FileStat) {
        scope.launch {
            driveRepo.locateFolder(file.id)
                .logFailure(TAG, "定位条目失败")
                .onSuccess { parents ->
                    driveRepo.updateFolderStack(parents)
                    highlight(setOf(file.id))
                }
                .onFailure { _messages.tryEmit("找不到它所在的文件夹") }
        }
    }

    /** 从最近添加或播放历史里移除这几项的记录，文件本身不动。先从列表里拿掉，失败再放回来。 */
    fun removeFromLibrary(ids: Collection<String>) {
        val library = libraryView?.takeIf { it.isEventLog } ?: return
        val eventIds = ids.mapNotNull { libraryEvents[it]?.id }
        if (eventIds.isEmpty()) return
        val before = files
        val removedIds = ids.toSet()
        val generation = libraryGeneration
        files = files.filterNot { it.id in removedIds }
        exitSelection()
        scope.launch {
            driveRepo.deleteEvents(eventIds)
                .onSuccess {
                    libraryListings.remove(library)
                    _messages.tryEmit(if (ids.size == 1) "已从${library.title}中移除" else "已从${library.title}中移除 ${ids.size} 项")
                }
                .logFailure(TAG, "移除记录失败")
                .onFailure {
                    if (activeFolderId == library.id && generation == libraryGeneration) {
                        files = reinsertRemoved(files, before, removedIds)
                    }
                    _messages.tryEmit("移除失败")
                }
        }
    }

    /**
     * 库列表的代次，清空时加一。清空之前发出的单项移除晚于清空失败时不再放回：
     * 那条记录已随清空没了，放回就是往清空的列表里塞一条不存在的记录。
     * 只看位置不够，清空前后都停在播放历史里。
     */
    private var libraryGeneration = 0

    /** 清空播放历史。服务端没有撤销，官方客户端里的历史一起没了，由界面先确认。 */
    fun clearPlayHistory() {
        val before = files
        libraryGeneration++
        files = emptyList()
        exitSelection()
        scope.launch {
            driveRepo.clearPlayHistory()
                .onSuccess {
                    libraryListings.remove(DriveLibrary.HISTORY)
                    _messages.tryEmit("已清空播放历史")
                }
                .logFailure(TAG, "清空播放历史失败")
                .onFailure {
                    if (activeFolderId == DriveLibrary.HISTORY.id) files = reinsertRemoved(files, before, before.mapTo(HashSet()) { it.id })
                    _messages.tryEmit("清空失败")
                }
        }
    }

    /** 回收站里有恢复或彻底删除在进行。两者改的是同一份列表，并发执行会让选中与结果对不上。 */
    var isTrashActionRunning by mutableStateOf(false)
        private set

    fun restoreFromTrash(ids: List<String>) = trashAction(ids) {
        driveRepo.restore(ids)
            .onSuccess {
                exitSelection()
                _messages.tryEmit(if (ids.size == 1) "已恢复" else "已恢复 ${ids.size} 项")
            }
            .logFailure(TAG, "恢复失败")
            .onFailure { _messages.tryEmit("恢复失败") }
    }

    /** 彻底删除，不能撤销，由界面先确认。 */
    fun deletePermanently(ids: List<String>) = trashAction(ids) {
        driveRepo.delete(ids)
            .onSuccess {
                exitSelection()
                _messages.tryEmit(if (ids.size == 1) "已彻底删除" else "已彻底删除 ${ids.size} 项")
            }
            .logFailure(TAG, "彻底删除失败")
            .onFailure { _messages.tryEmit("删除失败") }
    }

    private fun trashAction(ids: List<String>, action: suspend () -> Unit) {
        if (ids.isEmpty() || isTrashActionRunning) return
        isTrashActionRunning = true
        scope.launch {
            try {
                action()
            } finally {
                isTrashActionRunning = false
                load(useCache = false, showRefreshing = false)
            }
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

    /** 浏览历史里的后退与前进，见 PikoDriveRepository.historyFlow。 */
    val history get() = driveRepo.historyFlow

    fun goBack(): Boolean = driveRepo.goBack()

    fun goForward(): Boolean = driveRepo.goForward()

    fun navigateToBreadcrumb(index: Int) {
        driveRepo.popToBreadcrumb(index)
    }

    /**
     * 地址栏里输入的路径，写法见 [addressPathNames]。
     * 找到了就跳过去（记进浏览历史），返回 true；找不到哪一层就提示哪一层，返回 false，地址栏留着让用户改。
     */
    suspend fun goToPath(text: String): Boolean {
        val names = addressPathNames(text, addressBase())
        return driveRepo.resolveFolderPath(names).fold(
            onSuccess = { stack ->
                driveRepo.updateFolderStack(stack)
                true
            },
            onFailure = { error ->
                _messages.emit(
                    if (error is PikoDriveRepository.FolderNotFoundException) error.message!! else "打不开这个路径，请检查网络后重试",
                )
                false
            },
        )
    }

    /**
     * 地址栏边输边给的补全：最后一个分隔符之前是上级（写法同 [goToPath]），之后是正在输的一段，
     * 列出上级里名字含这一段的文件夹，开头相同的在前，不分大小写。
     * 还没输分隔符时上级是根，眼前不在根上的话，当前文件夹里匹配的也列上：多半是想往下走。
     * 上级找不到时 [AddressCompletion.parentFound] 为 false，界面据此改给搜索。
     * 列目录经仓库的子文件夹表，同一层连着敲字不会反复请求；调用方负责防抖与取消旧的一次。
     */
    suspend fun addressCompletions(text: String): AddressCompletion {
        val current = addressBase()
        val cut = text.indexOfLast { it == '/' || it == '\\' }
        val partial = text.substring(cut + 1).trim()
        val parentText = if (cut < 0) "" else text.substring(0, cut + 1)
        val parent = driveRepo.resolveFolderPath(addressPathNames(parentText, current)).getOrNull()
            ?: return AddressCompletion(partial, parentFound = false, matches = emptyList())
        val bases = if (cut < 0 && current.size > 1) listOf(current, parent) else listOf(parent)
        val matches = bases.flatMap { base ->
            val folders = driveRepo.subfolders(base.last().id).getOrNull().orEmpty()
            rankByName(folders, partial).map { base + it }
        }.distinctBy { it.last().id }.take(ADDRESS_COMPLETION_LIMIT)
        return AddressCompletion(partial, parentFound = true, matches = matches)
    }

    // 相对路径与补全以眼前的位置为起点；人在库里时那不是网盘里的一条路径，改从根起
    private fun addressBase(): List<PikoPathBreadcrumb> =
        driveRepo.folderStackFlow.value.takeIf { it.library == null } ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)

    /** 某一级下的全部文件夹，地址栏里路径段后面的 › 点开用。 */
    suspend fun subfoldersOf(folderId: String): Result<List<PikoPathBreadcrumb>> = driveRepo.subfolders(folderId)

    /** 地址栏历史里的快速访问项：只存 ID 与名字，上级逐层查出来再跳，与侧边栏的快速访问相同。 */
    fun openPinned(folder: PikoPathBreadcrumb) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(TAG, "地址栏定位固定的文件夹失败")
                .onSuccess { parents -> driveRepo.updateFolderStack(parents + folder) }
                .onFailure { _messages.emit("找不到这个文件夹，它可能已被删除") }
        }
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
        driveRepo.knownChildContents(folder.id)?.let { known ->
            // 记下的是空表时分不清真空还是只有子文件夹，海报墙要据此画空文件夹，探一下
            if (known.isEmpty()) {
                delay(PREFETCH_DWELL_MILLIS)
                driveRepo.probeFolderEmptiness(folder.id)
            }
            return
        }
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

    /**
     * 桌面的框选：选中的换成 [base] 加上框住的 [boxed]。拖动时每动一下调一次，[base] 是按下时已选的
     * （按着主修饰键开始框选时保留原来的选择，否则为空）。结果为空就退出多选。
     */
    fun selectBoxed(base: Set<String>, boxed: Collection<String>) {
        val next = LinkedHashSet(base).apply { addAll(boxed) }
        if (next.isEmpty()) {
            exitSelection()
            return
        }
        isSelectionMode = true
        if (selectedFileIds.toSet() != next) {
            selectedFileIds.clear()
            selectedFileIds.addAll(next)
        }
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
        Snapshot.withMutableSnapshot {
            currentAnalysis?.let { revealHighlightedItems(it, ids) }
            highlightedFileIds = ids
            highlightRevision++
        }
    }

    private fun revealHighlightedItems(structure: DriveStructure, ids: Set<String>) {
        if (ids.isEmpty()) return
        if (ids.any { it in structure.foldedIds }) setShowAllFiles(true)
        structure.blocks.filter { block -> block.fileIds.any { it in ids } }.forEach { expandSection(it.id) }
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
        if (name.isBlank() || libraryView != null) return
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
        if (VaultEntry.isVaulted(fileId)) {
            renameInVault(fileId, trimmed)
            return
        }
        val listedName = knownFile(fileId)?.name
        scope.launch {
            // 撤销要用旧名字。列表刚被别的改动刷走、还没列回来时找不到这一项，改之前向服务端查一次
            val oldName = listedName ?: driveRepo.getFileDetail(fileId).getOrNull()?.name
            driveRepo.rename(fileId, trimmed)
                .onSuccess {
                    load()
                    if (oldName != null && oldName != trimmed) {
                        driveRepo.changes.record(DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(fileId, oldName, trimmed)), "已重命名"))
                    } else {
                        _messages.tryEmit("已重命名")
                    }
                }
                .logFailure(TAG, "重命名失败")
                .onFailure { _messages.tryEmit("重命名失败") }
        }
    }

    /** 加或去星标。星标只体现在列表条目的 tags 里，完成后重新列一次，这一项的状态才跟着变。 */
    fun setStarred(file: FileStat, starred: Boolean) {
        if (file.isVaulted) {
            _messages.tryEmit(VAULTED_NEEDS_RESTORE)
            return
        }
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

    /** 归档条目没有文件可进回收站，删它就是从清单里移除，见 [removeFromVault]。 */
    fun moveToTrash(ids: List<String>) {
        val (vaulted, real) = ids.partition(VaultEntry::isVaulted)
        removeFromVault(vaulted)
        if (real.isEmpty()) return
        scope.launch {
            driveRepo.trash(real)
                .onSuccess {
                    exitSelection()
                    load()
                    driveRepo.changes.record(
                        DriveChangeJournal.Change.Trash(real, if (real.size == 1) "已移入回收站" else "已将 ${real.size} 项移入回收站"),
                    )
                }
                .logFailure(TAG, "移入回收站失败")
                .onFailure { _messages.tryEmit("移入回收站失败") }
        }
    }

    /**
     * [sources] 是各项原来所在的文件夹，撤销时移回去用。眼前列表里有的从列表取；剪切后换了目录再粘贴时
     * 它们已不在列表里，由剪贴板带过来，否则会被当成原本就在目标里。
     */
    fun move(ids: List<String>, targetId: String, targetName: String, sources: Map<String, String> = emptyMap()) {
        fun sourceOf(id: String) = knownFile(id)?.parentId ?: sources[id]
        // 已经在目标里的不动：拖回原处、把文件夹拖到它自己上面，服务端要么白做一次、要么拒绝
        val moving = withoutVaulted(ids).filter { it != targetId && sourceOf(it) != targetId }
        if (moving.isEmpty()) return
        val from = moving.associateWith { sourceOf(it) ?: activeFolderId }
        scope.launch {
            driveRepo.move(moving, targetId)
                .onSuccess {
                    exitSelection()
                    load()
                    val summary = if (moving.size == 1) "已移至 $targetName" else "已将 ${moving.size} 项移至 $targetName"
                    driveRepo.changes.record(DriveChangeJournal.Change.Move(from, targetId, summary))
                }
                .logFailure(TAG, "移动失败")
                .onFailure { _messages.tryEmit("移动失败") }
        }
    }

    /** 撤销最近一次移动、移入回收站或重命名，见 [DriveChangeJournal]。 */
    fun undoLast(): Boolean = driveRepo.changes.undoLast()

    fun undo(change: DriveChangeJournal.Change) = driveRepo.changes.undo(change)

    /** 做完一次可撤销的改动或撤销之后的提示，界面把可撤销的配上「撤销」按钮。 */
    val changeEvents get() = driveRepo.changes.events

    // 眼前列表里的那一项：全盘搜索的结果不在当前目录的列表里，两处都找
    private fun knownFile(id: String): FileStat? = displayedFiles.firstOrNull { it.id == id } ?: files.firstOrNull { it.id == id }

    /** 剪切或复制到剪贴板，照资源管理器：换个目录粘贴，见 [paste]。 */
    fun putOnClipboard(requested: List<String>, cut: Boolean) {
        val ids = withoutVaulted(requested)
        if (ids.isEmpty()) return
        driveRepo.setClipboard(DriveClipboard(ids.associateWith { knownFile(it)?.parentId ?: activeFolderId }, cut))
        val verb = if (cut) "剪切" else "复制"
        _messages.tryEmit(if (ids.size == 1) "已$verb，到目标文件夹粘贴" else "已$verb ${ids.size} 项，到目标文件夹粘贴")
    }

    /** 粘贴到眼前的文件夹：剪切的移过来（剪贴板随即清空，与资源管理器相同），复制的复制一份过来。 */
    fun paste() {
        val clip = driveRepo.clipboardFlow.value ?: return
        val target = driveRepo.folderStackFlow.value.lastOrNull()?.takeIf { DriveLibrary.of(it.id) == null } ?: return
        val ids = clip.sources.keys.toList()
        if (clip.cut) {
            driveRepo.setClipboard(null)
            move(ids, target.id, target.name, clip.sources)
        } else {
            copy(ids, target.id, target.name)
        }
    }

    fun copy(requested: List<String>, targetId: String, targetName: String) {
        val ids = withoutVaulted(requested)
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

    /**
     * 去掉归档条目并提示一句。移动、复制与剪切要的是网盘里的文件，归档条目只是清单里的一行；
     * 在这里拦而不是在各个按钮上：键盘、拖放与命令栏都走到这几个入口。
     */
    private fun withoutVaulted(ids: List<String>): List<String> {
        val real = ids.filterNot(VaultEntry::isVaulted)
        if (real.size < ids.size) _messages.tryEmit(VAULTED_NEEDS_RESTORE)
        return real
    }

    /** 眼前列表里的归档条目，按所在文件夹分组：清单一个文件夹一份。值是列表里的虚拟 ID。 */
    private fun vaultedByFolder(ids: Collection<String>): Map<String, Set<String>> =
        ids.filter(VaultEntry::isVaulted)
            .groupBy { knownFile(it)?.parentId ?: activeFolderId }
            .mapValues { it.value.toSet() }

    private fun entryIds(virtualIds: Collection<String>): Set<String> = virtualIds.mapNotNullTo(HashSet(), VaultEntry::entryIdOf)

    /** 从清单里去掉这几条，可以撤销。网盘里本来就没有它们的文件，这是删除归档条目的唯一含义。 */
    fun removeFromVault(ids: Collection<String>) {
        val byFolder = vaultedByFolder(ids)
        if (byFolder.isEmpty()) return
        scope.launch {
            val removed = mutableMapOf<String, List<VaultEntry>>()
            val result = runSuspendCatching {
                for ((folderId, virtualIds) in byFolder) {
                    val entryIds = entryIds(virtualIds)
                    val write = driveRepo.vault.update(folderId, VaultEdits.remove(entryIds)).getOrThrow()
                    removed[folderId] = write.before.filter { it.id in entryIds }
                }
            }
            // 部分文件夹已经改成功也要记：撤销时把已移除的那些写回去
            val count = removed.values.sumOf { it.size }
            if (count > 0) {
                exitSelection()
                load()
                driveRepo.changes.record(
                    DriveChangeJournal.Change.Vault(
                        removed.mapValues { (_, entries) -> VaultEdits.add(entries) },
                        if (count == 1) "已从归档移除" else "已从归档移除 $count 项",
                    ),
                )
            }
            result.reportFailure(TAG, "从归档移除") { _messages.tryEmit(it) }
        }
    }

    /**
     * 恢复成网盘里的文件：按 gcid 秒传回所在的文件夹，成功的从清单里去掉。要占网盘空间，
     * 先比一次剩余；云端已不存的秒传不出来，也不扣额度，留在清单里。
     */
    data class VaultRestoreProgress(
        val folderName: String,
        val stage: String = "正在扫描归档条目",
        val scannedFolders: Int = 0,
        val done: Int = 0,
        val total: Int? = null,
        val fileName: String? = null,
    )

    var vaultRestoreProgress by mutableStateOf<VaultRestoreProgress?>(null)
        private set

    fun restoreFromVault(ids: Collection<String>) {
        val byFolder = vaultedByFolder(ids)
        if (byFolder.isEmpty() || vaultRestoreProgress != null) return
        vaultRestoreProgress = VaultRestoreProgress("恢复所选文件")
        scope.launch {
            try {
                runSuspendCatching { restoreVaultEntries(byFolder) }
                    .reportFailure(TAG, "恢复归档") { _messages.tryEmit(it) }
            } finally { vaultRestoreProgress = null }
        }
    }

    /** 递归恢复文件夹中的归档条目，真实文件保持原样。清点失败时不开始恢复。 */
    fun restoreVaultFolder(folderId: String, folderName: String = "文件夹") {
        if (vaultRestoreProgress != null) return
        vaultRestoreProgress = VaultRestoreProgress(folderName)
        scope.launch {
            try {
                val result = runSuspendCatching {
                    val byFolder = mutableMapOf<String, Set<String>>()
                    val queue = ArrayDeque<String>()
                    val visited = mutableSetOf<String>()
                    queue.add(folderId)
                    while (queue.isNotEmpty()) {
                        val id = queue.removeFirst()
                        if (!visited.add(id)) continue
                        val listing = driveRepo.listAllFiles(id).getOrThrow()
                        val entries = driveRepo.vault.read(id, listing).getOrThrow().filter { it.isArchived }
                        vaultRestoreProgress = vaultRestoreProgress?.copy(scannedFolders = visited.size)
                        if (entries.isNotEmpty()) byFolder[id] = entries.mapTo(HashSet()) { it.virtualId }
                        listing.filter { it.isFolder && !it.trashed && it.name !in setOf("Piko-Temp", ".piko") }
                            .forEach { queue.add(it.id) }
                    }
                    if (byFolder.isEmpty()) _messages.tryEmit("这个文件夹中没有归档条目")
                    else restoreVaultEntries(byFolder)
                }
                result.reportFailure(TAG, "取消文件夹归档") { _messages.tryEmit(it) }
            } finally { vaultRestoreProgress = null }
        }
    }

    private suspend fun restoreVaultEntries(byFolder: Map<String, Set<String>>) {
        vaultRestoreProgress = vaultRestoreProgress?.copy(stage = "正在检查恢复空间", total = byFolder.values.sumOf { it.size })
        val trash = driveRepo.trashFiles().getOrNull().orEmpty()
        val originals = byFolder.flatMap { (folderId, ids) ->
            ids.mapNotNull { id ->
                val file = VaultEntry.resolvedFileOf(id) ?: return@mapNotNull null
                trash.firstOrNull {
                    !it.isFolder && it.parentId == folderId && it.name == file.name &&
                        it.hash.equals(file.gcid, ignoreCase = true) && it.sizeBytes == file.size
                }?.let { id to it.id }
            }
        }.toMap()
        val needed = byFolder.values.flatten().filterNot { it in originals }
            .sumOf { VaultEntry.resolvedFileOf(it)?.size ?: 0L }
        val remaining = driveRepo.getQuota().getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.remainingBytes
        if (remaining != null && needed > remaining) {
            _messages.tryEmit("网盘空间不足，放不下这 ${byFolder.values.sumOf { it.size }} 项")
            return
        }
        var restored = 0
        var missing = 0
        val created = mutableListOf<String>()
        val reverts = mutableMapOf<String, VaultEdit>()
        for ((folderId, virtualIds) in byFolder) {
            val done = mutableSetOf<String>()
            val restoredFiles = mutableMapOf<String, FileStat>()
            for (virtualId in virtualIds) {
                val file = VaultEntry.resolvedFileOf(virtualId) ?: continue
                vaultRestoreProgress = vaultRestoreProgress?.copy(stage = "正在恢复到网盘", fileName = file.name)
                val originalId = originals[virtualId]
                val restoredFile = if (originalId != null) driveRepo.restore(listOf(originalId)).map { originalId }
                    else driveRepo.instantCreate(file, folderId)
                restoredFile
                    .onSuccess {
                        done += virtualId
                        created += it
                        VaultEntry.entryIdOf(virtualId)?.let { entryId ->
                            restoredFiles[entryId] = FileStat(id = it, name = file.name, hash = file.gcid.orEmpty(), size = file.size.toString())
                        }
                    }
                    .onFailure { if (it is InstantContentUnavailableException) missing++ }
                    .logFailure(TAG, "恢复归档条目失败")
                vaultRestoreProgress = vaultRestoreProgress?.let { it.copy(done = it.done + 1) }
            }
            if (done.isEmpty()) continue
            // 清单没改成的话，文件已恢复、条目还在，列表里会重复一行，不丢东西
            val doneIds = entryIds(done)
            vaultRestoreProgress = vaultRestoreProgress?.copy(stage = "正在更新归档记录", fileName = null)
            driveRepo.vault.update(folderId, VaultEdits.restore(restoredFiles))
                .onSuccess { write -> reverts[folderId] = VaultEdits.add(write.before.filter { it.id in doneIds }) }
                .logFailure(TAG, "恢复后改写归档清单失败")
            restored += done.size
        }
        exitSelection()
        load()
        val failed = byFolder.values.sumOf { it.size } - restored
        val summary = when {
            failed == 0 -> if (restored == 1) "已恢复到网盘" else "已恢复 $restored 项"
            missing == failed -> "已恢复 $restored 项，$missing 项云端已无内容"
            else -> "已恢复 $restored 项，$failed 项失败"
        }
        if (created.isNotEmpty()) {
            driveRepo.changes.record(DriveChangeJournal.Change.Vault(reverts, summary, trashOnRevert = created))
        } else {
            _messages.tryEmit(summary)
        }
    }

    private fun renameInVault(fileId: String, newName: String) {
        val folderId = knownFile(fileId)?.parentId ?: activeFolderId
        val entryId = VaultEntry.entryIdOf(fileId) ?: return
        scope.launch {
            driveRepo.vault.update(folderId, VaultEdits.rename(entryId, newName))
                .onSuccess { write ->
                    load()
                    val oldName = write.before.firstOrNull { it.id == entryId }?.name
                    if (oldName != null && oldName != newName) {
                        val revert = mapOf(folderId to VaultEdits.rename(entryId, oldName))
                        driveRepo.changes.record(DriveChangeJournal.Change.Vault(revert, "已重命名"))
                    } else {
                        _messages.tryEmit("已重命名")
                    }
                }
                .reportFailure(TAG, "重命名") { _messages.tryEmit(it) }
        }
    }
}

/**
 * 地址栏补全的结果。[matches] 是完整路径栈（含根），末项是匹配 [partial] 的文件夹。
 * [parentFound] 为 false 时输入的上级不存在，这时的输入多半不是路径。
 */
class AddressCompletion(
    val partial: String,
    val parentFound: Boolean,
    val matches: List<List<PikoPathBreadcrumb>>,
)

private const val ADDRESS_COMPLETION_LIMIT = 50

/**
 * 地址栏的输入换成从根起的文件夹名。分隔符 / 与 \ 都认；以 .. 或 . 开头的相对当前位置，
 * 其余一律从根起，开头的 / 与「网盘」可写可不写。.. 可以叠用、也可以写在中间，退过根就停在根。
 */
fun addressPathNames(text: String, current: List<PikoPathBreadcrumb>): List<String> {
    val segments = text.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
    val relative = !text.trimStart().startsWith('/') && segments.firstOrNull().let { it == ".." || it == "." }
    val names = if (relative) current.drop(1).mapTo(ArrayList()) { it.name } else ArrayList()
    segments.forEachIndexed { index, segment ->
        when {
            segment == ".." -> names.removeLastOrNull()
            segment == "." -> Unit
            index == 0 && !relative && segment == PikoDriveRepository.ROOT_BREADCRUMB.name -> Unit
            else -> names += segment
        }
    }
    return names
}

/** 名字含 [partial] 的，开头相同的在前，各自保持原来的顺序；[partial] 为空时全部。 */
private fun rankByName(folders: List<PikoPathBreadcrumb>, partial: String): List<PikoPathBreadcrumb> {
    if (partial.isEmpty()) return folders
    val (prefixed, rest) = folders.filter { it.name.contains(partial, ignoreCase = true) }
        .partition { it.name.startsWith(partial, ignoreCase = true) }
    return prefixed + rest
}
