package dev.piko.shared.state

import dev.piko.shared.i18n.enCount
import dev.piko.shared.presentation.localizedName

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEdit
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultEntryDraft
import dev.piko.shared.data.VaultIngest
import dev.piko.shared.data.VaultMediaMeta
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.VaultWrite
import dev.piko.shared.data.VaultWriteUnconfirmed
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.reportFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val LARGE_FILE_MIN_BYTES = 50L * 1024 * 1024

/**
 * 一次归档的范围：几个文件夹各自的整棵树，加上单独选的几个文件。单独的文件按所在文件夹分组，清单写进各自那一层，
 * 与整棵树里的一层没有区别，确认、处置、撤销、进度与通知都走同一条路。[name] 用在进度、提示与通知里。
 */
data class VaultScope(
    val name: String,
    val folders: List<PikoPathBreadcrumb> = emptyList(),
    val files: List<FileStat> = emptyList(),
) {
    /** 不含文件夹：确认框据此省掉对单个或几个文件没有意义的选项。 */
    val filesOnly: Boolean get() = folders.isEmpty()
    val targets: VaultWorkTargets get() = VaultWorkTargets(folders.mapTo(HashSet()) { it.id }, files.mapTo(HashSet()) { it.parentId }, files.mapTo(HashSet()) { it.id })

    // 日志里只留数量与 ID，名字不进日志
    internal val logScope: String get() = "${folders.size} 个文件夹（${folders.joinToString { it.id }}）与 ${files.size} 个单独的文件"

    companion object {
        fun of(folder: PikoPathBreadcrumb) = VaultScope(folder.localizedName, folders = listOf(folder))
    }
}

data class VaultWorkTargets(val roots: Set<String> = emptySet(), val manifests: Set<String> = emptySet(), val files: Set<String> = emptySet())

data class MissingVaultEntry(val folderId: String, val entry: VaultEntry)

/**
 * 把文件夹里或单独选的真实文件换成归档条目（范围见 [VaultScope]），腾出网盘空间，以及反过来恢复到网盘。进程级：离开网盘页照常进行；
 * 归档与恢复同一时刻只做一件，免得两边同时改同一份清单。
 *
 * 八个目录并行处理，各自清单确认写成后才处置原文件。中途失败时已写成的引用与恢复依据保留。
 *
 * 默认将原文件移入回收站，也可选择永久删除。撤销时按实际处置方式恢复，再去掉清单条目。
 *
 * 全局十六个任务取样 CID，同内容只取一次；取样超时不挡归档。CID 用于日后的只读体检。
 */
