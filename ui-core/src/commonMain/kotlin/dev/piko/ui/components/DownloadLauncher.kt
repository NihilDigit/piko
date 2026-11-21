package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.DownloadService
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.downloadMaxHeightFor
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlinx.coroutines.CancellationException
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

@Immutable
internal data class DownloadPreviewItem(
    val key: String,
    val visual: FileVisual,
    val subtitle: String? = null,
)

@Immutable
internal data class DownloadItemSummary(
    val primaryText: String,
    val itemCount: Int,
    val folderCount: Int,
    val totalKnownBytes: Long,
    val timeRangeLabel: String? = null,
    val items: List<DownloadPreviewItem> = emptyList(),
) {
    val isFolderOnly: Boolean get() = itemCount == 1 && folderCount == 1
}

internal sealed interface DownloadPayload {
    class Files(
        val files: List<FileStat>,
        val folderSource: DownloadFolderSource?,
        val onQueued: (notice: String?) -> Unit,
    ) : DownloadPayload

    class Resolved(
        val files: List<ResolvedFile>,
        val parentId: String,
        val onQueued: (notice: String?) -> Unit,
    ) : DownloadPayload

    class Segment(
        val file: FileStat,
        val startMs: Long,
        val endMs: Long,
        val timeRangeLabel: String,
        val streamUrl: String?,
        val startByte: Long,
        val lengthBytes: Long,
        val quality: DownloadQuality?,
        val onNotice: (String) -> Unit,
        val onQueued: () -> Unit,
    ) : DownloadPayload
}

private fun summarizePayload(payload: DownloadPayload): DownloadItemSummary = when (payload) {
    is DownloadPayload.Files -> DownloadItemSummary(
        primaryText = payload.files.first().name,
        itemCount = payload.files.size,
        folderCount = payload.files.count { it.isFolder },
        totalKnownBytes = payload.files.asSequence()
            .filter { !it.isFolder }
            .sumOf { it.sizeBytes.coerceAtLeast(0L) },
        items = payload.files.mapIndexed { idx, file ->
            DownloadPreviewItem(
                key = file.id.ifBlank { "$idx:${file.name}" },
                visual = file.fileVisual(),
                subtitle = if (file.isFolder) {
                    tr("文件夹", "Folder", "フォルダー", "폴더", "資料夾")
                } else {
                    file.sizeBytes.takeIf { it > 0L }?.toReadableSize()
                },
            )
        },
    )
    is DownloadPayload.Resolved -> DownloadItemSummary(
        primaryText = payload.files.first().name,
        itemCount = payload.files.size,
        folderCount = 0,
        totalKnownBytes = payload.files.sumOf { it.size.coerceAtLeast(0L) },
        items = payload.files.mapIndexed { idx, rf ->
            val category = rf.name.fileCategory()
            val isVid = rf.name.isPlayableVideo()
            val hash = rf.gcid?.takeIf { it.isNotBlank() }
            val cover = when {
                hash == null -> null
                isVid -> videoThumbnailUrlOf(hash)
                category == FileCategory.IMAGE -> imageThumbnailUrlOf(hash)
                else -> null
            }
            DownloadPreviewItem(
                key = "$idx:${rf.path}",
                visual = FileVisual(
                    name = rf.name,
                    thumbnail = cover,
                    isVideo = isVid,
                    category = category,
                    stableId = hash,
                ),
                subtitle = rf.size.takeIf { it > 0L }?.toReadableSize(),
            )
        },
    )
    is DownloadPayload.Segment -> DownloadItemSummary(
        primaryText = payload.file.name,
        itemCount = 1,
        folderCount = 0,
        totalKnownBytes = payload.lengthBytes.coerceAtLeast(0L),
        timeRangeLabel = payload.timeRangeLabel.takeIf { it.isNotBlank() },
        items = listOf(
            DownloadPreviewItem(
                key = payload.file.id.ifBlank { payload.file.name },
                visual = payload.file.fileVisual(),
                subtitle = payload.lengthBytes.takeIf { it > 0L }?.toReadableSize(),
            ),
        ),
    )
}

@Stable
internal class DownloadConfirmRequest(
    val payload: DownloadPayload,
    val defaultDirectory: String,
) {
    var targetDirectory by mutableStateOf(defaultDirectory)
        internal set

    val summary: DownloadItemSummary = summarizePayload(payload)
}

