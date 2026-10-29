package dev.piko.ui.screens.instant

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
import dev.piko.shared.presentation.AddLinkViewModel
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
fun DockedAddLink(
    state: InstantSheetState,
    onTargetPicked: ((List<dev.piko.data.repository.PathBreadcrumb>) -> Unit)? = null,
    onPreview: (fileId: String, fileName: String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 登记为页面落点：按在右栏的空白处同样把焦点拿进来，Esc 才归右栏，而不是落到网盘页的返回上
    Column(Modifier.fillMaxSize().pageFocusTarget(focus)) {
        InstantSheetContent(state = state, inSideSheet = true, onTargetPicked = onTargetPicked, onPreview = onPreview)
    }
}

internal val CollapseAddLinkLabel: String get() = tr("收起添加链接", "Collapse add link", "リンク追加を折りたたむ", "링크 추가 접기")
internal val DiscardAddLinkLabel: String get() = tr("放弃添加", "Discard links", "追加を破棄", "추가 취소")

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
 * 人亲手勾过要保存的文件、还没保存时先确认（[AddLinkViewModel.discardNeedsConfirm]）；什么都没勾、只粘了链接的直接结束，
 * 再粘一次就回来，问了只是多点一下。
 */
@Composable
fun rememberDiscardAddLink(session: AddLinkViewModel): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        PikoDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(Res.string.text_close_add_link)) },
            text = { Text(if (session.state?.saveIssue != null || session.state?.shareSave?.hasPendingResult == true || session.state?.batch?.rows?.any { it.state.saveIssue != null } == true)
                tr("本次保存已有部分结果。已提交的请求不会取消，已保存文件保留；待确认的结果仍可在任务页查看。", "Some save results exist. Submitted requests continue and saved files are kept. Check unconfirmed results in Tasks.", "一部の保存結果があります。送信済みのリクエストは取り消されず、保存したファイルは保持します。未確認の結果はタスク画面で確認できます。", "일부 저장 결과가 있습니다. 제출된 요청은 취소되지 않으며 저장된 파일은 유지됩니다. 미확인 결과는 작업 페이지에서 확인할 수 있습니다.")
                else tr("当前输入的链接和勾选的文件不会保存。", "Entered links and selected files will not be saved.", "入力したリンクと選択したファイルは保存されません。", "입력한 링크와 선택한 파일은 저장되지 않습니다.")) },
            confirmButton = {
                PikoDialogConfirm(tr("关闭", "Close", "閉じる", "닫기"), destructive = true, onClick = {
                    asking = false
                    session.discard()
                })
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    return remember(session) { { if (session.discardNeedsConfirm) asking = true else session.discard() } }
}

@Composable
fun AddLinkBlockedAction(viewModel: AddLinkViewModel) {
    val blocked = viewModel.blockedAction ?: return
    PikoDialog(
        onDismissRequest = viewModel::dismissBlockedAction,
        title = { Text(stringResource(Res.string.text_cannot_replace_links_right_now)) },
        text = { Text(blocked.reason) },
        confirmButton = { TextButton(onClick = viewModel::dismissBlockedAction) { Text(stringResource(Res.string.common_got_it)) } },
    )
}
