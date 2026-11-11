package dev.piko.shared.state

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveWriteAccess
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import dev.piko.shared.notice.PikoErrorMapper
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val fileWriteIds = MutableStateFlow(0L)

/** 仅复制保留远端任务；普通编辑委托 Mutation 执行器。 */
class FileWriteSession(
    private val repository: PikoDriveRepository,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 3_000,
) {
    private val executions = AccountExecutionScopes(repository, scope)
    private val _works = MutableStateFlow<List<FileWriteWork>>(emptyList())
    val works = _works.asStateFlow()
    private val _outcomes = MutableSharedFlow<FileWriteOutcome>(extraBufferCapacity = 16)
    val mutations = FileMutations(repository, scope)
    val outcomes = merge(_outcomes.asSharedFlow(), mutations.outcomes)
    suspend fun awaitResult(id: Long): FileWriteResult? {
        mutations.awaitResult(id)?.let { return it }
        if (works.value.none { it.id == id }) return null
        return works.first { all -> all.any { it.id == id && it.result != null } }.first { it.id == id }.result
    }
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())

    internal fun submit(command: FileWriteCommand): Long? {
        val account = repository.currentAccount ?: return null
        val access = repository.writeAccess(account) ?: return null
        if (command !is FileWriteCommand.Copy) return mutations.submit(fileWriteIds.getAndUpdate { it + 1 } + 1, command.frozen())
        val work = FileWriteWork(fileWriteIds.getAndUpdate { it + 1 } + 1, account, command.copy(ids = command.ids.distinct()), now())
        _works.update { it + work }
        execute(work, access, recheck = false)
        return work.id
    }

    fun canRecheck(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.account != repository.currentAccount || work.result?.unconfirmed.isNullOrEmpty()) return false
        if (jobs.value[id]?.isActive == true) return false
        return work.copies.any { copy ->
            copy.phase != TaskPhase.ERROR && copy.sources.any { it in work.result.unconfirmed }
        }
    }

    fun recheck(id: Long): Boolean {
        if (!canRecheck(id)) return false
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        val access = repository.writeAccess(work.account) ?: return false
        _works.update { all -> all.map { if (it.id == id) it.copy(stopping = false) else it } }
        execute(work.copy(stopping = false), access, recheck = true)
        return true
    }

    fun requestStop(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (!work.isRunning || work.account != repository.currentAccount) return false
        _works.update { all -> all.map { if (it.id == id) it.copy(stopping = true) else it } }
        return true
    }

    fun forget(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.isRunning || jobs.value[id]?.isActive == true) return false
        if (work.result?.undoChangeIds.orEmpty().any {
            repository.changes.state.value.undo[it]?.status == DriveChangeJournal.UndoStatus.RUNNING
        }) return false
        _works.update { it.filterNot { item -> item.id == id } }
        return true
    }

    fun clear(account: String? = null) {
        mutations.clear(account)
        val ids = _works.value.filter { account == null || it.account == account }.mapTo(HashSet()) { it.id }
        jobs.getAndUpdate { all -> if (account == null) emptyMap() else all - ids }.filterKeys { account == null || it in ids }.values.forEach { it.cancel() }
        _works.update { all -> if (account == null) emptyList() else all.filterNot { it.account == account } }
    }

    private fun execute(initial: FileWriteWork, access: DriveWriteAccess, recheck: Boolean) {
        val command = initial.command
        val owner = executions.forAccount(initial.account)
        val job = owner.launch(start = CoroutineStart.LAZY) {
            var work = initial.copy(result = null, phase = FileWritePhase.COPYING)
            val confirmed = initial.result?.confirmed.orEmpty().toMutableSet()
            var refusalError: Throwable? = null
            fun publish() { _works.update { all -> all.map { if (it.id == work.id) work else it } } }
            try {
                publish()
                if (!recheck) {
                    access.copy(command.ids, command.target.id).onSuccess { taskIds ->
                        work = work.copy(copies = taskIds.map { FileCopyTask(it, command.ids) })
                        publish()
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        if (definiteRefusal(error)) {
                            refusalError = error
                            val pikpak = error as? PikPakException
                            PikoLog.w(
                                TAG,
                                "Copy request refused by server (work=${work.id}, items=${command.ids.size}, target=${logFolder(command.target.id)}, status=${pikpak?.httpStatus}, code=${pikpak?.errorMessage})",
                                error,
                            )
                        } else {
                            PikoLog.w(
                                TAG,
                                "Copy request unconfirmed before task creation (work=${work.id}, items=${command.ids.size}, target=${logFolder(command.target.id)})",
                                error,
                            )
                        }
                        null
                    } ?: return@launch finishWork(work, command, confirmed, refusalError)
                }
                var queryFailures = 0
                var lastPollError: Throwable? = null
                while (currentCoroutineContext().isActive) {
                    if (_works.value.firstOrNull { it.id == work.id }?.stopping == true) break
                    var unreadable = false
                    work = work.copy(copies = work.copies.map { copy ->
                        if (copy.phase == TaskPhase.COMPLETE || copy.phase == TaskPhase.ERROR) copy
                        else {
                            val taskInfo = access.task(copy.id).getOrElse { pollErr ->
                                if (pollErr is CancellationException) throw pollErr
                                unreadable = true
                                lastPollError = pollErr
                                null
                            }
                            if (taskInfo != null) {
                                if (taskInfo.phase == TaskPhase.ERROR && copy.phase != TaskPhase.ERROR) {
                                    PikoLog.w(
                                        TAG,
                                        "Remote copy task failed (work=${work.id}, task=${copy.id}, target=${logFolder(command.target.id)}, message=${taskInfo.message.ifBlank { "none" }})",
                                    )
                                }
                                copy.copy(phase = taskInfo.phase, progress = taskInfo.progress)
                            } else {
                                copy
                            }
                        }
                    })
                    publish()
                    queryFailures = if (unreadable) queryFailures + 1 else 0
                    if (work.copies.isNotEmpty() && work.copies.all { it.phase == TaskPhase.COMPLETE }) {
                        confirmed += command.ids
                        access.publish(DriveChange.Copied(command.ids, command.target.id))
                        break
                    }
                    if (work.copies.any { it.phase == TaskPhase.ERROR }) break
                    if (queryFailures >= MAX_POLL_FAILURES) {
                        PikoLog.w(
                            TAG,
                            "Copy status polling aborted after $queryFailures consecutive failures (work=${work.id}, tasks=${work.copies.size}, target=${logFolder(command.target.id)})",
                            lastPollError,
                        )
                        break
                    }
                    delay(pollMillis)
                }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PikoLog.w(
                    TAG,
                    "Copy execution failed (work=${work.id}, recheck=$recheck, items=${command.ids.size}, target=${logFolder(command.target.id)})",
                    error,
                )
            } finally {
                jobs.update { it - initial.id }
            }
            finishWork(work, command, confirmed, refusalError)
        }
        jobs.update { it + (initial.id to job) }
        job.start()
    }

    private fun finishWork(
        current: FileWriteWork,
        command: FileWriteCommand.Copy,
        confirmed: Set<String>,
        refusalError: Throwable?,
    ) {
        val complete = confirmed.containsAll(command.ids)
        val remaining = command.ids.toSet() - confirmed
        val refused = refusalError != null && current.copies.isEmpty()
        val hasTaskError = current.copies.any { it.phase == TaskPhase.ERROR }
        val completion = when {
            complete -> FileWriteCompletion.SUCCEEDED
            refused || hasTaskError -> FileWriteCompletion.FAILED
            else -> FileWriteCompletion.UNCONFIRMED
        }
        val message = when {
            complete -> tr("复制完成", "Copy completed", "コピー完了", "복사 완료", "複製完成")
            refused -> PikoErrorMapper.failure(tr("复制", "Copy", "コピー", "복사", "複製"), refusalError)
            hasTaskError -> tr("复制失败，请刷新后重试", "Copy failed. Refresh and retry", "コピーに失敗しました。更新して再試行してください", "복사에 실패했습니다. 새로고침 후 다시 시도하세요", "複製失敗，請重新整理後重試")
            else -> tr("复制尚未完成，可重新查询结果", "Copy is incomplete. Check its status again", "コピーは未完了です。状態を再確認できます", "복사가 완료되지 않았습니다. 상태를 다시 확인하세요", "複製尚未完成，可重新查詢結果")
        }
        val result = FileWriteResult(
            completion,
            message,
            confirmed = confirmed,
            unconfirmed = if (refused) emptySet() else remaining,
            failed = if (refused) remaining else emptySet(),
            finishedAtMs = now(),
        )
        val finished = current.copy(phase = FileWritePhase.FINISHED, result = result)
        _works.update { all -> all.map { if (it.id == finished.id) finished else it } }
        _outcomes.tryEmit(FileWriteOutcome(finished.account, finished.id, command.title, result))
    }

    private fun definiteRefusal(error: Throwable?): Boolean =
        error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422, 429)

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TAG = "FileCopy"
        const val MAX_POLL_FAILURES = 5
    }
}

private fun FileWriteCommand.frozen(): FileWriteCommand = when (this) {
    is FileWriteCommand.CreateFolder -> copy(name = name.trim())
    is FileWriteCommand.Rename -> copy(name = name.trim())
    is FileWriteCommand.Move -> copy(ids = ids.distinct(), knownBefore = knownBefore.toMap())
    is FileWriteCommand.Copy -> copy(ids = ids.distinct())
    is FileWriteCommand.Trash -> copy(ids = ids.distinct())
    is FileWriteCommand.Restore -> copy(ids = ids.distinct())
    is FileWriteCommand.Delete -> copy(ids = ids.distinct())
    is FileWriteCommand.Star -> copy(ids = ids.distinct())
}
