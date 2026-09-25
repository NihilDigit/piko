package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val isSearching: Boolean by derivedStateOf { isGlobalSearchActive || searchQuery.isNotBlank() }

    /** 列表项：作品头、分区标题与文件。搜索与解析关闭时照原样平铺，认不出任何作品时也平铺。 */
    val displayItems: List<DriveListItem> by derivedStateOf {
        val structure = currentAnalysis
        val hideFolded = isFoldingActive && !showAllFilesTemporarily
        when {
            isGlobalSearchActive -> globalSearchHits.map { DriveListItem.File(it.file, null) }
            searchQuery.isNotBlank() -> files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }.map { DriveListItem.File(it, null) }
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
        shown + attachments
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
        scope.launch {
            driveRepo.folderStackFlow.collect { stack -> activeFolderId = stack.lastOrNull()?.id.orEmpty() }
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
            snapshotFlow { files }.collectLatest { list -> describeFolders(list.filter(FileStat::isFolder)) }
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
        driveRepo.updateFolderStack(stack)
        onFolderChanged()
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
    fun load(refresh: Boolean = false) {
        val folderId = activeFolder.id
        val cached = if (refresh) null else driveRepo.cachedFiles(folderId, sortOrder)
        when {
            cached != null -> {
                files = cached
                loadedFolderId = folderId
                isLoading = false
            }
            refresh -> isRefreshing = true
            else -> isLoading = true
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

    /** 进入目录。搜索态、选中态、防窥揭示都不该跨目录留存。 */
    fun openFolder(id: String, name: String) {
        driveRepo.pushFolder(id, name)
        onFolderChanged()
    }

    fun navigateUp(): Boolean {
        val popped = driveRepo.popFolder() != null
        if (popped) onFolderChanged()
        return popped
    }

    fun navigateToBreadcrumb(index: Int) {
        driveRepo.popToBreadcrumb(index)
        onFolderChanged()
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        driveRepo.navigateToFolder(breadcrumb)
        onFolderChanged()
    }

    private fun onFolderChanged() {
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
        folders.forEach { folder -> folderViews[folder.id] = folderView(folder, driveRepo.knownChildNames(folder.id)) }
    }

    private suspend fun folderView(folder: FileStat, content: List<String>?): DriveFolderView {
        val key = DriveViewMemory.folderKey(folder, content)
        return DriveViewMemory.folderView(key)
            ?: withContext(Dispatchers.Default) { describeDriveFolder(folder.name, content) }.also { DriveViewMemory.putFolderView(key, it) }
    }

    private val contentRequests = HashSet<String>()

    /**
     * 文件夹进入可见区域时调用。文件夹名看得出是一个发布、作品名却解析不出（多半写成了中文）时，
     * 补取一页文件名再解析；每个文件夹至多请求一次，其余文件夹不发请求。
     */
    fun onFolderVisible(folder: FileStat) {
        if (!isNameParsing || folderViews[folder.id]?.wantsContent != true) return
        if (!contentRequests.add(folder.id)) return
        scope.launch {
            val content = driveRepo.fetchChildNames(folder.id)
            if (content.isNotEmpty()) folderViews[folder.id] = folderView(folder, content)
        }
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
    }

    fun setSelected(fileId: String, selected: Boolean) {
        if (selected) {
            if (fileId !in selectedFileIds) selectedFileIds.add(fileId)
        } else {
            selectedFileIds.remove(fileId)
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
        highlightedFileIds = ids
    }

    fun clearHighlight() {
        highlightedFileIds = emptySet()
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
                .onFailure { _messages.tryEmit("复制失败") }
        }
    }
}
