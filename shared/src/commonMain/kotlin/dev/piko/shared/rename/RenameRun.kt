package dev.piko.shared.rename

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile

/**
 * 执行一份 [RenamePlan]：按 [RenamePlan.steps] 逐个改名，批量重命名（含文件夹的规范命名）与转存后的规范命名共用。
 *
 * 一次一个：顺序本身就是为了避开 A 改成 B、B 改成 C 的中间冲突，并发会打乱它；改名请求也只是一次元数据修改，
 * 逐个执行的耗时可以接受。一项失败后它余下的步骤跳过；停在临时名称上的项试着改回原名，改不回就留着临时名称，撤销时照样能改回。
 *
 * 不自己记撤销：调用方在 [execute] 结束（含被取消）后把 [renamed] 记进改动日志，被取消时已改成的也在里面。
 */
class RenameRun(private val driveRepo: PikoDriveRepository, private val tag: String) {
    /** 要改名的项数，与已处理的项数（含失败）。 */
    var total by mutableStateOf(0)
        private set
    var processed by mutableStateOf(0)
        private set

    /** 改名失败的项，成功的不回滚。 */
    val failures = mutableStateListOf<RenameRow>()

    /** 成功的每一步，按执行顺序，撤销时倒着改回去。临时名称的那一步也在里面，撤销时同样倒着经过它。 */
    val renamed = mutableListOf<DriveChangeJournal.Renamed>()

    suspend fun execute(plan: RenamePlan) {
        val targets = plan.rows.associateBy { it.source.id }
        total = plan.changeCount
        processed = 0
        failures.clear()
        renamed.clear()
        val failedIds = mutableSetOf<String>()
        for (step in plan.steps) {
            val id = step.source.id
            if (id in failedIds) continue
            val row = targets.getValue(id)
            val isFinal = step.to == row.newName
            val succeeded = renameStep(id, step.from, step.to)
            if (!succeeded) {
                failedIds += id
                failures += row
                if (step.from != step.source.name) renameStep(id, step.from, step.source.name)
            }
            if (!succeeded || isFinal) processed++
        }
    }

    val succeeded: Int get() = processed - failures.size

    private suspend fun renameStep(id: String, from: String, to: String): Boolean =
        driveRepo.rename(id, to)
            .logFailure(tag, "重命名失败：${logFile(id, from)}")
            .onSuccess { renamed += DriveChangeJournal.Renamed(id, from, to) }
            .isSuccess
}
