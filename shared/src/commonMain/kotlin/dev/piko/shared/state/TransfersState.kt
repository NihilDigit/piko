package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.download.FolderListing
import dev.piko.shared.log.logFailure
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
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 传输列表中的一项：本地下载、上传、云端离线任务或秒传。 */
sealed interface TransferItem {
    /** 列表 key。两类任务的 id 来自不同命名空间，加前缀防撞。 */
    val key: String
    val createdAtMs: Long

    data class Local(val task: DownloadTask) : TransferItem {
        override val key: String get() = "local:${task.taskId}"
        override val createdAtMs: Long get() = task.createdAtMs
    }

    /**
     * 一次文件夹下载：列出中或等确认时只有 [listing]，之后是任务表里同一批的 [tasks]。
     * 各文件仍是普通任务，展开后各占一行（[Local]）。
     */
    data class LocalBatch(val batch: DownloadBatch, val tasks: List<DownloadTask>, val listing: FolderListing?) : TransferItem {
        override val key: String get() = "batch:${batch.id}"
        override val createdAtMs: Long get() = listing?.createdAtMs ?: tasks.minOfOrNull { it.createdAtMs } ?: 0L

        val totalBytes: Long get() = tasks.sumOf { it.totalBytes }
        val downloadedBytes: Long get() = tasks.sumOf { it.downloadedBytes }
        val completedCount: Int get() = tasks.count { it.status == DownloadStatus.COMPLETED }
        val failedCount: Int get() = tasks.count { it.status == DownloadStatus.FAILED }
        val speedBytesPerSec: Long get() = tasks.sumOf { it.speedBytesPerSec }
        val progress: Float get() = totalBytes.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

        /**
         * 整组的状态：有一个还在下、排着或暂停就算进行中，其余有失败的算失败，否则完成。
         * 列出失败与超额待确认都要用户处理，记作失败。
         */
        val status: DownloadStatus
            get() = when {
                listing != null -> if (listing.isListing) DownloadStatus.PENDING else DownloadStatus.FAILED
                tasks.any { it.status == DownloadStatus.DOWNLOADING } -> DownloadStatus.DOWNLOADING
                tasks.any { it.status == DownloadStatus.PENDING } -> DownloadStatus.PENDING
                tasks.any { it.status == DownloadStatus.PAUSED } -> DownloadStatus.PAUSED
                failedCount > 0 -> DownloadStatus.FAILED
                else -> DownloadStatus.COMPLETED
            }
    }

    data class Upload(val task: UploadTask) : TransferItem {
        override val key: String get() = "upload:${task.taskId}"
        override val createdAtMs: Long get() = task.createdAtMs
    }
    data class UploadPreparing(val work: dev.piko.shared.upload.UploadPreparation) : TransferItem {
        override val key: String get() = "upload-prepare:${work.id}"
        override val createdAtMs: Long get() = work.createdAtMs
    }

    data class Cloud(val task: DriveTask) : TransferItem {
        override val key: String get() = "cloud:${task.id}"
        override val createdAtMs: Long get() = parseEpochMillis(task.createdTime) ?: 0L
    }

    /**
     * 整包离线：一个云端任务加上完成后的清理与改名。[task] 是传输页列表里同一任务的快照，
     * 可见期间它每 4 秒刷新一次，下载进度取它的；跟踪器按退避轮询，不够及时。
     */
    data class Pack(val job: OfflinePackJob, val task: DriveTask?, val isBusy: Boolean = false) : TransferItem {
        // 与 Cloud 同一命名空间：同一任务只该出现一次
        override val key: String get() = "cloud:${job.taskId}"
        override val createdAtMs: Long get() = job.createdAtMs

        val progress: Int get() = if (task?.phase == TaskPhase.RUNNING) task.progress else job.progress
    }