class FolderVaultSession internal constructor(
    private val operations: FolderVaultOperations,
    private val scope: CoroutineScope,
    private val cidTimeoutMillis: Long = 2_500,
) {
    constructor(driveRepo: PikoDriveRepository, scope: CoroutineScope) : this(DriveFolderVaultOperations(driveRepo), scope)

    enum class Completion { SUCCEEDED, PARTIAL, FAILED, STOPPED }
    data class Work(val id: Long, val name: String, val restoring: Boolean, val createdAtMs: Long)
    data class Record(
        val work: Work,
        val completion: Completion,
        val title: String,
        val message: String,
        val finishedAtMs: Long,
        val undoChangeId: Long? = null,
        val missingEntries: List<MissingVaultEntry> = emptyList(),
    ) {
        val missingSources: List<String> get() = missingEntries.mapNotNull { it.entry.source?.takeIf(String::isNotBlank) }.distinct()
    }

    var activeWork by mutableStateOf<Work?>(null)
        private set
    var activeTargets by mutableStateOf<VaultWorkTargets?>(null)
        private set
    var conflictReason: (VaultWorkTargets) -> String? = { null }
    fun unavailableReason(targets: VaultWorkTargets): String? = if (isRunning) tr("已有归档或恢复任务正在进行", "An archive or restore task is already running", "アーカイブまたは復元タスクを実行中です", "보관 또는 복원 작업이 이미 진행 중입니다", "已有歸檔或還原任務正在進行") else conflictReason(targets)

    private fun refused(targets: VaultWorkTargets): Boolean {
        val reason = unavailableReason(targets) ?: return false
        _messages.tryEmit(reason)
        return true
    }
    var records by mutableStateOf<List<Record>>(emptyList())
        private set
    private var sequence = 0L

    fun dismissRecord(id: Long) { records = records.filterNot { it.work.id == id } }

    /**
     * 一次归档之前的清点，给确认框用。没有来源记录的（自己上传、秒传）单独计。
     */
    class Survey(
        val files: Int,
        val bytes: Long,
        val unsourcedFiles: Int,
        val unsourcedBytes: Long,
        val largeFiles: Survey? = null,
    )

    /** 正在归档的文件夹与进度。 */
    class Progress(val folderName: String, val done: Int, val total: Int, val prepared: Int = done)

    var progress by mutableStateOf<Progress?>(null)
        private set

    /** 恢复到网盘的进度。[total] 为 null 时还在扫描。 */
    data class RestoreProgress(
        val folderName: String,
        val stage: String = tr("正在扫描归档条目", "Scanning archive items", "アーカイブ項目をスキャン中", "보관 항목 스캔 중", "正在掃描歸檔項目"),
        val scannedFolders: Int = 0,
        val done: Int = 0,
        val total: Int? = null,
    )

    var restoreProgress by mutableStateOf<RestoreProgress?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 一次归档或恢复的结局，给不在眼前的用户发系统通知（见 ui 的 workNotices）。[messages] 只有网盘页收，
     * 用户切到别处或应用退到后台时没人看见；归档动辄几分钟，正是会走开的时候。
     */
    class Outcome(
        val title: String,
        val message: String,
        /** 同一结局已记进改动日志，由主界面带「撤销」提示过；页内的结局提示据此略过，免得说两遍。 */
        val undoable: Boolean = false,
    )

    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    /** 归档或恢复正在进行，Android 据此保持前台服务。 */
    val isRunning: Boolean get() = activeWork != null

    private var job: Job? = null

    /**
     * 用户要求停下。不取消协程：一个目录的清单写成与处置原文件之间被打断，会留下条目与原文件并存，
     * 撤销记录也不全。只是不再开始新的目录与文件，正在做的做完，做完的照常记撤销。
     */
    var stopping by mutableStateOf(false)
        private set

    fun stop() {
        if (activeWork != null) stopping = true
    }

    fun stop(id: Long) { if (activeWork?.id == id) stop() }

    /** 已有一件在做时提示一句并返回 true，否则为新的一件清掉停止标记。 */
    private fun busy(): Boolean {
        if (activeWork == null) {
            stopping = false
            return false
        }
        val running = progress?.let { tr("正在归档「${it.folderName}」", "Archiving “${it.folderName}”", "「${it.folderName}」をアーカイブ中", "“${it.folderName}” 보관 중", "正在歸檔「${it.folderName}」") }
            ?: restoreProgress?.let { tr("正在恢复「${it.folderName}」", "Restoring “${it.folderName}”", "「${it.folderName}」を復元中", "“${it.folderName}” 복원 중", "正在還原「${it.folderName}」") }
            ?: tr("正在处理归档", "Processing archive", "アーカイブを処理中", "보관 처리 중", "正在處理歸檔")
        _messages.tryEmit(tr("$running，请稍后再试", "$running. Please try again later", "$running。しばらくしてからお試しください", "$running. 잠시 후 다시 시도해 주세요", "$running，請稍後再試"))
        return true
    }

    private fun launchWork(name: String, restoring: Boolean, targets: VaultWorkTargets, block: suspend (Work) -> Unit) {
        if (!scope.isActive) return
        val work = Work(++sequence, name, restoring, Clock.System.now().toEpochMilliseconds())
        activeWork = work
        activeTargets = targets
        if (restoring) restoreProgress = RestoreProgress(name)
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try { block(work) } finally {
                if (activeWork === work) {
                    activeWork = null
                    activeTargets = null
                    progress = null
                    restoreProgress = null
                }
            }
        }
        job = nextJob
        nextJob.start()
    }

    private suspend fun ensureCurrent(work: Work) {
        currentCoroutineContext().ensureActive()
        if (activeWork !== work) throw CancellationException(tr("归档会话已结束", "Archive session ended", "アーカイブのセッションは終了しました", "보관 세션이 종료되었습니다", "歸檔工作階段已結束"))
    }

    private fun publish(work: Work, completion: Completion, title: String, message: String, change: DriveChangeJournal.Change.Vault? = null, missingEntries: List<MissingVaultEntry> = emptyList()) {
        if (activeWork !== work) return
        val record = Record(work, completion, title, message, Clock.System.now().toEpochMilliseconds(), change?.id, missingEntries)
        Snapshot.withMutableSnapshot {
            records = (records + record).takeLast(100)
            activeWork = null
            activeTargets = null
            progress = null
            restoreProgress = null
        }
        _outcomes.tryEmit(Outcome(title, message, undoable = change != null))
    }

    /** 清点 [folder] 整棵树里能归档的文件。 */
    suspend fun survey(folder: PikoPathBreadcrumb): Result<Survey> = survey(VaultScope.of(folder))

    /** 清点 [target] 里能归档的文件。 */
    suspend fun survey(target: VaultScope): Result<Survey> = runSuspendCatching {
        val ops = bindOperations()
        val files = levelsOf(ops, target).flatMap { it.second }
        val unsourced = files.filter { it.sourceUrl.isNullOrBlank() }
        val large = files.filter { it.sizeBytes >= LARGE_FILE_MIN_BYTES }
        val largeUnsourced = large.filter { it.sourceUrl.isNullOrBlank() }
        Survey(files.size, files.sumOf { it.sizeBytes }, unsourced.size, unsourced.sumOf { it.sizeBytes },
            Survey(large.size, large.sumOf { it.sizeBytes }, largeUnsourced.size, largeUnsourced.sumOf { it.sizeBytes }))
    }

    /** 开始归档 [folder] 整棵树。 */
    fun archive(folder: PikoPathBreadcrumb, includeUnsourced: Boolean, onlyLargeFiles: Boolean = false, moveToTrash: Boolean = true) =
        archive(VaultScope.of(folder), includeUnsourced, onlyLargeFiles, moveToTrash)

    /** 开始归档 [target]。[includeUnsourced] 为假时没有来源记录的文件原样留着。已有一个在做时不接。 */
    fun archive(target: VaultScope, includeUnsourced: Boolean, onlyLargeFiles: Boolean = false, moveToTrash: Boolean = true) {
        if (busy() || refused(target.targets)) return
        launchWork(target.name, restoring = false, target.targets) { work ->
            val ops = runCatching { bindOperations() }.getOrElse { err ->
                val message = dev.piko.shared.notice.PikoErrorMapper.failure(tr("归档", "Archive", "アーカイブ", "보관", "歸檔"), err)
                _messages.tryEmit(message)
                publish(work, Completion.FAILED, tr("归档失败", "Archiving failed", "アーカイブに失敗しました", "보관 실패", "歸檔失敗"), scopedSummary(target.name, message))
                return@launchWork
            }
            val untrashByFolder = mutableMapOf<String, Map<String, FileStat>>()
            val trashed = mutableListOf<String>()
            val deleted = mutableMapOf<String, List<VaultEntry>>()
            val parents = ops.knownParents().toMutableMap()
            val scannedFolders = mutableSetOf<String>()
            val started = TimeSource.Monotonic.markNow()
            // CID 取样失败或超时的文件数，条目照样写，只是少一份体检资料
            var noCid = 0
            var skippedFiles = false
            var archivedFiles = 0
            var plannedTotal = 0
            val folderErrors = mutableListOf<Throwable>()
            val result = runSuspendCatching {
                val delete = !moveToTrash
                val scannedLevels = levelsOf(ops, target, until = { stopping }, parents = parents, scannedFolders = scannedFolders)
                val levels = scannedLevels.map { (folderId, files) ->
                    folderId to files.filter {
                        (includeUnsourced || !it.sourceUrl.isNullOrBlank()) && (!onlyLargeFiles || it.sizeBytes >= LARGE_FILE_MIN_BYTES)
                    }
                }.filter { it.second.isNotEmpty() }
                skippedFiles = levels.sumOf { it.second.size } != scannedLevels.sumOf { it.second.size }
                ensureCurrent(work)
                val total = levels.sumOf { it.second.size }
                plannedTotal = total
                val targets = requireNotNull(activeTargets)
                activeTargets = targets.copy(
                    manifests = targets.manifests + levels.map { it.first },
                    files = targets.files + levels.flatMap { it.second }.map { it.id },
                )
                PikoLog.i(TAG, "开始归档：${target.logScope}，${levels.size} 个文件夹里 $total 个文件，${levels.sumOf { l -> l.second.sumOf { it.sizeBytes } }} 字节，" +
                    "${if (includeUnsourced) "含" else "不含"}无来源的${if (onlyLargeFiles) "，只归档大文件" else ""}，原文件${if (delete) "直接删除" else "移入回收站"}")
                var done = 0
                var prepared = 0
                val progressLock = Mutex()
                val levelMap = levels.toMap()
                progress = Progress(target.name, 0, total, 0)
                val outcomes = coroutineScope {
                    val sampler = VaultCidSampler(this, ops::sampleCid, cidTimeoutMillis)
                    val folders = Semaphore(12)
                    VaultIngest.ingestFolders(
                        folderIds = levels.map { it.first },
                        folderPermits = folders,
                        shouldStop = { stopping },
                        prepareDrafts = { folderId ->
                            val files = levelMap.getValue(folderId)
                            coroutineScope {
                                files.map { file ->
                                    async {
                                        val cid = file.params["piko_vault_cid"]
                                            ?: ops.knownCid(file.hash, file.sizeBytes)
                                            ?: sampler.sample(file)
                                        progressLock.withLock {
                                            prepared++
                                            if (activeWork === work) progress = Progress(target.name, done, total, prepared)
                                            if (cid == null) noCid++
                                        }
                                        VaultEntryDraft(
                                            name = file.name,
                                            size = file.sizeBytes,
                                            gcid = file.hash,
                                            source = file.sourceUrl,
                                            sourcePath = file.name,
                                            meta = VaultMediaMeta.fromFileStat(file, sampledCid = cid),
                                            originFile = file,
                                        )
                                    }
                                }.awaitAll()
                            }
                        },
                        knownMetaLookup = ops::knownMeta,
                        beforeFolderWrite = { _, _, _ -> ensureCurrent(work) },
                        writeFolder = { folderId, entries, _ ->
                            runSuspendCatching { ops.write(folderId, entries, requireRemoteComplete = delete) }
                        },
                        onFolderCommitted = { folderId, drafts, entries ->
                            val files = drafts.map { requireNotNull(it.originFile) }
                            val fileIds = files.map { it.id }
                            val starredEntries = entries.zip(files).mapNotNull { (entry, file) ->
                                entry.takeIf { file.tags.any { tag -> tag.name.equals("STAR", ignoreCase = true) } }
                            }
                            if (starredEntries.isNotEmpty()) {
                                ops.rememberStarred(folderId, starredEntries)
                            }
                            ensureCurrent(work)
                            // 处置请求的返回状态可能不明确，请求发出前完整记录原文件映射与待恢复 ID；撤销时逐项核对真实文件回到原位后才将条目标为已取回。
                            progressLock.withLock {
                                if (delete) {
                                    deleted[folderId] = entries
                                } else {
                                    untrashByFolder[folderId] = entries.zip(files).associate { (entry, file) -> entry.id to file }
                                    trashed += fileIds
                                }
                            }
                            ops.remove(fileIds, delete)
                            progressLock.withLock {
                                done += files.size
                                archivedFiles = done
                                if (activeWork === work) progress = Progress(target.name, done, total, prepared)
                            }
                        },
                    )
                }
                val committedDrafts = ArrayList<VaultEntryDraft>()
                for (outcome in outcomes) {
                    if (outcome.writeResult.isSuccess) {
                        committedDrafts += outcome.drafts
                    }
                    outcome.failure?.let { err ->
                        ensureCurrent(work)
                        folderErrors += err
                    }
                }
                if (committedDrafts.isNotEmpty()) {
                    val sourceRecords = VaultIngest.sourceRecordsOf(committedDrafts)
                    if (sourceRecords.isNotEmpty()) ops.rememberSources(sourceRecords)
                }
                if (folderErrors.isNotEmpty()) throw folderErrors.first()
                done
            }
            ensureCurrent(work)
            progress = null
            val stopped = stopping
            val writtenFolderIds = untrashByFolder.keys + deleted.keys
            PikoLog.i(TAG, "归档${if (result.isFailure) "中途失败" else if (stopped) "已停止" else "完成"}：${target.logScope}，" +
                "写成清单 ${writtenFolderIds.size} 个文件夹，归档 $archivedFiles/$plannedTotal 个文件，失败文件夹 ${folderErrors.size} 个，CID 未取到 $noCid 个，" +
                "历时 ${started.elapsedNow().inWholeMilliseconds} ms")
            // 文件夹范围只记到所选根；单独归档的文件沿已知父级补上祖先标记。
            ops.rememberTree(vaultTree(target.folders.mapTo(HashSet()) { it.id }, writtenFolderIds, parents))
            // 做完的几层记成一条改动，哪怕后面失败了：撤销得回已经归档的那些
            if (untrashByFolder.isNotEmpty() || deleted.isNotEmpty()) {
                val count = when {
                    result.isFailure && archivedFiles > 0 -> tr(
                        "已归档 $archivedFiles/$plannedTotal 个文件（${folderErrors.size} 个文件夹未完成）",
                        "Archived $archivedFiles of $plannedTotal files (${enCount(folderErrors.size, "folder", "folders")} incomplete)",
                        "$archivedFiles/$plannedTotal ファイルをアーカイブ済み（${folderErrors.size} フォルダー未完了）",
                        "파일 $archivedFiles/${plannedTotal}개 보관 완료 (${folderErrors.size}개 폴더 미완료)",
                        "已歸檔 $archivedFiles/$plannedTotal 個檔案（${folderErrors.size} 個資料夾未完成）",
                    )
                    result.isFailure -> null
                    stopped -> tr("已停止，已归档 $archivedFiles 个文件", "Stopped; archived ${enCount(archivedFiles, "file", "files")}", "停止しました。$archivedFiles ファイルをアーカイブ済み", "중지됨. 파일 ${archivedFiles}개 보관 완료", "已停止，已歸檔 $archivedFiles 個檔案")
                    else -> tr("已归档 $archivedFiles 个文件", "Archived ${enCount(archivedFiles, "file", "files")}", "$archivedFiles ファイルをアーカイブしました", "파일 ${archivedFiles}개 보관 완료", "已歸檔 $archivedFiles 個檔案")
                }
                val report = if (count == null) tr("部分归档记录已写入，原文件处理未全部完成", "Some archive records saved; original file processing incomplete", "一部のアーカイブ記録を保存しましたが、元ファイルの処理が未完了です", "일부 보관 기록을 저장했지만 원본 파일 처리는 완료되지 않았습니다", "部分歸檔記錄已寫入，原始檔案處理尚未全部完成")
                    else if (deleted.isNotEmpty()) tr("$count，原文件已删除", "$count; original files deleted", "$count、元ファイルを削除しました", "$count, 원본 파일 삭제 완료", "$count，原始檔案已刪除") else tr("$count，原文件已移入回收站", "$count; original files moved to trash", "$count、元ファイルをゴミ箱に移動しました", "$count, 원본 파일을 휴지통으로 이동했습니다", "$count，原始檔案已移至資源回收筒")
                // 带上名字：这条提示常在别的页、几分钟后才弹出，不写明是哪个文件夹就认不出
                val summary = scopedSummary(target.name, report)
                val change = DriveChangeJournal.Change.Vault(
                    reverts = emptyMap(),
                    summary = summary,
                    untrashOnRevert = trashed,
                    recreateOnRevert = deleted,
                    untrashByFolder = untrashByFolder,
                )
                ops.record(change)
                val title = when {
                    result.isFailure -> tr("归档未全部完成", "Archive partially completed", "一部の項目をアーカイブできませんでした", "일부 항목을 보관하지 못했습니다", "歸檔未全部完成")
                    stopped -> tr("归档已停止", "Archiving stopped", "アーカイブを停止しました", "보관을 중지했습니다", "歸檔已停止")
                    else -> tr("归档完成", "Archiving complete", "アーカイブ完了", "보관 완료", "歸檔完成")
                }
                publish(work, when { result.isFailure -> Completion.PARTIAL; stopped -> Completion.STOPPED; else -> Completion.SUCCEEDED }, title, summary, change)
            } else if (result.getOrNull() == 0) {
                val message = if (stopped) tr("已停止归档", "Archiving stopped", "アーカイブを停止しました", "보관을 중지했습니다", "已停止歸檔") else tr("无可归档的文件", "No files to archive", "アーカイブできるファイルがありません", "보관할 파일이 없습니다", "無可歸檔的檔案")
                _messages.tryEmit(message)
                publish(work, if (stopped) Completion.STOPPED else Completion.SUCCEEDED, message, scopedSummary(target.name, message))
            }
            result.reportFailure(TAG, tr("归档", "Archive", "アーカイブ", "보관", "歸檔")) { message ->
                _messages.tryEmit(message)
                if (untrashByFolder.isEmpty() && deleted.isEmpty()) publish(work, Completion.FAILED, tr("归档失败", "Archiving failed", "アーカイブに失敗しました", "보관 실패", "歸檔失敗"), scopedSummary(target.name, message))
            }
            ops.refresh(scannedFolders + writtenFolderIds + target.folders.map { it.id } + target.files.map { it.parentId })
            if (result.isSuccess && !stopping && !target.filesOnly && !skippedFiles) {
                ops.rememberFullyVaulted(scannedFolders + target.folders.map { it.id })
            }
        }
    }

    /**
     * 中止正在做的归档，换号时用：再做下去，请求会发到新账号上。已做完的几层保持归档，不记撤销，
     * 换号本来也会清掉撤销记录；回到原账号后可逐项恢复到网盘。
     */
    fun cancel() {
        job?.cancel()
        job = null
        activeWork = null
        activeTargets = null
        progress = null
        restoreProgress = null
        records = emptyList()
        stopping = false
    }

    /** 把列表里的这几行归档条目恢复成网盘文件。 */
    fun restore(files: List<FileStat>) {
        val requested = files.filter { it.isVaulted }.groupBy { it.parentId }
            .mapValues { (_, rows) -> rows.mapNotNullTo(HashSet()) { VaultEntry.entryIdOf(it.id) } }
        if (requested.isEmpty() || busy() || refused(VaultWorkTargets(manifests = requested.keys))) return
        launchWork(tr("所选文件", "Selected files", "選択したファイル", "선택한 파일", "所選檔案"), restoring = true, VaultWorkTargets(manifests = requested.keys)) { work ->
            runSuspendCatching {
                val ops = bindOperations()
                // 现读清单，不用列表里的行：别的设备可能已恢复或移除了其中几条
                val byFolder = coroutineScope {
                    requested.map { (folderId, ids) ->
                        async { folderId to ops.archived(folderId).entries.filter { it.id in ids } }
                    }.awaitAll()
                }.filter { it.second.isNotEmpty() }.toMap()
                ensureCurrent(work)
                restoreEntries(work, ops, byFolder)
            }.reportFailure(TAG, tr("恢复归档", "Restore archive", "アーカイブを復元", "보관 항목 복원", "還原歸檔")) { restoreFailed(work, it) }
        }
    }

    /**
     * 取消归档之前的清点，给确认框用：多少条、多大，其中几条能从回收站取回原文件，其余要占多少新空间。
     * [remainingBytes] 为 null 时不限量或查不到。
     */
    class RestoreSurvey(val entries: Int, val bytes: Long, val fromTrash: Int, val neededBytes: Long, val remainingBytes: Long?) {
        val fits: Boolean get() = remainingBytes == null || neededBytes <= remainingBytes
    }

    suspend fun surveyRestore(folder: PikoPathBreadcrumb): Result<RestoreSurvey> = runSuspendCatching {
        val ops = bindOperations()
        val byFolder = scanArchived(ops, folder.id)
        val plan = planRestore(ops, byFolder)
        val entries = byFolder.values.flatten()
        RestoreSurvey(entries.size, entries.sumOf { it.size }, plan.originals.size, plan.neededBytes, plan.remainingBytes)
    }

    /** 递归恢复 [folder] 中的归档条目，真实文件保持原样。清点失败时不开始恢复。 */
    fun restoreFolder(folder: PikoPathBreadcrumb) {
        if (busy() || refused(VaultWorkTargets(roots = setOf(folder.id)))) return
        launchWork(folder.name, restoring = true, VaultWorkTargets(roots = setOf(folder.id))) { work ->
            runSuspendCatching {
                val ops = bindOperations()
                // 确认框里清点过一次，这里重扫：确认前后别的设备可能已改了清单，照旧的恢复会多秒传一份
                val byFolder = scanArchived(ops, folder.id, until = { stopping }) { scanned ->
                    if (activeWork === work) restoreProgress = restoreProgress?.copy(scannedFolders = scanned)
                }
                ensureCurrent(work)
                when {
                    stopping -> publish(work, Completion.STOPPED, tr("恢复已停止", "Restore stopped", "復元を停止しました", "복원을 중지했습니다", "還原已停止"), tr("「${folder.name}」：已停止恢复", "“${folder.name}”: restore stopped", "「${folder.name}」：復元を停止しました", "“${folder.name}”: 복원 중지됨", "「${folder.name}」：已停止還原"))
                    byFolder.isEmpty() -> publish(work, Completion.SUCCEEDED, tr("恢复完成", "Restore complete", "復元完了", "복원 완료", "還原完成"), tr("「${folder.name}」：无归档条目", "“${folder.name}”: no archived items", "「${folder.name}」：アーカイブ項目はありません", "“${folder.name}”: 보관 항목 없음", "「${folder.name}」：無歸檔項目"))
                    else -> restoreEntries(work, ops, byFolder)
                }
            }.reportFailure(TAG, tr("取消文件夹归档", "Undo folder archiving", "フォルダーのアーカイブを解除", "폴더 보관 해제", "取消資料夾歸檔")) { restoreFailed(work, it) }
        }
    }

    private fun restoreFailed(work: Work, message: String) {
        if (activeWork !== work) return
        _messages.tryEmit(message)
        publish(work, Completion.FAILED, tr("恢复失败", "Restore failed", "復元に失敗しました", "복원 실패", "還原失敗"), scopedSummary(work.name, message))
    }

    private suspend fun scanArchived(
        ops: FolderVaultOperations,
        rootId: String,
        until: () -> Boolean = { false },
        onScanned: (Int) -> Unit = {},
    ): Map<String, List<VaultEntry>> {
        val found = mutableMapOf<String, List<VaultEntry>>()
        val visited = mutableSetOf(rootId)
        var queue = listOf(rootId)
        var scanned = 0
        while (queue.isNotEmpty() && !until()) {
            val next = mutableListOf<String>()
            for (chunk in queue.chunked(8)) {
                val levels = coroutineScope { chunk.map { id -> async { ops.archived(id) } }.awaitAll() }
                chunk.zip(levels).forEach { (folderId, level) ->
                    if (level.entries.isNotEmpty()) found[folderId] = level.entries
                    level.subfolders.forEach { if (visited.add(it.id)) next += it.id }
                }
                scanned += chunk.size
                onScanned(scanned)
            }
            queue = next
        }
        return found
    }

    private enum class RestoreOrigin { ALREADY_PRESENT, UNTRASHED, CREATED }

    private class RestorePlan(
        val alreadyPresent: Map<String, FileStat>,
        val originals: Map<String, FileStat>,
        val neededBytes: Long,
        val remainingBytes: Long?,
    )

    private suspend fun planRestore(ops: FolderVaultOperations, byFolder: Map<String, List<VaultEntry>>): RestorePlan = coroutineScope {
        // 原位真实文件核对失败时必须上抛失败，严禁降级为空集合导致重复秒传与重复扣减额度
        val presentDeferred = async {
            val slots = Semaphore(12)
            byFolder.keys.map { folderId ->
                async {
                    slots.withPermit { folderId to ops.presentRealFiles(folderId) }
                }
            }.awaitAll().toMap()
        }
        val trashDeferred = async {
            runSuspendCatching { ops.trash() }
                .logFailure(TAG, tr("读取回收站失败", "Failed to load trash", "ゴミ箱を読み込めませんでした", "휴지통을 불러오지 못했습니다", "讀取資源回收筒失敗"))
                .getOrDefault(emptyList())
        }
        val remainingDeferred = async { ops.remainingBytes() }
        val presentByFolder = presentDeferred.await()
        val alreadyPresent = LinkedHashMap<String, FileStat>()
        for ((folderId, entries) in byFolder) {
            val candidates = presentByFolder[folderId].orEmpty()
            if (candidates.isEmpty()) continue
            val used = HashSet<String>()
            for (entry in entries) {
                val existing = candidates.firstOrNull { it.id !in used && entry.matches(it) } ?: continue
                used += existing.id
                alreadyPresent[entry.id] = existing
            }
        }
        val unresolvedByFolder = byFolder.mapValues { (_, entries) ->
            entries.filter { it.id !in alreadyPresent }
        }.filterValues { it.isNotEmpty() }
        val trash = trashDeferred.await()
        val originals = trashOriginals(unresolvedByFolder, trash)
        val needed = byFolder.values.flatten().filter { it.id !in alreadyPresent && it.id !in originals }.sumOf { it.size }
        RestorePlan(alreadyPresent, originals, needed, remainingDeferred.await())
    }

    /**
     * 原位既有真实文件优先直接关联（零新增空间、撤销时不删除用户既有文件）；其次从回收站恢复原文件；
     * 其余按 gcid 秒传，先比一次剩余空间。云端已不存的秒传不出来，也不扣额度，留在清单里。
     */
    private suspend fun restoreEntries(work: Work, ops: FolderVaultOperations, byFolder: Map<String, List<VaultEntry>>) {
        val total = byFolder.values.sumOf { it.size }
        if (total == 0) {
            publish(work, Completion.SUCCEEDED, tr("恢复完成", "Restore complete", "復元完了", "복원 완료", "還原完成"), tr("「${work.name}」：所选项目已不在归档中", "“${work.name}”: selected items are no longer archived", "「${work.name}」：選択した項目は既にアーカイブにありません", "“${work.name}”: 선택한 항목이 더 이상 보관 상태가 아닙니다", "「${work.name}」：所選項目已不在歸檔中"))
            return
        }
        restoreProgress = restoreProgress?.copy(stage = tr("正在检查恢复空间", "Checking space for restore", "復元に必要な空き容量を確認中", "복원 공간 확인 중", "正在檢查還原所需空間"), total = total)
        val plan = planRestore(ops, byFolder)
        ensureCurrent(work)
        val alreadyPresent = plan.alreadyPresent
        val originals = plan.originals
        val targets = requireNotNull(activeTargets)
        activeTargets = targets.copy(
            manifests = targets.manifests + byFolder.keys,
            files = targets.files + originals.values.map { it.id } + alreadyPresent.values.map { it.id },
        )
        PikoLog.i(TAG, "恢复归档：${byFolder.size} 个文件夹 $total 项，原位已有 ${alreadyPresent.size} 项，回收站里有原文件 ${originals.size} 项，" +
            "需秒传 ${plan.neededBytes} 字节，剩余空间 ${plan.remainingBytes ?: "不限或未知"}")
        if (plan.remainingBytes != null && plan.neededBytes > plan.remainingBytes) {
            restoreFailed(work, tr("网盘空间不足，无法恢复所选的 $total 项", "Insufficient cloud storage to restore ${enCount(total, "item", "items")}", "ドライブの空き容量が不足しているため、$total 項目を復元できません", "드라이브 저장 공간이 부족하여 ${total}개 항목을 복원할 수 없습니다", "雲端硬碟空間不足，無法還原所選的 $total 項"))
            return
        }
        val started = TimeSource.Monotonic.markNow()
        restoreProgress = restoreProgress?.copy(stage = tr("正在恢复到网盘", "Restoring to drive", "ドライブに復元中", "드라이브로 복원 중", "正在還原至雲端硬碟"))
        val lock = Mutex()
        val missingEntries = mutableListOf<MissingVaultEntry>()
        var manifestFailures = 0
        var restoredTotal = 0
        val trashByFolder = LinkedHashMap<String, List<String>>()
        val reverts = LinkedHashMap<String, VaultEdit>()
        coroutineScope {
            val folders = Semaphore(8)
            val recreates = Semaphore(16)
            byFolder.map { (folderId, entries) -> async {
                folders.withPermit {
                    if (stopping) return@withPermit
                    val restored = LinkedHashMap<String, FileStat>()
                    val folderUndoTrashIds = ArrayList<String>()
                    suspend fun finish(entry: VaultEntry, fileId: String?, origin: RestoreOrigin?) = lock.withLock {
                        if (fileId != null && origin != null) {
                            restored[entry.id] = FileStat(id = fileId, name = entry.name, hash = entry.gcid, size = entry.size.toString())
                            restoredTotal++
                            if (origin != RestoreOrigin.ALREADY_PRESENT) {
                                folderUndoTrashIds += fileId
                            }
                        }
                        if (activeWork === work) restoreProgress = restoreProgress?.let { it.copy(done = it.done + 1) }
                    }
                    val (inPlace, notInPlace) = entries.partition { it.id in alreadyPresent }
                    for (entry in inPlace) {
                        finish(entry, alreadyPresent.getValue(entry.id).id, RestoreOrigin.ALREADY_PRESENT)
                    }
                    val (fromTrash, fromCloud) = notInPlace.partition { it.id in originals }
                    if (fromTrash.isNotEmpty()) {
                        ensureCurrent(work)
                        val untrashed = runSuspendCatching { ops.untrash(fromTrash.map { originals.getValue(it.id).id }) }
                            .logFailure(TAG, "从回收站恢复原文件失败").isSuccess
                        for (entry in fromTrash) {
                            val original = originals.getValue(entry.id)
                            // 条目归档后改过名时，原文件跟着改；改不成也算恢复了，只是名字是旧的
                            if (untrashed && original.name != entry.name) {
                                runSuspendCatching { ops.rename(original.id, entry.name) }.logFailure(TAG, "恢复后改名失败")
                            }
                            finish(entry, original.id.takeIf { untrashed }, RestoreOrigin.UNTRASHED.takeIf { untrashed })
                        }
                    }
                    fromCloud.map { entry -> async {
                        recreates.withPermit {
                            if (stopping) return@withPermit
                            ensureCurrent(work)
                            val id = runSuspendCatching { ops.recreate(entry, folderId) }
                                .onFailure { if (it is InstantContentUnavailableException) lock.withLock { missingEntries += MissingVaultEntry(folderId, entry) } }
                                .logFailure(TAG, "恢复归档条目失败")
                                .getOrNull()
                            finish(entry, id, RestoreOrigin.CREATED.takeIf { id != null })
                        }
                    } }.awaitAll()
                    if (restored.isEmpty()) return@withPermit
                    ensureCurrent(work)
                    // 清单确认或未决时均同步绑定该目录的反向恢复条目与待回收副本，确保撤销必先写回归档条目再移走实体文件
                    runSuspendCatching { ops.markRestored(folderId, restored) }
                        .onSuccess { write ->
                            lock.withLock {
                                reverts[folderId] = VaultEdits.add(write.before.filter { it.id in restored })
                                if (folderUndoTrashIds.isNotEmpty()) {
                                    trashByFolder[folderId] = folderUndoTrashIds.toList()
                                }
                            }
                        }
                        .onFailure { err ->
                            lock.withLock {
                                manifestFailures++
                                val attemptedBefore = (err as? VaultWriteUnconfirmed)?.attempted?.before
                                val fallbackEntries = (attemptedBefore ?: entries).filter { it.id in restored }
                                if (folderUndoTrashIds.isNotEmpty() || attemptedBefore != null) {
                                    reverts[folderId] = VaultEdits.add(fallbackEntries)
                                }
                                if (folderUndoTrashIds.isNotEmpty()) {
                                    trashByFolder[folderId] = folderUndoTrashIds.toList()
                                }
                            }
                        }
                        .logFailure(TAG, "恢复后改写归档清单失败")
                }
            } }.awaitAll()
        }
        ensureCurrent(work)
        val failed = total - restoredTotal
        val missing = missingEntries.size
        PikoLog.i(TAG, "恢复归档${if (stopping) "已停止" else "结束"}：恢复 $restoredTotal/$total 项，云端未收录 $missing 项，" +
            "其他失败 ${failed - missing} 项，改写清单 ${reverts.size - manifestFailures.coerceAtMost(reverts.size)}/${byFolder.size} 个文件夹，历时 ${started.elapsedNow().inWholeMilliseconds} ms")
        val summary = when {
            stopping -> if (restoredTotal == 0) tr("已停止恢复", "Restore stopped", "復元を停止しました", "복원을 중지했습니다", "已停止還原") else tr("已停止，已恢复 $restoredTotal 项", "Stopped; restored ${enCount(restoredTotal, "item", "items")}", "停止しました。$restoredTotal 項目を復元済み", "중지됨. ${restoredTotal}개 항목 복원 완료", "已停止，已還原 $restoredTotal 項")
            failed == 0 && manifestFailures > 0 -> tr("已恢复 $total 项，$manifestFailures 个文件夹的归档清单未更新，列表可能重复显示", "Restored ${enCount(total, "item", "items")}; $manifestFailures folder manifests were not updated and may show duplicates", "$total 項目を復元しました。$manifestFailures フォルダーの一覧を更新できず、重複表示する可能性があります", "${total}개 항목 복원 완료. ${manifestFailures}개 폴더의 보관 목록이 갱신되지 않아 중복 표시될 수 있습니다", "已還原 $total 項，$manifestFailures 個資料夾的歸檔清單未更新，列表可能重複顯示")
            failed == 0 -> if (total == 1) tr("已恢复到网盘", "Restored to drive", "ドライブに復元しました", "드라이브로 복원했습니다", "已還原至雲端硬碟") else tr("已恢复 $total 项", "Restored ${enCount(total, "item", "items")}", "$total 項目を復元しました", "${total}개 항목 복원 완료", "已還原 $total 項")
            missing == failed -> tr("已恢复 $restoredTotal 项，$missing 项在云端不可用", "Restored ${enCount(restoredTotal, "item", "items")}; $missing unavailable in the cloud", "$restoredTotal 項目を復元しました（$missing 項目はクラウドで利用不可）", "${restoredTotal}개 항목 복원 완료, ${missing}개는 클라우드에서 사용 불가", "已還原 $restoredTotal 項，$missing 項在雲端無法使用")
            else -> tr("已恢复 $restoredTotal 项，$failed 项失败", "Restored ${enCount(restoredTotal, "item", "items")}; $failed failed", "$restoredTotal 項目を復元しました。$failed 項目は失敗しました", "${restoredTotal}개 항목 복원 완료, ${failed}개 실패", "已還原 $restoredTotal 項，$failed 項失敗")
        }
        val manifestNote = if (manifestFailures > 0 && (failed > 0 || stopping)) tr("，$manifestFailures 个文件夹的归档清单未更新，列表可能重复显示", "; $manifestFailures folder manifests were not updated and may show duplicates", "。$manifestFailures フォルダーの一覧を更新できず、重複表示する可能性があります", ". ${manifestFailures}개 폴더의 보관 목록이 갱신되지 않아 중복 표시될 수 있습니다", "，$manifestFailures 個資料夾的歸檔清單未更新，列表可能重複顯示") else ""
        val named = scopedSummary(work.name, "$summary$manifestNote")
        val undoTrashFlat = trashByFolder.values.flatten()
        val undoable = reverts.isNotEmpty()
        val change = if (undoable) {
            DriveChangeJournal.Change.Vault(
                reverts = reverts,
                summary = named,
                trashOnRevert = undoTrashFlat,
                trashByFolder = trashByFolder,
            )
        } else null
        if (undoable) {
            ops.record(requireNotNull(change))
        } else {
            _messages.tryEmit(summary)
        }
        val title = when {
            stopping -> tr("恢复已停止", "Restore stopped", "復元を停止しました", "복원을 중지했습니다", "還原已停止")
            failed == 0 && manifestFailures == 0 -> tr("恢复完成", "Restore complete", "復元完了", "복원 완료", "還原完成")
            restoredTotal == 0 -> tr("恢复失败", "Restore failed", "復元に失敗しました", "복원 실패", "還原失敗")
            else -> tr("恢复未全部完成", "Restore partially completed", "一部の項目を復元できませんでした", "일부 항목을 복원하지 못했습니다", "還原未全部完成")
        }
        publish(work, when {
            stopping -> Completion.STOPPED
            failed == 0 && manifestFailures == 0 -> Completion.SUCCEEDED
            restoredTotal == 0 -> Completion.FAILED
            else -> Completion.PARTIAL
        }, title, named, change, missingEntries.toList())
        ops.refresh(byFolder.keys)
    }

    /**
     * 归档时移进回收站、还没清掉的原文件，按条目配对。只认所在目录、gcid 与大小，不认名字：条目归档后可能改过名，
     * 按名字配会错过原文件，转而秒传一份新的，原文件仍在回收站里占着空间。一份原文件只配一条，同名的优先。
     */
    private fun trashOriginals(byFolder: Map<String, List<VaultEntry>>, trash: List<FileStat>): Map<String, FileStat> {
        val paired = mutableMapOf<String, FileStat>()
        val used = mutableSetOf<String>()
        for ((folderId, entries) in byFolder) {
            val candidates = trash.filter { !it.isFolder && it.parentId == folderId }
            if (candidates.isEmpty()) continue
            for (entry in entries.sortedByDescending { entry -> candidates.any { it.name == entry.name } }) {
                val original = candidates
                    .filter { it.id !in used && it.hash.equals(entry.gcid, ignoreCase = true) && it.sizeBytes == entry.size }
                    .minByOrNull { if (it.name == entry.name) 0 else 1 } ?: continue
                used += original.id
                paired[entry.id] = original
            }
        }
        return paired
    }

    /**
     * [target] 涉及的各层：文件夹各取整棵树，单独的文件按所在文件夹归入那一层，同一层只出现一次。
     * 单独的文件现读所在文件夹再取：列表里的行可能已被别的设备移走、删掉，来源也以仓库按账本补上的为准。
     */
    private suspend fun levelsOf(
        ops: FolderVaultOperations,
        target: VaultScope,
        until: () -> Boolean = { false },
        parents: MutableMap<String, String>? = null,
        scannedFolders: MutableSet<String>? = null,
    ): List<Pair<String, List<FileStat>>> {
        val levels = LinkedHashMap<String, LinkedHashMap<String, FileStat>>()
        fun add(folderId: String, files: List<FileStat>) {
            levels.getOrPut(folderId) { LinkedHashMap() }.putAll(files.associateBy { it.id })
        }
        for (folder in target.folders) {
            if (until()) break
            walk(ops, folder.id, until, parents).forEach { (folderId, files) ->
                scannedFolders?.add(folderId)
                add(folderId, files)
            }
        }
        val requested = target.files.groupBy({ it.parentId }, { it.id })
        for (chunk in requested.entries.chunked(12)) {
            if (until()) break
            val listings = coroutineScope { chunk.map { (folderId, _) -> async { ops.list(folderId) } }.awaitAll() }
            chunk.zip(listings).forEach { (request, listing) ->
                val ids = request.value.toSet()
                add(request.key, listing.filter { it.id in ids && archivable(it) })
            }
        }
        return levels.map { (folderId, files) -> folderId to files.values.toList() }
    }

    /**
     * [rootId] 整棵树，每层一项：目录 ID 与其中能归档的文件。已是归档条目的、清单文件、还在上传的、
     * 没有 gcid 的都不算；Piko-Temp 与同步设置的 .piko 不进去。
     */
    private suspend fun walk(
        ops: FolderVaultOperations,
        rootId: String,
        until: () -> Boolean = { false },
        /** 填上子文件夹 → 上一层，归档后据此记下从选的那一层到各写了清单的文件夹之间的路径。 */
        parents: MutableMap<String, String>? = null,
    ): List<Pair<String, List<FileStat>>> {
        val levels = mutableListOf<Pair<String, List<FileStat>>>()
        var queue = listOf(rootId)
        val visited = mutableSetOf(rootId)
        while (queue.isNotEmpty() && !until()) {
            val next = mutableListOf<String>()
            for (chunk in queue.chunked(12)) {
                val listings = coroutineScope { chunk.map { id -> async { ops.list(id) } }.awaitAll() }
                chunk.zip(listings).forEach { (folderId, listing) ->
                    listing.filter { it.isFolder && it.name !in SKIPPED_FOLDERS && !it.trashed }
                        .forEach {
                            if (visited.add(it.id)) {
                                next += it.id
                                parents?.put(it.id, folderId)
                            }
                        }
                    levels += folderId to listing.filter(::archivable)
                }
            }
            queue = next
        }
        return levels
    }

    private fun scopedSummary(name: String, detail: String): String =
        tr("「$name」：$detail", "“$name”: $detail", "「$name」：$detail", "“$name”: $detail", "「$name」：$detail")

    private fun bindOperations(): FolderVaultOperations =
        if (operations is DriveFolderVaultOperations) operations.bindAccount() else operations

    private fun archivable(file: FileStat): Boolean =
        !file.isFolder && !file.trashed && !file.isVaulted && !VaultStore.looksLikeManifest(file) && !VaultStore.isRecoveryFile(file) &&
            file.phase == TaskPhase.COMPLETE && file.hash.isNotBlank()

    private companion object {
        const val TAG = "Vault"
    }
}

