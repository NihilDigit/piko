package dev.piko.ui.screens.instant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.PikoApplication
import dev.piko.shared.state.InstantSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 一次「添加链接」的会话，活得比面板长。
 *
 * 面板划一下就关，而粘好的链接、解析结果与勾选都记在状态里，原先随面板一起丢掉，要重新粘贴、
 * 重新勾。现在关面板只是收起，屏幕底部留一个把手；只有明确再点一次「添加链接」、从外部
 * 打开新的磁力链、保存成功或在把手上关掉，才结束这一次。
 *
 * 放在进程级而不是网盘页里：切到别的页再回来，网盘页会重建，会话不能跟着丢。作用域用主线程，
 * 与原先面板里 rememberCoroutineScope 的调度一致，结束时连同进行中的解析一起取消。
 */
object InstantSession {
    var state by mutableStateOf<InstantSheetState?>(null)
        private set

    var isSheetOpen by mutableStateOf(false)
        private set

    private var scope: CoroutineScope? = null

    /** 开一次新的会话，丢弃上一次。 */
    fun start(initialMagnet: String = "") {
        end()
        val app = PikoApplication.instance
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sessionScope
        state = InstantSheetState(
            instantRepo = app.instantMagnetRepository,
            driveRepo = app.driveRepository,
            preferences = app.sessionManager,
            scope = sessionScope,
            initialMagnet = initialMagnet,
        )
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
