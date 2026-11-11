package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class OfflinePackStage { QUEUED, DOWNLOADING, PRUNING, DONE, FAILED }

data class OfflinePackRef(val account: String, val taskId: String)

/**
 * 一次「整包离线、完成后删掉未选文件」的跟踪记录，持久化，重启后接着轮询。
 *
 * [keep] 是 resolveMagnet 给出的相对路径，与离线产出一一对应，见 SDK 的 pruneOfflineOutput。
 */
@Serializable
data class OfflinePackJob(
    /** 记录属于哪个账号。换号登录时别的账号的任务既查不到，也不该显示。 */
    val account: String,
    val taskId: String,
    val url: String,
    val targetId: String,
    /** 完成后把产出文件夹改成这个名字，即面板里填的文件夹名。 */
    val folderName: String,
    val keep: Set<String>,
    val totalFiles: Int,
    val totalBytes: Long,
    val createdAtMs: Long,
    /** 勾选部分的大小。早先的记录没有这个字段，读回为 0，界面只显示整包大小。 */
    val keptBytes: Long = 0,
    val stage: OfflinePackStage = OfflinePackStage.QUEUED,
    val progress: Int = 0,
    val outputId: String = "",
    val finishedAtMs: Long = 0,
    /** 失败原因，或完成时的附注（如改名失败）。 */
    val message: String = "",
    /** 失败发生在清理阶段：文件已下完，重试只需再清理一次，不必重新离线。 */
    val cleanupFailed: Boolean = false,
) {
    /** 完成后要删掉的文件数。 */
    val prunedCount: Int get() = (totalFiles - keep.size).coerceAtLeast(0)

    val isActive: Boolean
        get() = stage == OfflinePackStage.QUEUED || stage == OfflinePackStage.DOWNLOADING ||
            stage == OfflinePackStage.PRUNING
}

/**
 * 整包离线任务的跟踪器，进程级。
 *
 * 离线只能整条磁链一起下，挑文件靠下完之后删：任务完成后用 pruneOfflineOutput 永久删除
 * 未选的文件，再把产出文件夹改成面板里填的名字。任务可能要几分钟甚至更久，所以记录落盘，
 * 应用重启后 [run] 接着轮询。
 *
 * 轮询按任务提交后经过的时间退避，见 [offlinePollDelay]。传输页可见时它自己也每 4 秒拉一次
 * 列表，这里的进度只在页面不可见时兜底，不必跟那边一样勤。
 */
