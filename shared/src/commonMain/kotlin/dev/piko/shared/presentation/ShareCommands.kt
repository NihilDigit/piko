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
        if (account == null || repository.currentAccount != account || !acceptsActions()) return ActionAvailability.Blocked(tr("当前分享会话已结束", "This share session has ended", "共有セッションは終了しました", "현재 공유 세션이 종료되었습니다", "目前的分享工作階段已結束"))
        if (!isDriveFolderId(command.target.id)) return ActionAvailability.Blocked(tr("请选择网盘中的目标文件夹", "Choose a destination folder in the drive", "ドライブ内の保存先フォルダーを選択してください", "드라이브에서 대상 폴더를 선택해 주세요", "請選擇雲端硬碟中的目標資料夾"))
        if (command.shareId.isBlank() || command.sources.isEmpty() || command.sources.any { it.id.isBlank() }) return ActionAvailability.Blocked(tr("请先选择要转存的项目", "Select items from the share first", "先に共有内の項目を選択してください", "먼저 공유 항목을 선택해 주세요", "請先選取要轉存的項目"))
        val target = targetAvailability(command.target.id)
        if (target is ActionAvailability.Blocked) return target
        if (session.works.value.any { work -> work.account == account && work.command.shareId == command.shareId &&
                work.command.target.id == command.target.id && work.blockedSources.any { source -> command.sources.any { it.id == source } } })
            return ActionAvailability.Blocked(tr("所选项目有进行中或待确认的转存任务，请先在任务页核对", "A save for these items is running or unconfirmed. Check Tasks first", "同じ項目の共有保存が進行中または未確認です。先にタスク画面で確認してください", "동일한 항목의 공유 저장이 진행 중이거나 미확인 상태입니다. 먼저 작업 페이지에서 확인해 주세요", "所選項目有進行中或待確認的轉存任務，請先至任務頁核對"))
        return ActionAvailability.Available
    }
    fun submit(command: ShareSaveCommand, token: String): ShareCommandResult = when (val availability = availability(command)) {
        is ActionAvailability.Blocked -> ShareCommandResult.Rejected(availability.reason)
        ActionAvailability.Available -> session.submit(command, token)?.let(ShareCommandResult::Accepted) ?: ShareCommandResult.Rejected(tr("账号已切换，未提交转存", "Account changed; share save not submitted", "アカウントが切り替わったため、共有の保存は送信されませんでした", "계정이 전환되어 공유 저장을 제출하지 않았습니다", "帳號已切換，未提交轉存"))
    }
}
