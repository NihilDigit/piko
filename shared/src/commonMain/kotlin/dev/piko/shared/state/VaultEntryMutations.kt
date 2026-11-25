package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEdit
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlin.time.Clock

sealed interface VaultEntryEditCommand {
    val entries: Map<String, Set<String>>
    val title: String
    data class Rename(val folderId: String, val entryId: String, val name: String) : VaultEntryEditCommand {
        override val entries get() = mapOf(folderId to setOf(entryId))
        override val title get() = tr("将归档条目重命名为「$name」", "Rename archive item to “$name”", "アーカイブ項目の名前を「$name」に変更", "보관 항목 이름을 “$name”(으)로 변경", "將歸檔項目重新命名為「$name」")
    }
    data class RenameMany(val renamesByFolder: Map<String, Map<String, String>>) : VaultEntryEditCommand {
        override val entries get() = renamesByFolder.mapValues { it.value.keys }
        override val title get() = tr("重命名 ${renamesByFolder.values.sumOf { it.size }} 项归档条目", "Rename ${renamesByFolder.values.sumOf { it.size }} archive items", "${renamesByFolder.values.sumOf { it.size }} 件のアーカイブ項目の名前を変更", "보관 항목 ${renamesByFolder.values.sumOf { it.size }}개 이름 변경", "重新命名 ${renamesByFolder.values.sumOf { it.size }} 項歸檔項目")
    }
    data class Move(override val entries: Map<String, Set<String>>, val targetFolderId: String, val targetName: String = "") : VaultEntryEditCommand {
        override val title get() = tr("移动 ${entries.values.sumOf { it.size }} 项归档条目", "Move ${entries.values.sumOf { it.size }} archive items", "${entries.values.sumOf { it.size }} 件のアーカイブ項目を移動", "보관 항목 ${entries.values.sumOf { it.size }}개 이동", "移動 ${entries.values.sumOf { it.size }} 項歸檔項目")
    }
    data class Remove(override val entries: Map<String, Set<String>>) : VaultEntryEditCommand {
        override val title get() = tr("从归档移除 ${entries.values.sumOf { it.size }} 项", "Remove ${entries.values.sumOf { it.size }} archive items", "${entries.values.sumOf { it.size }} 項目をアーカイブから削除", "보관 항목 ${entries.values.sumOf { it.size }}개 삭제", "從歸檔移除 ${entries.values.sumOf { it.size }} 項")
    }
}

enum class VaultEntryEditCompletion { SUCCEEDED, PARTIAL, FAILED }
data class VaultEntryEditResult(val completion: VaultEntryEditCompletion, val message: String,
    val confirmed: Map<String, Set<String>>, val failed: Map<String, Set<String>>,
    val undoChangeIds: List<Long>, val finishedAtMs: Long)
data class VaultEntryEditOutcome(val account: String, val id: Long, val title: String, val result: VaultEntryEditResult)
private val vaultEntryEditIds = MutableStateFlow(0L)

