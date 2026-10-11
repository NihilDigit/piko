package dev.piko.shared.state

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEdit
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.VaultWriteUnconfirmed
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

sealed interface VaultEntryEditCommand {
    val entries: Map<String, Set<String>>
    val title: String
    data class Rename(val folderId: String, val entryId: String, val name: String) : VaultEntryEditCommand {
        override val entries get() = mapOf(folderId to setOf(entryId))
        override val title get() = "归档条目重命名为「$name」"
    }
    data class Remove(override val entries: Map<String, Set<String>>) : VaultEntryEditCommand {
        override val title get() = "移除 ${entries.values.sumOf { it.size }} 项归档条目"
    }
}

enum class VaultEntryEditCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED }
data class VaultEntryEditResult(val completion: VaultEntryEditCompletion, val message: String,
    val confirmed: Map<String, Set<String>>, val unconfirmed: Map<String, Set<String>>,
    val notAttempted: Map<String, Set<String>>, val undoChangeIds: List<Long>, val finishedAtMs: Long)
data class VaultEntryEditWork(val id: Long, val account: String, val command: VaultEntryEditCommand, val createdAtMs: Long,
    val before: Map<String, List<VaultEntry>> = emptyMap(), val result: VaultEntryEditResult? = null,
    val activeFolders: Set<String> = command.entries.keys.toSet()) {
    val isRunning: Boolean get() = result == null
    val pendingFolders: Set<String> get() = if (isRunning) activeFolders else result?.unconfirmed.orEmpty().keys
}
data class VaultEntryEditOutcome(val account: String, val id: Long, val title: String, val result: VaultEntryEditResult)
private val vaultEntryEditIds = MutableStateFlow(0L)

/** 归档条目编辑沿用可信写入；失败后的核对不重新写入清单，撤销只涵盖已确认部分。 */
class VaultEntryEditSession(private val repository: PikoDriveRepository, private val scope: CoroutineScope) {
    private val executionScopes = AccountExecutionScopes(repository, scope)
    private val _works = MutableStateFlow<List<VaultEntryEditWork>>(emptyList())
    val works = _works.asStateFlow()
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private val _outcomes = MutableSharedFlow<VaultEntryEditOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()

    internal fun submit(command: VaultEntryEditCommand): Long? {
        if (!scope.isActive) return null
        val account = repository.currentAccount ?: return null
        val store = repository.vaultAccess(account) ?: return null
        val frozen = when (command) {
            is VaultEntryEditCommand.Rename -> command.copy(name = command.name.trim())
            is VaultEntryEditCommand.Remove -> command.copy(entries = command.entries.mapValues { it.value.toSet() })
        }
        val work = VaultEntryEditWork(vaultEntryEditIds.getAndUpdate { it + 1 } + 1, account, frozen, now())
        _works.update { it + work }
        execute(work, store, recheck = false)
        return work.id
    }

    fun canRecheck(id: Long): Boolean = _works.value.any { it.id == id && it.account == repository.currentAccount && it.result?.unconfirmed?.isNotEmpty() == true }
    fun recheck(id: Long): Boolean {
        if (!canRecheck(id) || jobs.value[id]?.isActive == true) return false
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        val store = repository.vaultAccess(work.account) ?: return false
        execute(work, store, recheck = true)
        return true
    }

