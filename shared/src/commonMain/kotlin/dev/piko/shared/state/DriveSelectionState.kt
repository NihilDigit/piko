package dev.piko.shared.state

import androidx.compose.runtime.*
import io.github.nihildigit.pikpak.FileStat

internal class DriveSelectionState(
    private val rows: () -> List<DriveListItem.File>,
    private val visible: () -> List<FileStat>,
    private val canSelect: (String) -> Boolean,
) {
    private val fileByRowKey by derivedStateOf { rows().associate { it.key to it.file } }
    private val rowIndexByKey by derivedStateOf {
        val list = rows()
        if (list.isEmpty()) emptyMap()
        else HashMap<String, Int>(list.size).also { map ->
            for (i in list.indices) map.put(list[i].key, i)
        }
    }
    val ids = mutableStateListOf<String>()
    val idSet: Set<String> by derivedStateOf {
        if (ids.isEmpty()) emptySet() else ids.toHashSet()
    }
    private val _selectedFileIds get() = ids
    private var selectionHeldOpen by mutableStateOf(false)
    val active by derivedStateOf { selectionHeldOpen || ids.isNotEmpty() }
    fun enterSelection(fileId: String? = null) {
        if (fileId != null && !canSelect(fileId)) return
        selectionHeldOpen = true
        if (fileId != null && fileId !in idSet) _selectedFileIds.add(fileId)
    }

    fun exitSelection() {
        selectionHeldOpen = false
        _selectedFileIds.clear()
        selectionAnchor = null
    }

    fun setSelected(fileId: String, selected: Boolean) {
        if (selected && !canSelect(fileId)) return
        if (selected) {
            if (fileId !in idSet) _selectedFileIds.add(fileId)
        } else {
            if (fileId in idSet) _selectedFileIds.remove(fileId)
        }
    }

    private var selectionAnchor: String? = null

    fun toggleSelected(rowKey: String) {
        val fileId = fileByRowKey[rowKey]?.id ?: return
        setSelected(fileId, fileId !in idSet)
        selectionAnchor = rowKey
        if (_selectedFileIds.isEmpty()) exitSelection()
    }

    fun selectRange(rowKey: String) {
        val currentRows = rows()
        val indexByKey = rowIndexByKey
        val to = indexByKey[rowKey] ?: return
        val from = selectionAnchor?.let { indexByKey[it] }
        if (from == null) {
            setSelected(currentRows[to].file.id, true)
            selectionAnchor = rowKey
            return
        }
        val existing = idSet
        val added = HashSet<String>()
        val batch = ArrayList<String>()
        val start = minOf(from, to)
        val end = maxOf(from, to)
        for (i in start..end) {
            val fileId = currentRows[i].file.id
            if (fileId !in existing && added.add(fileId) && canSelect(fileId)) {
                batch.add(fileId)
            }
        }
        if (batch.isNotEmpty()) _selectedFileIds.addAll(batch)
    }

    fun selectFiles(fileIds: Iterable<String>) {
        val existing = idSet
        val added = HashSet<String>()
        val batch = ArrayList<String>()
        for (fileId in fileIds) {
            if (fileId !in existing && added.add(fileId) && canSelect(fileId)) {
                batch.add(fileId)
            }
        }
        if (batch.isNotEmpty()) _selectedFileIds.addAll(batch)
    }

    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) {
        val byRow = fileByRowKey
        val next = LinkedHashSet<String>(base.size + boxedRows.size)
        for (id in base) {
            if (canSelect(id)) next.add(id)
        }
        for (rowKey in boxedRows) {
            val id = byRow[rowKey]?.id ?: continue
            if (canSelect(id)) next.add(id)
        }
        if (next.isEmpty()) {
            exitSelection()
            return
        }
        if (idSet != next) {
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
