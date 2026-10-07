package dev.piko.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.shared.data.DriveChangeJournal
import kotlinx.coroutines.flow.Flow

/**
 * 根页面（网盘、传输、「我的」）共用的 Snackbar 队列，由主界面提供。各页照旧在自己的 Scaffold 里摆 SnackbarHost，
 * 位置与对 FAB 的避让不变；队列只有一个，主界面发的提示在哪一页都弹得出，也不与页内的提示叠在一起。
 * 压栈页盖住根页面时没有宿主，提示排着，回到根页面再弹。
 */
val LocalRootSnackbar = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalRootSnackbar 只在 PikoMainScaffold 里提供")
}

/**
 * 网盘改动做完或撤销后的提示，可撤销的带「撤销」。收在主界面一层而不在网盘页：改动日志的事件不重放，没人订阅时发出即丢；
 * 归档要做几分钟，手机上人这时多半在传输页或「我的」，网盘页已离开组合。
 * 每条先等界面回到前台（[awaitVisible]）再弹：退到后台时 Snackbar 仍可能照常计时，回来时已经消失。
 */
suspend fun showChangeNotices(
    events: Flow<DriveChangeJournal.Event>,
    host: SnackbarHostState,
    awaitVisible: suspend () -> Unit,
    undo: (DriveChangeJournal.Change) -> Unit,
) {
    events.collect { event ->
        awaitVisible()
        val change = event.change
        val result = host.showSnackbar(
            message = event.message,
            actionLabel = if (change != null) "撤销" else null,
            withDismissAction = true,
            // 停留长一些，好来得及点撤销
            duration = if (change != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (change != null && result == SnackbarResult.ActionPerformed) undo(change)
    }
}
