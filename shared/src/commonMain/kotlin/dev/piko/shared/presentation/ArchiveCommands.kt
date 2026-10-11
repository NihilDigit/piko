package dev.piko.shared.presentation

import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import io.github.nihildigit.pikpak.FileStat

class ArchiveCommands(private val session: ArchiveExtractSession) {
    fun startAvailability(files: List<FileStat>): ActionAvailability {
        val ids = files.mapTo(HashSet()) { it.id }
        session.jobs.firstOrNull { it.file.id in ids }?.let {
            return ActionAvailability.Blocked("「${it.file.name}」已有解压任务，请在任务页查看。")
        }
        session.completedJobs.firstOrNull {
            it.file.id in ids && (it.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED
        }?.let {
            return ActionAvailability.Blocked("「${it.file.name}」的上次解压结果尚未确认。请先在任务页重新查询或检查所在目录，避免重复提交。")
        }
        return ActionAvailability.Available
    }
}
