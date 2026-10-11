package dev.piko.ui

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.screens.transfers.remainingTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

/** 后台工作的种类。通知按它挑图标，进度列表按它排序。 */
enum class WorkKind { VAULT, EXTRACT, FILES, DOWNLOAD, UPLOAD, DUPLICATES }

fun dev.piko.shared.upload.UploadPreparation.workProgress() = WorkProgress(
    WorkKind.UPLOAD, "上传到「$parentName」", if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (result?.completion) {
        null -> "正在准备上传"
        dev.piko.shared.upload.UploadPreparationCompletion.SUCCEEDED -> "已入队"
        dev.piko.shared.upload.UploadPreparationCompletion.PARTIAL -> "部分准备未完成"
        dev.piko.shared.upload.UploadPreparationCompletion.FAILED -> "准备失败"
        dev.piko.shared.upload.UploadPreparationCompletion.UNCONFIRMED -> "目录结果待确认"
        dev.piko.shared.upload.UploadPreparationCompletion.STOPPED -> "准备已停止"
    }, result?.message ?: "正在读取所选的 $selectedCount 项并建立目标目录",
)

fun dev.piko.shared.state.OfflineSubmitWork.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (status) {
        dev.piko.shared.state.OfflineSubmitStatus.SUBMITTING -> "正在提交离线"
        dev.piko.shared.state.OfflineSubmitStatus.COMPLETE -> "已保存"
        dev.piko.shared.state.OfflineSubmitStatus.REFUSED -> "未提交"
        dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED -> "结果待确认"
    }, message,
)

fun dev.piko.shared.state.ShareSaveWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.FILES, title,
    when {
        !isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING -> WorkMeter.None
        phase == dev.piko.shared.state.ShareSavePhase.TRACKING && remoteProgress != null -> WorkMeter.Determinate(requireNotNull(remoteProgress).coerceIn(0, 100) / 100f)
        else -> WorkMeter.Indeterminate
    },
    undo?.message ?: result?.let { when (it.completion) {
        dev.piko.shared.state.ShareSaveCompletion.SUCCEEDED -> "已转存"
        dev.piko.shared.state.ShareSaveCompletion.PARTIAL -> "部分处理未完成"
        dev.piko.shared.state.ShareSaveCompletion.FAILED -> "未提交转存"
        dev.piko.shared.state.ShareSaveCompletion.UNCONFIRMED -> "结果待确认"
    } } ?: when (phase) {
        dev.piko.shared.state.ShareSavePhase.PREPARING -> "正在准备转存"
        dev.piko.shared.state.ShareSavePhase.SUBMITTING -> "正在提交转存"
        dev.piko.shared.state.ShareSavePhase.TRACKING -> "云端正在转存"
        dev.piko.shared.state.ShareSavePhase.IDENTIFYING -> "正在核对转存产物"
        dev.piko.shared.state.ShareSavePhase.NAMING -> "正在规范命名"
        dev.piko.shared.state.ShareSavePhase.FINISHED -> "正在重新查询"
    }, result?.message,
)

fun dev.piko.shared.state.InstantSaveAttempt.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    if (isRunning) "正在保存" else if (receipt?.requiresInspection == true) "结果需要核对" else if (receipt?.confirmed?.isEmpty() != false) "未保存" else "部分保存未完成",
    receipt?.message,
)

fun dev.piko.shared.state.FileWriteWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress {
    val status = undo?.message ?: result?.let {
        when (it.completion) {
            dev.piko.shared.state.FileWriteCompletion.SUCCEEDED -> "已完成"
            dev.piko.shared.state.FileWriteCompletion.PARTIAL -> "部分完成"
            dev.piko.shared.state.FileWriteCompletion.FAILED -> "未完成"
            dev.piko.shared.state.FileWriteCompletion.UNCONFIRMED -> "结果待确认"
        }
    } ?: when (phase) {
        dev.piko.shared.state.FileWritePhase.PREPARING -> "正在核对文件"
        dev.piko.shared.state.FileWritePhase.WRITING -> "正在提交修改"
        dev.piko.shared.state.FileWritePhase.CHECKING -> "正在核对结果"
        dev.piko.shared.state.FileWritePhase.COPYING -> "服务端正在复制"
        dev.piko.shared.state.FileWritePhase.FINISHED -> "已结束"
    }
    return WorkProgress(WorkKind.FILES, command.title,
        if (isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
        status, result?.message?.takeUnless { it == "已完成" },
    )
}

fun dev.piko.shared.state.HistoryEditWork.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, command.title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (result?.completion) {
        null -> "正在处理历史记录"
        dev.piko.shared.state.HistoryEditCompletion.SUCCEEDED -> "已完成"
        dev.piko.shared.state.HistoryEditCompletion.PARTIAL -> "部分完成"
        dev.piko.shared.state.HistoryEditCompletion.FAILED -> "未完成"
        dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED -> "结果待确认"
    }, result?.message,
)

