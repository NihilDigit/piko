package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 目录选择器：逐层浏览文件夹、按页加载、就地新建。
 *
 * 路径栈是自己的，不碰仓库层的 folderStackFlow：那是网盘主界面的全局位置，
 * 选择器里进出目录会把主界面一起带走。
 */
class FolderPickerState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    var path by mutableStateOf(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        private set
    val current: PikoPathBreadcrumb by derivedStateOf { path.last() }

    var folders by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var isCreatingFolder by mutableStateOf(false)
        private set

    /** 当前目录加载失败的原因，换目录或重载成功后清空。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pageToken = ""
    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        reload()
    }

    fun open(folder: FileStat) {
        path = path + PikoPathBreadcrumb(folder.id, folder.name)
        reload()
    }

    /** 返回上一级。已在根目录时返回 false，由调用方决定是否关闭选择器。 */
    fun navigateUp(): Boolean {
        if (path.size <= 1) return false
        path = path.dropLast(1)
        reload()
        return true
    }

    fun navigateTo(index: Int) {
        if (index !in 0 until path.lastIndex) return
        path = path.take(index + 1)
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        folders = emptyList()
        pageToken = ""
        hasMore = true
        isLoading = true
        loadError = null
        loadJob = scope.launch { loadPage() }
    }

    fun loadMore() {
        if (isLoading || isLoadingMore || !hasMore) return
        loadJob = scope.launch { loadPage() }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || isCreatingFolder) return
        isCreatingFolder = true
        val parent = current
        scope.launch {
            driveRepo.createFolder(parent.id, trimmed)
                .onSuccess { id ->
                    // 建好直接进去：新建目录的目的就是把东西存进它
                    if (current == parent) {
                        path = path + PikoPathBreadcrumb(id, trimmed)
                        reload()
                    }
                }
                .logFailure("FolderPicker", "新建文件夹失败")
                .onFailure { _messages.tryEmit("新建文件夹失败") }
            isCreatingFolder = false
        }
    }

    private suspend fun loadPage() {
        val parentId = current.id
        val generation = ++loadGeneration
        isLoadingMore = true
        var token = pageToken
        var added = 0
        try {
            // 一页 100 条可能全是文件，只靠触底加载永远翻不到后面的文件夹，
            // 因此本轮零收获时继续向后翻，直到拿到文件夹或翻完为止。
            while (true) {
                val (list, next) = driveRepo.listFiles(parentId = parentId, pageToken = token)
                    .getOrElse { error ->
                        PikoLog.w("FolderPicker", "读取目录失败", error)
                        loadError = error.message ?: "未知错误"
                        return
                    }
                val childFolders = list.filter(FileStat::isFolder)
                if (childFolders.isNotEmpty()) {
                    folders = folders + childFolders
                    added += childFolders.size
                }
                token = next
                if (next.isEmpty()) {
                    hasMore = false
                    break
                }
                if (added > 0) break
            }
            pageToken = token
        } finally {
            // 被 reload 取消的旧加载会晚于新加载开始才走到这里，不能替新加载收尾
            if (generation == loadGeneration) {
                isLoading = false
                isLoadingMore = false
            }
        }
    }
}