class OfflinePackTracker(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()

    // 全部账号的记录；null 表示还没从偏好里读出来
    private var all: List<OfflinePackJob>? = null
    private var account: String? = null

    private val _jobs = MutableStateFlow<List<OfflinePackJob>>(emptyList())

    /** 当前账号的记录，新提交的在前。 */
    val jobs: StateFlow<List<OfflinePackJob>> = _jobs.asStateFlow()
    private val _busy = MutableStateFlow<Set<OfflinePackRef>>(emptySet())
    val busy = _busy.asStateFlow()

    // 新提交或重试的任务 id：唤醒轮询，并让这一项不等退避、立即查一次
    private val nudges = Channel<String>(Channel.UNLIMITED)

    // 只在轮询循环里读写；重启后为空，所有任务都立即查一次
    private val lastCheckedMs = mutableMapOf<String, Long>()

    // 服务端明确报错的连续次数。断网之类的传输层异常不计：等网络回来就好了
    private val serverFailures = mutableMapOf<String, Int>()
    private val lastServerErrorTypes = mutableMapOf<String, KClass<out Throwable>>()

    /**
     * 以 [account] 的身份轮询，挂起直到调用方取消。换号或退出登录时由调用方取消后重来。
     */
    suspend fun run(account: String) {
        lock.withLock {
            if (all == null) all = restoreOfflinePacks(preferences.loadOfflinePacks(), now())
            this.account = account
            publish()
        }
        lastCheckedMs.clear()
        serverFailures.clear()
        lastServerErrorTypes.clear()
        try {
            pollLoop()
        } finally {
            lock.withLock {
                this.account = null
                publish()
            }
        }
    }

    /**
     * 提交整条链接的离线任务并开始跟踪。服务端没建任务、直接给出结果时（SDK 的
     * InstantComplete，实测几乎不出现）文件已经存好，无从清理，返回 null。
     */
    suspend fun submit(
        url: String,
        targetId: String,
        folderName: String,
        keep: Set<String>,
        totalFiles: Int,
        totalBytes: Long,
        keptBytes: Long,
    ): Result<OfflinePackJob?> {
        val owner = lock.withLock { account } ?: return Result.failure(IllegalStateException("未登录"))
        val created = instantRepo.enqueueOfflineTask(url, targetId, title = folderName, expectedAccount = owner, requiresTask = totalFiles > 1).getOrElse { return Result.failure(it) }
        val task = (created as? CreateUrlResult.Queued)?.task ?: run {
            PikoLog.i(
                TAG,
                "Batch offline submission completed immediately; skipping unchosen-file pruning (target=${logFolder(targetId)}, keep=${keep.size}/$totalFiles)",
            )
            return Result.success(null)
        }
        PikoLog.i(
            TAG,
            "Submitted batch offline task ${task.id}: target=${logFolder(targetId)}, keep=${keep.size}/$totalFiles, bytes=$keptBytes/$totalBytes",
        )
        val job = OfflinePackJob(
            account = owner,
            taskId = task.id,
            url = url,
            targetId = targetId,
            folderName = folderName,
            keep = keep,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            createdAtMs = now(),
            keptBytes = keptBytes,
        )
        lock.withLock {
            all = listOf(job) + all.orEmpty()
            persist()
        }
        nudges.trySend(job.taskId)
        return Result.success(job)
    }

    /**
     * 取消进行中的任务，或移除已结束的记录。两者都是删掉服务端的任务记录：未完成任务的
     * 占位文件由服务端一并清掉，已完成任务的文件保留。
     */
    suspend fun discard(taskId: String): Result<Unit> {
        val job = lock.withLock {
            val job = all.orEmpty().firstOrNull { it.account == account && it.taskId == taskId } ?: return Result.success(Unit)
            val ref = OfflinePackRef(job.account, taskId)
            if (ref in _busy.value) return Result.failure(IllegalStateException(tr("此整包离线任务正在处理，请待完成后重试", "This batch download task is being processed. Try again when finished", "この一括ダウンロードタスクは処理中です。完了後に再試行してください", "이 전체 다운로드 작업을 처리 중입니다. 완료 후 다시 시도해 주세요", "此整包離線任務正在處理，請待完成後重試")))
            _busy.update { it + ref }
            job
        }
        val ref = OfflinePackRef(job.account, taskId)
        try {
            return instantRepo.deleteOfflineTask(taskId, job.account).logFailure(TAG, "Failed to delete batch offline task $taskId").onSuccess {
                PikoLog.i(TAG, "Deleted batch offline task $taskId")
                lock.withLock {
                    all = all.orEmpty().filterNot { it.account == job.account && it.taskId == taskId }
                    persist()
                }
            }
        } finally { _busy.update { it - ref } }
    }

    /** 清理失败的只重做清理；下载失败的以原链接重新提交，换成新任务继续跟踪。 */
    suspend fun retry(taskId: String): Result<Unit> {
        val job = lock.withLock {
            val job = all.orEmpty().firstOrNull { it.account == account && it.taskId == taskId } ?: return Result.success(Unit)
            val ref = OfflinePackRef(job.account, taskId)
            if (ref in _busy.value) return Result.failure(IllegalStateException(tr("此整包离线任务正在处理，请待完成后重试", "This batch download task is being processed. Try again when finished", "この一括ダウンロードタスクは処理中です。完了後に再試行してください", "이 전체 다운로드 작업을 처리 중입니다. 완료 후 다시 시도해 주세요", "此整包離線任務正在處理，請待完成後重試")))
            if (job.stage != OfflinePackStage.FAILED) return Result.success(Unit)
            _busy.update { it + ref }
            job
        }
        val refs = mutableSetOf(OfflinePackRef(job.account, taskId))
        try {
            return runSuspendCatching {
                PikoLog.i(TAG, "Retrying batch offline task $taskId (mode=${if (job.cleanupFailed) "prune-only" else "resubmit"})")
                val trackedId = if (job.cleanupFailed) {
                    update(taskId, persist = true) {
                        it.copy(stage = OfflinePackStage.PRUNING, cleanupFailed = false, message = "")
                    }
                    taskId
                } else {
                    val created = instantRepo.enqueueOfflineTask(job.url, job.targetId, title = job.folderName,
                        expectedAccount = job.account, requiresTask = job.totalFiles > 1).getOrThrow()
                    val task = (created as? CreateUrlResult.Queued)?.task ?: error("服务端未返回任务")
                    refs += OfflinePackRef(job.account, task.id)
                    _busy.update { it + refs }
                    PikoLog.i(TAG, "Resubmitted batch offline task $taskId as ${task.id}")
                    update(taskId, persist = true) {
                        it.copy(taskId = task.id, stage = OfflinePackStage.QUEUED, progress = 0, createdAtMs = now(), message = "")
                    }
                    nudges.trySend(task.id)
                    // 先接管新任务；旧记录删不掉只多留一条，不影响新任务的跟踪。
                    instantRepo.deleteOfflineTask(taskId, job.account).logFailure(TAG, "Failed to delete superseded offline task $taskId after retry")
                    task.id
                }
                nudges.trySend(trackedId)
            }
        } finally { _busy.update { it - refs } }
    }

    private suspend fun pollLoop() {
        while (true) {
            val active = jobs.value.filter { it.isActive }
            if (active.isEmpty()) {
                onNudge(nudges.receive())
                continue
            }
            active.filter { dueAt(it) <= now() }.forEach { job ->
                step(job)
                lastCheckedMs[job.taskId] = now()
            }
            val nextDue = jobs.value.filter { it.isActive }.minOfOrNull(::dueAt) ?: continue
            withTimeoutOrNull((nextDue - now()).coerceAtLeast(0)) { onNudge(nudges.receive()) }
        }
    }

    private fun onNudge(first: String) {
        lastCheckedMs.remove(first)
        while (true) lastCheckedMs.remove(nudges.tryReceive().getOrNull() ?: return)
    }

    private fun dueAt(job: OfflinePackJob): Long {
        val last = lastCheckedMs[job.taskId] ?: return 0
        return last + offlinePollDelay((last - job.createdAtMs).milliseconds).inWholeMilliseconds
    }

    private suspend fun step(job: OfflinePackJob) {
        val task = instantRepo.getTask(job.taskId).getOrElse { err ->
            recordFailure(job, err)
            return
        }
        instantRepo.offlineSubmissions.observeTasks(job.account, listOf(task), job.targetId)
        serverFailures.remove(job.taskId)
        lastServerErrorTypes.remove(job.taskId)
        when (task.phase) {
            TaskPhase.PENDING -> update(job.taskId) { it.copy(stage = OfflinePackStage.QUEUED) }
            TaskPhase.RUNNING -> {
                if (job.stage == OfflinePackStage.QUEUED) {
                    PikoLog.d(TAG, "Batch offline task ${job.taskId} started downloading after ${(now() - job.createdAtMs) / 1000}s in queue")
                }
                update(job.taskId) { it.copy(stage = OfflinePackStage.DOWNLOADING, progress = task.progress) }
            }
            TaskPhase.ERROR -> {
                PikoLog.w(
                    TAG,
                    "Batch offline task ${job.taskId} failed on server (progress=${task.progress}%, elapsedSec=${(now() - job.createdAtMs) / 1000}, detail=${task.message.take(80)})",
                )
                update(job.taskId, persist = true) {
                    it.copy(
                        stage = OfflinePackStage.FAILED,
                        message = task.message.ifEmpty { tr("离线下载失败", "Cloud download failed", "クラウドダウンロードに失敗しました", "클라우드 다운로드 실패", "離線下載失敗") },
                        finishedAtMs = now(),
                    )
                }
            }
            TaskPhase.COMPLETE -> finish(job, task)
        }
    }

    private suspend fun finish(job: OfflinePackJob, task: DriveTask) {
        val pruningJob = if (job.stage != OfflinePackStage.PRUNING || job.outputId != task.fileId) {
            val updated = job.copy(stage = OfflinePackStage.PRUNING, progress = 100, outputId = task.fileId)
            update(job.taskId, persist = true) { updated }
            updated
        } else {
            job
        }
        if (task.fileId.isEmpty()) {
            fail(pruningJob, tr("云端任务未生成文件", "Cloud task produced no files", "クラウドタスクからファイルが生成されませんでした", "클라우드 작업에서 생성된 파일이 없습니다", "雲端任務未產生檔案"), cleanup = false)
            return
        }
        // 全选时没有要删的，省掉逐层列目录
        val pruneResult = if (pruningJob.prunedCount > 0) {
            instantRepo.pruneOfflineOutput(task, pruningJob.keep, pruningJob.url).getOrElse { err ->
                recordFailure(pruningJob, err)
                return
            }
        } else null
        // 单文件的种子产出就是文件本身，不改名。走到整包离线的至少两个文件
        val renameNote = if (pruningJob.totalFiles > 1 && pruningJob.folderName.isNotBlank() && task.fileName != pruningJob.folderName) {
            driveRepo.rename(task.fileId, pruningJob.folderName)
                .logFailure(TAG, "Failed to rename batch offline output ${task.fileId} in ${logFolder(pruningJob.targetId)}")
                .fold({ "" }, { tr("未能重命名为 ${pruningJob.folderName}", "Failed to rename to ${pruningJob.folderName}", "${pruningJob.folderName} に名前を変更できませんでした", "${pruningJob.folderName}(으)로 이름을 변경하지 못했습니다", "未能重新命名為 ${pruningJob.folderName}") })
        } else {
            ""
        }
        val actualDeleted = pruneResult?.deleted?.size ?: 0
        val actualMissing = pruneResult?.missing?.size ?: 0
        PikoLog.i(
            TAG,
            "Batch offline task ${pruningJob.taskId} completed: output=${task.fileId}, target=${logFolder(pruningJob.targetId)}, deleted=$actualDeleted, missing=$actualMissing, expectedPrune=${pruningJob.prunedCount}, elapsedSec=${(now() - pruningJob.createdAtMs) / 1000}, renamed=${renameNote.isEmpty()}",
        )
        update(pruningJob.taskId, persist = true) {
            it.copy(
                stage = OfflinePackStage.DONE,
                outputId = task.fileId,
                finishedAtMs = now(),
                message = renameNote,
            )
        }
        if (pruneResult != null && pruneResult.deletedIds.isNotEmpty()) {
            driveRepo.applyChange(DriveChange.Deleted(pruneResult.deletedIds.toList(), pruneResult.touchedFolderIds))
        }
        val affectedFolders = buildSet {
            add(pruningJob.targetId)
            add(task.fileId)
            if (pruneResult != null) addAll(pruneResult.touchedFolderIds)
            addAll(driveRepo.knownDescendantFolders(task.fileId))
        }
        // 定向移除已裁剪条目并失效目标接收目录与产出子树中所有已知目录，不作废无关文件树
        driveRepo.applyChange(DriveChange.ContentsChanged(affectedFolders))
    }

    private suspend fun recordFailure(job: OfflinePackJob, err: Throwable) {
        if (err is CancellationException) throw err
        if (err is OfflinePruneBlocked) {
            fail(job, err.message.orEmpty(), cleanup = true, cause = err)
            return
        }
        val isPruning = job.stage == OfflinePackStage.PRUNING
        if (err !is PikPakException) {
            if (isPruning) {
                PikoLog.w(
                    TAG,
                    "Batch offline task ${job.taskId} pruning failed (${err::class.simpleName}); will retry on next poll",
                    err,
                )
            } else {
                PikoLog.d(TAG, "Batch offline task ${job.taskId} poll failed (${err::class.simpleName}); waiting for network")
            }
            return
        }
        val count = (serverFailures[job.taskId] ?: 0) + 1
        serverFailures[job.taskId] = count
        val prevType = lastServerErrorTypes.put(job.taskId, err::class)
        val stageName = if (isPruning) "pruning" else "polling"
        val attachCause = err.takeIf { count == 1 || prevType != err::class }
        PikoLog.w(
            TAG,
            "Batch offline task ${job.taskId} $stageName rejected by server ($count/$MAX_SERVER_FAILURES, code=${err.errorCode}, http=${err.httpStatus})",
            attachCause,
        )
        if (count >= MAX_SERVER_FAILURES) {
            serverFailures.remove(job.taskId)
            lastServerErrorTypes.remove(job.taskId)
            fail(
                job,
                err.message ?: tr("服务端返回错误", "Server error", "サーバーエラーが発生しました", "서버 오류가 발생했습니다", "伺服器傳回錯誤"),
                cleanup = isPruning,
                cause = err,
            )
        }
    }

    private suspend fun fail(job: OfflinePackJob, reason: String, cleanup: Boolean, cause: Throwable? = null) {
        PikoLog.w(
            TAG,
            "Batch offline task ${job.taskId} marked as failed (stage=${if (cleanup) "pruning" else "download"}, output=${job.outputId.ifEmpty { "none" }})",
            cause,
        )
        update(job.taskId, persist = true) {
            it.copy(
                stage = OfflinePackStage.FAILED,
                message = if (cleanup) tr("清理失败：$reason", "Cleanup failed: $reason", "整理に失敗しました：$reason", "정리 실패: $reason", "清理失敗：$reason") else reason,
                cleanupFailed = cleanup,
                finishedAtMs = now(),
            )
        }
    }

    /** 只改已有的记录：轮询途中被移除的任务不会被这里写回来。进度变化不落盘，重启后重查即可。 */
    private suspend fun update(taskId: String, persist: Boolean = false, transform: (OfflinePackJob) -> OfflinePackJob) {
        lock.withLock {
            all = all.orEmpty().map { if (it.taskId == taskId) transform(it) else it }
            if (persist) persist() else publish()
        }
    }

    private suspend fun persist() {
        publish()
        preferences.saveOfflinePacks(json.encodeToString(serializer, all.orEmpty()))
    }

    private fun publish() {
        val owner = account
        _jobs.value = if (owner == null) emptyList() else all.orEmpty().filter { it.account == owner }
    }

    companion object {
        internal const val TAG = "OfflinePack"

        /** 服务端连续这么多次明确报错才算失败，例如任务已在别处被删。 */
        const val MAX_SERVER_FAILURES = 5

        /** 已完成的记录保留这么久，与传输页云端已完成任务的展示窗口一致。 */
        val DONE_RETENTION: Duration = 7.days
    }
}

