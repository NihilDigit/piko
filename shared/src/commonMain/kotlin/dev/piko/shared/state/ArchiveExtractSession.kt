package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.data.ArchiveRepository
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.work.DurableExtractJobPayload
import dev.piko.shared.work.WorkJobStatus
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

enum class ArchiveCompletion { SUCCEEDED, FAILED }

sealed interface ArchiveJobStatus {
    data object Waiting : ArchiveJobStatus

    /** 解压请求已发出，服务端在校验密码、建任务。 */
    data object Submitting : ArchiveJobStatus
    data object Checking : ArchiveJobStatus
    data class Extracting(val progress: Int) : ArchiveJobStatus

    /** [incorrect] 为假表示还没给过密码。 */
    data class NeedsPassword(val incorrect: Boolean) : ArchiveJobStatus
    data class Finished(val outcome: ArchiveCompletion, val message: String, val finishedAtMs: Long) : ArchiveJobStatus
}

data class ArchiveJob(
    val file: FileStat,
    val status: ArchiveJobStatus = ArchiveJobStatus.Waiting,
    /** 用户输入的密码。空串表示不带密码提交，普通压缩包会忽略它。 */
    val password: String = "",
    /** 只解压包里的这几项（包内路径），在压缩包里多选后解压时用；为空是全部。 */
    val paths: List<String> = emptyList(),
    /** 任务唯一键；[ArchiveExtractSession] 入队时分配递增序号 `"archive-N"`，直接构造时默认回退为 [file] 的 ID。 */
    val id: String = file.id,
    val createdAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    val remoteTaskId: String? = null,
    val account: String = "",
    internal val wasSubmittingBeforeRestart: Boolean = false,
)

/** 一个压缩包的结局：解压完成，或以某种原因失败。[message] 与 Snackbar 上的提示相同。 */
class ArchiveOutcome(val fileName: String, val outcome: ArchiveCompletion, val message: String) {
    val succeeded: Boolean get() = outcome == ArchiveCompletion.SUCCEEDED
}

/**
 * 压缩包的服务端解压，进程级：离开网盘页、切到别处，解压照常进行。
 *
 * [jobs] 只含未结束的压缩包；结局保留在 [completedJobs]，提示只是额外的即时反馈。
 * 多个压缩包逐个提交、逐个等完成：并发提交时 PikPak 会回「系统繁忙」，
 * 而解压本身多在几秒内完成，串行慢不了多少。
 *
 * 加密包不阻塞队列：服务端要密码时标为待输入，接着处理下一个，用户输入后排回队首。
 * 已保存的密码只列在密码框里供挑选，不自动逐个尝试。
 *
 * 状态读写都在调用方给的 [scope] 上，应为主线程；网络请求由仓库切到后台。
 */
