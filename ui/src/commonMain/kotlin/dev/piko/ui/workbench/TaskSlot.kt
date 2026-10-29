package dev.piko.ui.workbench

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.shared.presentation.ActionAvailability

/** 添加链接与信息流仍按面板空间确认替换；查重独立运行，只在更换扫描起点时确认丢弃旧结果。 */
@Stable
class TaskSlot {
    fun requestUndoLast(changes: dev.piko.shared.data.DriveChangeJournal, commands: dev.piko.shared.presentation.FileCommands? = null): Boolean {
        if (changes.state.value.undo.values.any { it.status == dev.piko.shared.data.DriveChangeJournal.UndoStatus.RUNNING }) {
            showBlocked(tr("撤销", "Undo", "元に戻す", "실행 취소"), tr("上一项撤销仍在进行，完成后再试。", "The previous undo is still running. Retry after it finishes.", "前の操作を元に戻している途中です。完了後に再試行してください。", "이전 실행 취소가 진행 중입니다. 완료 후 다시 시도하세요."))
            return true
        }
        val change = changes.state.value.available.lastOrNull() ?: return false
        val state = commands?.undoAvailability(change)
        if (state is ActionAvailability.Blocked) { showBlocked(tr("撤销", "Undo", "元に戻す", "실행 취소"), state.reason); return true }
        return changes.undoLast()
    }

    enum class Task {
        DUPLICATES, ADD_LINK, FEED,

    }

    class Occupant(
        val task: Task,
        /** 对话框标题里的名字：「结束{name}并…」。 */
        val name: String,
        /** 结束后丢掉什么，对话框正文。 */
        val loss: String,
        val active: () -> Boolean,
        val end: () -> Unit,
        val replacementAvailability: () -> ActionAvailability = { ActionAvailability.Available },
    )

    class Claim(val title: String, val body: String, val confirmLabel: String, val proceed: () -> Unit)
    class BlockedClaim(val title: String, val reason: String, val resolve: (() -> Unit)? = null)

    /** 面板空间只在移动端排他；替换同一份查重会话的确认不随平台改变。 */
    var exclusive by mutableStateOf(false)

    /** 由主界面登记，开打时才读，不必是状态。 */
    var occupants: List<Occupant> = emptyList()

    var pending by mutableStateOf<Claim?>(null)
        private set
    var blocked by mutableStateOf<BlockedClaim?>(null)
        private set

    fun showBlocked(action: String, reason: String, resolve: (() -> Unit)? = null) {
        pending = null
        blocked = BlockedClaim(tr("暂时无法$action", "Unable to $action right now", "今は${action}できません", "지금은 ${action}할 수 없습니다"), reason, resolve)
    }

    /**
     * 打开一件事。[action] 接在对话框标题「结束…并」后面，[confirmLabel] 是确认按钮。
     * 同一件事再点一次不算冲突（添加链接收着时是放回来，查找同一个起点是回到它）；内容不同时调用方传 [replacesSame]，
     * 例如换一个起点查找、外部来了新的磁力链。[conflictsWith] 限定和哪几件冲突：压缩包只是一个位置，只与信息流的临时浏览冲突。
     */
    fun claim(
        action: String,
        confirmLabel: String,
        task: Task?,
        replacesSame: Boolean = false,
        conflictsWith: Set<Task> = Task.entries.toSet(),
        start: () -> Unit,
    ) {
        pending = null
        blocked = null
        val blocking = occupants.filter {
            val duplicateReplacement = it.task == Task.DUPLICATES || task == Task.DUPLICATES
            if (duplicateReplacement) it.task == task && replacesSame && it.active()
            else (exclusive || (it.task == task && replacesSame)) &&
                it.task in conflictsWith && (it.task != task || replacesSame) && it.active()
        }
        if (!canReplace(blocking, action)) return
        if (blocking.isEmpty()) {
            start()
            return
        }
        pending = Claim(
            title = tr("结束${blocking.first().name}并$action？", "End ${blocking.first().name} and $action?", "${blocking.first().name}を終了して${action}しますか？", "${blocking.first().name}을(를) 종료하고 ${action}할까요?"),
            body = blocking.joinToString("") { it.loss },
            confirmLabel = confirmLabel,
        ) {
            if (!canReplace(blocking, action)) return@Claim
            blocking.forEach { it.end() }
            start()
        }
    }

    private fun canReplace(occupants: List<Occupant>, action: String): Boolean {
        val unavailable = occupants.filter { it.active() }
            .map { it.replacementAvailability() }
            .filterIsInstance<ActionAvailability.Blocked>()
            .firstOrNull() ?: return true
        blocked = BlockedClaim(tr("暂时无法$action", "Unable to $action right now", "今は${action}できません", "지금은 ${action}할 수 없습니다"), unavailable.reason)
        return false
    }

    fun confirm() {
        val claim = pending ?: return
        pending = null
        claim.proceed()
    }

    fun dismiss() {
        pending = null
        blocked = null
    }
}

/** 默认一份不排他的，截图与预览里不经主界面也能用。 */
val LocalTaskSlot = staticCompositionLocalOf { TaskSlot() }

/** 切换前的确认，主界面里只画这一处。 */
@Composable
fun ClaimDialog(slot: TaskSlot) {
    val blocked = slot.blocked
    if (blocked != null) {
        PikoDialog(
            onDismissRequest = slot::dismiss,
            title = { Text(blocked.title) },
            text = { Text(blocked.reason) },
            confirmButton = { TextButton(onClick = { slot.dismiss(); blocked.resolve?.invoke() }) { Text(if (blocked.resolve == null) tr("知道了", "Got it", "了解", "확인") else tr("查看任务", "View tasks", "タスクを表示", "작업 보기")) } },
            dismissButton = blocked.resolve?.let { { TextButton(onClick = slot::dismiss) { Text(stringResource(Res.string.common_cancel)) } } },
        )
        return
    }
    val claim = slot.pending ?: return
    PikoDialog(
        onDismissRequest = slot::dismiss,
        title = { Text(claim.title) },
        text = { Text(claim.body) },
        confirmButton = { PikoDialogConfirm(claim.confirmLabel, slot::confirm) },
        dismissButton = { TextButton(onClick = slot::dismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