private val SKIPPED_FOLDERS = setOf("Piko-Temp", ".piko")

/**
 * 从选的那几层 [roots] 到各写了清单的文件夹（[written]）之间的每一层 → 其下写了清单的那些，
 * 见 FolderContentMemory.markedFolders。写了清单的文件夹自己不进来，它们有直接的标记。
 * 未指定根时沿已知父级追溯；指定根时仅保留能追到根的路径，不扩散到范围外。
 */
internal fun vaultTree(roots: Set<String>, written: Set<String>, parents: Map<String, String>): Map<String, Set<String>> {
    val tree = mutableMapOf<String, MutableSet<String>>()
    for (folderId in written) {
        var current = folderId
        val visited = mutableSetOf(folderId)
        val ancestors = mutableListOf<String>()
        while (current !in roots) {
            val parent = parents[current] ?: break
            if (parent.isEmpty() || !visited.add(parent)) break
            current = parent
            ancestors += current
        }
        if (roots.isNotEmpty() && current !in roots) continue
        for (ancestor in ancestors) tree.getOrPut(ancestor) { mutableSetOf() } += folderId
    }
    return tree
}

internal interface FolderVaultOperations {
    suspend fun list(folderId: String): List<FileStat>
    fun knownCid(gcid: String, size: Long): String? = null
    fun knownMeta(gcid: String, size: Long): VaultMediaMeta? = knownCid(gcid, size)?.let { VaultMediaMeta(cid = it) }
    suspend fun sampleCid(file: FileStat): String?
    suspend fun write(folderId: String, entries: List<VaultEntry>, requireRemoteComplete: Boolean) = write(folderId, entries)
    suspend fun write(folderId: String, entries: List<VaultEntry>)
    suspend fun remove(ids: List<String>, permanently: Boolean)
    fun record(change: DriveChangeJournal.Change.Vault)
    fun refresh(affectedFolders: Set<String> = emptySet())
    fun rememberTree(members: Map<String, Set<String>>) {}
    fun rememberStarred(folderId: String, entries: List<VaultEntry>) {}
    fun rememberSources(sources: Map<String, List<ResolvedFile>>) {}
    fun knownParents(): Map<String, String> = emptyMap()
    fun rememberFullyVaulted(folderIds: Set<String>) {}

