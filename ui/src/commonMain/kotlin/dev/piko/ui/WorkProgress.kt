package dev.piko.ui

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.CanonicalNamingState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.screens.transfers.remainingTime

/** 后台工作的种类。通知按它挑图标，进度列表按它排序。 */
enum class WorkKind { VAULT, EXTRACT, DOWNLOAD, UPLOAD, DUPLICATES, NAMING }

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

/** 一个压缩包的解压进度。 */
fun ArchiveJob.workProgress(): WorkProgress {
    val title = "解压「${file.name}」"
    return when (val status = status) {
        ArchiveJobStatus.Waiting -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(0f), "排队中")
        ArchiveJobStatus.Submitting -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, "正在提交")
        is ArchiveJobStatus.Extracting ->
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(status.progress.coerceIn(0, 100) / 100f), "已解压 ${status.progress}%")
        is ArchiveJobStatus.NeedsPassword -> WorkProgress(
            WorkKind.EXTRACT,
            title,
            WorkMeter.None,
            if (status.incorrect) "密码错误" else "需要密码",
            "输入密码后继续，或放弃解压",
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

/** 按番号规范命名的扫描与查片名。查片名有总数，进度确定。 */
fun CanonicalNamingState.workProgress(): WorkProgress? {
    val title = "按番号规范命名「${root.name}」"
    return when (phase) {
        CanonicalNamingState.Phase.SCANNING ->
            WorkProgress(WorkKind.NAMING, title, WorkMeter.Indeterminate, "已扫描 $scannedFolders 个文件夹、$scannedFiles 个文件")
        CanonicalNamingState.Phase.TITLES -> {
            val titles = titlesProgress
            if (titles == null) {
                WorkProgress(WorkKind.NAMING, title, WorkMeter.Indeterminate, "正在从 MetaTube 查询片名")
            } else {
                WorkProgress(WorkKind.NAMING, title, fraction(titles.first, titles.second), "正在从 MetaTube 查询片名 ${titles.first} / ${titles.second}")
            }
        }
        CanonicalNamingState.Phase.DONE, CanonicalNamingState.Phase.FAILED -> null
    }
}

/**
 * 眼下在后台跑的全部工作，按 [WorkKind] 的次序。读的是 Compose 状态，在 snapshotFlow 或组合里调用才会随之更新。
 * 下载与上传另读 StateFlow 的当前值，调用方要自己订阅它们的变化。
 */
fun PikoServices.runningWork(): List<WorkProgress> = listOfNotNull(
    folderVaultSession.workProgress(),
    archiveExtractSession.jobs.extractProgress(),
    transferProgress(downloadManager.tasks.value.values.toList(), currentAccountUploads()),
    duplicateSession.state?.workProgress(),
    canonicalNamingSession.state?.workProgress(),
).sortedBy { it.kind.ordinal }

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
        archiveExtractSession.jobs.isNotEmpty() ||
        duplicateSession.state?.isScanning == true ||
        canonicalNamingSession.state?.isScanning == true ||
        downloadManager.tasks.value.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING } ||
        currentAccountUploads().any { it.status.isActive }

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
