package dev.piko.shared.presentation

import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.affectedFileIds
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.affectedManifestFolders
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** 菜单、快捷键和拖放共用的动作入口。只阻止已知冲突，不把不同文件串成一个执行队列。 */
class FileCommands(
    private val repository: PikoDriveRepository,
    val session: FileWriteSession,
    private val account: String? = repository.currentAccount,
    private val cleanup: dev.piko.shared.state.TrashSession? = null,
    private val vault: dev.piko.shared.state.FolderVaultSession? = null,
    private val vaultEdits: dev.piko.shared.state.VaultEntryEditSession? = null,
    private val shareSaves: dev.piko.shared.state.ShareSaveSession? = null,
    private val receiving: ReceivingActivity? = null,
    private val acceptsActions: () -> Boolean = { true },
) {
    val receivingChanges: Flow<Unit> get() = merge(
        session.works.map { Unit }, repository.changes.state.map { Unit },
        shareSaves?.works?.map { Unit } ?: emptyFlow(),
        receiving?.changes ?: emptyFlow(),
        snapshotFlow { vault?.activeTargets to cleanup?.projectedIds?.takeIf { cleanup.progress != null || cleanup.isUndoing } }.map { Unit },
    )

    /** 新文件写入可与星标、目录改名及无关文件处理并行；目标整树的处置需要先结束。 */
    fun receivingAvailability(folder: String): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked("当前工作区已结束")
        if (!isDriveFolderId(folder)) return ActionAvailability.Blocked("请选择网盘中的目标文件夹")
        if (receiving?.exclusiveRoots(account)?.any { contains(it, folder) } == true)
            return ActionAvailability.Blocked("目标目录正在裁剪整包内容，请完成后再保存")
        if (repository.changes.state.value.undo.values.any { undo ->
                undo.activeTreeIds.any { contains(it, folder) }
            }) return ActionAvailability.Blocked("目标目录正在撤销相关改动，请完成后再保存")
        vault?.activeTargets?.let { targets ->
            if ((targets.roots + targets.files).any { contains(it, folder) })
                return ActionAvailability.Blocked("目标目录正在归档或恢复，请完成后再保存")
        }
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && cleanup.projectedIds.any { contains(it, folder) })
            return ActionAvailability.Blocked("目标目录正在移入回收站或恢复，请完成后再保存")
        if (shareBlocked(emptyList(), folder, false)) return ActionAvailability.Blocked("目标目录中的分享文件正在规范命名，请先在任务页处理")
        val conflict = session.works.value.firstOrNull { work ->
            work.account == account && (work.isRunning || work.result?.unconfirmed?.isNotEmpty() == true) &&
                changesTree(work.command) && pendingCommand(work).ids.any { contains(it, folder) }
        }
        return if (conflict == null) ActionAvailability.Available else ActionAvailability.Blocked(
            if (conflict.isRunning) "目标目录正在移动、删除或恢复，请完成后再保存" else "目标目录的上次修改尚未确认，请先在任务页核对",
        )
    }

    fun availability(command: FileWriteCommand): ActionAvailability {
        fun blocked(reason: String) = ActionAvailability.Blocked(reason)
        if (!acceptsActions() || account == null || repository.currentAccount != account) return blocked("当前浏览上下文已结束，请重新打开")
        if (command.ids.any {
                it.isBlank() || VaultEntry.isVaulted(it) || ArchiveEntryId.isEntry(it) ||
                    ArchiveLocation.of(it) != null || ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null
            }) {
            return blocked("此操作只适用于网盘中的真实文件")
        }
        if (command !is FileWriteCommand.CreateFolder && command.ids.isEmpty()) return blocked("请先选择文件")
        if (command.target?.let { !isDriveFolderId(it.id) } == true) return blocked("请选择网盘中的目标文件夹")
        if ((command is FileWriteCommand.CreateFolder && command.name.isBlank()) ||
            (command is FileWriteCommand.Rename && command.name.isBlank())) return blocked("名称不能为空")
        if (command is FileWriteCommand.Move || command is FileWriteCommand.Copy) {
            val target = requireNotNull(command.target).id
            if (command.ids.any { contains(it, target) }) return blocked("不能将文件夹放入自身或其子目录")
        }
        if (command !is FileWriteCommand.Star && shareBlocked(command.ids, command.target?.id, command is FileWriteCommand.Rename))
            return blocked("相关文件正在转存或规范命名，或名称修改待确认，请先在任务页处理")
        if (command !is FileWriteCommand.Star &&
            (receivingBlocked(if (command is FileWriteCommand.Copy) emptyList() else command.ids, changesTree(command)) ||
                command.target?.let { target -> receiving?.exclusiveRoots(account)?.any { contains(it, target.id) } } == true))
            return blocked("相关文件或目录有保存、上传或解压任务，请先在任务页完成、核对或移除相关任务")
        if (repository.changes.state.value.undo.values.any { undo -> overlaps(command.ids, undo.activeFileIds) ||
                command.target?.let { target -> undo.activeTreeIds.any { contains(it, target.id) } } == true }) return blocked("相关文件正在撤销，完成后再试")
        if (changesTree(command) && repository.changes.state.value.undo.values.any { touchesFolders(command.ids, it.activeManifestFolders) }) return blocked("相关文件夹的归档清单正在撤销，完成后再试")
        val targets = vault?.activeTargets
        if (targets != null && (affects(command, targets.files) || command.ids.any { id -> targets.roots.any { contains(it, id) || contains(id, it) } } ||
            command.target?.let { folder -> targets.roots.any { contains(it, folder.id) } } == true ||
            (changesTree(command) && touchesFolders(command.ids, targets.manifests)))) return blocked("相关文件正在归档或恢复，完成后再试")
        if (changesTree(command) && vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(command.ids, it.pendingFolders) }) return blocked("相关文件夹的归档清单正在编辑或待确认，完成后再试")
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && affects(command, cleanup.projectedIds)) return blocked("相关文件正在移入回收站或恢复，完成后再试")
        val conflict = session.works.value.firstOrNull { work ->
            work.account == account && (work.isRunning || work.result?.unconfirmed?.isNotEmpty() == true) && conflicts(command, pendingCommand(work))
        }
        return if (conflict == null) ActionAvailability.Available else blocked(
            if (conflict.isRunning) "「${conflict.command.title}」正在处理相关文件，请完成后再试"
            else "相关文件的上次操作尚未确认，请先在任务页查询或检查结果",
        )
    }

    fun filesAvailability(ids: Collection<String>): ActionAvailability = filesAvailability(ids, tree = true)

    private fun filesAvailability(ids: Collection<String>, tree: Boolean): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked("当前工作区已结束")
        if (shareBlocked(ids, null, !tree)) return ActionAvailability.Blocked("相关文件有进行中或待确认的分享转存，请先在任务页处理")
        if (receivingBlocked(ids, tree)) return ActionAvailability.Blocked("相关目录有保存、上传或解压任务，请先在任务页处理")
        if (repository.changes.state.value.undo.values.any { overlaps(ids, it.activeFileIds) }) return ActionAvailability.Blocked("相关文件正在撤销，完成后再试")
        if (repository.changes.state.value.undo.values.any { touchesFolders(ids, it.activeManifestFolders) }) return ActionAvailability.Blocked("相关归档清单正在撤销，完成后再试")
        vault?.activeTargets?.let { targets ->
            if (overlaps(ids, targets.files) || overlaps(ids, targets.roots) || touchesFolders(ids, targets.manifests)) return ActionAvailability.Blocked("相关文件正在归档或恢复，完成后再试")
        }
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(ids, it.pendingFolders) }) return ActionAvailability.Blocked("相关归档清单正在编辑或待确认，完成后再试")
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && overlaps(ids, cleanup.projectedIds)) return ActionAvailability.Blocked("相关文件正在移入回收站或恢复，完成后再试")
        val work = session.works.value.firstOrNull {
            it.account == account && (it.isRunning || it.result?.unconfirmed?.isNotEmpty() == true) &&
                pendingCommand(it).let { command -> overlaps(ids, command.ids) || command.target?.let { target -> ids.any { contains(it, target.id) } } == true }
        }
        return if (work == null) ActionAvailability.Available else ActionAvailability.Blocked("相关文件有进行中或待确认的操作，请先在任务页处理")
    }

    fun manifestAvailability(folders: Set<String>): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked("当前工作区已结束")
        if (repository.changes.state.value.undo.values.any { undo -> folders.any { it in undo.activeManifestFolders } || undo.activeFileIds.any { id -> folders.any { contains(id, it) } } }) return ActionAvailability.Blocked("相关归档清单正在撤销，完成后再试")
        vault?.activeTargets?.let { targets ->
            if (folders.any { it in targets.manifests || targets.roots.any { root -> contains(root, it) } }) return ActionAvailability.Blocked("相关文件夹正在归档或恢复，完成后再试")
        }
        if (session.works.value.any { it.account == account && (it.isRunning || it.result?.unconfirmed?.isNotEmpty() == true) && changesTree(it.command) && touchesFolders(pendingCommand(it).ids, folders) }) return ActionAvailability.Blocked("相关文件夹有进行中或待确认的修改，请先在任务页处理")
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && it.pendingFolders.any { folder -> folder in folders } }) return ActionAvailability.Blocked("同一归档清单正在编辑或待确认，请先在任务页处理")
        return ActionAvailability.Available
    }

    fun vaultAvailability(targets: dev.piko.shared.state.VaultWorkTargets): ActionAvailability {
        val files = filesAvailability(targets.roots + targets.files)
        if (files is ActionAvailability.Blocked) return files
        val manifests = manifestAvailability(targets.manifests)
        if (manifests is ActionAvailability.Blocked) return manifests
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(targets.roots, it.pendingFolders) }) {
            return ActionAvailability.Blocked("选中文件夹中的归档清单正在编辑或待确认，请先在任务页处理")
        }
        return ActionAvailability.Available
    }

    fun undoAvailability(change: DriveChangeJournal.Change): ActionAvailability {
        if (shareSaves?.works?.value.orEmpty().any { it.account == account && change.id in it.undoChangeIds && it.pendingRenames.isNotEmpty() })
            return ActionAvailability.Blocked("这次命名仍有待确认的步骤，请先在任务页查询结果")
        if (receivingBlocked(change.affectedFileIds(), change !is DriveChangeJournal.Change.Rename))
            return ActionAvailability.Blocked("相关目录有保存、上传或解压任务，请先在任务页处理")
        val files = filesAvailability(change.affectedFileIds(), tree = change !is DriveChangeJournal.Change.Rename)
        if (files is ActionAvailability.Blocked) return files
        return manifestAvailability(change.affectedManifestFolders())
    }

    fun undo(changeId: Long): FileCommandResult {
        val change = repository.changes.state.value.available.firstOrNull { it.id == changeId }
            ?: return FileCommandResult.Rejected("撤销记录已不可用，或正在撤销")
        return when (val state = undoAvailability(change)) {
            is ActionAvailability.Blocked -> FileCommandResult.Rejected(state.reason)
            ActionAvailability.Available -> if (repository.changes.undo(change)) FileCommandResult.Accepted(change.id)
                else FileCommandResult.Rejected("撤销记录已不可用，或正在撤销")
        }
    }

    private fun overlaps(a: Collection<String>, b: Collection<String>): Boolean =
        a.any { x -> b.any { y -> contains(x, y) || contains(y, x) } }

    private fun receivingBlocked(ids: Collection<String>, tree: Boolean): Boolean {
        val owner = account ?: return false
        val activity = receiving ?: return false
        val files = activity.files(owner)
        val targets = if (tree) activity.targets(owner) else emptySet()
        val exclusive = activity.exclusiveRoots(owner)
        return ids.any { it in files } || ids.any { id -> exclusive.any { contains(it, id) } || targets.any { contains(id, it) } }
    }

    private fun shareBlocked(ids: Collection<String>, target: String?, metadataOnly: Boolean): Boolean =
        shareSaves?.works?.value.orEmpty().filter { it.account == account }.any { work ->
            val pending = work.pendingRenames.mapTo(HashSet()) { it.id }
            val roots = work.command.sources.filter { it.folder && it.id in work.blockedSources }.mapNotNull { work.associatedOutputs[it.id] }
            val subjects = if (work.isRunning) work.associatedOutputs.values.toSet() + pending else pending
            val touches = ids.any { id -> roots.any { contains(it, id) } } ||
                if (work.isRunning) (if (metadataOnly) ids.any { it in subjects } else overlaps(ids, subjects)) || ids.any { newShareFile(work, it) } else if (metadataOnly) ids.any { it in pending }
                else ids.any { id -> (pending + roots).any { contains(id, it) } }
            touches || (target != null && roots.any { contains(it, target) }) || (work.isRunning && target != null && subjects.any { contains(it, target) }) ||
                (!metadataOnly && (work.isRunning || work.result?.unconfirmedSources?.isNotEmpty() == true) && ids.any { contains(it, work.command.target.id) })
        }

    private fun newShareFile(work: dev.piko.shared.state.ShareSaveWork, id: String): Boolean {
        val before = work.beforeIds ?: return false
        if (!work.command.canonicalNames) return false
        var candidate = id
        repeat(64) {
            val file = repository.knownFile(candidate) ?: return false
            if (file.id !in before && file.parentId == work.command.target.id && file.params["original_share_id"] == work.command.shareId &&
                file.params["original_file_id"] in work.sourceIds) return true
            if (file.parentId.isEmpty() || file.parentId == candidate) return false
            candidate = file.parentId
        }
        return false
    }

    private fun affects(command: FileWriteCommand, subjects: Set<String>): Boolean = overlaps(command.ids, subjects) ||
        command.target?.let { target -> subjects.any { contains(it, target.id) } } == true

    private fun touchesFolders(ids: Collection<String>, folders: Set<String>) = ids.any { id -> folders.any { contains(id, it) } }
    private fun changesTree(command: FileWriteCommand) = command is FileWriteCommand.Move || command is FileWriteCommand.Delete || command is FileWriteCommand.Restore

    private fun pendingCommand(work: dev.piko.shared.state.FileWriteWork): FileWriteCommand {
        if (work.isRunning) return work.command
        val ids = work.result?.unconfirmed.orEmpty().toList()
        return when (val command = work.command) {
            is FileWriteCommand.Move -> command.copy(ids = ids)
            is FileWriteCommand.Copy -> command.copy(ids = ids)
            is FileWriteCommand.Restore -> command.copy(ids = ids)
            is FileWriteCommand.Delete -> command.copy(ids = ids)
            is FileWriteCommand.Star -> command.copy(ids = ids)
            else -> command
        }
    }

    fun submit(command: FileWriteCommand): FileCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> FileCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(FileCommandResult::Accepted)
            ?: FileCommandResult.Rejected("账号已改变，尚未提交操作")
    }

    private fun contains(ancestor: String, child: String): Boolean = ancestor == child ||
        repository.folderIndex.pathTo(child)?.any { it.id == ancestor } == true ||
        repository.knownParentOf(child)?.let { parent -> parent == ancestor || repository.folderIndex.pathTo(parent)?.any { it.id == ancestor } == true } == true

    private fun conflicts(a: FileWriteCommand, b: FileWriteCommand): Boolean {
        if (a is FileWriteCommand.CreateFolder && b is FileWriteCommand.CreateFolder) {
            return a.target.id == b.target.id && a.name.trim() == b.name.trim()
        }
        // 星标与名称、位置、读取相互独立，删除/恢复则改变条目的存在状态。
        if (a is FileWriteCommand.Star && b !is FileWriteCommand.Star && b !is FileWriteCommand.Delete && b !is FileWriteCommand.Restore) return false
        if (b is FileWriteCommand.Star && a !is FileWriteCommand.Star && a !is FileWriteCommand.Delete && a !is FileWriteCommand.Restore) return false
        if (a is FileWriteCommand.Copy && b is FileWriteCommand.Copy && a.target.id != b.target.id) return false
        val sourcesOverlap = a.ids.any { x -> b.ids.any { y -> contains(x, y) || contains(y, x) } }
        // 目标目录的改名不会破坏按 ID 提交的写入；移动、删除或恢复它会改变执行的语境。
        return sourcesOverlap ||
            (changesTree(a) && b.target?.let { target -> a.ids.any { contains(it, target.id) } } == true) ||
            (changesTree(b) && a.target?.let { target -> b.ids.any { contains(it, target.id) } } == true)
    }
}

sealed interface FileCommandResult {
    data class Accepted(val workId: Long) : FileCommandResult
    data class Rejected(val reason: String) : FileCommandResult
}
