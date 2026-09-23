package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * 一次「添加链接」的会话，活得比面板长。
 *
 * 面板划一下就关，而粘好的链接、解析结果与勾选都记在状态里，原先随面板一起丢掉，要重新粘贴、
 * 重新勾。现在关面板只是收起；只有明确再点一次「添加链接」、从外部打开新的磁力链、保存成功、
 * 退出登录或在收起后的入口上关掉，才结束这一次。
 *
 * 由应用级对象持有而不是放在网盘页里：切到别的页再回来，网盘页会重建，会话不能跟着丢。
 * 作用域由调用方给，Android 用主线程，结束时连同进行中的解析一起取消。
 */
class InstantSession(
    private val newScope: () -> CoroutineScope,
    private val newState: (scope: CoroutineScope, initialMagnet: String) -> InstantSheetState,
) {
    var state by mutableStateOf<InstantSheetState?>(null)
        private set

    var isSheetOpen by mutableStateOf(false)
        private set

    private var scope: CoroutineScope? = null

    /** 开一次新的会话，丢弃上一次。 */
    fun start(initialMagnet: String = "") {
        end()
        val sessionScope = newScope()
        scope = sessionScope
        state = newState(sessionScope, initialMagnet)
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
