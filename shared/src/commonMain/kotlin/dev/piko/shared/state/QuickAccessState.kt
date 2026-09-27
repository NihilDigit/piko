package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 宽窗口网盘页左侧的快捷栏，资源管理器的导航窗格、Finder 的边栏：根目录、星标文件夹与最近去过的文件夹，
 * 点一下直接跳过去，跳转记进浏览历史，后退能回来。
 *
 * 星标取自服务端，改过星标时重新取；最近去过的由仓库在每次换目录时记下，见 [PikoDriveRepository.recentFoldersFlow]。
 */
class QuickAccessState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    /** 星标的文件夹，按名字排。取到之前与失败时为空，快捷栏不显示这一组。 */
    var starredFolders by mutableStateOf<List<FileStat>>(emptyList())
        private set

    val recentFolders: StateFlow<List<List<PikoPathBreadcrumb>>> get() = driveRepo.recentFoldersFlow

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 取星标，改过星标、登录或换号时重取，直到调用方取消。由显示快捷访问的界面在 LaunchedEffect 里调：
     * 不显示的时候（手机、窄窗口）不必请求；也不放在 init 里，holder 在组合期间建出，那时启动的写入界面收不到。
     */
    suspend fun watchStarred() {
        driveRepo.starredChanges.collect { loadStarred() }
    }

    private suspend fun loadStarred() {
        driveRepo.starredFiles()
            .logFailure(TAG, "快捷栏取星标失败")
            .onSuccess { files -> starredFolders = files.filter(FileStat::isFolder).sortedBy { it.name.lowercase() } }
    }

    fun openRoot() = driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))

    fun openRecent(stack: List<PikoPathBreadcrumb>) = driveRepo.updateFolderStack(stack)

    /** 星标只带着自己，上级要逐层查出来才能摆出完整的路径。 */
    fun openStarred(folder: FileStat) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(TAG, "快捷栏定位星标文件夹失败")
                .onSuccess { parents -> driveRepo.updateFolderStack(parents + PikoPathBreadcrumb(folder.id, folder.name)) }
                .onFailure { _messages.emit("找不到这个文件夹，它可能已被移走或删除") }
        }
    }

    private companion object {
        const val TAG = "QuickAccess"
    }
}
