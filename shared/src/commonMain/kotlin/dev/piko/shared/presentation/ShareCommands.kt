package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

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
        if (account == null || repository.currentAccount != account || !acceptsActions()) return ActionAvailability.Blocked(tr("当前分享上下文已结束", "This share session has ended", "共有セッションは終了しました", "현재 공유 세션이 종료되었습니다"))
        if (!isDriveFolderId(command.target.id)) return ActionAvailability.Blocked(tr("请选择网盘中的目标文件夹", "Choose a destination folder in the drive", "ドライブ内の保存先フォルダーを選択してください", "드라이브에서 대상 폴더를 선택해 주세요"))
        if (command.shareId.isBlank() || command.sources.isEmpty() || command.sources.any { it.id.isBlank() }) return ActionAvailability.Blocked(tr("请先选择分享里的条目", "Select items in the share first", "先に共有内の項目を選択してください", "먼저 공유에서 항목을 선택해 주세요"))
        val target = targetAvailability(command.target.id)
        if (target is ActionAvailability.Blocked) return target
        if (session.works.value.any { work -> work.account == account && work.command.shareId == command.shareId &&
                work.command.target.id == command.target.id && work.blockedSources.any { source -> command.sources.any { it.id == source } } })
            return ActionAvailability.Blocked(tr("相同条目有进行中或待确认的转存，请先在任务页核对", "A save of these items is running or unconfirmed. Check Tasks first", "同じ項目の保存が進行中か未確認です。先にタスク画面で確認してください", "같은 항목의 저장이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요"))
        return ActionAvailability.Available
    }
    fun submit(command: ShareSaveCommand, token: String): ShareCommandResult = when (val availability = availability(command)) {
        is ActionAvailability.Blocked -> ShareCommandResult.Rejected(availability.reason)
        ActionAvailability.Available -> session.submit(command, token)?.let(ShareCommandResult::Accepted) ?: ShareCommandResult.Rejected(tr("账号已改变，尚未提交转存", "Account changed; share save not submitted", "アカウントが変わったため、共有の保存は送信されていません", "계정이 변경되어 공유 저장을 제출하지 않았습니다"))
    }
}