    /** 秒传：当场完成，只出现在「已完成」里。 */
    data class Instant(val record: InstantSaveRecord) : TransferItem {
        override val key: String get() = "instant:${record.id}"
        override val createdAtMs: Long get() = record.createdAtMs
    }
    data class InstantAttempt(val work: InstantSaveAttempt) : TransferItem {
        override val key: String get() = "instant:${work.id}"
        override val createdAtMs: Long get() = work.finishedAtMs ?: work.createdAtMs
    }
    data class Share(val work: ShareSaveWork) : TransferItem {
        override val key: String get() = "share:${work.id}"
        override val createdAtMs: Long get() = work.result?.finishedAtMs ?: work.createdAtMs
    }
    data class OfflineSubmit(val work: OfflineSubmitWork) : TransferItem {
        override val key: String get() = "offline-submit:${work.id}"
        override val createdAtMs: Long get() = work.finishedAtMs ?: work.createdAtMs
    }

    /** 服务端解压与本次运行期的结局，来自 [ArchiveExtractSession]；提示没有订阅者也不丢记录。 */
    data class Extract(val job: ArchiveJob) : TransferItem {
        override val key: String get() = "extract:${job.id}"

        override val createdAtMs: Long get() = (job.status as? ArchiveJobStatus.Finished)?.finishedAtMs ?: job.createdAtMs
    }

    /** 归档或恢复及本次运行期的结果。同一次工作从进度到结局保持身份。 */
    data class Vault(val work: FolderVaultSession.Work, val record: FolderVaultSession.Record? = null) : TransferItem {
        val folderName: String get() = work.name
        val restoring: Boolean get() = work.restoring
        override val key: String get() = "vault:${work.id}"
        override val createdAtMs: Long get() = record?.finishedAtMs ?: work.createdAtMs
    }
    data class Write(val work: FileWriteWork) : TransferItem {
        override val key: String get() = "write:${work.id}"
        override val createdAtMs: Long get() = work.result?.finishedAtMs ?: work.createdAtMs
    }
    data class History(val work: HistoryEditWork) : TransferItem {
        override val key: String get() = "history:${work.id}"
        override val createdAtMs: Long get() = work.result?.finishedAtMs ?: work.createdAtMs
    }
    data class VaultEdit(val work: VaultEntryEditWork) : TransferItem {
        override val key: String get() = "vault-edit:${work.id}"
        override val createdAtMs: Long get() = work.result?.finishedAtMs ?: work.createdAtMs
    }
}

/** 某一类传输按「进行中」「需要处理」「已完成」「文件已删除」分成的四段。 */
class TransferSections(
    val inProgress: List<TransferItem>,
    val needsAttention: List<TransferItem>,
    val completed: List<TransferItem>,
    val outputDeleted: List<TransferItem>,
) {
    val isEmpty: Boolean
        get() = inProgress.isEmpty() && needsAttention.isEmpty() && completed.isEmpty() && outputDeleted.isEmpty()
}

/**
 * 传输页的类型筛选。秒传记录归「云端」：它与离线任务出自同一个「添加链接」，东西落在网盘里；
 * 从本机传上去却命中秒传的仍是一项上传，归「上传」。解压、归档、查重与清理归「文件处理」。
 */
enum class TransferKind(val label: String) {
    ALL("全部"),
    DOWNLOAD("下载"),
    UPLOAD("上传"),
    CLOUD("云端"),
    FILES("文件处理"),
    ;

    fun matches(item: TransferItem): Boolean = when (this) {
        ALL -> true
        DOWNLOAD -> item is TransferItem.Local || item is TransferItem.LocalBatch
        UPLOAD -> item is TransferItem.Upload || item is TransferItem.UploadPreparing
        CLOUD -> item is TransferItem.Cloud || item is TransferItem.Pack || item is TransferItem.Instant || item is TransferItem.InstantAttempt || item is TransferItem.Share || item is TransferItem.OfflineSubmit
        FILES -> item is TransferItem.Extract || item is TransferItem.Vault || item is TransferItem.Write || item is TransferItem.History || item is TransferItem.VaultEdit
    }
}