fun dev.piko.shared.state.VaultEntryEditWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.FILES, command.title,
    if (isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
    undo?.message ?: when (result?.completion) {
        null -> "正在编辑归档清单"
        dev.piko.shared.state.VaultEntryEditCompletion.SUCCEEDED -> "已完成"
        dev.piko.shared.state.VaultEntryEditCompletion.PARTIAL -> "部分完成"
        dev.piko.shared.state.VaultEntryEditCompletion.FAILED -> "未完成"
        dev.piko.shared.state.VaultEntryEditCompletion.UNCONFIRMED -> "结果待确认"
    }, result?.message,
)

/** 进度条画成什么样。 */
sealed interface WorkMeter {
    data class Determinate(val fraction: Float) : WorkMeter

    /** 在做，但总量或用时未知。 */
    data object Indeterminate : WorkMeter

    /** 停着等人处理（待输密码），不画进度条。 */
    data object None : WorkMeter
}

/**
 * 一件后台工作此刻的进度描述。传输页的行与详情面板、桌面的浮动卡片、Android 的进度通知都从这里取文案与进度值，
 * 不各拼一份：曾经通知只写「进行中」、行上写「准备归档 3 / 40」，同一件事两处说法对不上。
 *
 * [title] 是带对象的一句（归档「电影」），通知标题与浮动卡片用；传输页的行另以对象名作标题、前面挂类别标签，不读它。
 */
data class WorkProgress(
    val kind: WorkKind,
    val title: String,
    val meter: WorkMeter,
    /** 主状态：「已归档 132 / 480 个文件」。 */
    val status: String,
    /** 次要说明：当前项、速度、停止中的说明。 */
    val detail: String? = null,
)

private const val STOPPING_STATUS = "正在停止"
private const val STOPPING_DETAIL = "进行中的文件夹处理完即停"

private fun fraction(done: Int, total: Int?): WorkMeter =
    if (total != null && total > 0) WorkMeter.Determinate((done.toFloat() / total).coerceIn(0f, 1f)) else WorkMeter.Indeterminate

/**
 * 归档的进度。每个文件先取样内容标识（[FolderVaultSession.Progress.prepared]），整个文件夹写成清单、处置原文件后
 * 才计入 done，两段各占进度条的一半：只按 done 算时，取样那几分钟进度条一直停在 0。
 */
fun vaultArchiveProgress(progress: FolderVaultSession.Progress, stopping: Boolean): WorkProgress {
    val total = progress.total
    val meter = if (total > 0) {
        WorkMeter.Determinate(((progress.prepared + progress.done).toFloat() / (total * 2)).coerceIn(0f, 1f))
    } else {
        WorkMeter.Indeterminate
    }
    val status = when {
        stopping -> STOPPING_STATUS
        progress.prepared < total -> "准备归档 ${progress.prepared} / $total 个文件"
        else -> "已归档 ${progress.done} / $total 个文件"
    }
    return WorkProgress(WorkKind.VAULT, "归档「${progress.folderName}」", meter, status, STOPPING_DETAIL.takeIf { stopping })
}

