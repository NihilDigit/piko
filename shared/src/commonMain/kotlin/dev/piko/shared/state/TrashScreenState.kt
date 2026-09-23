package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 回收站的列表、多选与恢复、彻底删除动作，两端共用。
 *
 * 恢复出的文件回到原目录，而网盘界面此刻不在前台、不会自己重新拉取，所以恢复成功后
 * 经仓库层的 refreshEvents 通知它刷新。
 */
class TrashScreenState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    var files by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** 上次加载失败的原因，成功后清空。列表此时仍是旧数据。 */
    var loadError by mutableStateOf<String?>(null)
        private set

    /** 有恢复或删除在进行。两者都改的是同一份列表，并发执行会让选中集合与结果对不上。 */
    var isActionRunning by mutableStateOf(false)
        private set

    var isSelectionMode by mutableStateOf(false)
        private set
    val selectedFileIds = mutableStateListOf<String>()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val isAllSelected: Boolean by derivedStateOf {
        files.isNotEmpty() && selectedFileIds.size == files.size
    }

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        scope.launch { fetch() }
    }

    /** 动作完成后的静默重载：列表原地更新，不出现加载指示。 */
    private suspend fun fetch() {
        driveRepo.trashFiles()
            .onSuccess { list ->
                files = list
                loadError = null
                // 刷新后已被移除的条目不应继续留在选中集合里，否则批量操作会带上失效 id
                val alive = list.mapTo(HashSet()) { it.id }
                selectedFileIds.retainAll { it in alive }
            }
            .onFailure {
                loadError = it.message ?: "读取回收站失败"
                _messages.tryEmit("加载失败")
            }
        isLoading = false
        isRefreshing = false
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
        val all = isAllSelected
        selectedFileIds.clear()
        if (!all) selectedFileIds.addAll(files.map { it.id })
    }

    fun restore(ids: List<String>) {
        runAction(ids) {
            driveRepo.restore(ids)
                .onSuccess {
                    exitSelection()
                    driveRepo.requestRefresh()
                    _messages.tryEmit("已恢复 ${ids.size} 项")
                    fetch()
                }
                .onFailure { _messages.tryEmit("恢复失败") }
        }
    }

    fun deletePermanently(ids: List<String>) {
        runAction(ids) {
            driveRepo.delete(ids)
                .onSuccess {
                    exitSelection()
                    _messages.tryEmit("已彻底删除 ${ids.size} 项")
                    fetch()
                }
                .onFailure { _messages.tryEmit("删除失败") }
        }
    }

    private fun runAction(ids: List<String>, action: suspend () -> Unit) {
        if (ids.isEmpty() || isActionRunning) return
        isActionRunning = true
        scope.launch {
            try {
                action()
            } finally {
                isActionRunning = false
            }
        }
    }
}