private val json = Json { ignoreUnknownKeys = true }
private val serializer = ListSerializer(OfflinePackJob.serializer())

/**
 * 从偏好里读回的记录。已完成超过 [OfflinePackTracker.DONE_RETENTION] 的丢掉；
 * 内容损坏时从空表开始，不让一份坏数据挡住启动。
 */
fun restoreOfflinePacks(serialized: String, nowMs: Long): List<OfflinePackJob> {
    if (serialized.isBlank()) return emptyList()
    val jobs = runCatching { json.decodeFromString(serializer, serialized) }.getOrElse { err ->
        PikoLog.w(
            OfflinePackTracker.TAG,
            "Corrupted offline pack store (${serialized.length} chars, error=${err::class.simpleName}); resetting to empty",
            err.takeIf { it !is SerializationException },
        )
        return emptyList()
    }
    val cutoff = nowMs - OfflinePackTracker.DONE_RETENTION.inWholeMilliseconds
    return jobs.filterNot { it.stage == OfflinePackStage.DONE && it.finishedAtMs < cutoff }
}

/**
 * 距任务提交 [elapsed] 时的轮询间隔。已缓存的内容 5 到 10 秒就下完，所以第一分钟查得勤；
 * 过了这一段多半是在等种子，几分钟到几小时不等，间隔逐级拉长，封顶 5 分钟。
 */
fun offlinePollDelay(elapsed: Duration): Duration = when {
    elapsed < 1.minutes -> 3.seconds
    elapsed < 5.minutes -> 10.seconds
    elapsed < 30.minutes -> 30.seconds
    elapsed < 2.hours -> 2.minutes
    else -> 5.minutes
}