/** 归档条目编辑沿用可信写入；失败后的核对不重新写入清单，撤销只涵盖已确认部分。 */
class VaultEntryMutations(private val repository: PikoDriveRepository, private val scope: CoroutineScope) {
    private val executionScopes = AccountExecutionScopes(repository, scope)
    private val _outcomes = MutableSharedFlow<VaultEntryEditOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()
    internal fun submit(request: VaultEntryEditCommand): Long? {
        val command = when (request) {
            is VaultEntryEditCommand.Rename -> request.copy(name = request.name.trim())
            is VaultEntryEditCommand.RenameMany -> request.copy(
                renamesByFolder = request.renamesByFolder.mapValues { (_, map) -> map.mapValues { it.value.trim() } },
            )
            is VaultEntryEditCommand.Move -> request.copy(entries = request.entries.mapValues { it.value.toSet() })
            is VaultEntryEditCommand.Remove -> request.copy(entries = request.entries.mapValues { it.value.toSet() })
        }
        val account = repository.currentAccount ?: return null
        val store = repository.vaultAccess(account) ?: return null
        val id = vaultEntryEditIds.getAndUpdate { it + 1 } + 1
        execute(id, account, command, store)
        return id
    }
    fun clear(account: String) = Unit
    private fun execute(id: Long, account: String, command: VaultEntryEditCommand, store: VaultStore) {
        executionScopes.forAccount(account).launch {
            val confirmed = mutableMapOf<String, Set<String>>()
            val remaining = command.entries.toMutableMap()
            val before = mutableMapOf<String, List<VaultEntry>>()
            val inverses = mutableListOf<Long>()
            val opName = when (command) {
                is VaultEntryEditCommand.Remove -> "remove"
                is VaultEntryEditCommand.Rename -> "rename"
                is VaultEntryEditCommand.RenameMany -> "renameMany"
                is VaultEntryEditCommand.Move -> "move"
            }
            suspend fun check() { currentCoroutineContext().ensureActive(); check(repository.currentAccount == account) }
            fun record(folder: String, ids: Set<String>, before: List<VaultEntry>) {
                val touched = before.filter { it.id in ids }
                val revert: VaultEdit? = when (val command = command) {
                    is VaultEntryEditCommand.Remove -> touched.takeIf { it.isNotEmpty() }?.let(VaultEdits::add)
                    is VaultEntryEditCommand.Rename -> touched.firstOrNull()?.takeIf { it.name != command.name }?.let { VaultEdits.rename(it.id, it.name) }
                    is VaultEntryEditCommand.RenameMany -> {
                        val targets = command.renamesByFolder[folder].orEmpty()
                        val prevNames = touched.mapNotNull { entry ->
                            entry.takeIf { targets[entry.id] != null && entry.name != targets[entry.id] }?.let { entry.id to entry.name }
                        }.toMap()
                        prevNames.takeIf { it.isNotEmpty() }?.let(VaultEdits::renameAll)
                    }
                    is VaultEntryEditCommand.Move -> null
                }
                if (revert != null && repository.currentAccount == account) {
                    val change = DriveChangeJournal.Change.Vault(mapOf(folder to revert), tr("已处理归档条目", "Archive items processed", "アーカイブ項目を処理しました", "보관 항목을 처리했습니다", "已處理歸檔項目"))
                    repository.changes.record(change)
                    inverses += change.id
                }
            }
            fun confirm(folder: String, ids: Set<String>, after: List<VaultEntry>) {
                val already = confirmed[folder].orEmpty()
                val fresh = ids.filterTo(HashSet()) { id ->
                    when (val command = command) {
                        is VaultEntryEditCommand.Remove -> after.none { it.id == id }
                        is VaultEntryEditCommand.Rename -> after.any { it.id == id && it.name == command.name }
                        is VaultEntryEditCommand.RenameMany -> {
                            val expected = command.renamesByFolder[folder]?.get(id)
                            expected != null && after.any { it.id == id && it.name == expected }
                        }
                        is VaultEntryEditCommand.Move -> false
                    }
                } - already
                if (fresh.isNotEmpty()) {
                    confirmed[folder] = already + fresh
                    when (val command = command) {
                        is VaultEntryEditCommand.Remove -> repository.vaultStars.unstarByEntryIds(fresh)
                        is VaultEntryEditCommand.Rename -> if (command.entryId in fresh) {
                            repository.vaultStars.renameEntry(command.entryId, command.name, expectedAccount = account)
                        }
                        is VaultEntryEditCommand.RenameMany -> {
                            val targets = command.renamesByFolder[folder].orEmpty()
                            val renamedStars = fresh.mapNotNull { entryId -> targets[entryId]?.let { entryId to it } }.toMap()
                            repository.vaultStars.renameEntries(renamedStars, expectedAccount = account)
                        }
                        is VaultEntryEditCommand.Move -> Unit
                    }
                    record(folder, fresh, before[folder].orEmpty())
                }
                val pending = ids - confirmed[folder].orEmpty()
                if (pending.isEmpty()) {
                    remaining.remove(folder)
                } else remaining[folder] = pending
            }
            if (command is VaultEntryEditCommand.Move) {
                for ((sourceFolder, ids) in command.entries) {
                    if (sourceFolder == command.targetFolderId) {
                        confirmed[sourceFolder] = ids
                        remaining.remove(sourceFolder)
                        continue
                    }
                    val committedSource = store.committed(sourceFolder)
                        .logFailure(TAG, "Vault move failed to read source ${logFolder(sourceFolder)} (work=$id)")
                        .getOrNull() ?: break
                    check()
                    val moving = committedSource.filter { it.id in ids && it.isArchived }
                    if (moving.isEmpty()) {
                        confirmed[sourceFolder] = ids
                        remaining.remove(sourceFolder)
                        continue
                    }
                    val movingIds = moving.mapTo(HashSet()) { it.id }
                    // 先写入目标目录，再从源目录移除，避免中途网络失败导致归档引用丢失。
                    val addWrite = store.update(command.targetFolderId, VaultEdits.add(moving))
                    check()
                    if (addWrite.isFailure) {
                        addWrite.logFailure(TAG, "Vault move failed adding to ${logFolder(command.targetFolderId)} (work=$id)")
                        break
                    }
                    val appliedAdd = addWrite.getOrThrow()
                    val targetBefore = appliedAdd.before
                    val targetAfter = appliedAdd.after
                    val landedIds = movingIds.filterTo(HashSet()) { entryId -> targetAfter.any { it.id == entryId && it.isArchived } }
                    if (landedIds.isEmpty()) break
                    repository.vaultStars.moveEntries(landedIds, command.targetFolderId, expectedAccount = account)
                    val removeWrite = store.update(sourceFolder, VaultEdits.remove(landedIds))
                    check()
                    if (removeWrite.isFailure) {
                        removeWrite.logFailure(TAG, "Vault move failed removing from ${logFolder(sourceFolder)} (work=$id)")
                        break
                    }
                    val sourceAfter = removeWrite.getOrThrow().after
                    val removedIds = landedIds.filterTo(HashSet()) { entryId -> sourceAfter.none { it.id == entryId } }
                    if (removedIds.isNotEmpty()) {
                        confirmed[sourceFolder] = confirmed[sourceFolder].orEmpty() + removedIds
                        val movedEntries = moving.filter { it.id in removedIds }
                        val movedKeys = movedEntries.mapTo(HashSet()) { it.gcid to it.name }
                        val overwrittenInTarget = targetBefore.filter { entry ->
                            entry.id !in removedIds && (entry.gcid to entry.name) in movedKeys
                        }
                        val targetRevert: VaultEdit = if (overwrittenInTarget.isEmpty()) {
                            VaultEdits.remove(removedIds)
                        } else {
                            { current -> VaultEdits.add(overwrittenInTarget)(VaultEdits.remove(removedIds)(current)) }
                        }
                        if (repository.currentAccount == account) {
                            val change = DriveChangeJournal.Change.Vault(
                                reverts = emptyMap(),
                                summary = command.title,
                                orderedReverts = listOf(
                                    sourceFolder to VaultEdits.add(movedEntries),
                                    command.targetFolderId to targetRevert,
                                ),
                            )
                            repository.changes.record(change)
                            inverses += change.id
                        }
                    }
                    val missingInSource = ids - movingIds
                    val doneInSource = confirmed[sourceFolder].orEmpty() + missingInSource
                    val pendingInSource = ids - doneInSource
                    if (pendingInSource.isEmpty()) {
                        remaining.remove(sourceFolder)
                    } else {
                        remaining[sourceFolder] = pendingInSource
                        break
                    }
                }
            } else {
                for ((folder, ids) in command.entries) {
                    val edit = when (val command = command) {
                        is VaultEntryEditCommand.Remove -> VaultEdits.remove(ids)
                        is VaultEntryEditCommand.Rename -> VaultEdits.rename(command.entryId, command.name)
                        is VaultEntryEditCommand.RenameMany -> VaultEdits.renameAll(command.renamesByFolder[folder].orEmpty())
                        is VaultEntryEditCommand.Move -> error("unreachable")
                    }
                    val write = store.update(folder, edit)
                    check()
                    if (write.isSuccess) {
                        val applied = write.getOrThrow()
                        before[folder] = applied.before.filter { it.id in ids }
                        confirm(folder, ids, applied.after)
                    } else {
                        write.logFailure(TAG, "Vault manifest $opName failed in ${logFolder(folder)} (work=$id, items=${ids.size})")
                        break
                    }
                }
            }
            check()
            val completion = when {
                confirmed.isNotEmpty() && remaining.isNotEmpty() -> VaultEntryEditCompletion.PARTIAL
                remaining.isNotEmpty() -> VaultEntryEditCompletion.FAILED
                else -> VaultEntryEditCompletion.SUCCEEDED
            }
            val message = when (completion) {
                VaultEntryEditCompletion.SUCCEEDED -> tr("已完成归档条目编辑", "Archive item edits completed", "アーカイブ項目の編集が完了しました", "보관 항목 편집 완료", "已完成歸檔項目編輯")
                VaultEntryEditCompletion.PARTIAL -> tr("已完成 ${confirmed.values.sumOf { it.size }} 项，${remaining.values.sumOf { it.size }} 项未完成", "${confirmed.values.sumOf { it.size }} completed, ${remaining.values.sumOf { it.size }} incomplete", "${confirmed.values.sumOf { it.size }} 項目完了、${remaining.values.sumOf { it.size }} 項目未完了", "${confirmed.values.sumOf { it.size }}개 완료, ${remaining.values.sumOf { it.size }}개 미완료", "已完成 ${confirmed.values.sumOf { it.size }} 項，${remaining.values.sumOf { it.size }} 項未完成")
                VaultEntryEditCompletion.FAILED -> tr("归档条目编辑失败，请刷新后重试", "Archive item edits failed. Refresh and try again", "アーカイブ項目を編集できませんでした。更新して再試行してください", "보관 항목 편집에 실패했습니다. 새로고침 후 다시 시도해 주세요", "歸檔條目編輯失敗，請重新整理後重試")
            }
            val result = VaultEntryEditResult(completion, message, confirmed.toMap(), remaining.toMap(), inverses.toList(), now())
            _outcomes.tryEmit(VaultEntryEditOutcome(account, id, command.title, result))
        }
    }
    private fun now() = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TAG = "VaultMutations"
    }
}