@Stable
internal class DownloadRequest(
    val files: List<FileStat>,
    val folderSource: DownloadFolderSource?,
    val onQueued: (notice: String?) -> Unit,
    val askLocation: Boolean = false,
    val defaultDirectory: String = "",
) {
    /** 只有一个视频时逐档列出大小，否则只列级别。 */
    val video: FileStat? = files.singleOrNull()?.takeIf { !it.isFolder }

    var targetDirectory by mutableStateOf(defaultDirectory)
        internal set
}

/** 对话框里勾了「以后按此画质直接下载」时存成默认下载画质的上限；没勾为 null，所选只管这一次。 */
private fun keptDefaultMaxHeight(choice: DownloadChoice, keep: Boolean): Int? = if (!keep) null else when (choice) {
    is DownloadChoice.Exact -> downloadMaxHeightFor(choice.quality)
    is DownloadChoice.Cap -> choice.maxHeight
}

/** 勾选存下默认画质后，入队时给用户的提示：此后不再弹框，要告诉他去哪里改回。 */
internal val DEFAULT_QUALITY_SAVED_NOTICE: String
    get() = tr(
        "已设为默认下载画质，可在设置中更改",
        "Default download quality saved. Change it in Settings",
        "既定のダウンロード画質に設定しました。設定画面で変更できます",
        "기본 다운로드 화질로 설정했습니다. 설정에서 변경할 수 있습니다",
        "已設為預設下載畫質，可在設定中變更",
    )

/** 勾选存下默认保存位置并关闭每次询问后，入队时给用户的提示。 */
internal val DEFAULT_LOCATION_SAVED_NOTICE: String
    get() = tr(
        "已设为默认下载位置，可在设置中更改",
        "Default download location saved. Change it in Settings",
        "既定のダウンロード先に設定しました。設定画面で変更できます",
        "기본 다운로드 위치로 설정했습니다. 설정에서 변경할 수 있습니다",
        "已設為預設下載位置，可在設定中變更",
    )

/** 在指定画质弹窗中同时勾选记住默认画质与默认位置时的合并提示。 */
internal val DEFAULT_QUALITY_AND_LOCATION_SAVED_NOTICE: String
    get() = tr(
        "已保存默认下载画质与位置，可在设置中更改",
        "Default download quality and location saved. Change them in Settings",
        "既定のダウンロード画質と保存先を設定しました。設定画面で変更できます",
        "기본 다운로드 화질과 위치를 저장했습니다. 설정에서 변경할 수 있습니다",
        "已儲存預設下載畫質與位置，可在設定中變更",
    )

/**
 * 「下载」的唯一入口，网盘页（面板、右键菜单、命令栏、多选栏）与播放器都经它，画质与保存位置问不问、怎么问只在这里定。
 *
 * 开启「每次下载前选择位置」且当前平台支持选目录时：
 * - 普通下载、解析文件下载与片段下载先弹出轻量应用内确认框 [DownloadConfirmDialog]，用户可直接确认、点「更改」原地换目录或勾选不再每次询问；
 * - [downloadAtQuality] 在 [DownloadQualityDialog] 内同屏嵌入保存位置区块，避免连续弹出两个模态框。
 * 关闭「每次下载前选择位置」或平台不支持选目录时，普通下载直接按默认画质与默认目录入队。
 */
