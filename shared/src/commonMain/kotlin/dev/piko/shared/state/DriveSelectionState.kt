package dev.piko.shared.state

import androidx.compose.runtime.*
import io.github.nihildigit.pikpak.FileStat

internal class DriveSelectionState(
    private val rows: () -> List<DriveListItem.File>,
    private val visible: () -> List<FileStat>,
    private val canSelect: (String) -> Boolean,
) {
    private val fileByRowKey by derivedStateOf { rows().associate { it.key to it.file } }
    val ids = mutableStateListOf<String>()
    private val _selectedFileIds get() = ids
    private var selectionHeldOpen by mutableStateOf(false)
    val active by derivedStateOf { selectionHeldOpen || ids.isNotEmpty() }
    fun enterSelection(fileId: String? = null) {
        if (fileId != null && !canSelect(fileId)) return
        selectionHeldOpen = true
        if (fileId != null && fileId !in _selectedFileIds) _selectedFileIds.add(fileId)
    }

    fun exitSelection() {
        selectionHeldOpen = false
        _selectedFileIds.clear()
        selectionAnchor = null
    }

    fun setSelected(fileId: String, selected: Boolean) {
        if (selected && !canSelect(fileId)) return
        if (selected) {
            if (fileId !in _selectedFileIds) _selectedFileIds.add(fileId)
        } else {
            _selectedFileIds.remove(fileId)
        }
    }

    private var selectionAnchor: String? = null

    fun toggleSelected(rowKey: String) {
        val fileId = fileByRowKey[rowKey]?.id ?: return
        setSelected(fileId, fileId !in _selectedFileIds)
        selectionAnchor = rowKey
        if (_selectedFileIds.isEmpty()) exitSelection()
    }

    fun selectRange(rowKey: String) {
        val rows = rows()
        val to = rows.indexOfFirst { it.key == rowKey }.takeIf { it >= 0 } ?: return
        val from = selectionAnchor?.let { anchor -> rows.indexOfFirst { it.key == anchor } }?.takeIf { it >= 0 }
        if (from == null) {
            setSelected(rows[to].file.id, true)
            selectionAnchor = rowKey
            return
        }
        rows.subList(minOf(from, to), maxOf(from, to) + 1).forEach { setSelected(it.file.id, true) }
    }

    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) {
        val next = LinkedHashSet(base.filter(canSelect)).apply {
            boxedRows.mapNotNull { fileByRowKey[it]?.id }.filterTo(this, canSelect)
        }
        if (next.isEmpty()) {
            exitSelection()
            return
        }
        if (_selectedFileIds.toSet() != next) {
            _selectedFileIds.clear()
            _selectedFileIds.addAll(next)
        }
    }

    fun toggleSelectAll() {
        val visible = visible().filter { canSelect(it.id) }.map { it.id }
        if (_selectedFileIds.size == visible.size) {
            _selectedFileIds.clear()
        } else {
            _selectedFileIds.clear()
            _selectedFileIds.addAll(visible)
        }
    }

}