/**
 * 传输页：本地下载、上传与云端离线任务合并为「进行中」「需要处理」「已完成」三段，可按类型筛选（[filter]），
 * 多选照网盘页（[selectedKeys]）。
 *
 * 云端已完成的任务只列出最近 [COMPLETED_CLOUD_WINDOW] 内完成的，本地已完成的始终保留：
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
    val continuations: dev.piko.shared.presentation.TaskContinuations? = null,
    val fileWrites: FileWriteSession? = null,
    val historyEdits: HistoryEditSession? = null,
    val vaultEdits: VaultEntryEditSession? = null,
    val shareSaves: ShareSaveSession? = null,
    val offlineSubmits: OfflineSubmitSession? = taskRepo.offlineSubmissions,
    receiving: dev.piko.shared.presentation.ReceivingActivity? = null,
) {
    private val cloud = OfflineTasksState(taskRepo, scope)

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
    private var shareRecords by mutableStateOf(shareSaves?.works?.value.orEmpty().filter { it.account == account })
    private var offlineRecords by mutableStateOf(offlineSubmits?.works?.value.orEmpty().filter { it.account == account })

    private var journal by mutableStateOf(driveRepo.changes.state.value)
    private var writeRecords by mutableStateOf(fileWrites?.works?.value.orEmpty().filter { it.account == account })
    private var historyRecords by mutableStateOf(historyEdits?.works?.value.orEmpty().filter { it.account == account })
    private val fileCommands = fileWrites?.let { dev.piko.shared.presentation.FileCommands(driveRepo, it, account, continuations?.cleanup, vault, vaultEdits, shareSaves, receiving) }
    private var vaultEditRecords by mutableStateOf(vaultEdits?.works?.value.orEmpty().filter { it.account == account })

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
            statuses.any { it.status == DriveChangeJournal.UndoStatus.RUNNING } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.RUNNING, "正在撤销已确认部分")
            statuses.any { it.status == DriveChangeJournal.UndoStatus.FAILED } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.FAILED, "部分撤销未完成，可重新尝试撤销")
            ids.isNotEmpty() && statuses.size == ids.size && statuses.all { it.status == DriveChangeJournal.UndoStatus.UNDONE } -> DriveChangeJournal.UndoResult(DriveChangeJournal.UndoStatus.UNDONE, "已撤销已确认部分")
            else -> null
        }
    }
    fun canUndoWrite(work: FileWriteWork): Boolean = !work.isRunning && work.result?.undoChangeIds.orEmpty().any { id -> journal.available.any { it.id == id } }
    fun undoWrite(work: FileWriteWork) {
        if (work.account != driveRepo.currentAccount || work.isRunning || writeUndoStatus(work)?.status == DriveChangeJournal.UndoStatus.RUNNING) return
        work.result?.undoChangeIds.orEmpty().asReversed().filter { id -> driveRepo.changes.state.value.available.any { it.id == id } }.forEach(::requestUndo)
    }
    fun vaultUndoStatus(record: FolderVaultSession.Record): DriveChangeJournal.UndoResult? = record.undoChangeId?.let { journal.undo[it] }
    fun vaultEditUndoStatus(work: VaultEntryEditWork): DriveChangeJournal.UndoResult? = undoStatus(work.result?.undoChangeIds.orEmpty(), journal)
    fun shareUndoStatus(work: ShareSaveWork): DriveChangeJournal.UndoResult? = undoStatus(work.undoChangeIds, journal)
    fun canUndoShare(work: ShareSaveWork): Boolean = !work.isRunning && work.pendingRenames.isEmpty() && work.undoChangeIds.any { id -> journal.available.any {
        it.id == id && (fileCommands == null || fileCommands.undoAvailability(it) == dev.piko.shared.presentation.ActionAvailability.Available)
    } }
    fun undoShare(work: ShareSaveWork) {
        if (work.account != driveRepo.currentAccount || work.isRunning || shareUndoStatus(work)?.status == DriveChangeJournal.UndoStatus.RUNNING) return
        if (work.pendingRenames.isNotEmpty()) { _messages.tryEmit("这次命名仍有待确认的步骤，请先重新查询结果"); return }
        work.undoChangeIds.asReversed().filter { id -> driveRepo.changes.state.value.available.any { it.id == id } }.forEach(::requestUndo)
    }
    fun canUndoVaultEdit(work: VaultEntryEditWork): Boolean = !work.isRunning && work.result?.undoChangeIds.orEmpty().any { id -> journal.available.any { it.id == id } }
    fun undoVaultEdit(work: VaultEntryEditWork) {
        if (work.account != driveRepo.currentAccount || work.isRunning || vaultEditUndoStatus(work)?.status == DriveChangeJournal.UndoStatus.RUNNING) return
        work.result?.undoChangeIds.orEmpty().asReversed().filter { id -> driveRepo.changes.state.value.available.any { it.id == id } }.forEach(::requestUndo)
    }
    fun canUndoVault(record: FolderVaultSession.Record): Boolean = record.undoChangeId?.let { id -> journal.available.any { it.id == id } } == true
    fun undoVault(record: FolderVaultSession.Record) {
        val accepted = record.undoChangeId?.let(::requestUndo) == true
        if (!accepted) _messages.tryEmit("这次处理已在撤销，或撤销记录已不可用")
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 操作失败等提示。本地任务的失败体现在任务状态上，不走这里。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 云端列表尚未取回过。此时三段都空也不该显示空状态。 */
    val isLoading: Boolean get() = cloud.isLoading

    /** 云端列表最近一次拉取失败的原因。 */
    val cloudLoadError: String? get() = cloud.loadError

    /** 类型筛选。四段都只列这一类；[counts] 仍按全部算，筛选按钮上的数目不随筛选变。 */
    var filter by mutableStateOf(TransferKind.ALL)
        private set

    fun changeFilter(kind: TransferKind) {
        filter = kind
        // 选中项可能被筛掉，留着的话批量删除会动到看不见的任务
        clearSelection()
    }

    /** 四个分组来自同一次投影，每项只分类一次，状态变化时不会同时留在两段。 */
    private val allSections: TransferSections by derivedStateOf {
        val windowStartMs = nowMs() - COMPLETED_CLOUD_WINDOW.inWholeMilliseconds
        val currentJournal = journal
        val progress = mutableListOf<TransferItem>()
        val attention = mutableListOf<TransferItem>()
        val completed = mutableListOf<TransferItem>()
        val deleted = mutableListOf<TransferItem>()
        for (item in allItems()) {
            when (sectionOf(item, currentJournal, windowStartMs)) {
                Section.PROGRESS -> progress += item
                Section.ATTENTION -> attention += item
                Section.COMPLETED -> completed += item
                Section.DELETED -> deleted += item
                null -> Unit
            }
        }
        TransferSections(progress, attention, completed, deleted)
    }

    private val allInProgress get() = allSections.inProgress
    private val allCompleted get() = allSections.completed

    /**
     * 某一类的四段。横划切换类别时相邻的一页跟着手指进来，它列的是还没选中的那一类，所以不能只有 [filter] 的一份。
     * 读的是派生状态，调用方包一层 derivedStateOf 即可随任务刷新。
     */
    fun sectionsOf(kind: TransferKind): TransferSections {
        val all = allSections
        return TransferSections(all.inProgress.filter(kind::matches), all.needsAttention.filter(kind::matches),
            all.completed.filter(kind::matches), all.outputDeleted.filter(kind::matches))
    }

    private val current: TransferSections by derivedStateOf { sectionsOf(filter) }
    val inProgress: List<TransferItem> get() = current.inProgress
    val needsAttention: List<TransferItem> get() = current.needsAttention
    val completed: List<TransferItem> get() = current.completed
    val outputDeleted: List<TransferItem> get() = current.outputDeleted

    /** 各类的项数，按全部任务算，筛选按钮上显示。 */
    val counts: Map<TransferKind, Int> by derivedStateOf {
        val sections = allSections
        val items = sections.inProgress + sections.needsAttention + sections.completed + sections.outputDeleted
        TransferKind.entries.associateWith { kind -> items.count(kind::matches) + (continuations?.count(kind) ?: 0) }
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
    val canPauseAll: Boolean by derivedStateOf { allInProgress.any(::isPausable) }
    val canResumeAll: Boolean by derivedStateOf { allInProgress.any(::isResumable) }

    /**
     * 「清除已完成」能清掉的记录：云端任务、上传、秒传与整包离线。本地下载不在内：它的记录就是找回
     * 下载文件的入口，删记录只能连文件一起删，那是逐项的「删除本地文件」，不该混进一键清除。
     */
    val canClearCompleted: Boolean by derivedStateOf {
        allCompleted.any { it !is TransferItem.Local && it !is TransferItem.LocalBatch }
    }

    fun pauseAll() = allInProgress.filter(::isPausable).forEach(::pause)

    fun resumeAll() = allInProgress.filter(::isResumable).forEach(::resume)

    fun clearCompleted() {
        val done = allCompleted
        if (done.any { it is TransferItem.Cloud }) clearCompletedCloud()
        done.forEach { item ->
            when (item) {
                is TransferItem.Upload -> removeUpload(item.task.taskId)
                is TransferItem.Instant -> removeInstant(item.record.id)
                is TransferItem.InstantAttempt -> instantSaves.forgetAttempt(item.work.id)
                is TransferItem.Share -> shareSaves?.forget(item.work.id)
                is TransferItem.OfflineSubmit -> offlineSubmits?.forget(item.work.id)
                is TransferItem.Pack -> discardPack(item.job.taskId)
                is TransferItem.Extract -> skipExtract(item.job.id)
                is TransferItem.Vault -> vault.dismissRecord(item.work.id)
                is TransferItem.Write -> fileWrites?.forget(item.work.id)
                is TransferItem.History -> historyEdits?.forget(item.work.id)
                is TransferItem.VaultEdit -> vaultEdits?.forget(item.work.id)
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
        val sections = current
        val keys = selectedKeys
        (sections.inProgress + sections.needsAttention + sections.completed + sections.outputDeleted)
            .flatMap { item -> listOf(item) + (item as? TransferItem.LocalBatch)?.tasks.orEmpty().map { TransferItem.Local(it) } }
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
        selectedKeys = next
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
        is TransferItem.Upload -> item.task.status.isActive
        else -> false
    }

    /** 暂停的继续、失败的重试，都算「继续」。云端失败的重试要重新提交，走各自的操作。 */
    fun isResumable(item: TransferItem): Boolean = when (item) {
        is TransferItem.Local -> item.task.status == DownloadStatus.PAUSED || item.task.status == DownloadStatus.FAILED
        is TransferItem.LocalBatch -> item.tasks.any { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
        is TransferItem.Upload -> item.task.status == UploadStatus.PAUSED || item.task.status == UploadStatus.FAILED
        else -> false
    }

    fun pause(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> pauseLocal(item.task.taskId)
            is TransferItem.LocalBatch -> coordinator.pauseBatch(item.batch.id)
            is TransferItem.Upload -> pauseUpload(item.task.taskId)
            else -> Unit
        }
    }

    fun resume(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> resumeLocal(item.task.taskId)
            is TransferItem.LocalBatch -> coordinator.resumeBatch(item.batch.id)
            is TransferItem.Upload -> resumeUpload(item.task.taskId)
            else -> Unit
        }
    }

    /** 删除一项，与各自操作里的删除相同：本地下载连文件一起删，云端任务只删记录。 */
    fun remove(item: TransferItem) {
        when (item) {
            is TransferItem.Local -> removeLocal(item.task.taskId)
            is TransferItem.LocalBatch -> removeBatch(item.batch.id)
            is TransferItem.Upload -> removeUpload(item.task.taskId)
            is TransferItem.Cloud -> deleteCloud(item.task.id)
            is TransferItem.Pack -> discardPack(item.job.taskId)
            is TransferItem.Instant -> removeInstant(item.record.id)
            is TransferItem.InstantAttempt -> if (!instantSaves.forgetAttempt(item.work.id)) _messages.tryEmit("保存仍在进行，完成后才能移除记录") else Unit
            is TransferItem.Share -> if (shareSaves?.forget(item.work.id) == false) _messages.tryEmit("转存或撤销仍在进行，完成后才能移除记录") else Unit
            is TransferItem.OfflineSubmit -> if (offlineSubmits?.forget(item.work.id) == false) _messages.tryEmit("离线仍在提交，完成后才能移除记录") else Unit
            is TransferItem.UploadPreparing -> if (!uploads.forgetPreparation(item.work.id)) _messages.tryEmit("正在准备上传，完成后再移除记录") else Unit
            // 提交后的解压在服务端取消不了，只能放弃还在等密码的；归档只能停止，没有删除可言
            is TransferItem.Extract -> skipExtract(item.job.id)
            is TransferItem.Vault -> if (item.record != null) dismissVault(item.work.id) else Unit
            is TransferItem.Write -> if (fileWrites?.forget(item.work.id) == false) _messages.tryEmit("操作或撤销仍在进行，完成后才能移除记录") else Unit
            is TransferItem.History -> if (historyEdits?.forget(item.work.id) == false) _messages.tryEmit("记录仍在处理，完成后才能移除") else Unit
            is TransferItem.VaultEdit -> if (vaultEdits?.forget(item.work.id) == false) _messages.tryEmit("操作或撤销仍在进行，完成后才能移除记录") else Unit
        }
    }

    fun removeSelected() {
        selectedItems.forEach(::remove)
        clearSelection()
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
                is TransferItem.Local, is TransferItem.LocalBatch, is TransferItem.Upload, is TransferItem.UploadPreparing, is TransferItem.Instant, is TransferItem.InstantAttempt, is TransferItem.Share, is TransferItem.OfflineSubmit,
                is TransferItem.Extract, is TransferItem.Vault, is TransferItem.Write, is TransferItem.History, is TransferItem.VaultEdit -> null
            }?.takeIf { it.isNotEmpty() }
        }
    }

    /** 一项传输也没有，不论筛选。筛选后为空另看各段，界面给的是「这一类没有任务」。 */
    val isEmpty: Boolean by derivedStateOf {
        allSections.isEmpty && (continuations?.count(TransferKind.ALL) ?: 0) == 0
    }

    init {
        historyEdits?.let { session -> scope.launch { session.works.collect { historyRecords = it.filter { work -> work.account == account } } } }
        vaultEdits?.let { session -> scope.launch { session.works.collect { vaultEditRecords = it.filter { work -> work.account == account } } } }
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
        shareSaves?.let { session -> scope.launch { session.works.collect { shareRecords = it.filter { work -> work.account == account } } } }
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
        val parents = missing.associateWith { id -> driveRepo.getFileDetail(id).getOrNull()?.parentId }
        val found = mutableMapOf<String, String>()
        for (parentId in parents.values.filterNotNull().distinct()) {
            val listing = driveRepo.listAllFiles(parentId).getOrNull() ?: continue
            listing.filter { it.id in missing }.forEach { found[it.id] = it.thumbnailLink }
        }
        // 取不到的也记下，免得每次列表变动都重查同一批
        val loaded = missing.associateWith { found[it].orEmpty() }
        taskRepo.outputThumbnails.putAll(loaded)
        thumbnails = thumbnails + loaded
    }

    /** 可见期间轮询云端任务，挂起直到调用方的协程被取消。 */
    suspend fun whileVisible() = cloud.pollWhileVisible()

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
        if (path == null) _messages.tryEmit("文件夹已不存在")
        return path
    }

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
            packTracker.discard(taskId).onFailure { _messages.tryEmit("操作失败：${it.message}") }
        }
    }

    /** 放弃待输密码的压缩包或移除已结束的记录；已提交的远端任务不受影响。 */
    fun skipExtract(jobId: String) = extracts.skip(jobId)
    fun recheckExtract(jobId: String) = extracts.recheck(jobId)

    /** 停止归档或取消归档。进行中的文件夹处理完才停，期间 [isVaultStopping] 为真。 */
    fun stopVault() = vault.stop()
    fun stopVault(id: Long) = vault.stop(id)
    fun dismissVault(id: Long) {
        val record = vault.records.firstOrNull { it.work.id == id } ?: return
        if (record.undoChangeId?.let { driveRepo.changes.state.value.undo[it]?.status } == DriveChangeJournal.UndoStatus.RUNNING) {
            _messages.tryEmit("正在撤销，完成后才能移除这条记录")
        } else vault.dismissRecord(id)
    }

    val isVaultStopping: Boolean get() = vault.stopping

    /** 清理失败的重做清理，下载失败的以原链接重新离线。 */
    fun retryPack(taskId: String) {
        scope.launch {
            packTracker.retry(taskId).logFailure("OfflinePack", "重试整包离线任务 $taskId 失败").onFailure { _messages.tryEmit("重试失败：${it.message}") }
        }
    }

    private fun allItems(): List<TransferItem> {
        val local = localTasks
        val listing = listings
        val cloudTasks = cloud.tasks
        val packs = packJobs
        val grouped = local.filter { it.batch != null }.groupBy { it.batch!!.id }
        val batches = (grouped.keys + listing.keys).map { id ->
            val tasks = grouped[id].orEmpty().sortedBy { it.fileName }
            TransferItem.LocalBatch(tasks.firstOrNull()?.batch ?: listing.getValue(id).batch, tasks, listing[id])
        }
        val packIds = packs.mapTo(HashSet()) { it.taskId }
        val tracked = packIds + shareRecords.mapNotNull { it.remoteTaskId } + writeRecords.flatMap { it.copies.map { copy -> copy.id } } +
            (extracts.jobs + extracts.completedJobs).mapNotNull { it.remoteTaskId }
        val tasksById = cloudTasks.associateBy { it.id }
        val serverItems = if (!listsServerWork) emptyList() else {
            (extracts.jobs + extracts.completedJobs).map { TransferItem.Extract(it) } +
                listOfNotNull(vault.activeWork?.let { TransferItem.Vault(it) }) + vault.records.map { TransferItem.Vault(it.work, it) } +
                writeRecords.map { TransferItem.Write(it) } + historyRecords.map { TransferItem.History(it) } + vaultEditRecords.map { TransferItem.VaultEdit(it) }
        }
        // 同一身份的结局优先于在途快照；文件夹里的本机文件只作为批次成员出现。
        return (serverItems + local.filter { it.batch == null }.map { TransferItem.Local(it) } + batches +
            uploadTasks.map { TransferItem.Upload(it) } + uploadPreparations.map { TransferItem.UploadPreparing(it) } + cloudTasks.filter { it.id !in tracked }.map { TransferItem.Cloud(it) } +
            packs.map { TransferItem.Pack(it, tasksById[it.taskId], dev.piko.shared.data.OfflinePackRef(it.account, it.taskId) in busyPacks) } + instantRecords.map { TransferItem.Instant(it) } + instantAttempts.map { TransferItem.InstantAttempt(it) } + shareRecords.map { TransferItem.Share(it) } + offlineRecords.map { TransferItem.OfflineSubmit(it) })
            .associateBy { it.key }.values.sortedByDescending { it.createdAtMs }
    }

    private enum class Section { PROGRESS, ATTENTION, COMPLETED, DELETED }

    private fun localSection(status: DownloadStatus): Section = when (status) {
        DownloadStatus.COMPLETED -> Section.COMPLETED
        DownloadStatus.FAILED -> Section.ATTENTION
        else -> Section.PROGRESS
    }

    private fun sectionOf(item: TransferItem, currentJournal: DriveChangeJournal.JournalState, windowStart: Long): Section? = when (item) {
        is TransferItem.Local -> localSection(item.task.status)
        is TransferItem.LocalBatch -> localSection(item.status)
        is TransferItem.Upload -> when (item.task.status) {
            UploadStatus.COMPLETED -> Section.COMPLETED
            UploadStatus.FAILED -> Section.ATTENTION
            else -> Section.PROGRESS
        }
        is TransferItem.UploadPreparing -> if (item.work.isRunning) Section.PROGRESS else Section.ATTENTION
        is TransferItem.Cloud -> when {
            item.task.isOutputDeleted -> Section.DELETED
            item.task.phase == TaskPhase.PENDING || item.task.phase == TaskPhase.RUNNING -> Section.PROGRESS
            item.task.phase == TaskPhase.ERROR -> Section.ATTENTION
            item.task.phase == TaskPhase.COMPLETE && (parseEpochMillis(item.task.updatedTime) ?: Long.MIN_VALUE) >= windowStart -> Section.COMPLETED
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
        is TransferItem.InstantAttempt -> if (item.work.isRunning) Section.PROGRESS else Section.ATTENTION
        is TransferItem.OfflineSubmit -> when (item.work.status) {
            OfflineSubmitStatus.SUBMITTING -> Section.PROGRESS
            OfflineSubmitStatus.COMPLETE -> Section.COMPLETED
            OfflineSubmitStatus.REFUSED, OfflineSubmitStatus.UNCONFIRMED -> Section.ATTENTION
        }
        is TransferItem.Share -> {
            val undo = undoStatus(item.work.undoChangeIds, currentJournal)
            when {
                item.work.isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING -> Section.PROGRESS
                item.work.result?.completion != ShareSaveCompletion.SUCCEEDED || undo?.status == DriveChangeJournal.UndoStatus.FAILED -> Section.ATTENTION
                else -> Section.COMPLETED
            }
        }
        is TransferItem.Extract -> when (val status = item.job.status) {
            is ArchiveJobStatus.NeedsPassword -> Section.ATTENTION
            is ArchiveJobStatus.Finished -> if (status.outcome == ArchiveCompletion.SUCCEEDED) Section.COMPLETED else Section.ATTENTION
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
                item.work.result?.completion != FileWriteCompletion.SUCCEEDED || undo?.status == DriveChangeJournal.UndoStatus.FAILED -> Section.ATTENTION
                else -> Section.COMPLETED
            }
        }
        is TransferItem.History -> when {
            item.work.isRunning -> Section.PROGRESS
            item.work.result?.completion == HistoryEditCompletion.SUCCEEDED -> Section.COMPLETED
            else -> Section.ATTENTION
        }
        is TransferItem.VaultEdit -> {
            val undo = undoStatus(item.work.result?.undoChangeIds.orEmpty(), currentJournal)
            when {
                item.work.isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING -> Section.PROGRESS
                item.work.result?.completion != VaultEntryEditCompletion.SUCCEEDED || undo?.status == DriveChangeJournal.UndoStatus.FAILED -> Section.ATTENTION
                else -> Section.COMPLETED
            }
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
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
