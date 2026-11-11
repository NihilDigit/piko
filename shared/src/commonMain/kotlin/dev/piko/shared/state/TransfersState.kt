package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.download.FolderListing
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.presentation.needsAttention
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 传输列表中的一项：本机下载、上传（含准备批次）、云端离线或整包任务、秒传/引用保存，以及解压、归档与复制。 */
sealed class TransferItem {
    /** 列表唯一键。不同来源的任务 ID 来自各自命名空间，加类型前缀防撞。 */
    abstract val key: String
    abstract val createdAtMs: Long
    open val children: List<TransferItem> = emptyList()
    open val remoteTaskIds: Set<String> = emptySet()

    data class Local(val task: DownloadTask) : TransferItem() {
        override val key: String = "local:${task.taskId}"
        override val createdAtMs: Long = task.createdAtMs
    }

    /**
     * 一次文件夹下载：列出中或等确认时只有 [listing]，之后是任务表里同一批的 [tasks]。
     * 各文件仍是普通任务，展开后各占一行（[Local]）。
     */
    data class LocalBatch(
        val batch: DownloadBatch, val tasks: List<DownloadTask>, val listing: FolderListing?,
        override val children: List<TransferItem> = tasks.map { Local(it) },
    ) : TransferItem() {
        override val key: String = "batch:${batch.id}"
        override val createdAtMs: Long = listing?.createdAtMs ?: tasks.minOfOrNull { it.createdAtMs } ?: 0L

        val totalBytes: Long = tasks.sumOf { it.totalBytes }
        val downloadedBytes: Long = tasks.sumOf { it.downloadedBytes }
        val completedCount: Int = tasks.count { it.status == DownloadStatus.COMPLETED }
        val failedCount: Int = tasks.count { it.status == DownloadStatus.FAILED }
        val speedBytesPerSec: Long = tasks.sumOf { it.speedBytesPerSec }
        val progress: Float = totalBytes.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

        /**
         * 整组的状态：有一个还在下、排着或暂停就算进行中，其余有失败的算失败，否则完成。
         * 列出失败与超额待确认都要用户处理，记作失败。
         */
        val status: DownloadStatus = when {
            listing != null -> if (listing.isListing) DownloadStatus.PENDING else DownloadStatus.FAILED
            tasks.any { it.status == DownloadStatus.DOWNLOADING } -> DownloadStatus.DOWNLOADING
            tasks.any { it.status == DownloadStatus.PENDING } -> DownloadStatus.PENDING
            tasks.any { it.status == DownloadStatus.PAUSED } -> DownloadStatus.PAUSED
            failedCount > 0 -> DownloadStatus.FAILED
            else -> DownloadStatus.COMPLETED
        }
    }

    data class Upload(val task: UploadTask) : TransferItem() {
        override val key: String = "upload:${task.taskId}"
        override val createdAtMs: Long = task.createdAtMs
    }
    data class UploadPreparing(val work: dev.piko.shared.upload.UploadPreparation, override val children: List<TransferItem> = emptyList()) : TransferItem() {
        override val key: String = "upload-batch:${work.id}"
        override val createdAtMs: Long = work.createdAtMs
    }

    data class Cloud(val task: DriveTask) : TransferItem() {
        override val key: String = "cloud:${task.id}"
        override val createdAtMs: Long = parseEpochMillis(task.createdTime) ?: 0L
        val updatedAtMs: Long? = parseEpochMillis(task.updatedTime)
    }

    data class UploadBatch(
        val batchId: String, val title: String, val parentId: String, val parentName: String,
        val tasks: List<UploadTask>, val isFolder: Boolean,
        override val children: List<TransferItem> = tasks.map { Upload(it) },
    ) : TransferItem() {
        override val key: String = "upload-batch:$batchId"
        override val createdAtMs: Long = tasks.minOfOrNull { it.createdAtMs } ?: 0L
        val completedCount: Int = tasks.count { it.status == UploadStatus.COMPLETED }
        val totalBytes: Long = tasks.sumOf { it.size }
        val processedBytes: Long = tasks.sumOf { if (it.status == UploadStatus.COMPLETED) it.size else if (it.status == UploadStatus.UPLOADING) it.processedBytes else 0L }
        val speedBytesPerSec: Long = tasks.filter { it.status == UploadStatus.UPLOADING }.sumOf { it.speedBytesPerSec }
        val progress: Float = if (totalBytes > 0) (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else completedCount.toFloat() / tasks.size.coerceAtLeast(1)
        val status: UploadStatus = when {
            tasks.any { it.status == UploadStatus.UPLOADING } -> UploadStatus.UPLOADING
            tasks.any { it.status == UploadStatus.HASHING } -> UploadStatus.HASHING
            tasks.any { it.status == UploadStatus.QUEUED } -> UploadStatus.QUEUED
            tasks.any { it.status == UploadStatus.FAILED } -> UploadStatus.FAILED
            tasks.any { it.status == UploadStatus.PAUSED } -> UploadStatus.PAUSED
            else -> UploadStatus.COMPLETED
        }
    }

    /**
     * 整包离线：一个云端任务加上完成后的清理与改名。[task] 是传输页列表里同一任务的快照，
     * 可见期间它每 4 秒刷新一次，下载进度取它的；跟踪器按退避轮询，不够及时。
     */
    data class Pack(val job: OfflinePackJob, val task: DriveTask?, val isBusy: Boolean = false) : TransferItem() {
        // 与 Cloud 同一命名空间：同一任务只该出现一次
        override val key: String = "cloud:${job.taskId}"
        override val createdAtMs: Long = job.createdAtMs

        override val remoteTaskIds: Set<String> = setOf(job.taskId)
        val progress: Int get() = if (task?.phase == TaskPhase.RUNNING) task.progress else job.progress
    }

    /** 秒传或引用保存记录：保存即完成，在 [COMPLETED_CLOUD_WINDOW] 内展示于「已完成」。 */
    data class Instant(val record: InstantSaveRecord) : TransferItem() {
        override val key: String = "instant:${record.id}"
        override val createdAtMs: Long = record.createdAtMs
    }
    data class InstantAttempt(val work: InstantSaveAttempt) : TransferItem() {
        override val key: String = "instant:${work.id}"
        override val createdAtMs: Long = work.finishedAtMs ?: work.createdAtMs
    }
    data class OfflineSubmit(val work: OfflineSubmitWork) : TransferItem() {
        override val key: String = "offline-submit:${work.id}"
        override val createdAtMs: Long = work.finishedAtMs ?: work.createdAtMs
    }

    /** 服务端解压与本次运行期的结局，来自 [ArchiveExtractSession]；提示没有订阅者也不丢记录。 */
    data class Extract(val job: ArchiveJob) : TransferItem() {
        override val remoteTaskIds: Set<String> = setOfNotNull(job.remoteTaskId)
        override val key: String = "extract:${job.id}"

        override val createdAtMs: Long = (job.status as? ArchiveJobStatus.Finished)?.finishedAtMs ?: job.createdAtMs
    }

    /** 归档或恢复及本次运行期的结果。同一次工作从进度到结局保持身份。 */
    data class Vault(val work: FolderVaultSession.Work, val record: FolderVaultSession.Record? = null) : TransferItem() {
        val folderName: String get() = work.name
        val restoring: Boolean get() = work.restoring
        override val key: String = "vault:${work.id}"
        override val createdAtMs: Long = record?.finishedAtMs ?: work.createdAtMs
    }
    data class Write(val work: FileWriteWork) : TransferItem() {
        override val remoteTaskIds: Set<String> = work.copies.mapTo(HashSet()) { it.id }
        override val key: String = "write:${work.id}"
        override val createdAtMs: Long = work.result?.finishedAtMs ?: work.createdAtMs
    }
}

/** 同一快照中互斥的任务分组。 */
class TransferSections(
    val inProgress: List<TransferItem>,
    val needsAttention: List<TransferItem>,
    val completed: List<TransferItem>,
    val outputDeleted: List<TransferItem>,
    val paused: List<TransferItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = paused.isEmpty() && inProgress.isEmpty() && needsAttention.isEmpty() && completed.isEmpty() && outputDeleted.isEmpty()
}

/**
 * 传输页的类型筛选。秒传记录归「云端」：它与离线任务出自同一个「添加链接」，东西落在网盘里；
 * 从本机传上去却命中秒传的仍是一项上传，归「上传」。解压、归档与复制归「文件处理」。
 */
enum class TransferKind(private val labelText: () -> String) {
    ALL({ tr("全部", "All", "すべて", "전체", "全部") }),
    DOWNLOAD({ tr("下载", "Downloads", "ダウンロード", "다운로드", "下載") }),
    UPLOAD({ tr("上传", "Uploads", "アップロード", "업로드", "上傳") }),
    CLOUD({ tr("云端", "Cloud", "クラウド", "클라우드", "雲端") }),
    FILES({ tr("文件处理", "File operations", "ファイル操作", "파일 작업", "檔案處理") }),
    ;

