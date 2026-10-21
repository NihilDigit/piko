package dev.piko.shared.rename

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.DriveWriteAccess
import dev.piko.shared.data.DriveChange
import io.github.nihildigit.pikpak.PikPakException
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Executes independent steps concurrently, with a barrier between dependency waves. */
class RenameRun(private val driveRepo: PikoDriveRepository, private val tag: String, private val access: DriveWriteAccess? = null) {
    /** 要改名的项数，与已处理的项数（含失败）。 */
    var total by mutableStateOf(0)
        private set
    var processed by mutableStateOf(0)
        private set

    /** 改名失败的项，成功的不回滚。 */
    val failures = mutableStateListOf<RenameRow>()

    /** 成功的每一步，按执行顺序，撤销时倒着改回去。临时名称的那一步也在里面，撤销时同样倒着经过它。 */
    val renamed = mutableListOf<DriveChangeJournal.Renamed>()
    val unconfirmed = mutableListOf<DriveChangeJournal.Renamed>()

    suspend fun execute(plan: RenamePlan) {
        val targets = plan.rows.associateBy { it.source.id }
        total = plan.changeCount
        processed = 0
        failures.clear()
        renamed.clear()
        unconfirmed.clear()
        val failedIds = mutableSetOf<String>()
        val completedIds = mutableSetOf<String>()
        val occupied = mutableSetOf<Pair<String, String>>()
        val currentNames = plan.rows.associate { it.source.id to (it.source.parentId to it.source.name) }.toMutableMap()
        val limit = Semaphore(4)
        try {
            for (wave in plan.waves) {
                // Workers report progress immediately; results commit in plan order, including on cancellation.
                val outcomes = arrayOfNulls<StepResult>(wave.steps.size)
                val waveStartProcessed = processed
                val inWaveDone = AtomicInteger(0)
                try {
                    coroutineScope {
                        wave.steps.mapIndexed { index, step -> async {
                            limit.withPermit {
                                val outcome = when {
                                    step.source.id in failedIds -> null
                                    (step.source.parentId to step.to) in occupied -> StepResult(false)
                                    else -> renameStep(step)
                                }
                                outcomes[index] = outcome
                                if (outcome != null && (!outcome.success || step.to == targets.getValue(step.source.id).newName)) {
                                    processed = waveStartProcessed + inWaveDone.incrementAndGet()
                                }
                            }
                        } }.awaitAll()
                    }
                } finally {
                    processed = waveStartProcessed
                    wave.steps.forEachIndexed { index, step ->
                        val outcome = outcomes[index] ?: return@forEachIndexed
                        val id = step.source.id
                        val row = targets.getValue(id)
                        renamed += outcome.renamed
                        unconfirmed += outcome.unconfirmed
                        if (outcome.success) currentNames[id] = step.source.parentId to step.to
                        if (!outcome.success) {
                            failedIds += id
                            occupied += step.source.parentId to step.from
                            failures += row
                        }
                        if (!outcome.success || step.to == row.newName) processed++
                        if (outcome.success && step.to == row.newName) completedIds += id
                    }
                }
                // Restore a temporary name only if its original slot is still free.
                wave.steps.forEachIndexed { index, step ->
                    if (outcomes[index]?.success == false && step.from != step.source.name &&
                        unconfirmed.none { it.id == step.source.id } &&
                        (step.source.parentId to step.source.name) !in currentNames.values) {
                        val restored = renameStep(step.copy(to = step.source.name))
                        renamed += restored.renamed
                        unconfirmed += restored.unconfirmed
                        if (restored.success) {
                            occupied -= step.source.parentId to step.from
                            occupied += step.source.parentId to step.source.name
                            currentNames[step.source.id] = step.source.parentId to step.source.name
                        }
                    }
                }
                if (access != null && unconfirmed.isNotEmpty()) {
                    failures += plan.rows.filter { it.isChanged && it.problem == null && it.source.id !in completedIds && it.source.id !in failedIds }
                    processed = completedIds.size + failures.size
                    break
                }
            }
        } finally {
            if (access == null && renamed.isNotEmpty()) {
                driveRepo.applyChange(DriveChange.ContentsChanged(plan.rows.mapTo(HashSet()) { it.source.parentId }))
            }
        }
    }

    val succeeded: Int get() = processed - failures.size

    private data class StepResult(
        val success: Boolean,
        val renamed: List<DriveChangeJournal.Renamed> = emptyList(),
        val unconfirmed: List<DriveChangeJournal.Renamed> = emptyList(),
    )

    private suspend fun renameStep(step: RenameStep): StepResult {
        val (source, from, to) = step
        val id = source.id
        val bound = access
        var parentId = source.parentId
        if (bound != null) {
            val current = bound.detail(id).getOrNull() ?: return StepResult(false)
            if (current.trashed || current.name != from) return StepResult(false)
            parentId = current.parentId
        }
        var result = bound?.rename(id, to) ?: driveRepo.rename(id, to, parentId, notifyChange = false)
        for (retry in 0 until 3) {
            val error = result.exceptionOrNull() as? PikPakException ?: break
            if (error.httpStatus != 429 && error.httpStatus !in 500..599) break
            delay(300L shl retry)
            result = bound?.rename(id, to) ?: driveRepo.rename(id, to, parentId, notifyChange = false)
        }
        result.logFailure(tag, "重命名失败：${logFile(id, from)}")
        val error = result.exceptionOrNull()
        val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
        val observed = if (result.isFailure && bound != null && !refused) bound.detail(id).getOrNull()?.let {
            !it.trashed && it.name == to && it.parentId == parentId
        } == true else false
        val change = DriveChangeJournal.Renamed(id, from, to)
        if (result.isSuccess || observed) {
            bound?.publish(DriveChange.Renamed(id, to, parentId))
            return StepResult(true, renamed = listOf(change))
        }
        return StepResult(false, unconfirmed = if (bound != null && !refused) listOf(change) else emptyList())
    }
}
