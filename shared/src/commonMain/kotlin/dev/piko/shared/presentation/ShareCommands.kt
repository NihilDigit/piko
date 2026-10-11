package dev.piko.shared.presentation

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.state.ShareSaveCommand
import dev.piko.shared.state.ShareSaveSession

sealed interface ShareCommandResult {
    data class Accepted(val workId: Long) : ShareCommandResult
    data class Rejected(val reason: String) : ShareCommandResult
}

class ShareCommands(private val repository: PikoDriveRepository, val session: ShareSaveSession,
    private val account: String? = repository.currentAccount,
    private val targetAvailability: (String) -> ActionAvailability = { ActionAvailability.Available },
    private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: ShareSaveCommand): ActionAvailability {
        if (account == null || repository.currentAccount != account || !acceptsActions()) return ActionAvailability.Blocked("当前分享上下文已结束")
        if (!isDriveFolderId(command.target.id)) return ActionAvailability.Blocked("请选择网盘中的目标文件夹")
        if (command.shareId.isBlank() || command.sources.isEmpty() || command.sources.any { it.id.isBlank() }) return ActionAvailability.Blocked("请先选择分享里的条目")
        val target = targetAvailability(command.target.id)
        if (target is ActionAvailability.Blocked) return target
        if (session.works.value.any { work -> work.account == account && work.command.shareId == command.shareId &&
                work.command.target.id == command.target.id && work.blockedSources.any { source -> command.sources.any { it.id == source } } })
            return ActionAvailability.Blocked("相同条目有进行中或待确认的转存，请先在任务页核对")
        return ActionAvailability.Available
    }
    fun submit(command: ShareSaveCommand, token: String): ShareCommandResult = when (val availability = availability(command)) {
        is ActionAvailability.Blocked -> ShareCommandResult.Rejected(availability.reason)
        ActionAvailability.Available -> session.submit(command, token)?.let(ShareCommandResult::Accepted) ?: ShareCommandResult.Rejected("账号已改变，尚未提交转存")
    }
}
