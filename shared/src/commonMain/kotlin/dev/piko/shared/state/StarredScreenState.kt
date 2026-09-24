package dev.piko.shared.state

import androidx.compose.runtime.getValue
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
 * 星标页：全盘加了星标的文件与文件夹，文件夹在前，按名字排。星标与官方客户端共用。
 *
 * 取消星标后先从列表里拿掉再请求，失败时放回：一次往返要几百毫秒，等它回来再消失显得卡。
 */
class StarredScreenState(
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun load(refresh: Boolean = false) {
        if (refresh) isRefreshing = true else isLoading = true
        scope.launch {
            driveRepo.starredFiles()
                .onSuccess { list ->
                    files = list.sortedWith(compareByDescending<FileStat> { it.isFolder }.thenBy { it.name.lowercase() })
                    loadError = null
                }
                .onFailure {
                    loadError = it.message ?: "读取星标失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    fun unstar(file: FileStat) {
        val before = files
        files = files.filterNot { it.id == file.id }
        scope.launch {
            driveRepo.setStarred(listOf(file.id), starred = false)
                .onSuccess {
                    // 网盘列表里这一项的星标状态也变了
                    driveRepo.requestRefresh()
                }
                .onFailure {
                    files = before
                    _messages.tryEmit("取消星标失败")
                }
        }
    }
}
