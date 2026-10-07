package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.downloadMaxHeightFor
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 对话框里定下的画质。 */
internal sealed interface DownloadChoice {
    /** 单个视频选定的一档，开始下载时这一档没了就失败，不换。 */
    class Exact(val quality: DownloadQuality) : DownloadChoice

    /** 几项一起时选的级别，每个视频按它各自挑，0 是原画。 */
    class Cap(val maxHeight: Int) : DownloadChoice
}

internal class DownloadRequest(
    val files: List<FileStat>,
    val folderSource: DownloadFolderSource?,
    val onQueued: () -> Unit,
    /** 设置里的下载画质，对话框默认选中它会挑的那一档。 */
    val defaultCap: Int,
) {
    /** 只有一个视频时逐档列出大小，否则只列级别。 */
    val video: FileStat? = files.singleOrNull()?.takeIf { !it.isFolder }
}

/**
 * 「下载」的唯一入口，网盘页（面板、右键菜单、命令栏、多选栏）与播放器都经它，画质问不问、怎么问只在这里定。
 *
 * 有视频、或文件夹里有视频时先弹 [DownloadQualityDialog]，默认选中设置里的下载画质，一次确认即下；
 * 只有别的文件时直接下载。对话框里勾「以后按此画质直接下载」后不再弹，按设置的上限直接下，设置的「下载」里改回。
 * 归档条目与压缩包里的文件经借出的对象取流，只有原画，不弹。
 */
@Stable
class DownloadLauncher internal constructor(
    private val coordinator: PikoDownloadCoordinator,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
) {
    internal var request by mutableStateOf<DownloadRequest?>(null)

    /** [folderSource] 为 null 时 [files] 里的文件夹不下。有东西入队后调 [onQueued]（切到传输页、提示一句）。 */
    fun download(files: List<FileStat>, folderSource: DownloadFolderSource? = null, onQueued: () -> Unit = {}) {
        if (files.isEmpty()) return
        scope.launch {
            val pending = DownloadRequest(files, folderSource, onQueued, preferences.downloadMaxHeightFlow.first())
            val ask = preferences.downloadQualityPromptFlow.first() &&
                (files.any { !it.isFolder && it.hasDownloadQualities } || foldersHoldVideo(files.filter { it.isFolder }, folderSource))
            if (ask) request = pending else enqueue(pending, DownloadChoice.Cap(pending.defaultCap))
        }
    }

    internal fun confirm(request: DownloadRequest, choice: DownloadChoice, keep: Boolean) {
        this.request = null
        if (keep) {
            val cap = when (choice) {
                is DownloadChoice.Exact -> downloadMaxHeightFor(choice.quality)
                is DownloadChoice.Cap -> choice.maxHeight
            }
            scope.launch {
                preferences.setDownloadMaxHeight(cap)
                preferences.setDownloadQualityPrompt(false)
            }
        }
        enqueue(request, choice)
    }

    private fun enqueue(request: DownloadRequest, choice: DownloadChoice) {
        val files = request.files.filter { !it.isFolder }
        val folders = request.files.filter { it.isFolder }
        val cap = (choice as? DownloadChoice.Cap)?.maxHeight
        when (choice) {
            is DownloadChoice.Exact -> coordinator.enqueueQuality(files.single(), choice.quality)
            is DownloadChoice.Cap -> coordinator.enqueueFiles(files, choice.maxHeight)
        }
        val batches = request.folderSource?.takeIf { folders.isNotEmpty() }?.let { coordinator.enqueueFolders(folders, it, cap) } ?: 0
        if (files.isNotEmpty() || batches > 0) request.onQueued()
    }
}

/** 下载它时有没有画质可选：没借出的视频。 */
private val FileStat.hasDownloadQualities: Boolean
    get() = isPlayableVideo() && !LeasedFile.isLeased(id)

/**
 * 文件夹里（含子文件夹）有没有可选画质的视频，见到第一个就停。原先文件夹一律当作有，里面全是文档也弹画质框。
 * 列目录走网盘页的列表缓存，随后的文件夹下载本来也要列这一遍。查到 [FolderProbeLimit] 个文件夹还没见到视频时
 * 当作有：再往下查要等太久，宁可多问一次。列失败同样当作有，下载那一步会报出错。
 */
private suspend fun foldersHoldVideo(folders: List<FileStat>, source: DownloadFolderSource?): Boolean {
    if (folders.isEmpty() || source == null) return false
    val queue = ArrayDeque(folders.map { it.id })
    var probed = 0
    while (queue.isNotEmpty()) {
        if (probed++ >= FolderProbeLimit) return true
        val children = runCatching { source.list(queue.removeFirst()) }.getOrElse { return true }
        if (children.any { !it.isFolder && it.hasDownloadQualities }) return true
        children.filter { it.isFolder }.forEach { queue.addLast(it.id) }
    }
    return false
}

private const val FolderProbeLimit = 64

/** 对话框画在调用处所在的窗口里：播放器在桌面上是单独的窗口。 */
@Composable
fun rememberDownloadLauncher(): DownloadLauncher {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val launcher = remember(services, scope) { DownloadLauncher(services.downloadManager, services.preferences, scope) }
    launcher.request?.let { pending ->
        DownloadQualityDialog(
            request = pending,
            onConfirm = { choice, keep -> launcher.confirm(pending, choice, keep) },
            onDismiss = { launcher.request = null },
        )
    }
    return launcher
}
