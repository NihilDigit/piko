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
import dev.piko.shared.data.ArchiveSubmissionUnconfirmed
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.data.isExtractableArchive
import io.github.nihildigit.pikpak.ArchivePasswordException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

enum class ArchiveCompletion { SUCCEEDED, FAILED, UNCONFIRMED }

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
    val id: String = file.id,
    val createdAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    val remoteTaskId: String? = null,
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

    private fun newJob(file: FileStat, paths: List<String> = emptyList(), password: String = "") =
        ArchiveJob(file, password = password, paths = paths, id = "archive-${++sequence}")

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

    /** 把 [files] 里的压缩包加进队列，其余文件与已在队列里的忽略。 */
    fun extract(files: List<FileStat>) {
        val queued = jobs.map { it.file.id }.toSet()
        val archives = files.filter { it.isExtractableArchive && it.id !in queued }.distinctBy { it.id }
        val volumes = files.count { it.isArchiveVolume }
        if (volumes > 0 || archives.isEmpty()) {
            PikoLog.i(TAG, "解压：选了 ${files.size} 个，入队 ${archives.size} 个，分卷跳过 $volumes 个，已在队列 ${files.count { it.id in queued }} 个")
        }
        if (volumes > 0) _messages.tryEmit(if (volumes == files.size) VOLUME_UNSUPPORTED else tr("已跳过 $volumes 个分卷：$VOLUME_UNSUPPORTED", "Skipped $volumes split archives: $VOLUME_UNSUPPORTED", "$volumes 個の分割アーカイブをスキップしました：$VOLUME_UNSUPPORTED", "분할 압축 파일 ${volumes}개 건너뜀: $VOLUME_UNSUPPORTED"))
        if (archives.isEmpty()) {
            if (volumes == 0 && files.none { it.isExtractableArchive }) _messages.tryEmit(tr("所选文件中没有可解压的压缩包", "No extractable archives selected", "解凍できるアーカイブが選択されていません", "선택한 파일 중 압축 해제할 수 있는 파일이 없습니다"))
            return
        }
        jobs = jobs + archives.map { newJob(it) }
        ensureWorker()
    }

    /**
     * 解压压缩包里的几项，在包里浏览时用。[password] 是浏览时已经验证过的，为空是不要密码的包。
     * 同一个包已在队列里时不再加：任务按压缩包排队，同一个包的两批会互相顶掉。
     */
    fun extractEntries(archive: FileStat, paths: List<String>, password: String) {
        if (jobs.any { it.file.id == archive.id }) {
            _messages.tryEmit(tr("这个压缩包已在解压中", "This archive is already being extracted", "このアーカイブは解凍中です", "이미 압축 해제 중인 파일입니다"))
            return
        }
        jobs = jobs + newJob(archive, password = password, paths = paths)
        ensureWorker()
    }

    fun submitPassword(jobId: String, password: String) {
        val job = jobs.firstOrNull { it.id == jobId } ?: return
        if (requestedPasswordJobId == jobId) requestedPasswordJobId = null
        // 排到队首：用户正等着看这个密码对不对
        jobs = listOf(job.copy(status = ArchiveJobStatus.Waiting, password = password, remoteTaskId = null)) + jobs.filterNot { it.id == jobId }
        ensureWorker()
    }

    /** 放弃一个待输密码的压缩包。 */
    fun skip(jobId: String) {
        if (requestedPasswordJobId == jobId) requestedPasswordJobId = null
        jobs = jobs.filterNot { it.id == jobId && it.status is ArchiveJobStatus.NeedsPassword }
        completedJobs = completedJobs.filterNot { it.id == jobId }
    }

    fun canRecheck(jobId: String): Boolean = completedJobs.any {
        it.id == jobId && it.remoteTaskId != null && (it.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED
    }

    fun recheck(jobId: String) {
        val job = completedJobs.firstOrNull { it.id == jobId } ?: return
        if (!canRecheck(jobId)) return
        Snapshot.withMutableSnapshot {
            completedJobs = completedJobs.filterNot { it.id == jobId }
            jobs = jobs + job.copy(status = ArchiveJobStatus.Waiting)
        }
        ensureWorker()
    }

    /**
     * 退出登录或换号时调用。已提交的解压在服务端照常完成，只是不再跟踪。
     * 不取消 [scope]：它由调用方持有，之后还要复用。
     */
    fun clear() {
        worker?.cancel()
        worker = null
        requestedPasswordJobId = null
        jobs = emptyList()
        completedJobs = emptyList()
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
        job.remoteTaskId?.let { taskId ->
            update(job.id) { it.copy(status = ArchiveJobStatus.Checking) }
            follow(job, taskId)
            return
        }
        update(job.id) { it.copy(status = ArchiveJobStatus.Submitting) }
        val task = repository.start(job.file, job.password, job.paths).getOrElse { err ->
            PikoLog.w(TAG, "提交解压失败：${logFile(job.file.id, job.file.name)}", err)
            if (err is ArchivePasswordException) {
                PikoLog.i(TAG, "解压要密码：${logFile(job.file.id, job.file.name)}，${if (job.password.isEmpty()) "未带密码" else "所带密码被拒"}")
                // 没带密码时服务端报的是「缺少」；带了还被拒才算输错
                update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty())) }
            } else if (err is ArchiveSubmissionUnconfirmed) {
                finish(job, tr("无法确认 ${job.file.name} 是否已提交解压，请先检查所在目录", "Cannot confirm extraction submission for ${job.file.name}. Check its folder first", "${job.file.name} の解凍送信を確認できません。先に保存先フォルダーを確認してください", "${job.file.name}의 압축 해제 제출 여부를 확인할 수 없습니다. 먼저 해당 폴더를 확인해 주세요"), outcome = ArchiveCompletion.UNCONFIRMED)
            } else {
                finish(job, tr("${job.file.name} 解压失败：${failureReason(err)}", "Failed to extract ${job.file.name}: ${failureReason(err)}", "${job.file.name} の解凍に失敗しました：${failureReason(err)}", "${job.file.name} 압축 해제 실패: ${failureReason(err)}"))
            }
            return
        }
        PikoLog.i(TAG, "已提交解压：${logFile(job.file.id, job.file.name)}，任务 ${task.taskId}，${job.file.sizeBytes} 字节，" +
            "${if (job.paths.isEmpty()) "整包" else "选 ${job.paths.size} 项"}，${if (job.password.isEmpty()) "无密码" else "带密码"}，队列还有 ${jobs.size - 1} 个")
        // 服务端收下即说明密码正确，此时就存，不等解压完：下一个待输密码的包马上能选到它
        if (job.password.isNotEmpty()) vault.remember(job.password)
        update(job.id) { it.copy(status = ArchiveJobStatus.Extracting(0), remoteTaskId = task.taskId) }
        follow(job, task.taskId)
    }

    private suspend fun follow(job: ArchiveJob, taskId: String) {
        var failures = 0
        while (true) {
            val progress = repository.progress(taskId).getOrElse { err ->
                PikoLog.w(TAG, "查询解压进度失败：${logFile(job.file.id, job.file.name)}，第 ${failures + 1} 次", err)
                failures++
                if (failures >= MAX_POLL_FAILURES) {
                    finish(job, tr("暂时无法确认 ${job.file.name} 的解压结果：${failureReason(err)}", "Extraction result for ${job.file.name} is unconfirmed: ${failureReason(err)}", "${job.file.name} の解凍結果を確認できません：${failureReason(err)}", "${job.file.name}의 압축 해제 결과를 확인할 수 없습니다: ${failureReason(err)}"), outcome = ArchiveCompletion.UNCONFIRMED)
                    return
                }
                delay(queryRetryDelay)
                continue
            }
            failures = 0
            when (progress.phase) {
                TaskPhase.COMPLETE -> {
                    finish(job, tr("已解压 ${job.file.name}", "Extracted ${job.file.name}", "${job.file.name} を解凍しました", "${job.file.name} 압축 해제 완료"), succeeded = true)
                    // 解压出的文件夹落在压缩包旁边
                    driveRepository.applyChange(DriveChange.ContentsChanged(setOf(job.file.parentId)))
                    return
                }
                TaskPhase.ERROR -> {
                    // 数据加密而文件名未加密的包，列目录不要密码，要到解压时才失败
                    val cause = repository.failureCause(taskId)
                    if (cause == ArchiveRepository.INVALID_PASSWORD) {
                        PikoLog.i(TAG, "解压任务 $taskId 因密码失败，等用户输入：${logFile(job.file.id, job.file.name)}")
                        update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty())) }
                    } else if (cause == ArchiveRepository.INVALID_FORMAT) {
                        finish(job, tr("${job.file.name} 解压失败：$INVALID_FORMAT_REASON", "Failed to extract ${job.file.name}: $INVALID_FORMAT_REASON", "${job.file.name} の解凍に失敗しました：$INVALID_FORMAT_REASON", "${job.file.name} 압축 해제 실패: $INVALID_FORMAT_REASON"))
                    } else {
                        val reason = dev.piko.shared.notice.PikoErrorMapper.archiveReason(cause, progress.errorDescription)
                        finish(job, tr("${job.file.name} 解压失败：$reason", "Failed to extract ${job.file.name}: $reason", "${job.file.name} の解凍に失敗しました：$reason", "${job.file.name} 압축 해제 실패: $reason"))
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
        succeeded: Boolean = false,
        outcome: ArchiveCompletion = if (succeeded) ArchiveCompletion.SUCCEEDED else ArchiveCompletion.FAILED,
    ) {
        val current = jobs.firstOrNull { it.id == job.id } ?: return
        val record = current.copy(password = "", status = ArchiveJobStatus.Finished(outcome, message, Clock.System.now().toEpochMilliseconds()))
        Snapshot.withMutableSnapshot {
            jobs = jobs.filterNot { it.id == job.id }
            completedJobs = (completedJobs.filterNot { it.id == job.id } + record).takeLast(100)
        }
        // 提示里带着压缩包的名字，写进日志前换成 ID
        val logged = message.replace(job.file.name, logFile(job.file.id, job.file.name))
        PikoLog.log(if (succeeded) LogLevel.INFO else LogLevel.WARN, TAG, logged, null)
        _messages.tryEmit(message)
        _outcomes.tryEmit(ArchiveOutcome(job.file.name, outcome, message))
    }

    private fun update(jobId: String, transform: (ArchiveJob) -> ArchiveJob) {
        jobs = jobs.map { if (it.id == jobId) transform(it) else it }
    }

    private companion object {
        const val TAG = "Archive"
        const val MAX_POLL_FAILURES = 5
        val VOLUME_UNSUPPORTED: String get() = tr("PikPak 不支持解压分卷压缩包", "PikPak does not support multi-volume archives", "PikPakは分割アーカイブの解凍に対応していません", "PikPak은 분할 압축 해제를 지원하지 않습니다")
    }
}

/** 服务端给的 expires_in 当作下次查询的间隔：进行中是 1 到 2 秒。异常值收在 1 到 5 秒之间。 */
private fun pollDelay(expiresIn: Int): Duration = expiresIn.coerceIn(1, 5).seconds

// 服务端对损坏的包与分卷给的是同一个结果，分不出来，两种都说
private val INVALID_FORMAT_REASON: String get() = dev.piko.shared.notice.PikoErrorMapper.invalidArchive

private fun failureReason(err: Throwable): String = dev.piko.shared.notice.PikoErrorMapper.archive(err)
