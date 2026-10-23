package dev.piko.shared.presentation

import dev.piko.shared.state.DriveBrowserSession

class DriveBrowserStore(private val create: (Long) -> DriveBrowserSession) {
    private val sessions = mutableMapOf<Long, DriveBrowserSession>()
    private var closed = false

    fun peek(id: Long): DriveBrowserSession? = sessions[id]

    fun forTab(id: Long): DriveBrowserSession {
        check(!closed) { "浏览状态容器已释放" }
        return sessions.getOrPut(id) { create(id) }
    }

    fun retainTabs(ids: Set<Long>): Set<Long> {
        val removed = sessions.keys.filterTo(HashSet()) { it !in ids }
        removed.forEach { sessions.remove(it)?.close() }
        return removed
    }

    fun close() {
        closed = true
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
