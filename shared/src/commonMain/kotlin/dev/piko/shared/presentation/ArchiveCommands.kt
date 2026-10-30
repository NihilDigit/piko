package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import io.github.nihildigit.pikpak.FileStat

class ArchiveCommands(private val session: ArchiveExtractSession) {
    fun startAvailability(files: List<FileStat>): ActionAvailability {
        val ids = files.mapTo(HashSet()) { it.id }
        session.jobs.firstOrNull { it.file.id in ids }?.let {
            return ActionAvailability.Blocked(tr("「${it.file.name}」已有解压任务，请在任务页查看。", "“${it.file.name}” already has an extraction task. View it in Tasks.", "「${it.file.name}」には既に解凍タスクがあります。タスク画面で確認してください。", "“${it.file.name}”의 압축 해제 작업이 이미 있습니다. 작업 페이지에서 확인해 주세요.", "「${it.file.name}」已有解壓縮任務，請至任務頁查看。"))
        }
        session.completedJobs.firstOrNull {
            it.file.id in ids && (it.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED
        }?.let {
            return ActionAvailability.Blocked(tr("「${it.file.name}」的上次解压结果待确认，请先在任务页重新查询或核对所在文件夹。", "Previous extraction of “${it.file.name}” is unconfirmed. Check Tasks or its containing folder first.", "「${it.file.name}」の前回の解凍結果が未確認です。先にタスク画面で再確認するか保存先フォルダーを確認してください。", "“${it.file.name}”의 이전 압축 해제 결과가 미확인 상태입니다. 먼저 작업 페이지나 해당 폴더를 확인해 주세요.", "「${it.file.name}」的上次解壓縮結果待確認，請先至任務頁重新查詢或核對所在資料夾。"))
        }
        return ActionAvailability.Available
    }
}