    val label: String get() = labelText()

    fun matches(item: TransferItem): Boolean = when (this) {
        ALL -> true
        DOWNLOAD -> item is TransferItem.Local || item is TransferItem.LocalBatch
        UPLOAD -> item is TransferItem.Upload || item is TransferItem.UploadBatch || item is TransferItem.UploadPreparing
        CLOUD -> item is TransferItem.Cloud || item is TransferItem.Pack || item is TransferItem.Instant || item is TransferItem.InstantAttempt || item is TransferItem.OfflineSubmit
        FILES -> item is TransferItem.Extract || item is TransferItem.Vault || item is TransferItem.Write
    }
}

/**
 * 传输页：本机下载、上传、云端离线与文件处理任务合并为「已暂停」「进行中」「需要处理」「已完成」「文件已删除」五个分组，
 * 可按类型筛选（[filter]），多选照网盘页（[selectedKeys]）。
 *
 * 云端已完成的任务与秒传/引用保存记录只列出最近 [COMPLETED_CLOUD_WINDOW] 内完成的，本机下载已完成的始终保留：
 * 后者对应磁盘上的文件，是用户找回下载的入口。
 *
 * 轮询挂在 [whileVisible] 上，由视图在可见期间调用。
 */
class TransfersState(
    private val coordinator: PikoDownloadCoordinator,
    private val taskRepo: TaskRepository,
    private val packTracker: OfflinePackTracker,
    private val scope: CoroutineScope,
    private val driveRepo: PikoDriveRepository,
    private val uploads: PikoUploadCoordinator,
    private val instantSaves: InstantSaveRecords,
    /** 下载、上传任务与秒传记录都按账号记，只列当前账号的；别的账号的切回去才看得到。 */
    private val account: String,
    // 两者都是进程级会话，换账号时已各自清空，这里不再按账号过滤
    private val extracts: ArchiveExtractSession,
    private val vault: FolderVaultSession,
    /** 是否包含服务端文件处理。主任务页两端都包含，局部投影可关闭。 */
    private val listsServerWork: Boolean,
    val fileWrites: FileWriteSession? = null,
    val offlineSubmits: OfflineSubmitSession? = taskRepo.offlineSubmissions,
    receiving: dev.piko.shared.presentation.ReceivingActivity? = null,
) {

    private val cloud = OfflineTasksState(taskRepo, scope)
    private val itemCache = TransferItemCache()

    private fun isMine(task: DownloadTask): Boolean = task.belongsTo(account)

    private fun isMine(listing: FolderListing): Boolean = listing.account.isEmpty() || listing.account == account

    private var localTasks by mutableStateOf(coordinator.tasks.value.values.filter(::isMine))

    private var listings by mutableStateOf(coordinator.listings.value.filterValues(::isMine))

    private var uploadTasks by mutableStateOf(uploads.tasks.value.values.filter { it.account == account })
    private var uploadPreparations by mutableStateOf(uploads.preparations.value.filter { it.account == account })

    private var packJobs by mutableStateOf(packTracker.jobs.value)
    private var busyPacks by mutableStateOf(packTracker.busy.value)

    private var instantRecords by mutableStateOf(instantSaves.records.value.filter { it.account == account })
    private var instantAttempts by mutableStateOf(instantSaves.attempts.value.filter { it.account == account })
    private var offlineRecords by mutableStateOf(offlineSubmits?.works?.value.orEmpty().filter { it.account == account })

    private var journal by mutableStateOf(driveRepo.changes.state.value)
    private var writeRecords by mutableStateOf(fileWrites?.works?.value.orEmpty().filter { it.account == account })
    private val fileCommands = fileWrites?.let { dev.piko.shared.presentation.FileCommands(driveRepo, it, account, vault = vault, receiving = receiving) }

    private fun requestUndo(id: Long): Boolean {
        val commands = fileCommands ?: return driveRepo.changes.undo(id)
        return when (val result = commands.undo(id)) {
            is dev.piko.shared.presentation.FileCommandResult.Accepted -> true
            is dev.piko.shared.presentation.FileCommandResult.Rejected -> { _messages.tryEmit(result.reason); false }
        }
    }

    fun writeUndoStatus(work: FileWriteWork): DriveChangeJournal.UndoResult? = writeUndoStatus(work, journal)

    private fun writeUndoStatus(work: FileWriteWork, currentJournal: DriveChangeJournal.JournalState): DriveChangeJournal.UndoResult? {
        return undoStatus(work.result?.undoChangeIds.orEmpty(), currentJournal)
    }
    private fun undoStatus(ids: List<Long>, currentJournal: DriveChangeJournal.JournalState): DriveChangeJournal.UndoResult? {
        val statuses = ids.mapNotNull { currentJournal.undo[it] }
        return when {
            statuses.any { it.status == DriveChangeJournal.UndoStatus.RUNNING } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.RUNNING, tr("正在撤销已确认部分", "Undoing confirmed changes", "確認済みの変更を元に戻しています", "확인된 변경 실행 취소 중", "正在復原已確認部分"))
            statuses.any { it.status == DriveChangeJournal.UndoStatus.FAILED } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.FAILED, tr("部分更改未能撤销，可重试", "Some changes could not be undone. You can retry", "一部の変更を元に戻せませんでした。再試行できます", "일부 변경을 실행 취소하지 못했습니다. 다시 시도할 수 있습니다", "部分變更未能復原，可重試"))
            ids.isNotEmpty() && statuses.size == ids.size && statuses.all { it.status == DriveChangeJournal.UndoStatus.UNDONE } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.UNDONE, tr("已撤销已确认部分", "Confirmed changes undone", "確認済みの変更を元に戻しました", "확인된 변경 실행 취소 완료", "已復原已確認部分"))
            else -> null
        }
    }
    fun canUndoWrite(work: FileWriteWork): Boolean = !work.isRunning && work.result?.undoChangeIds.orEmpty().any { id -> journal.available.any { it.id == id } }
    fun undoWrite(work: FileWriteWork) {
        if (work.account != driveRepo.currentAccount || work.isRunning || writeUndoStatus(work)?.status == DriveChangeJournal.UndoStatus.RUNNING) return
        work.result?.undoChangeIds.orEmpty().asReversed().filter { id -> driveRepo.changes.state.value.available.any { it.id == id } }.forEach(::requestUndo)
    }
    fun vaultUndoStatus(record: FolderVaultSession.Record): DriveChangeJournal.UndoResult? = record.undoChangeId?.let { journal.undo[it] }
    fun canUndoVault(record: FolderVaultSession.Record): Boolean = record.undoChangeId?.let { id -> journal.available.any { it.id == id } } == true
    fun undoVault(record: FolderVaultSession.Record) {
        val accepted = record.undoChangeId?.let(::requestUndo) == true
        if (!accepted) _messages.tryEmit(tr("此操作正在撤销，或撤销记录已失效", "This operation is being undone or its undo record is unavailable", "この操作は元に戻している途中か、元に戻す記録が利用できません", "이 작업은 실행 취소 중이거나 실행 취소 기록을 사용할 수 없습니다", "此操作正在復原，或復原記錄已失效"))
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 操作失败等提示。本地任务的失败体现在任务状态上，不走这里。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 云端列表尚未取回过。此时各分组都空也不该显示空状态。 */
    val isLoading: Boolean get() = cloud.isLoading

    /** 云端列表最近一次拉取失败的原因。 */
    val cloudLoadError: String? get() = cloud.loadError

    /** 类型筛选。各分组只列这一类；[counts] 仍按全部算，筛选按钮上的数目不随筛选变。 */
    var filter by mutableStateOf(TransferKind.ALL)
        private set

    fun changeFilter(kind: TransferKind) {
        filter = kind
        // 选中项可能被筛掉，留着的话批量删除会动到看不见的任务
        clearSelection()
    }

    /** 各分组来自同一次投影，每项只分类一次，状态变化时不会同时留在两段。 */
    private val allSections: TransferSections by derivedStateOf {
        val windowStartMs = nowMs() - COMPLETED_CLOUD_WINDOW.inWholeMilliseconds
        val currentJournal = journal
        val progress = mutableListOf<TransferItem>()
        val attention = mutableListOf<TransferItem>()
        val completed = mutableListOf<TransferItem>()
        val deleted = mutableListOf<TransferItem>()
        val paused = mutableListOf<TransferItem>()
        for (item in allItems()) {
            when (sectionOf(item, currentJournal, windowStartMs)) {
                Section.PAUSED -> paused += item
                Section.PROGRESS -> progress += item
                Section.ATTENTION -> attention += item
                Section.COMPLETED -> completed += item
                Section.DELETED -> deleted += item
                null -> Unit
            }
        }
        TransferSections(progress, attention, completed, deleted, paused)
    }

    val activity: ActivitySummary by derivedStateOf {
        ActivitySummary(allSections.inProgress, allSections.needsAttention, allSections.paused, downloadSpeed, uploadSpeed,
            (allSections.inProgress + allSections.needsAttention).any { item -> item.requiresLocalExecution() ||
                item is TransferItem.Vault && item.record?.let(::vaultUndoStatus)?.status == DriveChangeJournal.UndoStatus.RUNNING })
    }

    private val allInProgress get() = allSections.inProgress
    private val allCompleted get() = allSections.completed

    /**
     * 按类型筛选分组。横划切换类别时相邻的一页跟着手指进来，它列的是还没选中的那一类，所以不能只有 [filter] 的一份。
     * 读的是派生状态，调用方包一层 derivedStateOf 即可随任务刷新。
     */
    fun sectionsOf(kind: TransferKind): TransferSections {
        val all = allSections
        return TransferSections(all.inProgress.filter(kind::matches), all.needsAttention.filter(kind::matches),
            all.completed.filter(kind::matches), all.outputDeleted.filter(kind::matches), all.paused.filter(kind::matches))
    }

    private val current: TransferSections by derivedStateOf { sectionsOf(filter) }
    val paused: List<TransferItem> get() = current.paused
    val inProgress: List<TransferItem> get() = current.inProgress
    val needsAttention: List<TransferItem> get() = current.needsAttention
    val completed: List<TransferItem> get() = current.completed
    val outputDeleted: List<TransferItem> get() = current.outputDeleted

    /** 各类的项数，按全部任务算，筛选按钮上显示。 */
    val counts: Map<TransferKind, Int> by derivedStateOf {
        val sections = allSections
        val items = sections.paused + sections.inProgress + sections.needsAttention + sections.completed + sections.outputDeleted
        TransferKind.entries.associateWith { kind -> items.count(kind::matches) }
    }

    /** 下行与上行的总速度，只算本机的下载与上传：云端离线任务没有速度可报。 */
    val downloadSpeed: Long by derivedStateOf {
        localTasks.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speedBytesPerSec }
    }
    val uploadSpeed: Long by derivedStateOf {
        uploadTasks.filter { it.status.isActive }.sumOf { it.speedBytesPerSec }
    }

    /** 排队、进行中与暂停的下载还要写多少字节。暂停的也算：它们迟早要落到同一块盘上。 */
    val pendingDownloadBytes: Long by derivedStateOf {
        localTasks.filter { it.status in IN_PROGRESS_LOCAL }.sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
    }

    /** 照眼下的下行速度，排队与进行中的下载还要多少秒；没在下载时为 null。暂停的不算，它们不会自己动。 */
    val downloadEtaSeconds: Long? by derivedStateOf {
        val speed = downloadSpeed.takeIf { it > 0 } ?: return@derivedStateOf null
        val remaining = localTasks
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
            .sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
        (remaining / speed).takeIf { remaining > 0 }
    }

    /** 排队与进行中的离线任务落进网盘后要占的空间。只知道整个任务的大小，下到一半的也按全部算。 */
    val pendingCloudBytes: Long by derivedStateOf {
        cloud.tasks
            .filter { it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.PENDING }
            .sumOf { it.fileSize.toLongOrNull() ?: 0L }
    }

    // 全局的「全部暂停」「全部继续」只管本机的下载与上传：云端离线任务不能暂停
    private val controllableWork: List<TransferItem> by derivedStateOf {
        (allSections.inProgress + allSections.needsAttention + allSections.paused).flatMap {
            if (isPausable(it) || isResumable(it)) listOf(it) else it.children
        }
    }
    val canPauseAll: Boolean by derivedStateOf { controllableWork.any(::isPausable) }
    val canResumeAll: Boolean by derivedStateOf { controllableWork.any(::isResumable) }

    /**
     * 「清除已完成」能清掉的记录：已完成的云端离线、上传、秒传与引用保存（含已结清回执）、离线提交回执、
     * 整包离线、服务端解压、归档任务与远端复制。本地下载不在内：它的记录就是找回下载文件的入口，
     * 删记录只能连文件一起删，那是逐项的「删除本地文件」，不该混进一键清除。
     */
    val canClearCompleted: Boolean by derivedStateOf {
        allCompleted.any { it !is TransferItem.Local && it !is TransferItem.LocalBatch }
    }

    fun pauseAll() = controllableWork.filter(::isPausable).forEach(::pause)

    fun resumeAll() = controllableWork.filter(::isResumable).forEach(::resume)

    fun clearCompleted() {
        val done = allCompleted
        if (done.any { it is TransferItem.Cloud }) clearCompletedCloud()
        done.forEach { item ->
            when (item) {
                is TransferItem.UploadBatch -> removeUploadBatch(item.batchId)
                is TransferItem.Upload -> removeUpload(item.task.taskId)
                is TransferItem.Instant -> removeInstant(item.record.id)
                is TransferItem.InstantAttempt -> instantSaves.forgetAttempt(item.work.id)
                is TransferItem.OfflineSubmit -> offlineSubmits?.forget(item.work.id)
                is TransferItem.Pack -> discardPack(item.job.taskId)
                is TransferItem.Extract -> skipExtract(item.job.id)
                is TransferItem.Vault -> vault.dismissRecord(item.work.id)
                is TransferItem.Write -> fileWrites?.forget(item.work.id)
                is TransferItem.Local, is TransferItem.LocalBatch, is TransferItem.Cloud, is TransferItem.UploadPreparing -> Unit
            }
        }
    }

    /**
     * 选中的几项，按 key 记：进度每半秒刷新一次，条目对象跟着换，记对象的话选中会丢。
     * 照网盘页：点选只选这一项，主修饰键加选，Shift 从 [anchorKey] 连选到这里。
     */
    var selectedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    private var anchorKey: String? = null

    /**
     * 多选态：各行画复选框，单击即勾选。照网盘页，由主修饰键或 Shift 点选、长按、全选进入；鼠标普通单击只选中
     * 一项、不进多选，否则点了 A 再点 B 会两项都选上，与资源管理器不符。
     */
    var checkboxMode by mutableStateOf(false)
        private set

    /** 选中项里还在列表上的，条目被移除后自然不算。文件夹下载展开后其中的文件也选得中。 */
    val selectedItems: List<TransferItem> by derivedStateOf {
        val keys = selectedKeys
        if (keys.isEmpty()) return@derivedStateOf emptyList()
        val sections = current
        (sections.paused + sections.inProgress + sections.needsAttention + sections.completed + sections.outputDeleted)
            .flatMap { item -> listOf(item) + item.children }
            .filter { it.key in keys }
    }

    fun selectOnly(key: String) {
        selectedKeys = setOf(key)
        anchorKey = key
        checkboxMode = false
    }

    fun toggleSelected(key: String) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        anchorKey = key
        checkboxMode = selectedKeys.isNotEmpty()
    }

    /**
     * 进多选并选上这一项：移动端的长按，桌面右键菜单里的「选择」。不用 [toggleSelected]：鼠标点选过的那一项
     * 已在选中集里，翻转会把它取消。
     */
    fun enterSelection(key: String) {
        selectedKeys = selectedKeys + key
        anchorKey = key
        checkboxMode = true
    }

    /** [order] 是眼前列表的先后，收起的「文件已删除」组不在其中，连选不会跨进去。 */
    fun selectRange(key: String, order: List<String>) {
        val anchor = anchorKey?.takeIf { it in order } ?: return selectOnly(key)
        val from = order.indexOf(anchor)
        val to = order.indexOf(key)
        if (to < 0) return
        selectedKeys = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
        checkboxMode = true
    }

    /**
     * 框选，照网盘页的 selectBoxed：选中的换成 [base] 加上框住的 [boxed]，拖动时每动一下调一次。
     * [base] 是按着主修饰键或 Shift 开始框选时原来选中的，否则为空。框住的不止一项才进多选态，一项时与点选一样。
     */
    fun selectBoxed(base: Set<String>, boxed: Collection<String>) {
        val next = base + boxed
        if (next.isEmpty()) return clearSelection()
        if (selectedKeys != next) selectedKeys = next
        checkboxMode = next.size > 1
    }

    fun selectAll(order: List<String>) {
        selectedKeys = order.toSet()
        checkboxMode = selectedKeys.isNotEmpty()
    }

    fun clearSelection() {
        selectedKeys = emptySet()
        anchorKey = null
        checkboxMode = false
    }

    fun isPausable(item: TransferItem): Boolean = when (item) {
        is TransferItem.Local -> item.task.status == DownloadStatus.DOWNLOADING || item.task.status == DownloadStatus.PENDING
        // 列出中的一组还没有任务可停
        is TransferItem.LocalBatch -> item.listing == null &&
            item.tasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        is TransferItem.UploadBatch -> item.tasks.any { it.status.isActive }
        is TransferItem.Upload -> item.task.status.isActive
        else -> false
    }

    /** 暂停的继续、失败的重试，都算「继续」。云端失败的重试要重新提交，走各自的操作。 */
    fun isResumable(item: TransferItem): Boolean = when (item) {
        is TransferItem.Local -> item.task.status == DownloadStatus.PAUSED || item.task.status == DownloadStatus.FAILED
        is TransferItem.LocalBatch -> item.tasks.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
        is TransferItem.UploadBatch -> item.tasks.any { it.status == UploadStatus.PAUSED || it.status == UploadStatus.FAILED }
        is TransferItem.Upload -> item.task.status == UploadStatus.PAUSED || item.task.status == UploadStatus.FAILED
        else -> false
    }

    fun pause(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> pauseLocal(item.task.taskId)
            is TransferItem.LocalBatch -> coordinator.pauseBatch(item.batch.id)
            is TransferItem.UploadBatch -> pauseUploadBatch(item.batchId)
            is TransferItem.Upload -> pauseUpload(item.task.taskId)
            else -> Unit
        }
    }

    fun resume(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> resumeLocal(item.task.taskId)
            is TransferItem.LocalBatch -> coordinator.resumeBatch(item.batch.id)
            is TransferItem.UploadBatch -> resumeUploadBatch(item.batchId)
            is TransferItem.Upload -> resumeUpload(item.task.taskId)
            else -> Unit
        }
    }

    /** 删除一项，与各自操作里的删除相同：本地下载连文件一起删，云端任务只删记录。 */
    fun remove(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> removeLocal(item.task.taskId)
            is TransferItem.LocalBatch -> removeBatch(item.batch.id)
            is TransferItem.UploadBatch -> removeUploadBatch(item.batchId)
                is TransferItem.Upload -> removeUpload(item.task.taskId)
            is TransferItem.Cloud -> deleteCloud(item.task.id)
            is TransferItem.Pack -> discardPack(item.job.taskId)
            is TransferItem.Instant -> removeInstant(item.record.id)
            is TransferItem.InstantAttempt -> if (!instantSaves.forgetAttempt(item.work.id)) _messages.tryEmit(tr("正在保存，请待完成后再移除记录", "Saving in progress. Remove the record after completion", "保存中です。完了後に記録を削除してください", "저장 중입니다. 완료 후 기록을 삭제해 주세요", "正在儲存，請待完成後再移除記錄")) else Unit
            is TransferItem.OfflineSubmit -> if (offlineSubmits?.forget(item.work.id) == false) _messages.tryEmit(tr("正在提交离线下载，请待完成后再移除记录", "Submitting cloud download. Remove the record after completion", "クラウドダウンロードを送信中です。完了後に記録を削除してください", "클라우드 다운로드 제출 중입니다. 완료 후 기록을 삭제해 주세요", "正在提交離線下載，請待完成後再移除記錄")) else Unit
            is TransferItem.UploadPreparing -> if (!uploads.forgetPreparation(item.work.id)) _messages.tryEmit(tr("正在准备上传，请待完成后再移除记录", "Preparing upload. Remove the record after completion", "アップロードを準備中です。完了後に記録を削除してください", "업로드 준비 중입니다. 완료 후 기록을 삭제해 주세요", "正在準備上傳，請待完成後再移除記錄")) else Unit
            // 提交后的解压在服务端取消不了，只能放弃还在等密码的；归档只能停止，没有删除可言
            is TransferItem.Extract -> skipExtract(item.job.id)
            is TransferItem.Vault -> if (item.record != null) dismissVault(item.work.id) else Unit
            is TransferItem.Write -> if (fileWrites?.forget(item.work.id) == false) _messages.tryEmit(tr("正在执行操作或撤销，请待完成后再移除记录", "Operation or undo in progress. Remove the record after completion", "操作または元に戻す処理の実行中です。完了後に記録を削除してください", "작업 또는 실행 취소 중입니다. 완료 후 기록을 삭제해 주세요", "正在執行操作或復原，請待完成後再移除記錄")) else Unit

        }
    }

    fun removeSelected() {
        removeKeys(selectedKeys)
    }

    fun itemsForKeys(keys: Set<String>): List<TransferItem> = allItems()
        .flatMap { item -> listOf(item) + item.children }
        .filter { it.key in keys }

    fun removeKeys(keys: Set<String>) {
        val items = itemsForKeys(keys)
        cloud.delete(items.filterIsInstance<TransferItem.Cloud>().map { it.task.id })
        items.filterNot { it is TransferItem.Cloud }.forEach(::remove)
        val removedKeys = keys + items.flatMap { it.children.map(TransferItem::key) }
        selectedKeys = selectedKeys - removedKeys
        if (selectedKeys.isEmpty()) clearSelection()
        else if (anchorKey in removedKeys) anchorKey = null
    }

    /**
     * 已完成任务产出的缩略图，按产出文件 id。查过但没有缩略图的记为空串，不再重查。
     *
     * 任务本身不带缩略图，产出的文件详情也靠不住：文件夹的 thumbnail_link 在详情里常为空，
     * 在父目录的列表里却有，网盘列表的文件夹封面用的就是后者。所以先查详情拿到父目录，
     * 再列父目录取缩略图；同一父目录只列一次，产出多半都落在同一个保存目录里。
     */
    private var thumbnails by mutableStateOf<Map<String, String>>(taskRepo.outputThumbnails.toMap())

    fun thumbnailOf(fileId: String): String? = thumbnails[fileId]?.ifEmpty { null }

    private val completedOutputIds: List<String> by derivedStateOf {
        allCompleted.mapNotNull { item ->
            when (item) {
                is TransferItem.Cloud -> item.task.fileId
                is TransferItem.Pack -> item.job.outputId
                is TransferItem.Instant -> item.record.locateId
                is TransferItem.Upload -> item.task.fileId
                is TransferItem.Local, is TransferItem.LocalBatch, is TransferItem.UploadBatch, is TransferItem.UploadPreparing, is TransferItem.InstantAttempt, is TransferItem.OfflineSubmit,
                is TransferItem.Extract, is TransferItem.Vault, is TransferItem.Write -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }

    /** 一项传输也没有，不论筛选。筛选后为空另看各段，界面给的是「这一类没有任务」。 */
    val isEmpty: Boolean by derivedStateOf {
        allSections.isEmpty
    }

    init {
        fileWrites?.let { session -> scope.launch { session.works.collect { records -> writeRecords = records.filter { it.account == account } } } }
        scope.launch { driveRepo.changes.state.collect { journal = it } }
        scope.launch {
            coordinator.tasks.collect { tasks -> localTasks = tasks.values.filter(::isMine) }
        }
        scope.launch {
            coordinator.listings.collect { all -> listings = all.filterValues(::isMine) }
        }
        scope.launch {
            packTracker.jobs.collect { packJobs = it }
        }
        scope.launch { packTracker.busy.collect { busyPacks = it } }
        scope.launch {
            uploads.tasks.collect { tasks -> uploadTasks = tasks.values.filter { it.account == account } }
        }
        scope.launch { uploads.preparations.collect { uploadPreparations = it.filter { work -> work.account == account } } }
        scope.launch {
            instantSaves.records.collect { records -> instantRecords = records.filter { it.account == account } }
        }
        scope.launch { instantSaves.attempts.collect { records -> instantAttempts = records.filter { it.account == account } } }
        offlineSubmits?.let { session -> scope.launch { session.works.collect { offlineRecords = it.filter { work -> work.account == account } } } }
        scope.launch {
            cloud.messages.collect { _messages.emit(it) }
        }
        scope.launch {
            snapshotFlow { completedOutputIds }.collect { ids -> loadThumbnails(ids) }
        }
    }

    private suspend fun loadThumbnails(ids: List<String>) {
        val missing = ids.filter { it !in thumbnails }.take(THUMBNAIL_BATCH)
        if (missing.isEmpty()) return
        val resolved = mutableMapOf<String, String>()
        for (id in missing) {
            driveRepo.knownFile(id)?.thumbnailLink?.takeIf { it.isNotBlank() }?.let { resolved[id] = it }
        }
        val unresolved = missing.filter { it !in resolved }
        if (unresolved.isNotEmpty()) {
            val parents = mutableMapOf<String, String>()
            for (id in unresolved) {
                val knownParent = driveRepo.knownFile(id)?.parentId ?: driveRepo.knownParentOf(id)
                if (knownParent != null) {
                    parents[id] = knownParent
                } else {
                    driveRepo.getFileDetail(id)
                        .onSuccess { detail -> parents[id] = detail.parentId }
                        .onFailure { err ->
                            if ((err as? PikPakException)?.httpStatus == 404) {
                                resolved[id] = ""
                            } else {
                                PikoLog.d(TAG, "Deferred thumbnail detail lookup for $id: ${err.message}")
                            }
                        }
                }
            }
            for (parentId in parents.values.distinct()) {
                val listing = driveRepo.listAllFiles(parentId)
                    .onFailure { err -> PikoLog.d(TAG, "Deferred thumbnail folder listing for ${logFolder(parentId)}: ${err.message}") }
                    .getOrNull() ?: continue
                val byId = listing.associateBy { it.id }
                for ((id, pid) in parents) {
                    if (pid == parentId) resolved[id] = byId[id]?.thumbnailLink.orEmpty()
                }
            }
        }
        // 已成功核对但无缩略图的记为空串免得重复拉取；瞬态网络报错的不写入缓存，留待下次重试
        if (resolved.isNotEmpty()) {
            taskRepo.outputThumbnails.putAll(resolved)
            thumbnails = thumbnails + resolved
        }
    }

    /** 可见期间轮询云端任务，挂起直到调用方的协程被取消。 */
    suspend fun whileVisible() = kotlinx.coroutines.coroutineScope {
        launch {
            while (true) {
                activity.active.forEach { item -> when (item) {
                    is TransferItem.Write -> if (!item.work.isRunning) fileWrites?.recheck(item.work.id)
                    is TransferItem.InstantAttempt -> if (!item.work.isRunning) instantSaves.recheck(item.work.id, driveRepo)
                    is TransferItem.Extract -> if (item.job.status is ArchiveJobStatus.Finished) extracts.recheck(item.job.id)
                    else -> Unit
                } }
                kotlinx.coroutines.delay(15_000)
            }
        }
        cloud.pollWhileVisible()
    }

    /** 下拉刷新。本机的下载与上传本就实时，只有云端任务要重新拉取。 */
    val isRefreshing: Boolean get() = cloud.isRefreshing

    fun refresh() = cloud.refresh()

    fun pauseLocal(taskId: String) = coordinator.pauseDownload(taskId)

    /** 继续暂停的任务，或重试失败的任务。 */
    fun resumeLocal(taskId: String) = coordinator.startDownload(taskId)

    /** 取消并删除本地文件。已完成的任务删的是成品，未完成的删的是半截文件。 */
    fun removeLocal(taskId: String) = coordinator.cancelDownload(taskId)

    /** 取消整个文件夹下载，已下载的文件一并删除；还在列出的停下。 */
    fun removeBatch(batchId: String) = coordinator.cancelBatch(batchId)

    fun pauseBatch(batchId: String) = coordinator.pauseBatch(batchId)

    fun resumeBatch(batchId: String) = coordinator.resumeBatch(batchId)

    fun retryListing(batchId: String) = coordinator.retryListing(batchId)

    /** 超出今日下载额度也照样下载。 */
    fun confirmListing(batchId: String) = coordinator.confirmListing(batchId)

    /** 打开一组对应的文件夹：交给平台前要先查出它在本机的位置，SAF 目录下是查一遍目录树。 */
    suspend fun batchFolderPath(batch: DownloadBatch): String? {
        val path = coordinator.batchFolderPath(batch)
        if (path == null) _messages.tryEmit(tr("文件夹已不存在", "Folder no longer exists", "フォルダーは存在しません", "폴더가 더 이상 존재하지 않습니다", "資料夾已不存在"))
        return path
    }

    fun pauseUploadBatch(batchId: String) = uploads.pauseBatch(batchId)
    fun resumeUploadBatch(batchId: String) = uploads.resumeBatch(batchId)
    fun removeUploadBatch(batchId: String) = uploads.removeBatch(batchId)

    fun pauseUpload(taskId: String) = uploads.pause(taskId)

    /** 继续暂停的上传，或重试失败的上传。 */
    fun resumeUpload(taskId: String) = uploads.resume(taskId)

    /** 未完成的一并放弃网盘里上传中的文件，已完成的只删记录。 */
    fun removeUpload(taskId: String) = uploads.remove(taskId)

    /** 只删秒传记录，文件留在网盘里。 */
    fun removeInstant(recordId: String) = instantSaves.remove(recordId)

    /** 以原链接重新提交，旧记录随之删除。任务缺少 sourceUrl 时视图应隐藏此操作。 */
    fun resubmitCloud(task: DriveTask) = cloud.resubmit(task)

    /** 删除任务记录，也用作已完成任务的「移除」。已完成任务的文件保留在网盘里。 */
    fun deleteCloud(taskId: String) = cloud.delete(taskId)

    /** 清除全部已完成的云端任务记录，含列表时间窗之外的。文件保留在网盘里。 */
    fun clearCompletedCloud() = cloud.clear(listOf(TaskPhase.COMPLETE))

    /** 清除全部失败的云端任务记录。 */
    fun clearFailedCloud() = cloud.clear(listOf(TaskPhase.ERROR))

    /** 取消进行中的整包离线，或移除已结束的记录。已完成的文件保留在网盘里。 */
    fun discardPack(taskId: String) {
        scope.launch {
            packTracker.discard(taskId)
                .logFailure(TAG, "Failed to discard offline pack task $taskId")
                .onFailure { _messages.tryEmit(dev.piko.shared.notice.PikoErrorMapper.failure(tr("操作", "Operation", "操作", "작업", "操作"), it)) }
        }
    }

    /** 放弃待输密码的压缩包或移除已结束的记录；已提交的远端任务不受影响。 */
    fun skipExtract(jobId: String) = extracts.skip(jobId)
    fun recheckExtract(jobId: String) = extracts.recheck(jobId)
    fun promptExtractPassword(jobId: String) = extracts.reopenPasswordPrompt(jobId)

    /** 停止归档或取消归档。进行中的文件夹处理完才停，期间 [isVaultStopping] 为真。 */
    fun stopVault() = vault.stop()
    fun stopVault(id: Long) = vault.stop(id)
    fun dismissVault(id: Long) {
        val record = vault.records.firstOrNull { it.work.id == id } ?: return
        if (record.undoChangeId?.let { driveRepo.changes.state.value.undo[it]?.status } == DriveChangeJournal.UndoStatus.RUNNING) {
            _messages.tryEmit(tr("正在撤销，请待完成后再移除记录", "Undo in progress. Remove the record after completion", "元に戻しています。完了後に記録を削除してください", "실행 취소 중입니다. 완료 후 기록을 삭제해 주세요", "正在復原，請待完成後再移除記錄"))
        } else vault.dismissRecord(id)
    }

    val isVaultStopping: Boolean get() = vault.stopping

    /** 清理失败的重做清理，下载失败的以原链接重新离线。 */
    fun retryPack(taskId: String) {
        scope.launch {
            packTracker.retry(taskId)
                .logFailure(TAG, "Failed to retry offline pack task $taskId")
                .onFailure { _messages.tryEmit(dev.piko.shared.notice.PikoErrorMapper.failure(tr("重试", "Retry", "再試行", "다시 시도", "重試"), it)) }
        }
    }

    private fun localItem(task: DownloadTask) = itemCache.project("local:${task.taskId}", task) { TransferItem.Local(task) }
    private fun uploadItem(task: UploadTask) = itemCache.project("upload:${task.taskId}", task) { TransferItem.Upload(task) }

    private fun allItems(): List<TransferItem> {
        val local = localTasks
        val listing = listings
        val cloudTasks = cloud.tasks
        val packs = packJobs
        val grouped = local.filter { it.batch != null }.groupBy { it.batch!!.id }
        val batches = (grouped.keys + listing.keys).map { id ->
            val tasks = grouped[id].orEmpty().sortedBy { it.fileName }
            val batch = tasks.firstOrNull()?.batch ?: listing.getValue(id).batch
            itemCache.project("batch:$id", Triple(batch, tasks, listing[id])) {
                TransferItem.LocalBatch(batch, tasks, listing[id], tasks.map(::localItem))
            }
        }
        val prepById = uploadPreparations.associateBy { it.id }
        val uploadGroups = uploadTasks.filter { it.batchId != null }.groupBy { it.batchId!! }
            .filterValues { it.isNotEmpty() }
        val ownedPrepTaskIds = uploadPreparations.flatMapTo(HashSet()) { prep -> prep.files.map { it.taskId } }
        val uploadItems = uploadTasks.filter { it.batchId !in uploadGroups && it.taskId !in ownedPrepTaskIds }.map(::uploadItem) +
            uploadGroups.filterKeys { it !in prepById }.map { (id, tasks) ->
                val first = tasks.first()
                val sorted = tasks.sortedBy { it.createdAtMs }
                itemCache.project("upload-batch:$id", sorted) {
                    TransferItem.UploadBatch(id, first.batchName ?: first.fileName, first.batchParentId,
                        first.batchParentName, sorted, first.batchIsFolder, sorted.map(::uploadItem))
                }
            }
        val prepItems = uploadPreparations.map { prep ->
            val children = uploadGroups[prep.id]?.sortedBy { it.createdAtMs }?.map(::uploadItem)
                ?: uploadTasks.filter { task -> prep.files.any { it.taskId == task.taskId } }.map(::uploadItem)
            itemCache.project("upload-batch:${prep.id}", prep to children) {
                TransferItem.UploadPreparing(prep, children)
            }
        }
        val tasksById = cloudTasks.associateBy { it.id }
        val serverItems = if (!listsServerWork) emptyList() else {
            (extracts.jobs + extracts.completedJobs).map { itemCache.project("extract:${it.id}", it) { TransferItem.Extract(it) } } +
                listOfNotNull(vault.activeWork?.let { itemCache.project("vault:${it.id}", it to null) { TransferItem.Vault(it) } }) +
                vault.records.map { itemCache.project("vault:${it.work.id}", it.work to it) { TransferItem.Vault(it.work, it) } } +
                writeRecords.map { itemCache.project("write:${it.id}", it) { TransferItem.Write(it) } }
        }
        // 同一身份的结局优先于在途快照；文件夹里的本机文件只作为批次成员出现。
        val items = resolveWorkIdentities(serverItems + local.filter { it.batch == null }.map(::localItem) + batches +
            uploadItems + prepItems +
            cloudTasks.map { itemCache.project("cloud:${it.id}", it) { TransferItem.Cloud(it) } } +
            packs.map {
                val task = tasksById[it.taskId]
                val busy = dev.piko.shared.data.OfflinePackRef(it.account, it.taskId) in busyPacks
                itemCache.project("cloud:${it.taskId}", Triple(it, task, busy)) { TransferItem.Pack(it, task, busy) }
            } + instantRecords.map { itemCache.project("instant:${it.id}", it) { TransferItem.Instant(it) } } +
            instantAttempts.map { itemCache.project("instant:${it.id}", it) { TransferItem.InstantAttempt(it) } } +
            offlineRecords.map { itemCache.project("offline-submit:${it.id}", it) { TransferItem.OfflineSubmit(it) } })
            .sortedByDescending { it.createdAtMs }
        itemCache.retain(items.flatMap { item -> listOf(item.key) + item.children.map { it.key } }.toSet())
        return items
    }

    private enum class Section { PAUSED, PROGRESS, ATTENTION, COMPLETED, DELETED }

    private fun localSection(status: DownloadStatus): Section = when (status) {
        DownloadStatus.PAUSED -> Section.PAUSED
        DownloadStatus.COMPLETED -> Section.COMPLETED
        DownloadStatus.FAILED -> Section.ATTENTION
        else -> Section.PROGRESS
    }

    private fun uploadSection(status: UploadStatus): Section = when (status) {
        UploadStatus.PAUSED -> Section.PAUSED
        UploadStatus.COMPLETED -> Section.COMPLETED
        UploadStatus.FAILED -> Section.ATTENTION
        else -> Section.PROGRESS
    }

    private fun sectionOf(item: TransferItem, currentJournal: DriveChangeJournal.JournalState, windowStart: Long): Section? = when (item) {
        is TransferItem.Local -> localSection(item.task.status)
        is TransferItem.LocalBatch -> localSection(item.status)
        is TransferItem.UploadBatch -> uploadSection(item.status)
        is TransferItem.Upload -> uploadSection(item.task.status)
        is TransferItem.UploadPreparing -> when {
            item.work.isRunning || item.work.result?.completion == dev.piko.shared.upload.UploadPreparationCompletion.UNCONFIRMED -> Section.PROGRESS
            item.work.result?.completion == dev.piko.shared.upload.UploadPreparationCompletion.STOPPED -> when {
                item.children.any { it.requiresLocalExecution() } -> Section.PROGRESS
                item.children.any(::isResumable) -> Section.PAUSED
                else -> Section.COMPLETED
            }
            else -> Section.ATTENTION
        }
        is TransferItem.Cloud -> when {
            item.task.isOutputDeleted -> Section.DELETED
            item.task.phase == TaskPhase.PENDING || item.task.phase == TaskPhase.RUNNING -> Section.PROGRESS
            item.task.phase == TaskPhase.ERROR -> Section.ATTENTION
            item.task.phase == TaskPhase.COMPLETE && (item.updatedAtMs ?: Long.MIN_VALUE) >= windowStart -> Section.COMPLETED
            else -> null
        }
        is TransferItem.Pack -> when {
            item.isBusy -> Section.PROGRESS
            item.job.isActive -> Section.PROGRESS
            item.job.stage == OfflinePackStage.FAILED -> Section.ATTENTION
            item.job.stage == OfflinePackStage.DONE && item.job.finishedAtMs >= windowStart -> Section.COMPLETED
            else -> null
        }
        is TransferItem.Instant -> Section.COMPLETED.takeIf { item.record.createdAtMs >= windowStart }
        is TransferItem.InstantAttempt -> when {
            item.work.isRunning -> Section.PROGRESS
            item.work.isComplete -> Section.COMPLETED
            item.work.receipt?.let { it.failed.isNotEmpty() || it.notAttempted.isNotEmpty() } == true -> Section.ATTENTION
            else -> Section.PROGRESS
        }
        is TransferItem.OfflineSubmit -> when (item.work.status) {
            OfflineSubmitStatus.SUBMITTING -> Section.PROGRESS
            OfflineSubmitStatus.COMPLETE -> Section.COMPLETED
            OfflineSubmitStatus.REFUSED -> Section.ATTENTION
            OfflineSubmitStatus.UNCONFIRMED -> Section.PROGRESS
        }
        is TransferItem.Extract -> when (val status = item.job.status) {
            is ArchiveJobStatus.NeedsPassword -> Section.ATTENTION
            is ArchiveJobStatus.Finished -> when (status.outcome) {
                ArchiveCompletion.SUCCEEDED -> Section.COMPLETED
                ArchiveCompletion.UNCONFIRMED -> Section.PROGRESS
                ArchiveCompletion.FAILED -> Section.ATTENTION
            }
            else -> Section.PROGRESS
        }
        is TransferItem.Vault -> {
            val record = item.record
            val undo = record?.undoChangeId?.let { currentJournal.undo[it] }
            when {
                record == null || undo?.status == DriveChangeJournal.UndoStatus.RUNNING -> Section.PROGRESS
                record.needsAttention(undo) -> Section.ATTENTION
                else -> Section.COMPLETED
            }
        }
        is TransferItem.Write -> {
            val undo = writeUndoStatus(item.work, currentJournal)
            when {
                item.work.isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING -> Section.PROGRESS
                item.work.result?.completion == FileWriteCompletion.UNCONFIRMED -> Section.PROGRESS
                item.work.result?.completion != FileWriteCompletion.SUCCEEDED || undo?.status == DriveChangeJournal.UndoStatus.FAILED -> Section.ATTENTION
                else -> Section.COMPLETED
            }
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        private const val TAG = "Transfers"

        /** 一次最多补查多少个产出，已完成列表只列最近一周，通常远不到这个数。 */
        const val THUMBNAIL_BATCH = 40

        /**
         * 云端已完成任务的展示窗口。按查看次数划界的话，看过一眼的完成项切页回来就消失了；
         * 按时间划界，一周内完成的都还算新近，窗口又有上限，不会倒出整份历史。
         */
        val COMPLETED_CLOUD_WINDOW = 7.days

        private val IN_PROGRESS_LOCAL = setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
    }
}

/**
 * 产出文件已被删除的任务。列表接口带 with=reference_resource 时，服务端把这类任务的 phase
 * 覆盖成 ERROR、message 写作「File deleted」，getTask 查同一任务却是 COMPLETE/Saved。
 * 2026-09-23 在真实账号上实测，按 message 精确匹配。
 */
val DriveTask.isOutputDeleted: Boolean
    get() = phase == TaskPhase.ERROR && message == "File deleted"

/** 解析服务端的 RFC 3339 时间。缺失或格式不认识时返回 null。 */
private fun parseEpochMillis(rfc3339: String?): Long? =
    rfc3339?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** 保留未变化的行身份，让 Strong Skipping 按引用跳过；移除的任务不继续占用缓存。 */
private class TransferItemCache {
    private data class Entry(val source: Any?, val item: TransferItem)
    private val entries = mutableMapOf<String, Entry>()

    fun project(key: String, source: Any?, create: () -> TransferItem): TransferItem {
        val previous = entries[key]
        if (previous != null && previous.source == source) return previous.item
        return create().also { entries[key] = Entry(source, it) }
    }

    fun retain(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }
}
