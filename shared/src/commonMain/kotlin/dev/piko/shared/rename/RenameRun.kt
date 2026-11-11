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
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logEntry
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Executes independent steps concurrently, with a barrier between dependency waves.
 *
 * When [access] is non-null, requests are bound to the originating account client, each item's current state is verified
 * before renaming, transport failures are reconciled against remote detail to distinguish confirmed vs [unconfirmed]
 * renames, subsequent waves halt if [unconfirmed] is non-empty, and per-item [DriveChange.Renamed] events are published.
 * When [access] is null (used by [BatchRenameState] and `InstantTitleFill`), steps delegate directly to
 * `driveRepo.rename(..., notifyChange = false)` without pre-check or post-timeout detail verification, and touched parent
 * folders are refreshed or invalidated in `finally` (callers may also refresh/invalidate in their own `finally` when
 * handling cancellation or zero-step failures).
 */
class RenameRun(private val driveRepo: PikoDriveRepository, private val tag: String, private val access: DriveWriteAccess? = null) {
    /** 要改名的项数，与已处理的项数（含失败）。 */
    var total by mutableStateOf(0)
        private set
    var processed by mutableStateOf(0)
        private set

    /** 已完成的临时交换步数与失败后回退回原名的步数（供诊断日志区分真实改名与净零回滚）。 */
    var swapsExecuted: Int = 0
        private set
    var rolledBack: Int = 0
        private set

    /** 改名失败的项，成功的不回滚。 */
    val failures = mutableStateListOf<RenameRow>()

    /** 成功的每一步，按执行顺序，撤销时倒着改回去。临时名称的那一步也在里面，撤销时同样倒着经过它。 */
    val renamed = mutableListOf<DriveChangeJournal.Renamed>()
    val unconfirmed = mutableListOf<DriveChangeJournal.Renamed>()

    private enum class StepPhase(val label: String) {
        SWAP("swap"),
        FINAL("final"),
        ROLLBACK("rollback"),
    }

