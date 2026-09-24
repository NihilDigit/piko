package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 转存一个 PikPak 分享：逐层浏览，勾选当前这一层的条目，转存到目标目录。
 *
 * 勾选只在当前层有效，换层即清空：转存要带上条目所在的各级分享目录，跨层勾选得按目录拆成几次请求，
 * 而分享多半顶层只有一个文件夹，勾它就是整个分享。转存后文件直接落在目标目录下，不带分享里的上级目录。
 */
class ShareSaveState(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
    val shareId: String,
    /** 随链接一起转发来的提取码，有就直接用上，省得再问一遍。 */
    initialPassCode: String = "",
) {
    var info by mutableStateOf<ShareInfo?>(null)
        private set

    /** 分享设了提取码而还没给或给错了。给错时 [errorMessage] 另有说明。 */
    var needsPassCode by mutableStateOf(false)
        private set
    var passCode by mutableStateOf(initialPassCode)

    /** 进到的分享目录，不含顶层。 */
    val path = mutableStateListOf<PikoPathBreadcrumb>()
    var entries by mutableStateOf<List<FileStat>>(emptyList())
        private set
    val selectedIds = mutableStateListOf<String>()

    var isLoading by mutableStateOf(true)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 转存成功后的提示，界面显示后由它清空。 */
    var doneMessage by mutableStateOf<String?>(null)

    val selectedBytes: Long by derivedStateOf { entries.filter { it.id in selectedIds }.sumOf { it.sizeBytes } }

    private var loadJob: Job? = null

    init {
        open()
    }

    /** 读顶层。带着 [passCode]；没设提取码的分享传空串即可。 */
    fun open() {
        load {
            driveRepo.shareInfo(shareId, passCode.trim())
                .onSuccess { share ->
                    info = share
                    needsPassCode = false
                    path.clear()
                    entries = share.files
                }
                .onFailure { error ->
                    val unavailable = error as? ShareUnavailableException
                    if (unavailable?.needsPassCode == true) {
                        needsPassCode = true
                        errorMessage = if (passCode.isBlank()) null else "提取码错误"
                    } else {
                        // 服务端的 statusText 可能是英文，不直接显示；能回 200 却读不了的，是取消或过期了
                        errorMessage = if (unavailable != null) "分享已失效" else "无法读取分享"
                    }
                }
        }
    }

    fun enter(folder: FileStat) {
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    path += PikoPathBreadcrumb(folder.id, folder.name)
                    entries = it
                }
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    /** 回到第 [depth] 层；0 是顶层。 */
    fun goTo(depth: Int) {
        if (depth >= path.size) return
        if (depth == 0) {
            val share = info ?: return
            path.clear()
            entries = share.files
            selectedIds.clear()
            return
        }
        val folder = path[depth - 1]
        val token = info?.passCodeToken ?: return
        load {
            driveRepo.shareFolder(shareId, token, folder.id)
                .onSuccess {
                    while (path.size > depth) path.removeAt(path.lastIndex)
                    entries = it
                }
                .onFailure { errorMessage = "无法打开文件夹" }
        }
    }

    fun toggle(file: FileStat) {
        if (!selectedIds.remove(file.id)) selectedIds += file.id
    }

    fun toggleAll() {
        if (selectedIds.size == entries.size) selectedIds.clear() else {
            selectedIds.clear()
            selectedIds += entries.map { it.id }
        }
    }

    fun save(target: PikoPathBreadcrumb) {
        val token = info?.passCodeToken ?: return
        val ids = selectedIds.toList()
        if (ids.isEmpty() || isSaving) return
        isSaving = true
        errorMessage = null
        scope.launch {
            driveRepo.restoreFromShare(shareId, token, ids, target.id, ancestorIds = path.map { it.id })
                .onSuccess {
                    selectedIds.clear()
                    driveRepo.requestRefresh()
                    doneMessage = "已转存 ${ids.size} 项到 ${target.name}"
                }
                .onFailure { errorMessage = "转存失败：${it.message}" }
            isSaving = false
        }
    }

    private fun load(block: suspend () -> Unit) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        selectedIds.clear()
        loadJob = scope.launch {
            block()
            isLoading = false
        }
    }
}
