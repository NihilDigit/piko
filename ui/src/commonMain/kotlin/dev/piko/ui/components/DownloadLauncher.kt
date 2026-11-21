package dev.piko.ui.components

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.downloadMaxHeightFor
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/** 对话框里勾了「以后按此画质直接下载」时存成默认下载画质的上限；没勾为 null，所选只管这一次。 */
private fun keptDefaultMaxHeight(choice: DownloadChoice, keep: Boolean): Int? = if (!keep) null else when (choice) {
    is DownloadChoice.Exact -> downloadMaxHeightFor(choice.quality)
    is DownloadChoice.Cap -> choice.maxHeight
}

/** 勾选存下默认画质后，入队时给用户的提示：此后不再弹框，要告诉他去哪里改回。 */
internal val DEFAULT_QUALITY_SAVED_NOTICE: String get() = tr("已设为默认下载画质，可在设置中更改", "Default download quality saved. Change it in Settings", "既定のダウンロード画質に設定しました。設定画面で変更できます", "기본 다운로드 화질로 설정했습니다. 설정에서 변경할 수 있습니다", "已設為預設下載畫質，可在設定中變更")

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
    private val canPickLocation: Boolean = false,
    private val pickLocation: ((initialPath: String, onResult: (String?) -> Unit) -> Unit)? = null,
) {
    internal var request by mutableStateOf<DownloadRequest?>(null)
        private set
    private var isPreparing = false
    private var preparation: Job? = null

    private sealed interface LocationDecision {
        data class Proceed(val targetDirectory: String?) : LocationDecision
        data object Cancelled : LocationDecision
    }

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

    /** 链接解析、分享树或播放器内临时借出条目的下载入口，开启逐次选择位置时同样先询问保存目录。 */
    fun downloadResolved(
        files: List<ResolvedFile>,
        parentId: String = "",
        onQueued: (notice: String?) -> Unit = {},
    ) {
        if (files.isEmpty() || isPreparing || request != null) return
        val snapshot = files.toList()
        launchPrepared {
            val decision = resolveTargetDirectory()
            if (decision !is LocationDecision.Proceed) return@launchPrepared
            currentCoroutineContext().ensureActive()
            coordinator.enqueueResolved(snapshot, parentId, targetDirectory = decision.targetDirectory)
            onQueued(null)
        }
    }

    /** 视频片段截取下载入口，开启逐次选择位置时在真正入队前询问一次保存目录；取消选择时不关闭面板、不弹报错。 */
    fun downloadSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
        quality: DownloadQuality? = null,
        onQueued: () -> Unit = {},
    ) {
        if (isPreparing || request != null) return
        launchPrepared {
            val decision = resolveTargetDirectory()
            if (decision !is LocationDecision.Proceed) return@launchPrepared
            currentCoroutineContext().ensureActive()
            coordinator.enqueueSegment(
                file = file,
                startMs = startMs,
                endMs = endMs,
                timeRangeLabel = timeRangeLabel,
                streamUrl = streamUrl,
                startByte = startByte,
                lengthBytes = lengthBytes,
                quality = quality,
                targetDirectory = decision.targetDirectory,
            )
            onQueued()
        }
    }

    private fun start(files: List<FileStat>, folderSource: DownloadFolderSource?, chooseQuality: Boolean, onQueued: (String?) -> Unit) {
        if (files.isEmpty() || isPreparing || request != null) return
        val folderCount = files.count { it.isFolder }
        if (folderCount == files.size && folderSource == null) {
            PikoLog.d(TAG, "Skipped download request: ${files.size} folder(s) without folderSource")
            return
        }
        val pending = DownloadRequest(files.toList(), folderSource, onQueued)
        if (chooseQuality) {
            PikoLog.d(TAG, "Awaiting download quality selection: ${files.size} item(s), $folderCount folder(s)")
            request = pending
            return
        }
        val started = TimeSource.Monotonic.markNow()
        PikoLog.d(TAG, "Preparing direct download: ${files.size} item(s), $folderCount folder(s)")
        launchPrepared {
            val decision = resolveTargetDirectory()
            if (decision !is LocationDecision.Proceed) return@launchPrepared
            // 普通下载直接使用默认画质上限，未设置时用 0（原画）；仅显式指定画质才弹窗询问
            val maxHeight = preferences.downloadMaxHeightFlow.first() ?: 0
            currentCoroutineContext().ensureActive()
            PikoLog.d(TAG, "Submitting direct download after ${started.elapsedNow().inWholeMilliseconds} ms (maxHeight=$maxHeight)")
            enqueue(pending, DownloadChoice.Cap(maxHeight), notice = null, targetDirectory = decision.targetDirectory)
        }
    }

    internal fun confirm(request: DownloadRequest, choice: DownloadChoice, keep: Boolean) {
        if (this.request !== request) return
        this.request = null
        val kept = keptDefaultMaxHeight(choice, keep)
        val choiceLabel = when (choice) {
            is DownloadChoice.Exact -> choice.quality.name ?: "original"
            is DownloadChoice.Cap -> if (choice.maxHeight <= 0) "original" else "${choice.maxHeight}P"
        }
        PikoLog.d(TAG, "Confirmed download quality: $choiceLabel, keepDefault=$keep, items=${request.files.size}")
        launchPrepared {
            val decision = resolveTargetDirectory()
            if (decision !is LocationDecision.Proceed) return@launchPrepared
            currentCoroutineContext().ensureActive()
            if (kept != null) preferences.setDownloadMaxHeight(kept)
            enqueue(
                request,
                choice,
                notice = DEFAULT_QUALITY_SAVED_NOTICE.takeIf { kept != null },
                targetDirectory = decision.targetDirectory,
            )
        }
    }

    private fun launchPrepared(block: suspend () -> Unit) {
        if (isPreparing) return
        isPreparing = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
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

    private suspend fun resolveTargetDirectory(): LocationDecision {
        val picker = pickLocation
        if (!canPickLocation || picker == null) return LocationDecision.Proceed(null)
        if (!preferences.askDownloadLocationFlow.first()) return LocationDecision.Proceed(null)
        val defaultDir = preferences.getDownloadDirPath()
        val deferred = CompletableDeferred<String?>()
        try {
            picker(defaultDir) { picked -> deferred.complete(picked) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Failed to invoke download location picker", e)
            return LocationDecision.Cancelled
        }
        val chosen = deferred.await()
        return if (chosen.isNullOrBlank()) {
            PikoLog.d(TAG, "Download cancelled at location picker")
            LocationDecision.Cancelled
        } else {
            LocationDecision.Proceed(chosen)
        }
    }

    private fun enqueue(
        request: DownloadRequest,
        choice: DownloadChoice,
        notice: String?,
        targetDirectory: String?,
    ) {
        val files = request.files.filter { !it.isFolder }
        val folders = request.files.filter { it.isFolder }
        val cap = (choice as? DownloadChoice.Cap)?.maxHeight
        when (choice) {
            is DownloadChoice.Exact -> coordinator.enqueueQuality(files.single(), choice.quality, targetDirectory = targetDirectory)
            is DownloadChoice.Cap -> coordinator.enqueueFiles(files, choice.maxHeight, targetDirectory = targetDirectory)
        }
        val batches = request.folderSource?.takeIf { folders.isNotEmpty() }
            ?.let { coordinator.enqueueFolders(folders, it, cap, targetDirectory = targetDirectory) } ?: 0
        if (files.isNotEmpty() || batches > 0) request.onQueued(notice)
    }

    private companion object {
        const val TAG = "Download"
    }
}

private class PendingLocationCallback {
    var onResult: ((String?) -> Unit)? = null
}

/** 对话框画在调用处所在的窗口里：播放器在桌面上是单独的窗口。 */
@Composable
fun rememberDownloadLauncher(): DownloadLauncher {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val pendingCallback = remember { PendingLocationCallback() }
    val launchPicker = platform.downloadLocation.rememberPickerLauncher { picked ->
        val callback = pendingCallback.onResult
        pendingCallback.onResult = null
        callback?.invoke(picked)
    }
    val currentLaunchPicker by rememberUpdatedState(launchPicker)
    DisposableEffect(pendingCallback) {
        onDispose { pendingCallback.onResult = null }
    }
    val launcher = remember(services, platform, scope, pendingCallback) {
        DownloadLauncher(
            coordinator = services.downloadManager,
            preferences = services.preferences,
            scope = scope,
            canPickLocation = platform.downloadLocation.canChoose,
            pickLocation = { initialPath, onResult ->
                pendingCallback.onResult = onResult
                currentLaunchPicker(initialPath)
            },
        )
    }
    launcher.request?.let { pending ->
        DownloadQualityDialog(
            request = pending,
            onConfirm = { choice, keep -> launcher.confirm(pending, choice, keep) },
            onDismiss = { launcher.dismissRequest(pending) },
        )
    }
    return launcher
}
