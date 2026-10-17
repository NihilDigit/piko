package dev.piko.ui.screens.transfers

import dev.piko.shared.state.TransferSections

/** Keeps rows in place during batch selection, while their displayed state continues to update. */
internal class TaskListOrder(sections: TransferSections) {
    private val groups = listOf(sections.inProgress, sections.needsAttention, sections.completed, sections.outputDeleted).map { rows -> rows.map { it.key } }
    private val known = groups.flatten().toSet()

    fun apply(live: TransferSections): TransferSections {
        val current = listOf(live.inProgress, live.needsAttention, live.completed, live.outputDeleted)
        val latest = current.flatten().associateBy { it.key }
        val ordered = groups.mapIndexed { index, keys -> keys.mapNotNull(latest::get) + current[index].filter { it.key !in known } }
        return TransferSections(ordered[0], ordered[1], ordered[2], ordered[3])
    }
}
