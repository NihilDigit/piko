package dev.piko.ui.screens.clips

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.state.Clip
import dev.piko.shared.state.ClipFeedSession
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 信息流右侧几个按钮背后的动作：收藏、分享、下载。提示经 [onMessage] 交给页面的 Snackbar。
 *
 * 段里只带 ID、名字与所在目录，下载要的大小与 gcid、分享框要的图标都得回到完整的条目上。
 * 条目取自会话列目录时记下的；存盘恢复、还没重新列到的，下载前查一次详情补上。
 */
internal class ClipActions(
    private val session: ClipFeedSession,
    private val driveRepo: PikoDriveRepository,
    private val downloads: PikoDownloadCoordinator,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    // 星标状态只在列表条目的 tags 里，详情里的 starred 字段不可信（见 SDK 的 FileDetail）。
    // 进页面时取一次全盘星标，之后以本地改动为准，不为每一段单独查
    private val starred = mutableStateMapOf<String, Boolean>()

    /** 正在分享的条目，界面据此弹出分享框。 */
    var sharing by mutableStateOf<FileStat?>(null)
        private set

    fun loadStars() {
        scope.launch {
            driveRepo.starredFiles()
                .logFailure(TAG, "信息流取星标失败")
                .onSuccess { files -> files.forEach { starred.getOrPut(it.id) { true } } }
        }
    }

    fun isStarred(clip: Clip): Boolean = starred[clip.fileId] ?: session.listedFile(clip.fileId)?.isStarred ?: false

    /** [value] 为 null 时切换。双击画面只加不减，与短视频应用的点赞一致。 */
    fun setStarred(clip: Clip, value: Boolean? = null) {
        val before = isStarred(clip)
        val target = value ?: !before
        if (target == before) return
        // 先改界面再请求，按钮跟手；失败了改回去
        starred[clip.fileId] = target
        scope.launch {
            driveRepo.setStarred(listOf(clip.fileId), target)
                .onSuccess {
                    // 网盘页可能就在旁边，列表里的星标跟着变
                    driveRepo.requestRefresh()
                    onMessage(if (target) "已添加星标" else "已取消星标")
                }
                .logFailure(TAG, "信息流修改星标失败")
                .onFailure {
                    starred[clip.fileId] = before
                    onMessage(if (target) "添加星标失败" else "取消星标失败")
                }
        }
    }

    fun share(clip: Clip) {
        sharing = session.listedFile(clip.fileId) ?: clip.file
    }

    fun dismissShare() {
        sharing = null
    }

    fun download(clip: Clip) {
        scope.launch {
            val file = session.listedFile(clip.fileId) ?: detailOf(clip)
            if (file == null) {
                onMessage("无法下载")
                return@launch
            }
            downloads.enqueue(file)
            onMessage("已加入下载")
        }
    }

    private suspend fun detailOf(clip: Clip): FileStat? =
        driveRepo.getFileDetail(clip.fileId)
            .logFailure(TAG, "信息流下载前查详情失败")
            .getOrNull()
            ?.let { detail ->
                PikoLog.d(TAG, "下载前补查详情：${logFile(clip.fileId, clip.name)}")
                FileStat(
                    kind = FileKind.FILE,
                    id = detail.id,
                    parentId = detail.parentId,
                    name = detail.name,
                    size = detail.size,
                    hash = detail.hash,
                    mimeType = detail.mimeType,
                    fileExtension = detail.fileExtension,
                    params = detail.params,
                )
            }

    private companion object {
        const val TAG = "Clips"
    }
}