class ArchiveExtractSession(
    clientProvider: PikoClientProvider,
    private val driveRepository: PikoDriveRepository,
    private val vault: ArchivePasswordVault,
    private val scope: CoroutineScope,
    private val queryRetryDelay: Duration = 3.seconds,
) {
    private val repository = ArchiveRepository(clientProvider)

    var jobs by mutableStateOf<List<ArchiveJob>>(emptyList())
        private set
    var completedJobs by mutableStateOf<List<ArchiveJob>>(emptyList())
        private set
    private var sequence = 0L

    private fun newJob(account: String, file: FileStat, paths: List<String> = emptyList(), password: String = "") =
        ArchiveJob(file, password = password, paths = paths, id = "archive-${++sequence}", account = account)

    val savedPasswords: StateFlow<List<String>> =
        vault.passwords.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _outcomes = MutableSharedFlow<ArchiveOutcome>(extraBufferCapacity = 16)

    /** 每个压缩包的结局，供不在前台时发通知。[messages] 里还混着「已跳过分卷」这类即时反馈，通知不要那些。 */
    val outcomes: SharedFlow<ArchiveOutcome> = _outcomes.asSharedFlow()

    private var requestedPasswordJobId by mutableStateOf<String?>(null)

    /**
     * 自动提示在上一个密码验证完成后继续，便于复用新保存的密码。
     * 从任务页显式打开的待输入任务优先，不受自动提示顺序限制。
     */
    val passwordPrompt: ArchiveJob? by derivedStateOf {
        val requested = jobs.firstOrNull { it.id == requestedPasswordJobId && it.status is ArchiveJobStatus.NeedsPassword }
        val verifying = jobs.any {
            it.password.isNotEmpty() && (it.status == ArchiveJobStatus.Waiting || it.status == ArchiveJobStatus.Submitting)
        }
        requested ?: if (verifying) null else jobs.firstOrNull { it.status is ArchiveJobStatus.NeedsPassword }
    }

    /** 任务页显式选择待输入任务，不重新提交解压请求。 */
    fun reopenPasswordPrompt(jobId: String): Boolean {
        if (jobs.none { it.id == jobId && it.status is ArchiveJobStatus.NeedsPassword }) return false
        requestedPasswordJobId = jobId
        return true
    }

    private var worker: Job? = null

    init {
        val readyState = driveRepository.durableStore?.readyState
        if (driveRepository.workJournal != null && readyState != null) {
            scope.launch {
                readyState.collect { ready ->
                    if (ready != null) restoreDurableJobs(ready.account)
                }
            }
        }
    }

    /** 把 [files] 里的压缩包加进队列，其余文件与已在队列里的忽略。 */
    fun extract(files: List<FileStat>) {
        val account = driveRepository.currentAccount.orEmpty()
        val queued = jobs.map { it.file.id }.toSet()
        val archives = files.filter { it.isExtractableArchive && it.id !in queued }.distinctBy { it.id }
        val volumes = files.count { it.isArchiveVolume }
        val alreadyQueued = files.count { it.id in queued }
        PikoLog.i(
            TAG,
            "Enqueue archive extraction: selected=${files.size}, enqueued=${archives.size}, skippedVolumes=$volumes, alreadyQueued=$alreadyQueued",
        )
        if (volumes > 0) _messages.tryEmit(if (volumes == files.size) VOLUME_UNSUPPORTED else tr("已跳过 $volumes 个分卷压缩包：$VOLUME_UNSUPPORTED", "Skipped $volumes multi-volume archives: $VOLUME_UNSUPPORTED", "$volumes 個の分割圧縮ファイルをスキップしました：$VOLUME_UNSUPPORTED", "분할 압축 파일 ${volumes}개 건너뜀: $VOLUME_UNSUPPORTED", "已略過 $volumes 個分割壓縮檔：$VOLUME_UNSUPPORTED"))
        if (archives.isEmpty()) {
            if (volumes == 0 && files.none { it.isExtractableArchive }) _messages.tryEmit(tr("所选项目中无可解压的压缩包", "No extractable archives selected", "解凍可能な圧縮ファイルが選択されていません", "선택한 항목 중 압축 해제 가능한 파일이 없습니다", "所選項目中無可解壓縮的壓縮檔"))
            return
        }
        val created = archives.mapNotNull { stat ->
            val candidate = newJob(account, stat)
            if (persistExtractJob(candidate, WorkJobStatus.IN_PROGRESS)) {
                candidate
            } else {
                _messages.tryEmit(tr("无法记录解压任务，请检查本机存储空间", "Could not persist extraction task; check device storage", "解凍タスクを保存できませんでした。端末の空き容量を確認してください", "압축 해제 작업을 기록할 수 없습니다. 기기 저장 공간을 확인하세요", "無法記錄解壓縮任務，請檢查本機儲存空間"))
                null
            }
        }
        if (created.isEmpty()) return
        jobs = jobs + created
        ensureWorker()
    }

    /**
     * 解压压缩包里的几项，在包里浏览时用。[password] 是浏览时已经验证过的，为空是不要密码的包。
     * 同一个包已在队列里时不再加：任务按压缩包排队，同一个包的两批会互相顶掉。
     */
    fun extractEntries(archive: FileStat, paths: List<String>, password: String) {
        val account = driveRepository.currentAccount.orEmpty()
        if (jobs.any { it.file.id == archive.id }) {
            PikoLog.d(TAG, "Skipped partial extraction for ${logFile(archive.id, archive.name)}: already queued")
            _messages.tryEmit(tr("此压缩包正在解压", "This archive is already being extracted", "この圧縮ファイルは解凍中です", "이미 압축 해제 중인 파일입니다", "此壓縮檔正在解壓縮"))
            return
        }
        val added = newJob(account, archive, password = password, paths = paths)
        if (!persistExtractJob(added, WorkJobStatus.IN_PROGRESS)) {
            _messages.tryEmit(tr("无法记录解压任务，请检查本机存储空间", "Could not persist extraction task; check device storage", "解凍タスクを保存できませんでした。端末の空き容量を確認してください", "압축 해제 작업을 기록할 수 없습니다. 기기 저장 공간을 확인하세요", "無法記錄解壓縮任務，請檢查本機儲存空間"))
            return
        }
        PikoLog.i(
            TAG,
            "Enqueued partial archive extraction ${added.id} for ${logFile(archive.id, archive.name)} (paths=${paths.size}, hasPassword=${password.isNotEmpty()})",
        )
        jobs = jobs + added
        ensureWorker()
    }

    fun submitPassword(jobId: String, password: String) {
        val job = jobs.firstOrNull { it.id == jobId } ?: return
        if (requestedPasswordJobId == jobId) requestedPasswordJobId = null
        PikoLog.d(TAG, "Submitted password for archive job $jobId (${logFile(job.file.id, job.file.name)})")
        // 排到队首：用户正等着看这个密码对不对
        val updated = job.copy(status = ArchiveJobStatus.Waiting, password = password, remoteTaskId = null, wasSubmittingBeforeRestart = false)
        jobs = listOf(updated) + jobs.filterNot { it.id == jobId }
        persistExtractJob(updated, WorkJobStatus.IN_PROGRESS)
        ensureWorker()
    }

    /** 放弃一个待输密码的压缩包。 */
    fun skip(jobId: String) {
        val target = jobs.firstOrNull { it.id == jobId && it.status is ArchiveJobStatus.NeedsPassword }
        if (requestedPasswordJobId == jobId) requestedPasswordJobId = null
        jobs = jobs.filterNot { it.id == jobId && it.status is ArchiveJobStatus.NeedsPassword }
        completedJobs = completedJobs.filterNot { it.id == jobId }
        val jobAccount = target?.account?.ifBlank { driveRepository.currentAccount.orEmpty() } ?: driveRepository.currentAccount.orEmpty()
        if (jobAccount.isNotEmpty()) {
            driveRepository.workJournal?.deleteJob(jobAccount, jobId)
        }
        if (target != null) {
            PikoLog.i(TAG, "Skipped archive extraction awaiting password: job=$jobId, file=${logFile(target.file.id, target.file.name)}")
        }
    }

    /**
     * 退出登录或换号时调用。已提交的解压在服务端照常完成，只是不再跟踪。
     * 不取消 [scope]：它由调用方持有，之后还要复用。
     */
    fun clear(account: String? = null) {
        val dropped = if (account == null) jobs else jobs.filter { it.account == account || it.account.isEmpty() }
        if (account == null || dropped.isNotEmpty()) {
            worker?.cancel()
            worker = null
        }
        if (requestedPasswordJobId != null && dropped.any { it.id == requestedPasswordJobId }) {
            requestedPasswordJobId = null
        }
        jobs = if (account == null) emptyList() else jobs.filterNot { it.account == account || it.account.isEmpty() }
        completedJobs = if (account == null) emptyList() else completedJobs.filterNot { it.account == account || it.account.isEmpty() }
        if (dropped.isNotEmpty()) {
            PikoLog.d(TAG, "Cleared archive extraction session (${dropped.size} active/queued jobs dropped)")
        }
        if (jobs.any { it.status == ArchiveJobStatus.Waiting }) {
            ensureWorker()
        }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = jobs.firstOrNull { it.status == ArchiveJobStatus.Waiting } ?: break
                process(next)
            }
            worker = null
        }
    }

    private suspend fun process(job: ArchiveJob) {
        if (job.account.isNotEmpty() && driveRepository.currentAccount != job.account) return
        job.remoteTaskId?.let { taskId ->
            update(job.id) { it.copy(status = ArchiveJobStatus.Checking) }
            follow(job, taskId)
            return
        }
        if (job.wasSubmittingBeforeRestart && job.paths.isEmpty() && job.file.parentId.isNotEmpty()) {
            val expectedFolderName = job.file.name.substringBeforeLast('.', job.file.name).trim()
            if (expectedFolderName.isNotEmpty()) {
                val siblings = driveRepository.listBrowsable(job.file.parentId, dev.piko.shared.data.PikoFileSortOrder.TIME_DESC).getOrNull().orEmpty()
                val alreadyExtracted = siblings.firstOrNull { cand ->
                    cand.isFolder && !cand.trashed && cand.name == expectedFolderName
                }
                if (alreadyExtracted != null) {
                    val inner = driveRepository.listBrowsable(alreadyExtracted.id, dev.piko.shared.data.PikoFileSortOrder.TIME_DESC).getOrNull()
                    if (inner != null && inner.isNotEmpty()) {
                        finish(
                            job = job,
                            message = tr("已解压 ${job.file.name}", "Extracted ${job.file.name}", "${job.file.name} を解凍しました", "${job.file.name} 압축 해제 완료", "已解壓縮 ${job.file.name}"),
                            logMessage = "Reconciled completed archive extraction after restart for ${logFile(job.file.id, job.file.name)} (job=${job.id}, folder=${alreadyExtracted.id})",
                            succeeded = true,
                        )
                        driveRepository.applyChange(DriveChange.ContentsChanged(setOf(job.file.parentId, alreadyExtracted.id)))
                        return
                    }
                }
            }
        }
        update(job.id) { it.copy(status = ArchiveJobStatus.Submitting, wasSubmittingBeforeRestart = false) }
        val submittingJob = jobs.firstOrNull { it.id == job.id }
        if (submittingJob != null && !persistExtractJob(submittingJob, WorkJobStatus.IN_PROGRESS, stateOverride = "SUBMITTING")) {
            finish(
                job = job,
                message = tr("${job.file.name} 解压失败：无法写入本机任务记录", "Failed to extract ${job.file.name}: could not persist local task receipt", "${job.file.name} の解凍に失敗しました：ローカル記録を保存できません", "${job.file.name} 압축 해제 실패: 로컬 작업 기록을 저장할 수 없습니다", "${job.file.name} 解壓縮失敗：無法寫入本機任務記錄"),
                logMessage = "Aborted archive extraction submission because journal write failed for ${logFile(job.file.id, job.file.name)} (job=${job.id})",
            )
            return
        }
        val task = repository.start(job.file, job.password, job.paths).getOrElse { err ->
            if (err is ArchivePasswordException) {
                PikoLog.i(
                    TAG,
                    "Archive extraction requires password for ${logFile(job.file.id, job.file.name)} (job=${job.id}, ${if (job.password.isEmpty()) "empty probe" else "password rejected"})",
                )
                // 没带密码时服务端报的是「缺少」；带了还被拒才算输错
                update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty())) }
                jobs.firstOrNull { it.id == job.id }?.let { persistExtractJob(it, WorkJobStatus.IN_PROGRESS) }
            } else {
                finish(
                    job = job,
                    message = tr("${job.file.name} 解压失败：${failureReason(err)}", "Failed to extract ${job.file.name}: ${failureReason(err)}", "${job.file.name} の解凍に失敗しました：${failureReason(err)}", "${job.file.name} 압축 해제 실패: ${failureReason(err)}", "${job.file.name} 解壓縮失敗：${failureReason(err)}"),
                    logMessage = "Failed to start archive extraction for ${logFile(job.file.id, job.file.name)} (job=${job.id})",
                    cause = err,
                )
            }
            return
        }
        val waitingOthers = jobs.count { it.id != job.id && it.status == ArchiveJobStatus.Waiting }
        PikoLog.i(
            TAG,
            "Submitted archive extraction for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=${task.taskId}, bytes=${job.file.sizeBytes}, " +
                "scope=${if (job.paths.isEmpty()) "full" else "paths:${job.paths.size}"}, hasPassword=${job.password.isNotEmpty()}, waitingInQueue=$waitingOthers)",
        )
        // 先立即持久化远端 taskId，再异步记忆密码，防止进程在密码存储期间被杀后丢失 taskId 而重复提交解压请求
        update(job.id) { it.copy(status = ArchiveJobStatus.Extracting(0), remoteTaskId = task.taskId) }
        jobs.firstOrNull { it.id == job.id }?.let { persistExtractJob(it, WorkJobStatus.IN_PROGRESS) }
        if (job.password.isNotEmpty()) vault.remember(job.password)
        follow(job, task.taskId)
    }

    private suspend fun follow(job: ArchiveJob, taskId: String) {
        val durable = driveRepository.workJournal != null
        var failures = 0
        while (true) {
            val progress = repository.progress(taskId).getOrElse { err ->
                PikoLog.w(
                    TAG,
                    "Failed to poll archive extraction progress for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=$taskId, attempt=${failures + 1})",
                    err,
                )
                failures++
                val transient = isTransientExtractPollError(err)
                if (failures >= MAX_POLL_FAILURES && (!durable || !transient)) {
                    finish(
                        job = job,
                        message = tr("${job.file.name} 解压失败：${failureReason(err)}", "Failed to extract ${job.file.name}: ${failureReason(err)}", "${job.file.name} の解凍に失敗しました：${failureReason(err)}", "${job.file.name} 압축 해제 실패: ${failureReason(err)}", "${job.file.name} 解壓縮失敗：${failureReason(err)}"),
                        logMessage = "Archive extraction polling failed after $failures attempts for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=$taskId)",
                        outcome = ArchiveCompletion.FAILED,
                        cause = err,
                    )
                    return
                }
                val shift = (failures - 1).coerceIn(0, 4)
                val nextDelay = if (durable) {
                    (queryRetryDelay * (1 shl shift)).coerceAtMost(MAX_DURABLE_POLL_BACKOFF)
                } else {
                    queryRetryDelay
                }
                delay(nextDelay)
                continue
            }
            failures = 0
            when (progress.phase) {
                TaskPhase.COMPLETE -> {
                    finish(
                        job = job,
                        message = tr("已解压 ${job.file.name}", "Extracted ${job.file.name}", "${job.file.name} を解凍しました", "${job.file.name} 압축 해제 완료", "已解壓縮 ${job.file.name}"),
                        logMessage = "Archive extraction completed for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=$taskId, outputId=${progress.fileId.ifBlank { "none" }})",
                        succeeded = true,
                    )
                    // 从压缩包内部发起解压时占位 FileStat 的 parentId 可能为空；优先用本地已知父级，缺口再查产出根或原压缩包详情
                    val actualParentId = job.file.parentId.takeIf { it.isNotEmpty() }
                        ?: progress.fileId.takeIf { it.isNotBlank() }?.let { id ->
                            driveRepository.knownParentOf(id)?.takeIf { it.isNotBlank() }
                                ?: driveRepository.knownFile(id)?.parentId?.takeIf { it.isNotBlank() }
                        }
                        ?: driveRepository.knownParentOf(job.file.id)
                        ?: driveRepository.knownFile(job.file.id)?.parentId
                        ?: progress.fileId.takeIf { it.isNotBlank() }?.let { id ->
                            driveRepository.getFileDetail(id)
                                .onFailure { err -> PikoLog.d(TAG, "Failed to resolve parent of extracted output $id: ${err.message}") }
                                .getOrNull()?.parentId
                        }
                        ?: driveRepository.getFileDetail(job.file.id)
                            .onFailure { err -> PikoLog.d(TAG, "Failed to resolve parent of archive ${logFile(job.file.id, job.file.name)}: ${err.message}") }
                            .getOrNull()?.parentId
                        ?: job.file.parentId
                    driveRepository.applyChange(DriveChange.ContentsChanged(setOf(actualParentId)))
                    return
                }
                TaskPhase.ERROR -> {
                    // 数据加密而文件名未加密的包，列目录不要密码，要到解压时才失败
                    val cause = repository.failureCause(taskId)
                    if (cause == ArchiveRepository.INVALID_PASSWORD) {
                        PikoLog.i(
                            TAG,
                            "Archive extraction task $taskId failed due to password for ${logFile(job.file.id, job.file.name)} (job=${job.id}); awaiting user input",
                        )
                        update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty()), remoteTaskId = null) }
                        jobs.firstOrNull { it.id == job.id }?.let { persistExtractJob(it, WorkJobStatus.IN_PROGRESS) }
                    } else if (cause == ArchiveRepository.INVALID_FORMAT) {
                        finish(
                            job = job,
                            message = tr("${job.file.name} 解压失败：$INVALID_FORMAT_REASON", "Failed to extract ${job.file.name}: $INVALID_FORMAT_REASON", "${job.file.name} の解凍に失敗しました：$INVALID_FORMAT_REASON", "${job.file.name} 압축 해제 실패: $INVALID_FORMAT_REASON", "${job.file.name} 解壓縮失敗：$INVALID_FORMAT_REASON"),
                            logMessage = "Archive extraction failed for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=$taskId, cause=$cause, detail=${progress.errorDescription.ifBlank { "none" }})",
                        )
                    } else {
                        val reason = dev.piko.shared.notice.PikoErrorMapper.archiveReason(cause, progress.errorDescription)
                        finish(
                            job = job,
                            message = tr("${job.file.name} 解压失败：$reason", "Failed to extract ${job.file.name}: $reason", "${job.file.name} の解凍に失敗しました：$reason", "${job.file.name} 압축 해제 실패: $reason", "${job.file.name} 解壓縮失敗：$reason"),
                            logMessage = "Archive extraction failed for ${logFile(job.file.id, job.file.name)} (job=${job.id}, task=$taskId, cause=${cause ?: "unknown"}, detail=${progress.errorDescription.ifBlank { "none" }})",
                        )
                    }
                    return
                }
                else -> {
                    update(job.id) { it.copy(status = ArchiveJobStatus.Extracting(progress.progress.coerceIn(0, 100))) }
                    delay(pollDelay(progress.expiresIn))
                }
            }
        }
    }

    private fun finish(
        job: ArchiveJob,
        message: String,
        logMessage: String,
        succeeded: Boolean = false,
        outcome: ArchiveCompletion = if (succeeded) ArchiveCompletion.SUCCEEDED else ArchiveCompletion.FAILED,
        cause: Throwable? = null,
    ) {
        val current = jobs.firstOrNull { it.id == job.id } ?: return
        val record = current.copy(password = "", status = ArchiveJobStatus.Finished(outcome, message, Clock.System.now().toEpochMilliseconds()))
        Snapshot.withMutableSnapshot {
            jobs = jobs.filterNot { it.id == job.id }
            completedJobs = (completedJobs.filterNot { it.id == job.id } + record).takeLast(100)
        }
        persistExtractJob(
            record,
            if (outcome == ArchiveCompletion.SUCCEEDED) WorkJobStatus.COMPLETED else WorkJobStatus.FAILED,
        )
        PikoLog.log(if (succeeded) LogLevel.INFO else LogLevel.WARN, TAG, logMessage, cause)
        _messages.tryEmit(message)
        _outcomes.tryEmit(ArchiveOutcome(job.file.name, outcome, message))
    }

    private fun restoreDurableJobs(account: String) {
        val journal = driveRepository.workJournal ?: return
        val existingIds = jobs.mapTo(HashSet()) { it.id }
        val restored = ArrayList<ArchiveJob>()
        for ((row, payload) in journal.loadInProgressExtractJobs(account)) {
            if (payload.id in existingIds) continue
            payload.id.removePrefix("archive-").toLongOrNull()?.let { num ->
                if (num > sequence) sequence = num
            }
            val stat = FileStat(
                id = payload.fileId,
                kind = FileKind.FILE,
                name = payload.fileName,
                parentId = payload.fileParentId,
                size = payload.fileSize.toString(),
                mimeType = payload.fileMimeType,
                hash = payload.fileGcid,
                phase = TaskPhase.COMPLETE,
            )
            val status = when {
                payload.remoteTaskId != null -> ArchiveJobStatus.Waiting
                payload.state == "NEEDS_PASSWORD" || payload.hasPassword -> ArchiveJobStatus.NeedsPassword(incorrect = false)
                else -> ArchiveJobStatus.Waiting
            }
            restored += ArchiveJob(
                file = stat,
                status = status,
                password = "",
                paths = payload.selectedInnerPaths.orEmpty(),
                id = payload.id,
                createdAtMs = row.createdAt,
                remoteTaskId = payload.remoteTaskId,
                account = payload.account.ifBlank { account },
                wasSubmittingBeforeRestart = payload.remoteTaskId == null && payload.state == "SUBMITTING",
            )
        }
        if (restored.isNotEmpty()) {
            jobs = jobs + restored
            if (restored.any { it.status == ArchiveJobStatus.Waiting }) {
                ensureWorker()
            }
        }
    }

    private fun persistExtractJob(
        job: ArchiveJob,
        status: WorkJobStatus,
        stateOverride: String? = null,
    ): Boolean {
        val journal = driveRepository.workJournal ?: return true
        val account = job.account.ifBlank { driveRepository.currentAccount.orEmpty() }
        if (account.isEmpty() || !journal.isPersistentFor(account)) return false
        val stateName = stateOverride ?: when (job.status) {
            is ArchiveJobStatus.NeedsPassword -> "NEEDS_PASSWORD"
            ArchiveJobStatus.Submitting -> "SUBMITTING"
            is ArchiveJobStatus.Extracting, ArchiveJobStatus.Checking -> "EXTRACTING"
            is ArchiveJobStatus.Finished -> "FINISHED"
            ArchiveJobStatus.Waiting -> "WAITING"
        }
        val progress = (job.status as? ArchiveJobStatus.Extracting)?.progress ?: 0
        val payload = DurableExtractJobPayload(
            id = job.id,
            account = account,
            fileId = job.file.id,
            fileName = job.file.name,
            fileParentId = job.file.parentId,
            fileSize = job.file.sizeBytes,
            fileMimeType = job.file.mimeType,
            fileGcid = job.file.hash,
            targetFolderId = job.file.parentId,
            targetFolderName = "",
            createSubfolder = false,
            deleteArchiveAfter = false,
            selectedInnerPaths = job.paths.takeIf { it.isNotEmpty() },
            hasPassword = job.password.isNotEmpty() || job.status is ArchiveJobStatus.NeedsPassword,
            state = stateName,
            remoteTaskId = job.remoteTaskId,
            progress = progress,
        )
        return journal.saveExtractJob(payload, status = status)
    }

    private fun isTransientExtractPollError(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is PikPakException -> {
            val code = error.httpStatus ?: 0
            code == 408 || code == 429 || code >= 500 || code == 0
        }
        else -> false
    }

    private fun update(jobId: String, transform: (ArchiveJob) -> ArchiveJob) {
        jobs = jobs.map { if (it.id == jobId) transform(it) else it }
    }

    private companion object {
        const val TAG = "Archive"
        const val MAX_POLL_FAILURES = 5
        val MAX_DURABLE_POLL_BACKOFF: Duration = 30.seconds
        val VOLUME_UNSUPPORTED: String get() = tr("PikPak 不支持解压分卷压缩包", "PikPak does not support extracting multi-volume archives", "PikPakは分割圧縮ファイルの解凍に対応していません", "PikPak은 분할 압축 파일 해제를 지원하지 않습니다", "PikPak 不支援解壓縮分割壓縮檔")
    }
}

/** 服务端给的 expires_in 当作下次查询的间隔：进行中是 1 到 2 秒。异常值收在 1 到 5 秒之间。 */
private fun pollDelay(expiresIn: Int): Duration = expiresIn.coerceIn(1, 5).seconds

// 服务端对损坏的包与分卷给的是同一个结果，分不出来，两种都说
private val INVALID_FORMAT_REASON: String get() = dev.piko.shared.notice.PikoErrorMapper.invalidArchive

private fun failureReason(err: Throwable): String = dev.piko.shared.notice.PikoErrorMapper.archive(err)
