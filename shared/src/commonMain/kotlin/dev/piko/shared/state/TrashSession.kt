package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 一次批量清理跨页面执行，失败重试仍属于同一次操作。 */
class TrashSession(private val repository: PikoDriveRepository, private val scope: CoroutineScope) {
    var progress by mutableStateOf<TrashProgress?>(null)
        private set
    var failure by mutableStateOf<TrashFailure?>(null)
        private set
    var completedCount by mutableStateOf(0)
        private set
    var undoChange by mutableStateOf<DriveChangeJournal.Change.Trash?>(null)
        private set
    var projectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    private var job: Job? = null
    private var epoch = 0
    private var onBatch: (List<String>) -> Unit = {}
    private var onComplete: () -> Unit = {}
    var isUndoing by mutableStateOf(false)
        private set
    var stopping by mutableStateOf(false)
        private set
    var undoProgress by mutableStateOf<TrashProgress?>(null)
        private set

    fun requestStop() {
        if (progress != null) stopping = true
        else if (isUndoing) stopping = undoChange?.let { repository.changes.requestStopUndo(it.id) } == true
    }

    init {
        scope.launch {
            repository.changes.state.collect { journal ->
                val change = undoChange
                val undo = change?.let { journal.undo[it.id] }
                undoProgress = if (undo?.status == DriveChangeJournal.UndoStatus.RUNNING)
                    TrashProgress(undo.completedTrashItems, requireNotNull(change).ids.size) else null
            }
        }
        scope.launch {
            repository.folderChanges.collect { change ->
                if (change is DriveChange.Restored) projectedIds = projectedIds - change.ids.toSet()
            }
        }
        scope.launch {
            repository.changes.events.collect { event ->
                if (event.undone != null && event.undone === undoChange) dismiss()
                if (event.failedChange != null && event.failedChange === undoChange) { isUndoing = false; stopping = false }
            }
        }
    }

    fun start(ids: List<String>, onBatch: (List<String>) -> Unit = {}, onComplete: () -> Unit = {}) {
        if (progress != null || isUndoing || ids.isEmpty()) return
        completedCount = 0
        undoChange = null
        this.onBatch = onBatch
        this.onComplete = onComplete
        projectedIds = ids.toSet()
        execute(ids.distinct())
    }

    private fun execute(ids: List<String>) {
        val generation = epoch
        stopping = false
        failure = null
        val previous = undoChange?.ids.orEmpty()
        progress = TrashProgress(previous.size, previous.size + ids.size)
        job = scope.launch {
            val completed = previous.toMutableList()
            try {
                for (batch in ids.chunked(100)) {
                    if (stopping) {
                        failure = TrashFailure(completed.size, ids.drop(completed.size - previous.size))
                        return@launch
                    }
                    val result = repository.trash(batch)
                    if (result.isFailure) {
                        result.logFailure("Trash", "移入回收站失败")
                        failure = TrashFailure(completed.size, ids.drop(completed.size - previous.size))
                        return@launch
                    }
                    completed += batch
                    onBatch(batch)
                    progress = TrashProgress(completed.size, previous.size + ids.size)
                }
                onComplete()
            } finally {
                if (generation == epoch) {
                    completedCount = completed.size
                    projectedIds = completed.toSet()
                    val added = completed.drop(previous.size)
                    if (added.isNotEmpty()) {
                        undoChange = repository.changes.recordTrash(
                            previousChange = undoChange,
                            ids = completed.toList(),
                            summary = tr("已将 ${completed.size} 项移入回收站", "Moved ${enCount(completed.size, "item", "items")} to trash", "${completed.size} 項目をゴミ箱に移動しました", "${completed.size}개 항목을 휴지통으로 이동했습니다"),
                        )
                    }
                    progress = null
                    stopping = false
                }
            }
        }
    }

    fun retry() {
        if (progress != null) return
        val remaining = failure?.remainingIds ?: return
        projectedIds = projectedIds + remaining
        execute(remaining)
    }
    fun dismissFailure() { failure = null }
    fun dismiss() { if (progress == null) { failure = null; completedCount = 0; undoChange = null; isUndoing = false } }
    fun undo() {
        if (progress != null || isUndoing) return
        isUndoing = undoChange?.let(repository.changes::undo) == true
    }
    fun clear() {
        epoch++
        undoProgress = null
        stopping = false
        job?.cancel()
        job = null
        progress = null
        failure = null
        completedCount = 0
        undoChange = null
        projectedIds = emptySet()
        isUndoing = false
        onBatch = {}
        onComplete = {}
    }
}
