package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** 从一个文件夹起扫一遍的任务：查找重复、按番号规范命名。 */
interface FolderTask {
    val root: PikoPathBreadcrumb
}

/**
 * 一次从文件夹起扫的任务，活得比网盘页长：大目录要扫好几分钟，人可以去别处，扫描照常进行，扫完时提示。
 * 结果在网盘页的一个位置里看（DriveLibrary.DUPLICATES、CANONICAL_NAMES）。
 *
 * 对同一个目录再开回到这一次；换一个目录就结束旧的、开新的。
 * 结果只在这一次里有效，结束就丢：它反映的是扫描那一刻的网盘，留着旧结果可能按过期的列表去删、去改名。
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
