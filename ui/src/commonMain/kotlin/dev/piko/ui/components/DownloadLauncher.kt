package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.downloadMaxHeightFor
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

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
    val onQueued: (notice: String?) -> Unit,
) {
    /** 只有一个视频时逐档列出大小，否则只列级别。 */
    val video: FileStat? = files.singleOrNull()?.takeIf { !it.isFolder }
}

/** 点「下载」之后怎么走。 */
internal sealed interface DownloadPlan {
    data object Ask : DownloadPlan

    /** 不问，按上限直接下，0 是原画。 */
    data class Direct(val maxHeight: Int) : DownloadPlan
}

/**
 * 普通下载直接使用默认画质，未设置时用原画。只有显式指定画质才询问，不检查目录内容。
 */
internal fun planDownload(defaultMaxHeight: Int?, chooseQuality: Boolean): DownloadPlan =
    if (chooseQuality) DownloadPlan.Ask else DownloadPlan.Direct(defaultMaxHeight ?: 0)

/** 对话框里勾了「以后按此画质直接下载」时存成默认下载画质的上限；没勾为 null，所选只管这一次。 */
internal fun keptDefaultMaxHeight(choice: DownloadChoice, keep: Boolean): Int? = if (!keep) null else when (choice) {
    is DownloadChoice.Exact -> downloadMaxHeightFor(choice.quality)
    is DownloadChoice.Cap -> choice.maxHeight
}

/** 勾选存下默认画质后，入队时给用户的提示：此后不再弹框，要告诉他去哪里改回。 */
internal const val DEFAULT_QUALITY_SAVED_NOTICE = "已设为默认下载画质，可在设置中更改"

/**
 * 「下载」的唯一入口，网盘页（面板、右键菜单、命令栏、多选栏）与播放器都经它，画质问不问、怎么问只在这里定。
 *
 * 普通下载直接入队；只有 [downloadAtQuality] 打开画质选择。文件夹直接成为可见的准备任务。
 * 对话框里勾「以后按此画质直接下载」即把所选存为默认，不勾只管这一次。
 * 归档条目与压缩包里的文件经借出的对象取流，只有原画，不弹。
 */
@Stable
class DownloadLauncher internal constructor(
    private val coordinator: PikoDownloadCoordinator,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
) {
    internal var request by mutableStateOf<DownloadRequest?>(null)
        private set
    private var isPreparing = false
    private var preparation: Job? = null

    internal fun dismissRequest(pending: DownloadRequest) {
        if (request === pending) request = null
    }

    /**
     * [folderSource] 为 null 时 [files] 里的文件夹不下。有东西入队后调 [onQueued]（切到传输页、提示一句），
     * notice 不为 null 时是要额外显示给用户的一句话，见 [DEFAULT_QUALITY_SAVED_NOTICE]。
     */
    fun download(files: List<FileStat>, folderSource: DownloadFolderSource? = null, onQueued: (notice: String?) -> Unit = {}) {
        start(files, folderSource, chooseQuality = false, onQueued)
    }

    fun downloadAtQuality(files: List<FileStat>, folderSource: DownloadFolderSource? = null, onQueued: (notice: String?) -> Unit = {}) {
        start(files, folderSource, chooseQuality = true, onQueued)
    }

    private fun start(files: List<FileStat>, folderSource: DownloadFolderSource?, chooseQuality: Boolean, onQueued: (String?) -> Unit) {
        if (files.isEmpty() || isPreparing || request != null) return
        val pending = DownloadRequest(files.toList(), folderSource, onQueued)
        if (chooseQuality) {
            request = pending
            return
        }
        isPreparing = true
        val started = TimeSource.Monotonic.markNow()
        PikoLog.d("Download", "准备下载：${files.size} 项，其中 ${files.count { it.isFolder }} 个文件夹")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val default = preferences.downloadMaxHeightFlow.first()
                val plan = planDownload(default, chooseQuality = false)
                currentCoroutineContext().ensureActive()
                PikoLog.d("Download", "下载准备完成：${started.elapsedNow().inWholeMilliseconds} ms，${if (plan is DownloadPlan.Ask) "等待选择画质" else "提交下载"}")
                when (plan) {
                    DownloadPlan.Ask -> request = pending
                    is DownloadPlan.Direct -> enqueue(pending, DownloadChoice.Cap(plan.maxHeight), notice = null)
                }
            } finally {
                if (preparation === currentCoroutineContext()[Job]) {
                    preparation = null
                    isPreparing = false
                }
            }
        }
        preparation = job
        job.start()
    }

    internal fun confirm(request: DownloadRequest, choice: DownloadChoice, keep: Boolean) {
        if (this.request !== request) return
        this.request = null
        val kept = keptDefaultMaxHeight(choice, keep)
        if (kept != null) scope.launch { preferences.setDownloadMaxHeight(kept) }
        enqueue(request, choice, notice = DEFAULT_QUALITY_SAVED_NOTICE.takeIf { kept != null })
    }

    private fun enqueue(request: DownloadRequest, choice: DownloadChoice, notice: String?) {
        val files = request.files.filter { !it.isFolder }
        val folders = request.files.filter { it.isFolder }
        val cap = (choice as? DownloadChoice.Cap)?.maxHeight
        when (choice) {
            is DownloadChoice.Exact -> coordinator.enqueueQuality(files.single(), choice.quality)
            is DownloadChoice.Cap -> coordinator.enqueueFiles(files, choice.maxHeight)
        }
        val batches = request.folderSource?.takeIf { folders.isNotEmpty() }?.let { coordinator.enqueueFolders(folders, it, cap) } ?: 0
        if (files.isNotEmpty() || batches > 0) request.onQueued(notice)
    }
}

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
            onDismiss = { launcher.dismissRequest(pending) },
        )
    }
    return launcher
}
