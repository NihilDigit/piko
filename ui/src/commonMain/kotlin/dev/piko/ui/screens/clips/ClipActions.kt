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
    // 进页面时取一次全盘星标，并在仓库发生目录/星标变动时同步刷新，保证网盘页与信息流双向一致
    private val starred = mutableStateMapOf<String, Boolean>()
    private val pending = HashSet<String>()
    private var starsLoaded by mutableStateOf(false)

    fun loadStars() {
        scope.launch {
            refreshStars()
            driveRepo.folderChanges.collect {
                refreshStars()
            }
        }
    }

    private suspend fun refreshStars() {
        driveRepo.starredFiles()
            .logFailure(TAG, "信息流取星标失败")
            .onSuccess { files ->
                val remoteIds = files.mapTo(HashSet()) { it.id }
                val toRemove = starred.keys.filter { it !in remoteIds && it !in pending }
                toRemove.forEach(starred::remove)
                remoteIds.forEach { id -> if (id !in pending) starred[id] = true }
                starsLoaded = true
            }
    }

    fun isStarred(clip: Clip): Boolean =
        starred[clip.fileId] ?: if (starsLoaded) false else (session.listedFile(clip.fileId)?.isStarred ?: false)

    /** [value] 为 null 时切换。双击画面只加不减，与短视频应用的点赞一致。 */
    fun setStarred(clip: Clip, value: Boolean? = null) {
        if (dev.piko.shared.data.VaultEntry.isVaulted(clip.fileId)) {
            onMessage("归档文件需要先恢复到网盘才能添加星标")
            return
        }
        val before = isStarred(clip)
        val target = value ?: !before
        if (target == before) return
        // 先改界面再请求，按钮跟手；失败了改回去
        starred[clip.fileId] = target
        pending += clip.fileId
        scope.launch {
            try {
                driveRepo.setStarred(listOf(clip.fileId), target)
                    // 网盘页可能就在旁边，列表里的星标由仓库通知它重列
                    .onSuccess { onMessage(if (target) "已添加星标" else "已取消星标") }
                    .logFailure(TAG, "信息流修改星标失败")
                    .onFailure {
                        starred[clip.fileId] = before
                        onMessage(if (target) "添加星标失败" else "取消星标失败")
                    }
            } finally {
                pending -= clip.fileId
            }
        }
    }

    private companion object {
        const val TAG = "Clips"
    }
}