/** 取消归档的进度。总数要扫完整棵树才知道，之前是不定进度，报已扫描的文件夹数。 */
fun vaultRestoreProgress(progress: FolderVaultSession.RestoreProgress, stopping: Boolean): WorkProgress {
    val title = "取消归档「${progress.folderName}」"
    val total = progress.total
    if (stopping) return WorkProgress(WorkKind.VAULT, title, fraction(progress.done, total), STOPPING_STATUS, STOPPING_DETAIL)
    if (total == null) {
        val scanned = progress.scannedFolders.takeIf { it > 0 }?.let { "已扫描 $it 个文件夹" }
        return WorkProgress(WorkKind.VAULT, title, WorkMeter.Indeterminate, progress.stage, scanned)
    }
    // 还没恢复出一个时阶段词（检查空间、开始恢复）才有信息量，之后计数已经说明了
    return WorkProgress(
        WorkKind.VAULT,
        title,
        fraction(progress.done, total),
        "已恢复 ${progress.done} / $total 个文件",
        progress.stage.takeIf { progress.done == 0 },
    )
}

/** 进程级归档会话眼下的那一件，没有在做时为 null。 */
fun FolderVaultSession.workProgress(): WorkProgress? =
    progress?.let { vaultArchiveProgress(it, stopping) } ?: restoreProgress?.let { vaultRestoreProgress(it, stopping) }
        ?: activeWork?.let { WorkProgress(WorkKind.VAULT, "归档「${it.name}」", WorkMeter.Indeterminate, if (stopping) STOPPING_STATUS else "正在清点文件") }

fun FolderVaultSession.Record.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.VAULT,
    "${if (work.restoring) "恢复归档" else "归档"}「${work.name}」",
    if (undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
    when (undo?.status) {
        DriveChangeJournal.UndoStatus.RUNNING -> "正在撤销"
        DriveChangeJournal.UndoStatus.UNDONE -> "已撤销"
        DriveChangeJournal.UndoStatus.FAILED -> "撤销未全部完成"
        null -> title
    },
    if (undo == null) message else "$message\n${undo.message}",
)

/** 一个压缩包的解压进度。 */
fun ArchiveJob.workProgress(): WorkProgress {
    val title = "解压「${file.name}」"
    return when (val status = status) {
        ArchiveJobStatus.Waiting -> if (remoteTaskId == null) {
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(0f), "排队中")
        } else WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, "等待重新查询")
        ArchiveJobStatus.Submitting -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, "正在提交")
        ArchiveJobStatus.Checking -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, "正在查询结果")
        is ArchiveJobStatus.Extracting ->
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(status.progress.coerceIn(0, 100) / 100f), "已解压 ${status.progress}%")
        is ArchiveJobStatus.NeedsPassword -> WorkProgress(
            WorkKind.EXTRACT,
            title,
            WorkMeter.None,
            if (status.incorrect) "密码错误" else "需要密码",
            "输入密码后继续，或放弃解压",
        )
        is ArchiveJobStatus.Finished -> WorkProgress(
            WorkKind.EXTRACT, title, WorkMeter.None,
            when (status.outcome) {
                ArchiveCompletion.SUCCEEDED -> "解压完成"
                ArchiveCompletion.FAILED -> "解压失败"
                ArchiveCompletion.UNCONFIRMED -> "结果待确认"
            },
            status.message,
        )
    }
}

/**
 * 几个压缩包合成一条，给通知与浮动卡片：进度与状态取眼下在解的那个，待输密码的另计一句。
 * 解压逐个提交，同一时刻只有一个在跑，合计各包的百分比没有意义。
 */
fun List<ArchiveJob>.extractProgress(): WorkProgress? {
    val current = firstOrNull { it.status !is ArchiveJobStatus.NeedsPassword } ?: firstOrNull() ?: return null
    val single = current.workProgress()
    if (size == 1) return single
    val needPassword = count { it.status is ArchiveJobStatus.NeedsPassword && it !== current }
    return single.copy(
        title = "解压 $size 个压缩包",
        detail = listOfNotNull(current.file.name, needPassword.takeIf { it > 0 }?.let { "另有 $it 个需要密码" }).joinToString("，"),
    )
}

internal fun ArchiveJobStatus.hasExecutingWork(): Boolean = this !is ArchiveJobStatus.NeedsPassword && this !is ArchiveJobStatus.Finished

/**
 * 下载与上传合成一条，给通知：传输页逐个列出，通知里只放得下一份总量。只有一个文件时写它的名字。
 * 片段事先不知道产物大小，字节总数为 0，进度不定。
 */