    /** [folderId] 的子文件夹与仍归档着的条目。清单读不出来就失败：当作没有条目会漏掉恢复。 */
    suspend fun archived(folderId: String): ArchivedLevel
    suspend fun presentRealFiles(folderId: String): List<FileStat> = emptyList()
    suspend fun trash(): List<FileStat>
    /** 剩余空间，不限量或查不到时为 null。 */
    suspend fun remainingBytes(): Long?
    suspend fun untrash(ids: List<String>)
    suspend fun rename(id: String, name: String)
    /** 按 gcid 秒传回 [folderId]，返回新文件的 ID。云端已不存时抛 InstantContentUnavailableException。 */
    suspend fun recreate(entry: VaultEntry, folderId: String): String
    suspend fun markRestored(folderId: String, files: Map<String, FileStat>): VaultWrite
}

internal class ArchivedLevel(val subfolders: List<FileStat>, val entries: List<VaultEntry>)

private class DriveFolderVaultOperations(private val drive: PikoDriveRepository) : FolderVaultOperations {
    fun bindAccount(): FolderVaultOperations {
        val access = drive.currentAccount?.let(drive::writeAccess)
            ?: error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        return BoundDriveFolderVaultOperations(access)
    }

    override suspend fun list(folderId: String): List<FileStat> = bindAccount().list(folderId)
    override fun knownCid(gcid: String, size: Long): String? = drive.vault.knownCidOf(gcid, size)
    override fun knownMeta(gcid: String, size: Long): VaultMediaMeta? = drive.knownVaultMetaOf(gcid, size)
    override suspend fun sampleCid(file: FileStat) = bindAccount().sampleCid(file)
    override suspend fun write(folderId: String, entries: List<VaultEntry>, requireRemoteComplete: Boolean) =
        bindAccount().write(folderId, entries, requireRemoteComplete)
    override suspend fun write(folderId: String, entries: List<VaultEntry>) = bindAccount().write(folderId, entries, false)
    override suspend fun remove(ids: List<String>, permanently: Boolean) = bindAccount().remove(ids, permanently)
    override fun record(change: DriveChangeJournal.Change.Vault) = drive.changes.record(change)
    override fun refresh(affectedFolders: Set<String>) {
        if (affectedFolders.isNotEmpty()) drive.notifyFoldersRefreshed(affectedFolders)
    }
    override fun rememberTree(members: Map<String, Set<String>>) { drive.vaultTrees.merge(members) }
    override fun rememberStarred(folderId: String, entries: List<VaultEntry>) { drive.vaultStars.starEntries(folderId, entries, starred = true) }
    override fun rememberSources(sources: Map<String, List<ResolvedFile>>) {
        sources.forEach { (source, files) -> drive.sourceLedger.record(source, files) }
    }
    override fun knownParents() = drive.knownVaultParents()
    override fun rememberFullyVaulted(folderIds: Set<String>) = drive.rememberFullyVaulted(folderIds)
    override suspend fun archived(folderId: String): ArchivedLevel = bindAccount().archived(folderId)
    override suspend fun presentRealFiles(folderId: String): List<FileStat> = bindAccount().presentRealFiles(folderId)
    override suspend fun trash() = bindAccount().trash()
    override suspend fun remainingBytes() = bindAccount().remainingBytes()
    override suspend fun untrash(ids: List<String>) = bindAccount().untrash(ids)
    override suspend fun rename(id: String, name: String) = bindAccount().rename(id, name)
    override suspend fun recreate(entry: VaultEntry, folderId: String) = bindAccount().recreate(entry, folderId)
    override suspend fun markRestored(folderId: String, files: Map<String, FileStat>): VaultWrite = bindAccount().markRestored(folderId, files)
}

