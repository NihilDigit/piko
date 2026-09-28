package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 大窗口侧边栏里的快速访问，照资源管理器：常驻的网盘根目录与 My Pack（秒传与离线下载默认存进它），
 * 其后是用户固定的文件夹。点一下直接跳过去，跳转记进浏览历史，后退能回来。
 * 固定的列表在仓库里，见 [PikoDriveRepository.pinnedFoldersFlow]。
 */
class QuickAccessState(
    private val driveRepo: PikoDriveRepository,
    private val clients: PikoClientProvider,
    private val scope: CoroutineScope,
) {
    val pinnedFolders: Flow<List<PikoPathBreadcrumb>> get() = driveRepo.pinnedFoldersFlow

    /** 根目录里的 My Pack；还没找到、没登录或网盘里没有时为 null，侧边栏不列这一项。 */
    var myPacks by mutableStateOf<PikoPathBreadcrumb?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 找 My Pack，登录或换号时重找（各账号的文件夹 ID 不同），直到调用方取消。由显示快速访问的界面在
     * LaunchedEffect 里调：不显示的时候（手机、窄窗口）不必列根目录。
     */
    suspend fun watchMyPacks() {
        clients.currentClient.collectLatest { client ->
            myPacks = null
            if (client == null) return@collectLatest
            myPacks = driveRepo.findMyPacksFolder().logFailure(TAG, "快速访问找 My Pack 失败").getOrNull()
        }
    }

    fun openRoot() = driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))

    /** My Pack 就在根目录下，路径不必再查。 */
    fun openMyPacks(folder: PikoPathBreadcrumb) = driveRepo.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, folder))

    fun openMyPacksInNewTab(folder: PikoPathBreadcrumb) {
        driveRepo.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB, folder), activate = false)
    }

    fun openRootInNewTab() {
        driveRepo.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB), activate = false)
    }

    /** 固定的只带着自己，上级要逐层查出来才能摆出完整的路径；文件夹被移走过也照样找得到。 */
    fun open(folder: PikoPathBreadcrumb) = withStack(folder) { driveRepo.updateFolderStack(it) }

    /** 在后台的新标签里打开，与中键点文件夹相同。 */
    fun openInNewTab(folder: PikoPathBreadcrumb) = withStack(folder) { driveRepo.openTab(it, activate = false) }

    fun unpin(folder: PikoPathBreadcrumb) = driveRepo.unpinFolder(folder.id)

    private fun withStack(folder: PikoPathBreadcrumb, open: (List<PikoPathBreadcrumb>) -> Unit) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(TAG, "快速访问定位文件夹失败")
                .onSuccess { parents -> open(parents + folder) }
                .onFailure { _messages.emit("找不到这个文件夹，它可能已被删除") }
        }
    }

    private companion object {
        const val TAG = "QuickAccess"
    }
}
