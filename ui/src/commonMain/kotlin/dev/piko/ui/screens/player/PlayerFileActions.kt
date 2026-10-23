package dev.piko.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.log.logFailure
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.drive.DriveActions
import dev.piko.ui.screens.share.ShareDialog
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 完整播放器设置面板里作用于正在播的文件的操作：下载、复制直链、星标、分享。
 *
 * 播放器手里只有文件 ID 与名字，下载要的大小与 gcid、分享框要的类型都得查一次详情；点了才查，不预先请求。
 * 本机副本（[isLocalPlayback]）已经下过了，不给下载。分享框在这里画，提示经 [onMessage] 交给播放器的 Snackbar。
 */
@Composable
fun rememberPlayerFileActions(fileId: String, isLocalPlayback: Boolean, onMessage: (String) -> Unit): List<SheetAction> {
    if (fileId.isEmpty()) return emptyList()
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    var sharing by remember { mutableStateOf<FileStat?>(null) }
    val downloads = rememberDownloadLauncher()
    val isLeasedOrVaulted = LeasedFile.isLeased(fileId) || VaultEntry.isVaulted(fileId)

    var isStarred by remember(fileId) { mutableStateOf(false) }
    LaunchedEffect(fileId, isLeasedOrVaulted) {
        if (isLeasedOrVaulted) return@LaunchedEffect
        fun refreshStar() {
            scope.launch {
                services.driveRepository.starredFiles()
                    .onSuccess { files -> isStarred = files.any { it.id == fileId } }
            }
        }
        refreshStar()
        services.driveRepository.folderChanges.collect {
            refreshStar()
        }
    }

    fun withFile(action: (FileStat) -> Unit) {
        scope.launch {
            val resolved = LeasedFile.resolvedFileOf(fileId)
            if (resolved != null) {
                action(
                    FileStat(
                        kind = FileKind.FILE,
                        id = fileId,
                        name = resolved.name,
                        size = resolved.size.toString(),
                        hash = resolved.gcid.orEmpty(),
                    ),
                )
                return@launch
            }
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

    // 名字与图标取自网盘的同名操作，两处叫法一致；下载、复制直链、星标、分享排成一排
    return buildList {
        if (!isLocalPlayback) {
            add(
                DriveActions.download(
                    onClick = {
                        val resolved = LeasedFile.resolvedFileOf(fileId)
                        if (resolved != null) {
                            services.downloadManager.enqueueResolved(listOf(resolved), "")
                            message("已加入下载")
                        } else {
                            withFile { file -> downloads.download(listOf(file)) { notice -> message(notice ?: "已加入下载") } }
                        }
                    },
                ),
            )
            add(
                DriveActions.copyDownloadLink {
                    scope.launch {
                        val url = services.mediaRepository.fetchDownloadUrl(fileId)
                            .logFailure(TAG, "播放器查文件下载链接失败")
                            .getOrNull()
                        if (!url.isNullOrBlank()) {
                            platform.copyToClipboard("下载链接", url)
                            message("已复制下载链接")
                        } else {
                            message("未能获取下载链接")
                        }
                    }
                },
            )
        }
        if (!isLeasedOrVaulted) {
            add(
                DriveActions.star(
                    starred = isStarred,
                    onClick = {
                        val target = !isStarred
                        isStarred = target
                        scope.launch {
                            services.driveRepository.setStarred(listOf(fileId), target)
                                .onSuccess { message(if (target) "已添加星标" else "已取消星标") }
                                .logFailure(TAG, "播放器修改星标失败")
                                .onFailure {
                                    isStarred = !target
                                    message(if (target) "添加星标失败" else "取消星标失败")
                                }
                        }
                    },
                ),
            )
            add(DriveActions.share { withFile { sharing = it } })
        }
    }
}

private const val TAG = "Player"
