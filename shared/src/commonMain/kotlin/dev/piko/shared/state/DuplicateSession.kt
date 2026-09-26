package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * 一次查找重复，活得比面板长：大目录要扫好几分钟，面板划走只是收起，扫描照常进行，
 * 底部留把手显示进度，扫完时由网盘页提示。与 [InstantSession] 同一个形状。
 *
 * 对同一个目录再点「查找重复」回到这一次，已勾选的不丢；换一个目录就结束旧的、开新的。
 * 结果只在这一次里有效，关掉就丢：它反映的是扫描那一刻的网盘，留着旧结果可能按过期的列表去删。
 */
class DuplicateSession(
    private val newScope: () -> CoroutineScope,
    private val newState: (scope: CoroutineScope, root: PikoPathBreadcrumb) -> DuplicateFinderState,
) {
    var state by mutableStateOf<DuplicateFinderState?>(null)
        private set

    var isSheetOpen by mutableStateOf(false)
        private set

    private var scope: CoroutineScope? = null

    fun open(root: PikoPathBreadcrumb) {
        if (state?.root?.id != root.id) {
            end()
            val sessionScope = newScope()
            scope = sessionScope
            state = newState(sessionScope, root)
        }
        isSheetOpen = true
    }

    fun reopen() {
        if (state != null) isSheetOpen = true
    }

    fun collapse() {
        isSheetOpen = false
    }

    fun end() {
        scope?.cancel()
        scope = null
        state = null
        isSheetOpen = false
    }
}
