package dev.piko.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.piko.shared.log.logFailure
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.screens.drive.DriveActions
import dev.piko.ui.screens.share.ShareDialog
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 完整播放器顶栏上作用于正在播的文件的操作：分享与下载。原先放在信息流右侧，信息流是一段段刷过去的，
 * 那里要的只是收藏、看完整与在网盘中显示；真想留下或发给别人时，多半已经点进来看完整了。
 *
 * 播放器手里只有文件 ID 与名字，下载要的大小与 gcid、分享框要的类型都得查一次详情；点了才查，不预先请求。
 * 本机副本（[isLocalPlayback]）已经下过了，不给下载。分享框在这里画，提示经 [onMessage] 交给播放器的 Snackbar。
 */
@Composable
fun rememberPlayerFileActions(fileId: String, isLocalPlayback: Boolean, onMessage: (String) -> Unit): List<SheetAction> {
    if (fileId.isEmpty()) return emptyList()
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    var sharing by remember { mutableStateOf<FileStat?>(null) }
    val downloads = rememberDownloadLauncher()

    fun withFile(action: (FileStat) -> Unit) {
        scope.launch {
            services.driveRepository.getFileDetail(fileId)
                .logFailure(TAG, "播放器查文件详情失败")
                .onSuccess { detail ->
                    action(
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
                        ),
                    )
                }
                .onFailure { message("找不到这个文件，它可能已被删除") }
        }
    }

    sharing?.let { file ->
        ShareDialog(
            files = listOf(file),
            onDismiss = { sharing = null },
            onCopied = { message("已复制分享链接") },
        )
    }

    // 名字与图标取自网盘的同名操作，两处叫法一致；下载与网盘同一个入口，画质对话框画在播放器的窗口里
    return buildList {
        add(DriveActions.share { withFile { sharing = it } })
        if (!isLocalPlayback) {
            add(
                DriveActions.download(
                    onClick = { withFile { file -> downloads.download(listOf(file)) { message("已加入下载") } } },
                    onPrepare = { services.mediaRepository.prefetchDownloadQualities(fileId) },
                ),
            )
        }
    }
}

private const val TAG = "Player"
