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
 * 信息流右侧收藏按钮背后的动作，提示经 [onMessage] 交给页面的 Snackbar。
 * 分享与下载在完整播放器上，见 rememberPlayerFileActions。
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
                // 网盘页可能就在旁边，列表里的星标由仓库通知它重列
                .onSuccess { onMessage(if (target) "已添加星标" else "已取消星标") }
                .logFailure(TAG, "信息流修改星标失败")
                .onFailure {
                    starred[clip.fileId] = before
                    onMessage(if (target) "添加星标失败" else "取消星标失败")
                }
        }
    }

    private companion object {
        const val TAG = "Clips"
    }
}
