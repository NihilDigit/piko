package dev.piko.shared.presentation

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.FolderIndex
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.library

data class NavigationEntryToken(
    val id: Long,
    val tabId: Long,
    val enteredStack: List<PikoPathBreadcrumb>,
)

data class DriveTab(val id: Long, val stack: List<PikoPathBreadcrumb>, val history: FolderHistory = FolderHistory()) {
    val title: String get() = stack.lastOrNull()?.name.orEmpty()
    val session: DriveLibrary? get() = stack.library?.takeIf { it.isSession }
}

/** 历史记录的是去过的位置，后退不同于返回父目录；末尾是距离当前最近的一步。 */
data class FolderHistory(
    val back: List<List<PikoPathBreadcrumb>> = emptyList(),
    val forward: List<List<PikoPathBreadcrumb>> = emptyList(),
) {
    val canGoBack: Boolean get() = back.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    fun visited(previous: List<PikoPathBreadcrumb>) =
        FolderHistory(back = (back + listOf(previous)).takeLast(LIMIT), forward = emptyList())

    internal fun resolved(index: FolderIndex): FolderHistory {
        val next = FolderHistory(back.map(index::resolve), forward.map(index::resolve))
        return if (next == this) this else next
    }

    companion object {
        const val LIMIT = 50
    }
}
