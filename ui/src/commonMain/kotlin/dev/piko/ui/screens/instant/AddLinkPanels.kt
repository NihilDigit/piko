package dev.piko.ui.screens.instant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.TooltipIconButton
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

/**
 * 右栏放不下时的退路：浮动的模态侧边面板。顶上一行与右栏的栏头相同：收起（[InstantSession.collapse]，点遮罩、Esc 同此）
 * 与放弃（×）。
 */
@Composable
fun FloatingAddLinkSheet(
    session: InstantSession,
    state: InstantSheetState,
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val discard = rememberDiscardAddLink(session)
    // 侧边面板的顶上已有标题与关闭那一行，标题交给它，内容里不再画第二个
    PikoSheet(
        onDismissRequest = session::collapse,
        sideSheetTitle = "添加链接",
        sideSheetButtons = { collapse ->
            TooltipIconButton(Icons.AutoMirrored.Outlined.ViewSidebar, CollapseAddLinkLabel, collapse, shortcut = "Esc")
            DiscardAddLinkButton(state, discard)
        },
    ) {
        val sideSheet = isSideSheet
        Column {
            InstantSheetContent(state = state, inSideSheet = sideSheet, onPreview = onPreview)
        }
    }
}

internal const val CollapseAddLinkLabel = "收起添加链接"
internal const val DiscardAddLinkLabel = "放弃添加"

/** 栏头与侧边面板顶上的 ×。正在保存时置灰：保存已经交出去，放弃不掉。 */
@Composable
fun DiscardAddLinkButton(state: InstantSheetState, discard: () -> Unit) {
    TooltipIconButton(
        Icons.Outlined.Close,
        DiscardAddLinkLabel,
        discard,
        enabled = !state.summary().busy,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 放弃这一次添加链接（×）。右栏、浮动侧边面板、浮动卡片与手机底部 sheet 的 × 都经这里，确认只写这一处。
 * 人亲手勾过要保存的文件、还没保存时先确认（[InstantSession.endNeedsConfirm]）；什么都没勾、只粘了链接的直接结束，
 * 再粘一次就回来，问了只是多点一下。
 */
@Composable
fun rememberDiscardAddLink(session: InstantSession): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        PikoDialog(
            onDismissRequest = { asking = false },
            title = { Text("放弃添加？") },
            text = { Text("已勾选的文件不会保存。") },
            confirmButton = {
                PikoDialogConfirm("放弃", destructive = true, onClick = {
                    asking = false
                    session.end()
                })
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
    return remember(session) { { if (session.endNeedsConfirm) asking = true else session.end() } }
}
