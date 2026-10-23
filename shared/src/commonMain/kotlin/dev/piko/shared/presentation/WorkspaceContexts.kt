package dev.piko.shared.presentation

import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** A workspace position has a stable identity independent of its visible page or task scope. */
sealed interface WorkspaceContext {
    val id: Long

    data class File(override val id: Long) : WorkspaceContext
    data class Duplicate(override val id: Long, val root: PikoPathBreadcrumb) : WorkspaceContext
    data class ImportDraft(override val id: Long) : WorkspaceContext
}

/**
 * Ordered work tabs shared by every platform and window layout. This owns only identity and
 * selection; the owner keeps each context's browser or task state alive until [close].
 */
class WorkspaceContexts(initialFileId: Long = 1L) {
    init { require(initialFileId != 0L) }

    var primaryFileId: Long = initialFileId
        private set

    fun canClose(id: Long): Boolean {
        val current = _contextsFlow.value
        val target = current.firstOrNull { it.id == id } ?: return false
        return if (target is WorkspaceContext.File) {
            current.count { it is WorkspaceContext.File } > 1
        } else {
            true
        }
    }

    private val _contextsFlow = MutableStateFlow<List<WorkspaceContext>>(listOf(WorkspaceContext.File(initialFileId)))
    val contextsFlow: StateFlow<List<WorkspaceContext>> = _contextsFlow.asStateFlow()

    private val _activeContextId = MutableStateFlow(initialFileId)
    val activeContextId: StateFlow<Long> = _activeContextId.asStateFlow()

    /** Called after a selection change, including one made directly through this collection. */
    var onActivated: ((WorkspaceContext) -> Unit)? = null

    /** New work only; restoring saved tabs or selecting existing work does not fire this. */
    var onCreated: ((WorkspaceContext) -> Unit)? = null

    private fun newId(): Long {
        var id: Long
        do { id = Random.nextLong() } while (id == 0L || _contextsFlow.value.any { it.id == id })
        return id
    }

    private fun insert(context: WorkspaceContext, activate: Boolean, afterId: Long = _activeContextId.value): Long {
        require(_contextsFlow.value.none { it.id == context.id })
        val current = _contextsFlow.value
        val after = current.indexOfFirst { it.id == afterId }
        require(after >= 0)
        _contextsFlow.value = current.toMutableList().apply { add(after + 1, context) }
        if (activate) activate(context.id)
        onCreated?.invoke(context)
        return context.id
    }

    fun openFile(id: Long, activate: Boolean = true, afterId: Long = activeContextId.value): Long {
        require(id != 0L)
        return insert(WorkspaceContext.File(id), activate, afterId)
    }

    fun openDuplicate(root: PikoPathBreadcrumb, activate: Boolean = true): Long =
        insert(WorkspaceContext.Duplicate(newId(), root), activate)

    fun openImportDraft(activate: Boolean = true): Long =
        insert(WorkspaceContext.ImportDraft(newId()), activate)

    fun activate(id: Long): Boolean {
        val context = _contextsFlow.value.firstOrNull { it.id == id } ?: return false
        if (_activeContextId.value != id) {
            _activeContextId.value = id
            onActivated?.invoke(context)
        }
        return true
    }

    fun move(id: Long, targetIndex: Int): Boolean {
        val current = _contextsFlow.value
        val from = current.indexOfFirst { it.id == id }
        if (from < 0 || current.size <= 1) return false
        val to = targetIndex.coerceIn(0, current.lastIndex)
        if (from == to) return false
        val next = current.toMutableList().apply { add(to, removeAt(from)) }
        _contextsFlow.value = next
        next.firstOrNull { it is WorkspaceContext.File }?.let { primaryFileId = it.id }
        return true
    }

    /** Any file browser can be closed as long as at least one file browser remains. */
    fun close(id: Long): WorkspaceContext? {
        if (!canClose(id)) return null
        val current = _contextsFlow.value
        val index = current.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val removed = current[index]
        if (current.size == 1) return null
        val next = current.toMutableList().apply { removeAt(index) }
        _contextsFlow.value = next
        if (id == primaryFileId) {
            next.firstOrNull { it is WorkspaceContext.File }?.let { primaryFileId = it.id }
        }
        if (_activeContextId.value == id) {
            val replacement = next.getOrNull(index) ?: next[index - 1]
            _activeContextId.value = replacement.id
            onActivated?.invoke(replacement)
        }
        return removed
    }

    /** Reset the primary browser while retaining auxiliary work after it. */
    fun resetFiles(firstId: Long = 1L) {
        require(firstId != 0L)
        val auxiliary = _contextsFlow.value.filterNot { it is WorkspaceContext.File }
        require(auxiliary.none { it.id == firstId })
        primaryFileId = firstId
        _contextsFlow.value = listOf(WorkspaceContext.File(firstId)) + auxiliary
        _activeContextId.value = firstId
        onActivated?.invoke(WorkspaceContext.File(firstId))
    }

    /** Replace startup file positions without disturbing a task opened during the cache read. */
    fun restoreFilesPreservingAux(fileIds: List<Long>, activeFileId: Long) {
        val ids = fileIds.distinct().ifEmpty { listOf(1L) }
        require(ids.none { it == 0L })
        val previous = _contextsFlow.value
        require(previous.none { it !is WorkspaceContext.File && it.id in ids })
        val wasFileActive = previous.any { it.id == _activeContextId.value && it is WorkspaceContext.File }
        val restoredFiles = ids.map { WorkspaceContext.File(it) }
        primaryFileId = ids.first()
        _contextsFlow.value = restoredFiles + previous.filterNot { it is WorkspaceContext.File }
        if (wasFileActive) {
            val selected = activeFileId.takeIf { it in ids } ?: ids.first()
            _activeContextId.value = selected
            onActivated?.invoke(WorkspaceContext.File(selected))
        }
    }
}
