package dev.piko.shared.presentation

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryEditSession

sealed interface VaultEntryCommandResult {
    data class Accepted(val workId: Long) : VaultEntryCommandResult
    data class Rejected(val reason: String) : VaultEntryCommandResult
}

class VaultEntryCommands(private val repository: PikoDriveRepository, val session: VaultEntryEditSession,
    private val fileCommands: FileCommands, private val account: String? = repository.currentAccount,
    private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: VaultEntryEditCommand): ActionAvailability {
        if (account == null || repository.currentAccount != account || !acceptsActions()) return ActionAvailability.Blocked("当前浏览上下文已结束")
        if (command.entries.isEmpty() || command.entries.any { !isDriveFolderId(it.key) || it.value.isEmpty() || it.value.any(String::isBlank) }) return ActionAvailability.Blocked("没有可编辑的归档条目")
        if (command is VaultEntryEditCommand.Rename && command.name.isBlank()) return ActionAvailability.Blocked("名称不能为空")
        if (session.works.value.any { it.account == account && it.pendingFolders.any { folder -> folder in command.entries } }) return ActionAvailability.Blocked("同一归档清单正在编辑或待确认，请先在任务页处理")
        return fileCommands.manifestAvailability(command.entries.keys)
    }
    fun submit(command: VaultEntryEditCommand): VaultEntryCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> VaultEntryCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(VaultEntryCommandResult::Accepted) ?: VaultEntryCommandResult.Rejected("账号已改变，尚未提交编辑")
    }
}
