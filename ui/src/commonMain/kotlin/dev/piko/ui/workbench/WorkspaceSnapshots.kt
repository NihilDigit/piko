package dev.piko.ui.workbench

import androidx.compose.runtime.mutableStateMapOf

/** Runtime-only, bounded snapshots of pages that were actually drawn. */
internal class WorkspaceSnapshots<T>(private val pixelBudget: Long = 2L * 1024 * 1024) {
    private class Entry<T>(val value: T, val pixels: Long)
    private val entries = mutableStateMapOf<Long, Entry<T>>()
    private val recency = mutableListOf<Long>()
    val size: Int get() = entries.size
    val pixels: Long get() = entries.values.sumOf { it.pixels }

    operator fun get(id: Long): T? = entries[id]?.value

    fun put(id: Long, value: T, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val pixels = width.toLong() * height
        if (pixels > pixelBudget) return
        entries[id] = Entry(value, pixels)
        recency.remove(id)
        recency.add(id)
        while (this.pixels > pixelBudget) remove(recency.first())
    }

    fun remove(id: Long) { entries.remove(id); recency.remove(id) }
    fun retain(ids: Set<Long>) { recency.toList().filterNot { it in ids }.forEach(::remove) }
    fun clear() { entries.clear(); recency.clear() }
}
