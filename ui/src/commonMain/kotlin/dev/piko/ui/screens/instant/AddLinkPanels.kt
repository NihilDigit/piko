package dev.piko.ui.screens.instant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.pageFocusTarget

// 桌面上添加链接的两种形态，由主界面按右栏放不放得下挑一种。移动端在网盘页底部的 TaskSheet 里，不经这里。
// 预览一律不先收起面板：桌面的播放器是独立窗口，面板盖不住它，看完回来接着挑。

/**
 * 停在右栏里的添加链接，与信息流同一栏、同一时刻只放一样，见 ui/CLAUDE.md 的「右侧那一栏」。
 * 打开即取得焦点：下一步多半是粘贴链接，右栏里的 Esc 也要当场能收起它。
 */
@Composable
fun DockedAddLink(state: InstantSheetState, onPreview: (fileId: String, fileName: String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 登记为页面落点：按在右栏的空白处同样把焦点拿进来，Esc 才归右栏，而不是落到网盘页的返回上
    Column(Modifier.fillMaxSize().pageFocusTarget(focus)) {
        InstantSheetContent(state = state, inSideSheet = true, onPreview = onPreview)
    }
}

/** 右栏放不下时的退路：浮动的模态侧边面板。关掉只是收起，见 [InstantSession.collapse]。 */
@Composable
fun FloatingAddLinkSheet(
    session: InstantSession,
    state: InstantSheetState,
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    // 侧边面板的顶上已有标题与关闭那一行，标题交给它，内容里不再画第二个
    PikoSheet(onDismissRequest = session::collapse, sideSheetTitle = "添加链接") {
        val sideSheet = isSideSheet
        Column {
            InstantSheetContent(state = state, inSideSheet = sideSheet, onPreview = onPreview)
        }
    }
}