    suspend fun execute(plan: RenamePlan) {
        val targets = plan.rows.associateBy { it.source.id }
        total = plan.changeCount
        processed = 0
        swapsExecuted = 0
        rolledBack = 0
        failures.clear()
        renamed.clear()
        unconfirmed.clear()
        val failedIds = mutableSetOf<String>()
        val completedIds = mutableSetOf<String>()
        val occupied = mutableSetOf<Pair<String, String>>()
        val currentNames = plan.rows.associate { it.source.id to (it.source.parentId to it.source.name) }.toMutableMap()
        val limit = Semaphore(16)
        try {
            for (wave in plan.waves) {
                // Workers report progress immediately; results commit in plan order, including on cancellation.
                val outcomes = arrayOfNulls<StepResult>(wave.steps.size)
                val waveStartProcessed = processed
                val progressLock = Mutex()
                var inWaveDone = 0
                try {
                    coroutineScope {
                        wave.steps.mapIndexed { index, step -> async {
                            limit.withPermit {
                                val targetRow = targets.getValue(step.source.id)
                                val isSwap = step.to != targetRow.newName
                                val outcome = when {
                                    step.source.id in failedIds -> null
                                    (step.source.parentId to step.to) in occupied -> {
                                        PikoLog.w(
                                            tag,
                                            "Skipped rename for ${logEntry(step.source.id, step.from, step.source.isFolder)} " +
                                                "in ${logFolder(step.source.parentId)}: target slot occupied after earlier failure",
                                        )
                                        StepResult(false)
                                    }
                                    else -> renameStep(step, if (isSwap) StepPhase.SWAP else StepPhase.FINAL)
                                }
                                outcomes[index] = outcome
                                if (outcome != null && (!outcome.success || !isSwap)) {
                                    progressLock.withLock {
                                        inWaveDone++
                                        processed = waveStartProcessed + inWaveDone
                                    }
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
                        val isSwap = step.to != row.newName
                        renamed += outcome.renamed
                        unconfirmed += outcome.unconfirmed
                        if (outcome.success) {
                            currentNames[id] = step.source.parentId to step.to
                            if (isSwap) swapsExecuted++
                        }
                        if (!outcome.success) {
                            failedIds += id
                            occupied += step.source.parentId to step.from
                            failures += row
                        }
                        if (!outcome.success || !isSwap) processed++
                        if (outcome.success && !isSwap) completedIds += id
                    }
                }
                // Restore a temporary name only if its original slot is still free.
                wave.steps.forEachIndexed { index, step ->
                    if (outcomes[index]?.success == false && step.from != step.source.name) {
                        val hasUnconfirmed = unconfirmed.any { it.id == step.source.id }
                        val slotTaken = (step.source.parentId to step.source.name) in currentNames.values
                        if (hasUnconfirmed || slotTaken) {
                            PikoLog.w(
                                tag,
                                "Temporary swap name retained on ${logEntry(step.source.id, step.from, step.source.isFolder)} " +
                                    "in ${logFolder(step.source.parentId)}: unconfirmed=$hasUnconfirmed, originalSlotTaken=$slotTaken",
                            )
                        } else {
                            val restored = renameStep(step.copy(to = step.source.name), StepPhase.ROLLBACK)
                            renamed += restored.renamed
                            unconfirmed += restored.unconfirmed
                            if (restored.success) {
                                rolledBack++
                                occupied -= step.source.parentId to step.from
                                occupied += step.source.parentId to step.source.name
                                currentNames[step.source.id] = step.source.parentId to step.source.name
                                PikoLog.i(
                                    tag,
                                    "Rolled back temporary swap name for ${logEntry(step.source.id, step.source.name, step.source.isFolder)} " +
                                        "in ${logFolder(step.source.parentId)}",
                                )
                            } else {
                                PikoLog.w(
                                    tag,
                                    "Failed to roll back temporary swap name on ${logEntry(step.source.id, step.from, step.source.isFolder)} " +
                                        "in ${logFolder(step.source.parentId)}",
                                )
                            }
                        }
                    }
                }
                if (access != null && unconfirmed.isNotEmpty()) {
                    val skipped = plan.rows.filter { it.isChanged && it.problem == null && it.source.id !in completedIds && it.source.id !in failedIds }
                    failures += skipped
                    processed = completedIds.size + failures.size
                    PikoLog.w(
                        tag,
                        "Aborted remaining rename waves due to ${unconfirmed.size} unconfirmed step(s) (skipped=${skipped.size})",
                    )
                    break
                }
            }
        } finally {
            val strandedSwaps = currentNames.count { (id, loc) ->
                val row = targets[id]
                row != null && loc.second != row.source.name && loc.second != row.newName
            }
            if (strandedSwaps > 0) {
                PikoLog.w(tag, "Rename run ended with $strandedSwaps item(s) still holding temporary swap names")
            }
            if (access == null && renamed.isNotEmpty()) {
                val touched = plan.rows.mapTo(HashSet()) { it.source.parentId }
                if (unconfirmed.isEmpty() && failures.isEmpty()) {
                    driveRepo.notifyFoldersRefreshed(touched)
                } else {
                    driveRepo.applyChange(DriveChange.ContentsChanged(touched))
                }
            }
        }
    }

    val succeeded: Int get() = processed - failures.size

    private data class StepResult(
        val success: Boolean,
        val renamed: List<DriveChangeJournal.Renamed> = emptyList(),
        val unconfirmed: List<DriveChangeJournal.Renamed> = emptyList(),
    )

    private suspend fun renameStep(step: RenameStep, phase: StepPhase): StepResult {
        val (source, from, to) = step
        val id = source.id
        val bound = access
        var parentId = source.parentId
        val entryDesc = logEntry(id, from, source.isFolder)
        if (bound != null) {
            val current = bound.detail(id)
                .logFailure(tag, "Rename pre-check failed for $entryDesc in ${logFolder(parentId)} (phase=${phase.label})")
                .getOrNull() ?: return StepResult(false)
            if (current.trashed || current.name != from) {
                PikoLog.w(
                    tag,
                    "Rename pre-check rejected $entryDesc in ${logFolder(current.parentId)}: " +
                        "trashed=${current.trashed}, nameChanged=${current.name != from} (phase=${phase.label})",
                )
                return StepResult(false)
            }
            parentId = current.parentId
        }
        var result = bound?.rename(id, to) ?: driveRepo.rename(id, to, parentId, notifyChange = false)
        var retries = 0
        for (retry in 0 until 3) {
            val error = result.exceptionOrNull() as? PikPakException ?: break
            if (error.httpStatus != 429 && error.httpStatus !in 500..599) break
            retries = retry + 1
            delay(300L shl retry)
            result = bound?.rename(id, to) ?: driveRepo.rename(id, to, parentId, notifyChange = false)
        }
        val error = result.exceptionOrNull()
        val refused = error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
        val observed = if (result.isFailure && bound != null && !refused) bound.detail(id).getOrNull()?.let {
            !it.trashed && it.name == to && it.parentId == parentId
        } == true else false
        val change = DriveChangeJournal.Renamed(id, from, to)
        if (result.isSuccess || observed) {
            if (observed) {
                PikoLog.i(
                    tag,
                    "Rename confirmed after transport failure: ${logEntry(id, to, source.isFolder)} in ${logFolder(parentId)} " +
                        "(phase=${phase.label}, retries=$retries, cause=${error?.let { it::class.simpleName } ?: "unknown"})",
                )
            }
            bound?.publish(DriveChange.Renamed(id, to, parentId))
            return StepResult(true, renamed = listOf(change))
        }
        result.logFailure(
            tag,
            "Rename failed: $entryDesc in ${logFolder(parentId)} " +
                "(phase=${phase.label}, retries=$retries, unconfirmed=${bound != null && !refused})",
        )
        return StepResult(false, unconfirmed = if (bound != null && !refused) listOf(change) else emptyList())
    }
}
