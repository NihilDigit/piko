package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.HeuristicFileFilter
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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

    var highlightedFileIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var showAllFilesTemporarily by mutableStateOf(false)
        private set

    var isHeuristicFilterEnabled by mutableStateOf(true)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 面向用户的一次性提示，各端自己决定怎么呈现。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val folderStack get() = driveRepo.folderStackFlow
    val activeFolder: PikoPathBreadcrumb
        get() = driveRepo.folderStackFlow.value.lastOrNull() ?: PikoPathBreadcrumb("", "网盘")

    /**
     * 启发式折叠只在叶目录、或子目录全是次要目录时启用。上层目录里折叠文件
     * 会把用户真正要找的东西藏起来。
     */
    // 以下都用 derivedStateOf 而不是 getter：这些值每帧会被读到多次（列表、空态判断、
    // 全选、折叠提示各读一次），纯 getter 意味着同一帧内把千项目录过滤好几遍。
    private val heuristicScope: Boolean by derivedStateOf {
        val childFolders = files.filter(FileStat::isFolder)
        childFolders.isEmpty() || childFolders.all { isLikelyNoiseFolderName(it.name) }
    }

    private val heuristicVisibleFiles: List<FileStat> by derivedStateOf {
        filterDriveFiles(
            files,
            enabled = isHeuristicFilterEnabled && heuristicScope,
            revealAll = false,
        )
    }

    val potentialHiddenCount: Int by derivedStateOf {
        (files.size - heuristicVisibleFiles.size).coerceAtLeast(0)
    }

    val displayedFiles: List<FileStat> by derivedStateOf {
        when {
            isGlobalSearchActive -> globalSearchHits.map { it.file }
            searchQuery.isBlank() -> if (showAllFilesTemporarily) files else heuristicVisibleFiles
            else -> files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

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

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        scope.launch {
            driveRepo.listAllFiles(parentId = activeFolder.id, sortOrder = sortOrder)
                .onSuccess {
                    files = it
                    loadError = null
                }
                .onFailure {
                    // 消息是一次性的，弹完就没了；而列表此刻显示的是上一次的内容，
                    // 界面需要一个持续的标记才能说明「这是陈旧数据」
                    loadError = it.message ?: "读取网盘失败"
                    _messages.tryEmit("加载失败: ${it.message}")
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
        showAllFilesTemporarily = false
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
                _messages.tryEmit("全盘搜索失败: ${e.message}")
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
        showAllFilesTemporarily = value
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
                    _messages.tryEmit("已创建文件夹: $trimmed")
                }
                .onFailure { _messages.tryEmit("新建文件夹失败: ${it.message}") }
        }
    }

    fun rename(fileId: String, newName: String) {
        if (newName.isBlank()) return
        val trimmed = newName.trim()
        scope.launch {
            driveRepo.rename(fileId, trimmed)
                .onSuccess {
                    load()
                    _messages.tryEmit("已重命名为: $trimmed")
                }
                .onFailure { _messages.tryEmit("重命名失败: ${it.message}") }
        }
    }

    fun moveToTrash(ids: List<String>, describe: String? = null) {
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.trash(ids)
                .onSuccess {
                    exitSelection()
                    load()
                    _messages.tryEmit(describe?.let { "已移入回收站: $it" } ?: "已移入回收站 ${ids.size} 项")
                }
                .onFailure { _messages.tryEmit("移入回收站失败: ${it.message}") }
        }
    }

    fun move(ids: List<String>, targetId: String, targetName: String) {
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.move(ids, targetId)
                .onSuccess {
                    exitSelection()
                    load()
                    _messages.tryEmit("已移动 ${ids.size} 项到 $targetName")
                }
                .onFailure { _messages.tryEmit("移动失败: ${it.message}") }
        }
    }
}
