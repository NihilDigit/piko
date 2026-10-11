package dev.piko.shared.state

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveWriteAccess
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.naming.av.canonicalAvNameOf
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.ShareUnavailableException
import dev.piko.shared.log.PikoLog
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val shareWorkIds = MutableStateFlow(0L)

/** 转存提交、远端跟踪和命名结果独立于分享浏览页；重查只读已知任务、产物和名称。 */
class ShareSaveSession(private val repository: PikoDriveRepository, private val scope: CoroutineScope,
    private val pollDelay: Duration = 1.seconds, private val identityDelay: Duration = 250.milliseconds) {
    private val executions = AccountExecutionScopes(repository, scope)
    private val _works = MutableStateFlow<List<ShareSaveWork>>(emptyList())
    val works = _works.asStateFlow()
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private val _outcomes = MutableSharedFlow<ShareSaveOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()

    internal fun submit(command: ShareSaveCommand, token: String): Long? {
        if (!scope.isActive) return null
        val account = repository.currentAccount ?: return null
        val access = repository.writeAccess(account) ?: return null
        val frozen = command.copy(sources = command.sources.distinctBy { it.id }, ancestors = command.ancestors.toList())
        val work = ShareSaveWork(shareWorkIds.getAndUpdate { it + 1 } + 1, account, frozen, now(), journalGeneration = repository.changes.state.value.generation)
        _works.update { it + work }
        execute(work, access, token)
        return work.id
    }

    fun canRecheck(id: Long): Boolean = _works.value.any { work ->
        work.id == id && work.account == repository.currentAccount && !work.isRunning &&
            (work.pendingRenames.isNotEmpty() || (work.result?.unconfirmedSources?.isNotEmpty() == true &&
                work.remoteTaskId != null))
    }
    fun recheck(id: Long): Boolean {
        if (!canRecheck(id) || jobs.value[id]?.isActive == true) return false
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        val access = repository.writeAccess(work.account) ?: return false
        execute(work, access, token = null)
        return true
    }
    fun forget(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.isRunning || jobs.value[id]?.isActive == true || work.undoChangeIds.any { repository.changes.state.value.undo[it]?.status == DriveChangeJournal.UndoStatus.RUNNING }) return false
        _works.update { it.filterNot { item -> item.id == id } }
        return true
    }
    fun clear(account: String) {
        val ids = _works.value.filter { it.account == account }.mapTo(HashSet()) { it.id }
        _works.update { it.filterNot { work -> work.account == account } }
        jobs.getAndUpdate { it - ids }.filterKeys { it in ids }.values.forEach { it.cancel() }
    }

    private fun execute(initial: ShareSaveWork, access: DriveWriteAccess, token: String?) {
        val job = executions.forAccount(initial.account).launch(start = CoroutineStart.LAZY) {
            val execution = currentCoroutineContext()[Job]
            var work = initial.copy(result = null)
            var submitted = token == null || initial.phase != ShareSavePhase.PREPARING
            suspend fun check() {
                currentCoroutineContext().ensureActive()
                if (repository.currentAccount != work.account || _works.value.none { it.id == work.id }) throw CancellationException("转存账号已结束")
            }
            fun publish() { _works.update { all -> all.map { if (it.id == work.id) work else it } } }
            fun phase(value: ShareSavePhase) { work = work.copy(phase = value); publish() }
            fun record(renamed: List<DriveChangeJournal.Renamed>) {
                if (renamed.isEmpty()) return
                val previous = work.undoChangeIds.lastOrNull()
                if (previous != null && repository.changes.appendRename(previous, renamed, work.journalGeneration)) {
                    work = work.copy(recordedRenameIds = work.recordedRenameIds + renamed.map { it.id })
                    return
                }
                val expired = previous != null && repository.changes.state.value.available.none { it.id == previous } &&
                    repository.changes.state.value.undo[previous]?.status != DriveChangeJournal.UndoStatus.UNDONE
                val dependent = renamed.filter { it.id in work.recordedRenameIds }
                val independent = renamed.filterNot { it.id in work.recordedRenameIds }
                if (independent.isNotEmpty()) {
                    val inverse = DriveChangeJournal.Change.Rename(independent, "已处理转存文件名称")
                    if (repository.changes.record(inverse, work.journalGeneration)) work = work.copy(undoChangeIds = work.undoChangeIds + inverse.id)
                    else work = work.copy(undoUnavailableNames = work.undoUnavailableNames + independent.map { it.id }, undoLimited = true)
                }
                work = work.copy(recordedRenameIds = work.recordedRenameIds + renamed.map { it.id },
                    undoUnavailableNames = work.undoUnavailableNames + dependent.map { it.id }, undoLimited = work.undoLimited || expired)
            }
            fun finish() {
                val all = work.sourceIds
                val failed = if (work.copyState == ShareCopyState.REFUSED) all else emptySet()
                val notAttempted = if (!submitted) all else emptySet()
                val unknown = all - work.confirmedSources - failed - notAttempted
                val completion = when {
                    unknown.isNotEmpty() && work.confirmedSources.isEmpty() -> ShareSaveCompletion.UNCONFIRMED
                    work.confirmedSources.isNotEmpty() && (unknown.isNotEmpty() || work.namingSkipped > 0 || work.pendingRenames.isNotEmpty()) -> ShareSaveCompletion.PARTIAL
                    failed.isNotEmpty() || notAttempted.isNotEmpty() -> ShareSaveCompletion.FAILED
                    else -> ShareSaveCompletion.SUCCEEDED
                }
                val message = buildList {
                    add(when (completion) {
                        ShareSaveCompletion.SUCCEEDED -> "已转存 ${all.size} 项到「${work.command.target.name}」"
                        ShareSaveCompletion.FAILED -> work.refusalMessage ?: "未能提交分享转存"
                        ShareSaveCompletion.UNCONFIRMED -> "转存结果待确认，请先检查任务与实际文件"
                        ShareSaveCompletion.PARTIAL -> "已确认转存 ${work.confirmedSources.size} / ${all.size} 项到「${work.command.target.name}」"
                    })
                    if (unknown.isNotEmpty() && completion == ShareSaveCompletion.PARTIAL) add("${unknown.size} 项待确认")
                    if (work.renamedCount > 0) add("已规范命名 ${work.renamedCount} 项")
                    if (work.namingSkipped > 0) add("${work.namingSkipped} 项名称未处理")
                    if (work.pendingRenames.isNotEmpty()) add("${work.pendingRenames.size} 项改名待确认")
                }.joinToString("，")
                val result = ShareSaveResult(completion, message, work.confirmedSources, unknown, failed, notAttempted, now())
                val pendingIds = work.pendingRenames.mapTo(HashSet()) { it.id }
                work = work.copy(phase = ShareSavePhase.FINISHED, result = result,
                    beforeIds = work.beforeIds.takeIf { unknown.isNotEmpty() },
                    renameGoals = work.renameGoals.filterKeys { it in pendingIds },
                    renameSources = work.renameSources.filterKeys { it in pendingIds },
                    recordedRenameIds = work.recordedRenameIds.intersect(pendingIds))
                jobs.update { if (it[work.id] === execution) it - work.id else it }
                publish()
                _works.update { allWorks ->
                    val kept = allWorks.filter { it.result != null && it.result.unconfirmedSources.isEmpty() && it.pendingRenames.isEmpty() }.takeLast(100).mapTo(HashSet()) { it.id }
                    allWorks.filter { it.isRunning || it.result?.unconfirmedSources?.isNotEmpty() == true || it.pendingRenames.isNotEmpty() || it.id in kept }
                }
                _outcomes.tryEmit(ShareSaveOutcome(work.account, work.id, work.title, result))
            }
            try {
                check(); publish()
                if (token != null) {
                    if (work.command.canonicalNames) {
                        val before = access.listFolder(work.command.target.id)
                        check()
                        work = work.copy(beforeIds = before.getOrNull()?.mapTo(HashSet()) { it.id }, baselineAvailable = before.isSuccess)
                    }
                    phase(ShareSavePhase.SUBMITTING)
                    submitted = true
                    val response = access.submitShare(work.command.shareId, token, work.command.sources.map { it.id },
                        work.command.target.id, work.command.ancestors)
                    check()
                    val restored = response.getOrNull()
                    val error = response.exceptionOrNull()
                    val unavailable = error as? ShareUnavailableException
                    val ownShare = error is PikPakException && error.errorMessage == "file_restore_own"
                    work = work.copy(remoteTaskId = restored?.restoreTaskId?.takeIf { it.isNotEmpty() }, copyState = when {
                        restored?.restoreStatus == "RESTORE_COMPLETE" -> ShareCopyState.COMPLETE
                        unavailable != null || ownShare || error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422) -> ShareCopyState.REFUSED
                        else -> ShareCopyState.UNKNOWN
                    }, needsPassCode = unavailable?.needsPassCode == true, refusalMessage = when {
                        ownShare -> "无法转存自己发布的分享"
                        unavailable?.needsPassCode == true -> "提取码需要重新验证，尚未转存"
                        unavailable != null -> "分享已失效，尚未转存"
                        else -> null
                    })
                    publish()
                }
                val taskId = work.remoteTaskId
                if (taskId != null && work.copyState != ShareCopyState.COMPLETE && work.copyState != ShareCopyState.REFUSED) {
                    phase(ShareSavePhase.TRACKING)
                    var polls = 0
                    while (polls++ < 60) {
                        val task = access.task(taskId).getOrNull()
                        check()
                        if (task == null) break
                        work = work.copy(remoteProgress = task.progress)
                        if (task.phase == TaskPhase.COMPLETE || task.phase == TaskPhase.ERROR) {
                            work = work.copy(copyState = if (task.phase == TaskPhase.COMPLETE) ShareCopyState.COMPLETE else ShareCopyState.ERROR)
                            break
                        }
                        publish()
                        delay(pollDelay)
                    }
                }
                if (work.copyState == ShareCopyState.COMPLETE) work = work.copy(confirmedSources = work.sourceIds)
                phase(ShareSavePhase.IDENTIFYING)
                // 来源字段只能关联文件，不能证明本次请求已执行或目录整树已完成。
                var listing = if (work.copyState != ShareCopyState.COMPLETE || work.beforeIds == null) null else access.listFolder(work.command.target.id).getOrNull()
                check()
                var outputs = if (listing == null) emptyMap() else associate(work, listing)
                if (token != null && work.command.canonicalNames && outputs.isNotEmpty()) {
                    delay(identityDelay)
                    val confirmedListing = access.listFolder(work.command.target.id).getOrNull()
                    check()
                    val second = confirmedListing?.let { associate(work, it) }.orEmpty()
                    outputs = outputs.filter { (source, file) -> second[source]?.id == file.id }.mapValues { (source, _) -> second.getValue(source) }
                    listing = confirmedListing
                }
                work = work.copy(associatedOutputs = work.associatedOutputs + outputs.mapValues { it.value.id })
                if (token != null && work.command.canonicalNames && work.copyState != ShareCopyState.REFUSED) {
                    fun needsNaming(source: ShareSource) = source.folder || canonicalAvNameOf(source.name)?.let { it != source.name } == true
                    val eligible = work.command.sources.count(::needsNaming)
                    val identifiedEligible = work.command.sources.count { it.id in outputs && needsNaming(it) }
                    if (work.copyState == ShareCopyState.COMPLETE) phase(ShareSavePhase.NAMING)
                    val naming = if (work.copyState == ShareCopyState.COMPLETE)
                        nameShareOutputs(repository, access, work.command.target.id, outputs.values.toList(), listing.orEmpty())
                    else ShareNamingResult(emptyList(), emptyList(), 0, 0, emptyMap(), emptyMap())
                    check()
                    record(naming.renamed)
                    val rootSources = work.associatedOutputs.entries.associate { it.value to it.key }
                    work = work.copy(renamedCount = naming.succeeded, namingSkipped = eligible - identifiedEligible + naming.skipped,
                        pendingRenames = naming.unconfirmed, renameGoals = naming.goals,
                        renameSources = naming.roots.mapNotNull { (id, root) -> rootSources[root]?.let { id to it } }.toMap())
                } else if (token == null && work.pendingRenames.isNotEmpty()) {
                    val confirmedNames = mutableListOf<DriveChangeJournal.Renamed>()
                    for (rename in work.pendingRenames) {
                        val actual = access.detail(rename.id).getOrNull()
                        check()
                        if (actual != null && !actual.trashed && actual.name == rename.newName) confirmedNames += rename
                    }
                    record(confirmedNames)
                    val completedGoals = confirmedNames.filter { it.newName == work.renameGoals[it.id] }.mapTo(HashSet()) { it.id }
                    val incompleteGoals = confirmedNames.filter { it.newName != work.renameGoals[it.id] }.mapTo(HashSet()) { it.id }
                    work = work.copy(pendingRenames = work.pendingRenames.filterNot { it in confirmedNames },
                        renamedCount = work.renamedCount + completedGoals.size, namingSkipped = work.namingSkipped + incompleteGoals.size)
                }
                check()
                access.publish(DriveChange.ContentsChanged(setOf(work.command.target.id)))
                finish()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                check()
                PikoLog.w("ShareSave", "转存处理异常：阶段 ${work.phase}，${error::class.simpleName}")
                if (work.phase == ShareSavePhase.NAMING) work = work.copy(namingSkipped = maxOf(1, work.namingSkipped))
                finish()
            }
            finally { jobs.update { if (it[work.id] === execution) it - work.id else it } }
        }
        jobs.update { it + (initial.id to job) }
        job.start()
    }

    private fun associate(work: ShareSaveWork, listing: List<FileStat>): Map<String, FileStat> {
        val before = work.beforeIds ?: return emptyMap()
        val groups = listing.filter { !it.trashed && it.id !in before && it.params["original_share_id"] == work.command.shareId }
            .groupBy { it.params["original_file_id"] }
        return work.command.sources.mapNotNull { source ->
            val file = groups[source.id]?.singleOrNull()?.takeIf { it.isFolder == source.folder &&
                (source.folder || ((source.hash.isBlank() || source.hash.equals(it.hash, true)) && source.bytes == it.sizeBytes)) }
            file?.let { source.id to it }
        }.toMap()
    }
    private fun now() = Clock.System.now().toEpochMilliseconds()
}