    fun forget(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.isRunning || work.result?.undoChangeIds.orEmpty().any { repository.changes.state.value.undo[it]?.status == DriveChangeJournal.UndoStatus.RUNNING }) return false
        _works.update { it.filterNot { item -> item.id == id } }
        return true
    }

    fun clear(account: String) {
        val ids = _works.value.filter { it.account == account }.mapTo(HashSet()) { it.id }
        _works.update { it.filterNot { work -> work.account == account } }
        jobs.getAndUpdate { it - ids }.filterKeys { it in ids }.values.forEach { it.cancel() }
    }

    private fun active(work: VaultEntryEditWork) = repository.currentAccount == work.account && _works.value.any { it.id == work.id }

    private fun execute(initial: VaultEntryEditWork, store: VaultStore, recheck: Boolean) {
        val job = executionScopes.forAccount(initial.account).launch(start = CoroutineStart.LAZY) {
            val execution = currentCoroutineContext()[Job]
            var work = initial.copy(result = null, activeFolders = if (recheck) initial.result!!.unconfirmed.keys else initial.command.entries.keys)
            val confirmed = initial.result?.confirmed.orEmpty().toMutableMap()
            val unknown = initial.result?.unconfirmed.orEmpty().toMutableMap()
            val remaining = initial.result?.notAttempted.orEmpty().toMutableMap()
            val inverses = initial.result?.undoChangeIds.orEmpty().toMutableList()
            var failureMessage: String? = null
            fun publish() { _works.update { all -> all.map { if (it.id == work.id) work else it } } }
            suspend fun check() { currentCoroutineContext().ensureActive(); check(active(work)) }
            fun record(folder: String, ids: Set<String>, before: List<VaultEntry>) {
                val touched = before.filter { it.id in ids }
                val revert: VaultEdit? = when (val command = work.command) {
                    is VaultEntryEditCommand.Remove -> touched.takeIf { it.isNotEmpty() }?.let(VaultEdits::add)
                    is VaultEntryEditCommand.Rename -> touched.firstOrNull()?.takeIf { it.name != command.name }?.let { VaultEdits.rename(it.id, it.name) }
                }
                if (revert != null && active(work)) {
                    val change = DriveChangeJournal.Change.Vault(mapOf(folder to revert), "已处理归档条目")
                    repository.changes.record(change)
                    inverses += change.id
                }
            }
            fun confirm(folder: String, ids: Set<String>, after: List<VaultEntry>) {
                val already = confirmed[folder].orEmpty()
                val fresh = ids.filterTo(HashSet()) { id ->
                    when (val command = work.command) {
                        is VaultEntryEditCommand.Remove -> after.none { it.id == id }
                        is VaultEntryEditCommand.Rename -> after.any { it.id == id && it.name == command.name }
                    }
                } - already
                if (fresh.isNotEmpty()) {
                    confirmed[folder] = already + fresh
                    record(folder, fresh, work.before[folder].orEmpty())
                }
                val pending = ids - confirmed[folder].orEmpty()
                if (pending.isEmpty()) {
                    unknown.remove(folder)
                    work = work.copy(activeFolders = work.activeFolders - folder)
                    publish()
                } else unknown[folder] = pending
            }
            try {
                if (!active(work)) return@launch
                publish()
                if (recheck) {
                    for ((folder, ids) in initial.result!!.unconfirmed) {
                        val current = store.committed(folder).getOrNull()
                        check()
                        if (current != null) confirm(folder, ids, current)
                    }
                } else {
                    remaining += work.command.entries
                    for ((folder, ids) in work.command.entries) {
                        val before = store.committed(folder).getOrNull()
                        check()
                        if (before == null) break
                        if (work.command is VaultEntryEditCommand.Rename && before.none { it.id == (work.command as VaultEntryEditCommand.Rename).entryId }) {
                            failureMessage = "归档条目已不存在，尚未提交改名"
                            break
                        }
                        work = work.copy(before = work.before + (folder to before.filter { it.id in ids })); publish()
                        remaining.remove(folder)
                        unknown[folder] = ids
                        val edit = when (val command = work.command) {
                            is VaultEntryEditCommand.Remove -> VaultEdits.remove(ids)
                            is VaultEntryEditCommand.Rename -> VaultEdits.rename(command.entryId, command.name)
                        }
                        val write = store.update(folder, edit).logFailure("VaultEdit", "归档清单编辑未确认，核对实际清单")
                        check()
                        if (write.isSuccess) {
                            val applied = write.getOrThrow()
                            work = work.copy(before = work.before + (folder to applied.before.filter { it.id in ids }))
                            confirm(folder, ids, applied.after)
                        } else {
                            val attempted = (write.exceptionOrNull() as? VaultWriteUnconfirmed)?.attempted
                            if (attempted == null) {
                                unknown.remove(folder)
                                remaining[folder] = ids
                                failureMessage = "未能提交归档清单编辑"
                                break
                            }
                            work = work.copy(before = work.before + (folder to attempted.before.filter { it.id in ids }))
                            store.committed(folder).getOrNull()?.let { after -> check(); confirm(folder, ids, after) }
                            break
                        }
                    }
                }
                check()
                val completion = when {
                    unknown.isNotEmpty() && confirmed.isEmpty() -> VaultEntryEditCompletion.UNCONFIRMED
                    unknown.isNotEmpty() || (confirmed.isNotEmpty() && remaining.isNotEmpty()) -> VaultEntryEditCompletion.PARTIAL
                    remaining.isNotEmpty() -> VaultEntryEditCompletion.FAILED
                    else -> VaultEntryEditCompletion.SUCCEEDED
                }
                val message = when (completion) {
                    VaultEntryEditCompletion.SUCCEEDED -> "已完成归档条目编辑"
                    VaultEntryEditCompletion.PARTIAL -> "已确认 ${confirmed.values.sumOf { it.size }} 项，${unknown.values.sumOf { it.size }} 项待确认，${remaining.values.sumOf { it.size }} 项未执行"
                    VaultEntryEditCompletion.FAILED -> failureMessage ?: "未能读取可信清单，尚未提交编辑"
                    VaultEntryEditCompletion.UNCONFIRMED -> "清单编辑结果尚未确认，重新查询不会再次写入"
                }
                val result = VaultEntryEditResult(completion, message, confirmed.toMap(), unknown.toMap(), remaining.toMap(), inverses.toList(), now())
                work = work.copy(result = result, before = work.before.mapValues { (folder, entries) -> entries.filter { it.id in unknown[folder].orEmpty() } }.filterValues { it.isNotEmpty() })
                jobs.update { if (it[work.id] === execution) it - work.id else it }
                publish()
                _works.update { all ->
                    val kept = all.filter { it.result != null && it.result.unconfirmed.isEmpty() }.takeLast(100).mapTo(HashSet()) { it.id }
                    all.filter { it.isRunning || it.result?.unconfirmed?.isNotEmpty() == true || it.id in kept }
                }
                _outcomes.tryEmit(VaultEntryEditOutcome(work.account, work.id, work.command.title, result))
            } finally { jobs.update { if (it[work.id] === execution) it - work.id else it } }
        }
        jobs.update { it + (initial.id to job) }
        job.start()
    }
    private fun now() = Clock.System.now().toEpochMilliseconds()
}
