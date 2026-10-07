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
 * 重新勾。现在关面板只是收起，再点「添加链接」回到收着的这一次（[reopen]），不另开新的；
 * 换链接是在输入框里改，同一个会话重新解析。只有点 ×（[end]，勾过的先确认，见 [endNeedsConfirm]）、
 * 从外部打开新的磁力链、保存成功或退出登录，才结束这一次。
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

    /** 收着而没做完：入口（命令栏的「添加链接」、FAB 菜单里那一项）据此挂小圆点。 */
    val hasCollapsedWork: Boolean
        get() = state?.isBlank == false && !isSheetOpen

    /**
     * 点 × 之前要不要先确认：人亲手挑过、还勾着要保存的文件。只粘了链接、什么都没勾的直接结束，
     * 丢掉的东西再粘一次就回来，见 [InstantSheetState.hasPickedFiles]。
     */
    val endNeedsConfirm: Boolean
        get() = state?.hasPickedFiles == true

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

    /**
     * 收起面板。会话里有东西（粘过的链接、解析结果）才留着，之后从「添加链接」或浮动卡片接着做；
     * 什么都没做就收起的，当作没打开过，直接结束，不留一个空的在那儿。
     */
    fun collapse() {
        if (state?.isBlank == true) end() else isSheetOpen = false
    }

    fun end() {
        scope?.cancel()
        scope = null
        state = null
        isSheetOpen = false
    }
}