@Stable
class DownloadLauncher internal constructor(
    private val coordinator: DownloadService,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    private val canPickLocation: Boolean = false,
    private val pickLocation: ((initialPath: String, onResult: (String?) -> Unit) -> Unit)? = null,
) {
    internal var request by mutableStateOf<DownloadRequest?>(null)
        private set
    internal var confirmRequest by mutableStateOf<DownloadConfirmRequest?>(null)
        private set
    private var isPreparing = false
    private var isPickingLocation = false
    private var preparation: Job? = null

    private val isBusy: Boolean
        get() = isPreparing || request != null || confirmRequest != null

    internal fun dismissRequest(pending: DownloadRequest) {
        if (request === pending) {
            request = null
            isPickingLocation = false
        }
    }

    internal fun dismissConfirm(pending: DownloadConfirmRequest) {
        if (confirmRequest === pending) {
            confirmRequest = null
            isPickingLocation = false
        }
    }

    /** 在应用内下载确认框中点「更改」，原地调起系统目录选择器刷新路径，取消时不关闭对话框。 */
    internal fun chooseLocation(pending: DownloadConfirmRequest) {
        if (confirmRequest !== pending) return
        openLocationPicker(pending.targetDirectory) { picked ->
            if (confirmRequest === pending) {
                pending.targetDirectory = picked
            }
        }
    }

    /** 在指定画质框中点「更改」，原地调起系统目录选择器刷新路径，取消时不关闭对话框。 */
    internal fun chooseLocation(pending: DownloadRequest) {
        if (request !== pending || !pending.askLocation) return
        openLocationPicker(pending.targetDirectory) { picked ->
            if (request === pending) {
                pending.targetDirectory = picked
            }
        }
    }

    /**
     * [folderSource] 为 null 时 [files] 里的文件夹不下。有东西入队后调 [onQueued]（切到传输页、提示一句），
     * notice 不为 null 时是要额外显示给用户的一句话，见 [DEFAULT_QUALITY_SAVED_NOTICE] 与 [DEFAULT_LOCATION_SAVED_NOTICE]。
     */
    fun download(files: List<FileStat>, folderSource: DownloadFolderSource? = null, onQueued: (notice: String?) -> Unit = {}) {
        start(files, folderSource, chooseQuality = false, onQueued)
    }

    fun downloadAtQuality(files: List<FileStat>, folderSource: DownloadFolderSource? = null, onQueued: (notice: String?) -> Unit = {}) {
        start(files, folderSource, chooseQuality = true, onQueued)
    }

    /** 链接解析、分享树或播放器内临时借出条目的下载入口，开启逐次选择位置时弹出应用内下载确认框。 */
    fun downloadResolved(
        files: List<ResolvedFile>,
        parentId: String = "",
        onQueued: (notice: String?) -> Unit = {},
    ) {
        if (files.isEmpty() || isBusy) return
        val snapshot = files.toList()
        launchPrepared {
            if (shouldAskLocation()) {
                val defaultDir = preferences.getDownloadDirPath()
                currentCoroutineContext().ensureActive()
                confirmRequest = DownloadConfirmRequest(
                    payload = DownloadPayload.Resolved(snapshot, parentId, onQueued),
                    defaultDirectory = defaultDir,
                )
                return@launchPrepared
            }
            currentCoroutineContext().ensureActive()
            coordinator.enqueueResolved(snapshot, parentId, targetDirectory = null)
            onQueued(null)
        }
    }

    /** 视频片段截取下载入口，开启逐次选择位置时在真正入队前弹出应用内下载确认框；取消时不关闭片段面板。 */
    fun downloadSegment(
        file: FileStat,
        startMs: Long,
        endMs: Long,
        timeRangeLabel: String,
        streamUrl: String?,
        startByte: Long,
        lengthBytes: Long,
        quality: DownloadQuality? = null,
        onNotice: (String) -> Unit = {},
        onQueued: () -> Unit = {},
    ) {
        if (isBusy) return
        launchPrepared {
            if (shouldAskLocation()) {
                val defaultDir = preferences.getDownloadDirPath()
                currentCoroutineContext().ensureActive()
                confirmRequest = DownloadConfirmRequest(
                    payload = DownloadPayload.Segment(
                        file = file,
                        startMs = startMs,
                        endMs = endMs,
                        timeRangeLabel = timeRangeLabel,
                        streamUrl = streamUrl,
                        startByte = startByte,
                        lengthBytes = lengthBytes,
                        quality = quality,
                        onNotice = onNotice,
                        onQueued = onQueued,
                    ),
                    defaultDirectory = defaultDir,
                )
                return@launchPrepared
            }
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
                targetDirectory = null,
            )
            onQueued()
        }
    }

    private fun start(files: List<FileStat>, folderSource: DownloadFolderSource?, chooseQuality: Boolean, onQueued: (String?) -> Unit) {
        if (files.isEmpty() || isBusy) return
        val folderCount = files.count { it.isFolder }
        if (folderCount == files.size && folderSource == null) {
            PikoLog.d(TAG, "Skipped download request: ${files.size} folder(s) without folderSource")
            return
        }
        val snapshot = files.toList()
        if (chooseQuality) {
            PikoLog.d(TAG, "Awaiting download quality selection: ${snapshot.size} item(s), $folderCount folder(s)")
            launchPrepared {
                val askLocation = shouldAskLocation()
                val defaultDir = if (askLocation) preferences.getDownloadDirPath() else ""
                currentCoroutineContext().ensureActive()
                request = DownloadRequest(
                    files = snapshot,
                    folderSource = folderSource,
                    onQueued = onQueued,
                    askLocation = askLocation,
                    defaultDirectory = defaultDir,
                )
            }
            return
        }
        val started = TimeSource.Monotonic.markNow()
        PikoLog.d(TAG, "Preparing direct download: ${snapshot.size} item(s), $folderCount folder(s)")
        launchPrepared {
            if (shouldAskLocation()) {
                val defaultDir = preferences.getDownloadDirPath()
                currentCoroutineContext().ensureActive()
                confirmRequest = DownloadConfirmRequest(
                    payload = DownloadPayload.Files(snapshot, folderSource, onQueued),
                    defaultDirectory = defaultDir,
                )
                return@launchPrepared
            }
            // 普通下载直接使用默认画质上限，未设置时用 0（原画）；仅显式指定画质才弹窗询问
            val maxHeight = preferences.downloadMaxHeightFlow.first() ?: 0
            currentCoroutineContext().ensureActive()
            PikoLog.d(TAG, "Submitting direct download after ${started.elapsedNow().inWholeMilliseconds} ms (maxHeight=$maxHeight)")
            enqueueFiles(
                files = snapshot,
                folderSource = folderSource,
                choice = DownloadChoice.Cap(maxHeight),
                notice = null,
                targetDirectory = null,
                onQueued = onQueued,
            )
        }
    }

    internal fun confirmLocation(pending: DownloadConfirmRequest, saveAsDefault: Boolean) {
        if (confirmRequest !== pending) return
        confirmRequest = null
        isPickingLocation = false
        val targetDir = pending.targetDirectory.takeIf { it.isNotBlank() }
        val dirChanged = pending.targetDirectory.isNotBlank() && pending.targetDirectory != pending.defaultDirectory
        PikoLog.d(TAG, "Confirmed download location: saveAsDefault=$saveAsDefault, changed=$dirChanged, items=${pending.summary.itemCount}")
        launchPrepared {
            if (saveAsDefault) {
                if (dirChanged) {
                    preferences.setDownloadDirPath(pending.targetDirectory)
                }
                preferences.setAskDownloadLocation(false)
            }
            val notice = DEFAULT_LOCATION_SAVED_NOTICE.takeIf { saveAsDefault }
            currentCoroutineContext().ensureActive()
            when (val payload = pending.payload) {
                is DownloadPayload.Files -> {
                    val maxHeight = preferences.downloadMaxHeightFlow.first() ?: 0
                    currentCoroutineContext().ensureActive()
                    enqueueFiles(
                        files = payload.files,
                        folderSource = payload.folderSource,
                        choice = DownloadChoice.Cap(maxHeight),
                        notice = notice,
                        targetDirectory = targetDir,
                        onQueued = payload.onQueued,
                    )
                }
                is DownloadPayload.Resolved -> {
                    coordinator.enqueueResolved(payload.files, payload.parentId, targetDirectory = targetDir)
                    payload.onQueued(notice)
                }
                is DownloadPayload.Segment -> {
                    coordinator.enqueueSegment(
                        file = payload.file,
                        startMs = payload.startMs,
                        endMs = payload.endMs,
                        timeRangeLabel = payload.timeRangeLabel,
                        streamUrl = payload.streamUrl,
                        startByte = payload.startByte,
                        lengthBytes = payload.lengthBytes,
                        quality = payload.quality,
                        targetDirectory = targetDir,
                    )
                    notice?.let(payload.onNotice)
                    payload.onQueued()
                }
            }
        }
    }

    internal fun confirm(
        request: DownloadRequest,
        choice: DownloadChoice,
        keep: Boolean,
        keepLocation: Boolean = false,
    ) {
        if (this.request !== request) return
        this.request = null
        isPickingLocation = false
        val keptQuality = keptDefaultMaxHeight(choice, keep)
        val saveLocation = request.askLocation && keepLocation
        val targetDir = if (request.askLocation) request.targetDirectory.takeIf { it.isNotBlank() } else null
        val dirChanged = saveLocation && request.targetDirectory.isNotBlank() && request.targetDirectory != request.defaultDirectory
        val choiceLabel = when (choice) {
            is DownloadChoice.Exact -> choice.quality.name ?: "original"
            is DownloadChoice.Cap -> if (choice.maxHeight <= 0) "original" else "${choice.maxHeight}P"
        }
        PikoLog.d(
            TAG,
            "Confirmed download quality: $choiceLabel, keepQuality=$keep, keepLocation=$saveLocation, items=${request.files.size}",
        )
        launchPrepared {
            if (keptQuality != null) preferences.setDownloadMaxHeight(keptQuality)
            if (saveLocation) {
                if (dirChanged) preferences.setDownloadDirPath(request.targetDirectory)
                preferences.setAskDownloadLocation(false)
            }
            val notice = when {
                keptQuality != null && saveLocation -> DEFAULT_QUALITY_AND_LOCATION_SAVED_NOTICE
                keptQuality != null -> DEFAULT_QUALITY_SAVED_NOTICE
                saveLocation -> DEFAULT_LOCATION_SAVED_NOTICE
                else -> null
            }
            currentCoroutineContext().ensureActive()
            enqueueFiles(
                files = request.files,
                folderSource = request.folderSource,
                choice = choice,
                notice = notice,
                targetDirectory = targetDir,
                onQueued = request.onQueued,
            )
        }
    }

    private suspend fun shouldAskLocation(): Boolean =
        canPickLocation && pickLocation != null && preferences.askDownloadLocationFlow.first()

    private fun openLocationPicker(currentPath: String, onPicked: (String) -> Unit) {
        val picker = pickLocation ?: return
        if (!canPickLocation || isPickingLocation) return
        isPickingLocation = true
        try {
            picker(currentPath) { picked ->
                isPickingLocation = false
                if (!picked.isNullOrBlank()) {
                    onPicked(picked)
                }
            }
        } catch (e: CancellationException) {
            isPickingLocation = false
            throw e
        } catch (e: Throwable) {
            isPickingLocation = false
            PikoLog.w(TAG, "Failed to invoke download location picker", e)
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

    private fun enqueueFiles(
        files: List<FileStat>,
        folderSource: DownloadFolderSource?,
        choice: DownloadChoice,
        notice: String?,
        targetDirectory: String?,
        onQueued: (notice: String?) -> Unit,
    ) {
        val regularFiles = files.filter { !it.isFolder }
        val folders = files.filter { it.isFolder }
        val cap = (choice as? DownloadChoice.Cap)?.maxHeight
        when (choice) {
            is DownloadChoice.Exact -> coordinator.enqueueQuality(regularFiles.single(), choice.quality, targetDirectory = targetDirectory)
            is DownloadChoice.Cap -> coordinator.enqueueFiles(regularFiles, choice.maxHeight, targetDirectory = targetDirectory)
        }
        val batches = folderSource?.takeIf { folders.isNotEmpty() }
            ?.let { coordinator.enqueueFolders(folders, it, cap, targetDirectory = targetDirectory) } ?: 0
        if (regularFiles.isNotEmpty() || batches > 0) onQueued(notice)
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
    launcher.confirmRequest?.let { pending ->
        DownloadConfirmDialog(
            request = pending,
            onPickLocation = { launcher.chooseLocation(pending) },
            onConfirm = { saveAsDefault -> launcher.confirmLocation(pending, saveAsDefault) },
            onDismiss = { launcher.dismissConfirm(pending) },
        )
    }
    launcher.request?.let { pending ->
        DownloadQualityDialog(
            request = pending,
            onPickLocation = { launcher.chooseLocation(pending) },
            onConfirm = { choice, keepQuality, keepLocation ->
                launcher.confirm(pending, choice, keepQuality, keepLocation)
            },
            onDismiss = { launcher.dismissRequest(pending) },
        )
    }
    return launcher
}
