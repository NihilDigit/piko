package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.ArchiveRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
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

sealed interface ArchiveJobStatus {
    data object Waiting : ArchiveJobStatus

    /** 解压请求已发出，服务端在校验密码、建任务。 */
    data object Submitting : ArchiveJobStatus
    data class Extracting(val progress: Int) : ArchiveJobStatus

    /** [incorrect] 为假表示还没给过密码。 */
    data class NeedsPassword(val incorrect: Boolean) : ArchiveJobStatus
}

data class ArchiveJob(
    val file: FileStat,
    val status: ArchiveJobStatus = ArchiveJobStatus.Waiting,
    /** 用户输入的密码。空串表示不带密码提交，普通压缩包会忽略它。 */
    val password: String = "",
) {
    val id: String get() = file.id
}

/**
 * 压缩包的服务端解压，进程级：离开网盘页、切到别处，解压照常进行。
 *
 * [jobs] 只含未结束的压缩包；完成、失败都从中移除，结果经 [messages] 提示。
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
    preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
) {
    private val repository = ArchiveRepository(clientProvider)
    private val vault = ArchivePasswordVault(preferences)

    var jobs by mutableStateOf<List<ArchiveJob>>(emptyList())
        private set

    val savedPasswords: StateFlow<List<String>> =
        vault.passwords.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * 该弹密码框的压缩包。刚输过密码的那个还没验证完时先不弹下一个：同一批分包常用同一个密码，
     * 等它验证通过、存进密码表，下一个框里就能直接点选。
     */
    val passwordPrompt: ArchiveJob? by derivedStateOf {
        val verifying = jobs.any {
            it.password.isNotEmpty() && (it.status == ArchiveJobStatus.Waiting || it.status == ArchiveJobStatus.Submitting)
        }
        if (verifying) null else jobs.firstOrNull { it.status is ArchiveJobStatus.NeedsPassword }
    }

    private var worker: Job? = null

    /** 把 [files] 里的压缩包加进队列，其余文件与已在队列里的忽略。 */
    fun extract(files: List<FileStat>) {
        val queued = jobs.map { it.id }.toSet()
        val archives = files.filter { it.isExtractableArchive && it.id !in queued }.distinctBy { it.id }
        if (archives.isEmpty()) {
            if (files.none { it.isExtractableArchive }) _messages.tryEmit("所选文件中没有可解压的压缩包")
            return
        }
        jobs = jobs + archives.map { ArchiveJob(it) }
        ensureWorker()
    }

    fun submitPassword(jobId: String, password: String) {
        val job = jobs.firstOrNull { it.id == jobId } ?: return
        // 排到队首：用户正等着看这个密码对不对
        jobs = listOf(job.copy(status = ArchiveJobStatus.Waiting, password = password)) + jobs.filterNot { it.id == jobId }
        ensureWorker()
    }

    /** 放弃一个待输密码的压缩包。 */
    fun skip(jobId: String) {
        jobs = jobs.filterNot { it.id == jobId && it.status is ArchiveJobStatus.NeedsPassword }
    }

    /**
     * 退出登录或换号时调用。已提交的解压在服务端照常完成，只是不再跟踪。
     * 不取消 [scope]：它由调用方持有，之后还要复用。
     */
    fun clear() {
        worker?.cancel()
        worker = null
        jobs = emptyList()
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
        update(job.id) { it.copy(status = ArchiveJobStatus.Submitting) }
        val task = repository.start(job.file, job.password).getOrElse { err ->
            if (err is ArchivePasswordException) {
                // 没带密码时服务端报的是「缺少」；带了还被拒才算输错
                update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty())) }
            } else {
                finish(job, "${job.file.name} 解压失败：${failureReason(err)}")
            }
            return
        }
        // 服务端收下即说明密码正确，此时就存，不等解压完：下一个待输密码的包马上能选到它
        if (job.password.isNotEmpty()) vault.remember(job.password)
        update(job.id) { it.copy(status = ArchiveJobStatus.Extracting(0)) }
        follow(job, task.taskId)
    }

    private suspend fun follow(job: ArchiveJob, taskId: String) {
        var failures = 0
        while (true) {
            val progress = repository.progress(taskId).getOrElse { err ->
                failures++
                if (failures >= MAX_POLL_FAILURES) {
                    finish(job, "${job.file.name} 解压进度查询失败：${failureReason(err)}")
                    return
                }
                delay(POLL_RETRY_DELAY)
                continue
            }
            failures = 0
            when (progress.phase) {
                TaskPhase.COMPLETE -> {
                    finish(job, "已解压 ${job.file.name}")
                    driveRepository.requestRefresh()
                    return
                }
                TaskPhase.ERROR -> {
                    // 数据加密而文件名未加密的包，列目录不要密码，要到解压时才失败
                    val cause = repository.failureCause(taskId)
                    if (cause == ArchiveRepository.INVALID_PASSWORD) {
                        update(job.id) { it.copy(status = ArchiveJobStatus.NeedsPassword(incorrect = job.password.isNotEmpty())) }
                    } else {
                        finish(job, "${job.file.name} 解压失败：${progress.errorDescription.ifEmpty { cause ?: "服务端未说明原因" }}")
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

    private fun finish(job: ArchiveJob, message: String) {
        jobs = jobs.filterNot { it.id == job.id }
        _messages.tryEmit(message)
    }

    private fun update(jobId: String, transform: (ArchiveJob) -> ArchiveJob) {
        jobs = jobs.map { if (it.id == jobId) transform(it) else it }
    }

    private companion object {
        const val MAX_POLL_FAILURES = 5
        val POLL_RETRY_DELAY = 3.seconds
    }
}

/** 服务端给的 expires_in 当作下次查询的间隔：进行中是 1 到 2 秒。异常值收在 1 到 5 秒之间。 */
private fun pollDelay(expiresIn: Int): Duration = expiresIn.coerceIn(1, 5).seconds

private fun failureReason(err: Throwable): String = when {
    err is PikPakException && err.errorMessage == "NEED_MORE_QUOTA" -> "解压额度不足"
    err is PikPakException -> err.errorDescription?.takeIf { it.isNotBlank() } ?: err.errorMessage
    err is IllegalStateException -> err.message ?: "未知错误"
    else -> "网络异常"
}
