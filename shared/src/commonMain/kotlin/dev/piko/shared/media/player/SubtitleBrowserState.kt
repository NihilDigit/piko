package dev.piko.shared.media.player

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 播放器里从网盘挑字幕：从视频所在的文件夹开始，列出子文件夹与播放器能读的字幕文件，可以上下进出。
 *
 * 路径栈是自己的，理由同 FolderPickerState：碰仓库层的 folderStackFlow 会把主界面的位置一起带走。
 * 一次列全不分页：字幕通常就在视频旁边，目录不大，分页只会让同目录的字幕排不到一起。
 */
class SubtitleBrowserState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    videoFileId: String,
) {
    /** 视频所在目录的完整路径还没查到时为空。 */
    var path by mutableStateOf<List<PikoPathBreadcrumb>>(emptyList())
        private set
    val current: PikoPathBreadcrumb? by derivedStateOf { path.lastOrNull() }

    var folders by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var subtitles by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null

    init {
        loadJob = scope.launch {
            // 查不到视频所在的目录（本机文件、查询失败）时从根目录开始
            path = driveRepo.locateFolder(videoFileId).getOrNull() ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            load()
        }
    }

    fun open(folder: FileStat) {
        if (path.isEmpty()) return
        path = path + PikoPathBreadcrumb(folder.id, folder.name)
        reload()
    }

    /** 已在根目录时返回 false。 */
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
        if (path.isEmpty()) return
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    private suspend fun load() {
        val folderId = current?.id ?: return
        isLoading = true
        loadError = null
        folders = emptyList()
        subtitles = emptyList()
        driveRepo.listAllFiles(folderId, PikoFileSortOrder.NAME_ASC)
            .onSuccess { files ->
                // .piko 是设置同步的目录，网盘页同样不列
                folders = files.filter { it.isFolder && !(folderId.isEmpty() && it.name == ".piko") }
                subtitles = files.filter { !it.isFolder && isPlayerSubtitleName(it.name) }
            }
            .onFailure { error ->
                PikoLog.w(TAG, "列字幕目录失败", error)
                loadError = error.message ?: "未知错误"
            }
        isLoading = false
    }

    private companion object {
        const val TAG = "SubtitleBrowser"
    }
}
