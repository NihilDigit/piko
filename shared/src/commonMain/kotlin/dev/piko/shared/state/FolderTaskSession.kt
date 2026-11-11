package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** 从一个文件夹起扫一遍的任务，如查找重复。 */
interface FolderTask {
    val root: PikoPathBreadcrumb
}

/**
 * 一次从文件夹起扫的任务，生命周期独立于网盘浏览页：大目录扫描期间切到别处，扫描照常进行。
 * 结果在网盘页或工作区上下文中展示（如查重报告）。
 *
 * 对同一个目录再开回到这一次；换一个目录就结束旧的、开新的。
 * 结果只在这一次里有效，结束就丢：它反映的是扫描那一刻的网盘，留着旧结果可能按过期的列表去删。
 */
class FolderTaskSession<S : FolderTask>(
    private val newScope: () -> CoroutineScope,
    private val newState: (scope: CoroutineScope, root: PikoPathBreadcrumb) -> S,
) {
    var state by mutableStateOf<S?>(null)
        private set

    private var scope: CoroutineScope? = null

    fun open(root: PikoPathBreadcrumb) {
        if (state?.root?.id == root.id) return
        end()
        val sessionScope = newScope()
        scope = sessionScope
        state = newState(sessionScope, root)
    }

    fun end() {
        scope?.cancel()
        scope = null
        state = null
    }
}

typealias DuplicateSession = FolderTaskSession<DuplicateFinderState>