fun transferProgress(downloads: List<DownloadTask>, uploads: List<UploadTask>): WorkProgress? {
    val active = downloads.filter { it.status == DownloadStatus.DOWNLOADING }
    val sending = uploads.filter { it.status.isActive }
    if (active.isEmpty() && sending.isEmpty()) return null
    val kind = if (active.isEmpty()) WorkKind.UPLOAD else WorkKind.DOWNLOAD
    val title = (active.map { it.fileName } + sending.map { it.fileName }).singleOrNull()
        ?: listOfNotNull(
            active.size.takeIf { it > 0 }?.let { "下载 $it 个文件" },
            sending.size.takeIf { it > 0 }?.let { "上传 $it 个文件" },
        ).joinToString("，")
    val processed = active.sumOf { it.downloadedBytes } + sending.sumOf { it.processedBytes }
    val total = active.sumOf { it.totalBytes } + sending.sumOf { it.size }
    val speed = active.sumOf { it.speedBytesPerSec } + sending.sumOf { it.speedBytesPerSec }
    val speedText = speed.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" }
    if (total <= 0) return WorkProgress(kind, title, WorkMeter.Indeterminate, "进行中", speedText)
    val bytes = "${processed.toReadableSize()} / ${total.toReadableSize()}"
    return WorkProgress(
        kind,
        title,
        WorkMeter.Determinate((processed.toDouble() / total).toFloat().coerceIn(0f, 1f)),
        listOfNotNull(bytes, remainingTime(total - processed, speed)?.let { "剩余 $it" }).joinToString("，"),
        speedText,
    )
}

/** 查找重复的扫描。与 sheet 头上的说法相同；总量事先不知道，进度不定。 */
fun DuplicateFinderState.workProgress(): WorkProgress? {
    if (!isScanning) return null
    val scanned = "已扫描 $scannedFolders 个文件夹、$scannedFiles 个文件"
    val title = "查找「${root.name}」中的重复"
    return if (phase == DuplicateFinderState.Phase.ANALYZING) {
        WorkProgress(WorkKind.DUPLICATES, title, WorkMeter.Indeterminate, "正在比对", scanned)
    } else {
        WorkProgress(WorkKind.DUPLICATES, title, WorkMeter.Indeterminate, scanned)
    }
}

/**
 * 眼下在后台跑的全部工作，按 [WorkKind] 的次序。读的是 Compose 状态，在 snapshotFlow 或组合里调用才会随之更新。
 * 下载与上传另读 StateFlow 的当前值，调用方要自己订阅它们的变化。
 */
fun PikoServices.runningWork(): List<WorkProgress> = (listOfNotNull(
    folderVaultSession.workProgress(),
    archiveExtractSession.jobs.extractProgress(),
    transferProgress(downloadManager.tasks.value.values.toList(), currentAccountUploads()),
    duplicateSession.state?.workProgress(),
    trashSession.progress?.let { progress ->
        WorkProgress(WorkKind.FILES, "移入回收站", fraction(progress.completed, progress.total), "已处理 ${progress.completed} / ${progress.total} 项")
    },
    instantSession.state?.takeIf { (it.isSaving || it.batch?.isSaving == true) && it.shareSave?.isSaving != true && instantMagnetRepository.offlineSubmissions.works.value.none { work -> work.isRunning } }?.let {
        WorkProgress(WorkKind.FILES, "保存添加链接", WorkMeter.Indeterminate, if (it.shareSave?.isSaving == true) "正在转存分享" else "正在保存")
    },
) + fileWrites.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    historyEdits.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    vaultEntryEdits.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    shareSaves.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    instantMagnetRepository.offlineSubmissions.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    uploadManager.preparations.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    offlinePacks.jobs.value.filter { it.account == driveRepository.currentAccount && dev.piko.shared.data.OfflinePackRef(it.account, it.taskId) in offlinePacks.busy.value }.map {
        WorkProgress(WorkKind.FILES, it.folderName, WorkMeter.Indeterminate, "正在处理云端任务")
    } +
    downloadManager.listings.value.values.filter { it.account == driveRepository.currentAccount && it.error == null }.map {
        WorkProgress(WorkKind.DOWNLOAD, "下载「${it.batch.folderName}」",
            if (it.isListing) WorkMeter.Indeterminate else WorkMeter.None,
            if (it.isListing) "正在列出文件夹" else "等待确认下载额度",
            "已找到 ${it.filesFound} 个文件",
        )
    } + listOfNotNull(driveRepository.changes.state.value.undo.values.count { it.status == DriveChangeJournal.UndoStatus.RUNNING }.takeIf { it > 0 }?.let {
        WorkProgress(WorkKind.FILES, "撤销文件改动", WorkMeter.Indeterminate, "正在撤销 $it 项改动")
    })).sortedBy { it.kind.ordinal }