private class BoundDriveFolderVaultOperations(
    private val access: dev.piko.shared.data.DriveWriteAccess,
) : FolderVaultOperations {
    override suspend fun list(folderId: String): List<FileStat> = access.browsableList(folderId).getOrThrow()
    override fun knownCid(gcid: String, size: Long): String? = access.vault.knownCidOf(gcid, size)
    override fun knownMeta(gcid: String, size: Long): VaultMediaMeta? = access.knownVaultMetaOf(gcid, size)
    override suspend fun sampleCid(file: FileStat): String? = access.sampleCid(file.id).getOrNull()
    override suspend fun write(folderId: String, entries: List<VaultEntry>, requireRemoteComplete: Boolean) {
        access.vault.update(folderId, VaultEdits.add(entries), requireRemoteComplete = requireRemoteComplete).getOrThrow()
    }
    override suspend fun write(folderId: String, entries: List<VaultEntry>) = write(folderId, entries, false)
    override suspend fun remove(ids: List<String>, permanently: Boolean) {
        if (permanently) {
            access.delete(ids).onSuccess { access.publish(DriveChange.Deleted(ids, emptySet())) }.getOrThrow()
        } else {
            access.trash(ids).onSuccess { access.publish(DriveChange.Trashed(ids, null)) }.getOrThrow()
        }
    }
    override fun record(change: DriveChangeJournal.Change.Vault) = access.recordChange(change)
    override fun refresh(affectedFolders: Set<String>) = access.notifyFoldersRefreshed(affectedFolders)
    override fun rememberTree(members: Map<String, Set<String>>) = access.rememberTree(members)
    override fun rememberStarred(folderId: String, entries: List<VaultEntry>) = access.starVaultEntries(folderId, entries)
    override fun rememberSources(sources: Map<String, List<ResolvedFile>>) = access.recordSources(sources)
    override fun knownParents(): Map<String, String> = access.knownVaultParents()
    override fun rememberFullyVaulted(folderIds: Set<String>) = access.rememberFullyVaulted(folderIds)

    override suspend fun archived(folderId: String): ArchivedLevel {
        val cleanChildren = access.localCleanChildren(folderId)
        if (cleanChildren != null) {
            val cachedEntries = access.vault.cached(folderId, cleanChildren)
            if (cachedEntries != null) {
                val subfolders = cleanChildren.filter { it.isFolder && !it.trashed && it.name !in SKIPPED_FOLDERS }
                return ArchivedLevel(subfolders, cachedEntries.filter { it.isArchived })
            }
        }
        val listing = access.listFolder(folderId).getOrThrow()
        val entries = access.vault.read(folderId, listing).getOrThrow().filter { it.isArchived }
        val subfolders = listing.filter { it.isFolder && !it.trashed && it.name !in SKIPPED_FOLDERS }
        return ArchivedLevel(subfolders, entries)
    }

    override suspend fun presentRealFiles(folderId: String): List<FileStat> =
        access.presentRealChildren(folderId).getOrThrow()

    override suspend fun trash(): List<FileStat> = access.trashFiles(fresh = false).getOrThrow()

    override suspend fun remainingBytes(): Long? =
        access.remainingQuotaBytes().logFailure("Vault", "查询剩余空间失败，按不限处理").getOrNull()

    override suspend fun untrash(ids: List<String>) {
        access.restore(ids).onSuccess { access.publish(DriveChange.Restored(ids)) }.getOrThrow()
    }

    override suspend fun rename(id: String, name: String) {
        access.rename(id, name).onSuccess { access.publish(DriveChange.Renamed(id, name, null)) }.getOrThrow()
    }

    override suspend fun recreate(entry: VaultEntry, folderId: String): String =
        access.instantCreate(
            file = ResolvedFile(path = entry.name, size = entry.size, gcid = entry.gcid),
            parentId = folderId,
            source = entry.source,
            notifyChange = false,
        ).getOrThrow()

    override suspend fun markRestored(folderId: String, files: Map<String, FileStat>): VaultWrite {
        val starredEntries = files.filterKeys(access::isVaultEntryStarred)
        return access.vault.update(folderId, VaultEdits.restore(files)).getOrThrow().also {
            if (starredEntries.isNotEmpty()) {
                access.star(starredEntries.values.map { it.id }, value = true).onSuccess {
                    access.unstarVaultEntries(starredEntries.keys)
                }
            }
        }
    }
}

/** CID 是可选的体检资料；取样并发受限，同内容只取一次，慢节点超时不阻塞归档。 */
internal class VaultCidSampler(
    private val scope: CoroutineScope,
    private val sample: suspend (FileStat) -> String?,
    private val timeoutMillis: Long,
) {
    private val slots = Semaphore(16)
    private val lock = Mutex()
    private val samples = mutableMapOf<Pair<String, Long>, Deferred<String?>>()

    suspend fun sample(file: FileStat): String? {
        val key = file.hash.uppercase() to file.sizeBytes
        val result = lock.withLock {
            samples.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    slots.withPermit {
                        withTimeoutOrNull(timeoutMillis) {
                            try { sample.invoke(file) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        }
                    }
                }.also { it.start() }
            }
        }
        return result.await()
    }
}