/** 文件处理、撤销和目录展开用 StateFlow 提供变化信号，平台据此重读后台工作状态。 */
fun PikoServices.fileWorkChanges(): Flow<Unit> = merge(
    combine(fileWrites.works, historyEdits.works, vaultEntryEdits.works, driveRepository.changes.state, downloadManager.listings) { _, _, _, _, _ -> Unit },
    shareSaves.works.map { Unit },
    instantMagnetRepository.offlineSubmissions.works.map { Unit },
    offlinePacks.busy.map { Unit },
    uploadManager.preparations.map { Unit },
)

/** 上传队列只跑当前账号的，别的账号排着的任务一直是 QUEUED，算进来就永远有活，见 [anyActiveFor]。 */
private fun PikoServices.currentAccountUploads(): List<UploadTask> {
    val account = clientManager.currentClient.value?.account
    return uploadManager.tasks.value.values.filter { it.account == account }
}

/**
 * 有没有要在后台跑下去的工作，Android 据此保持前台服务。比 [runningWork] 宽：排着队、还在查本地长度的下载也算，
 * 否则两个文件之间的空档会让前台服务退下，后台里再拉起会被系统拒绝。
 */
fun PikoServices.hasBackgroundWork(): Boolean =
    folderVaultSession.isRunning ||
        archiveExtractSession.jobs.any { it.status.hasExecutingWork() } ||
        duplicateSession.state?.isScanning == true ||
        trashSession.progress != null || trashSession.isUndoing ||
        instantSession.state?.let { it.isSaving || it.batch?.isSaving == true } == true ||
        fileWrites.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        historyEdits.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        vaultEntryEdits.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        shareSaves.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        instantMagnetRepository.offlineSubmissions.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        offlinePacks.busy.value.any { it.account == driveRepository.currentAccount } ||
        driveRepository.changes.state.value.undo.values.any { it.status == DriveChangeJournal.UndoStatus.RUNNING } ||
        downloadManager.listings.value.values.any { it.account == driveRepository.currentAccount && it.isListing } ||
        downloadManager.tasks.value.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING } ||
        currentAccountUploads().any { it.status.isActive } ||
        uploadManager.preparations.value.any { it.account == driveRepository.currentAccount && it.isRunning }

/** 平台共同订阅业务执行状态；等待密码、确认或结果待查不会单独维持后台执行。 */
fun PikoServices.backgroundWorkChanges(): Flow<Boolean> = combine(
    snapshotFlow { hasBackgroundWork() }, downloadManager.tasks, uploadManager.tasks, fileWorkChanges(),
) { _, _, _, _ -> hasBackgroundWork() }.distinctUntilChanged()

/** 进度通知的内容。只有一件时原样照搬它；几件并行时标题与进度取第一件，展开后逐件一行。 */
data class WorkNotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val meter: WorkMeter,
    /** 展开后的逐件列表，只有一件时为空。 */
    val lines: List<String>,
    /** 全是上传，通知图标用上传的箭头。 */
    val uploadOnly: Boolean,
)

fun workNotificationContent(work: List<WorkProgress>): WorkNotificationContent {
    val first = work.firstOrNull()
        // 服务刚拉起、下载还在查本地长度（PENDING）时，什么进度都还没有
        ?: return WorkNotificationContent("正在准备", "", null, WorkMeter.Indeterminate, emptyList(), uploadOnly = false)
    val others = work.size - 1
    return WorkNotificationContent(
        title = if (others == 0) first.title else "${first.title}，另有 $others 项",
        text = first.status,
        subText = first.detail,
        meter = first.meter,
        lines = if (others == 0) emptyList() else work.map { "${it.title}：${it.status}" },
        uploadOnly = work.all { it.kind == WorkKind.UPLOAD },
    )
}
