package dev.piko.ui.workbench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm

/**
 * 窄窗口里同一时刻只做一件占位置的事：查找重复、添加链接（网盘页底部那一块 sheet）与信息流的临时浏览（「继续刷」那一条）。
 * 宽窗口靠标签与浮动卡片能同时挂着几件；窄窗口只有栈导航，再开一件要先明确结束眼前这件，不静默叠加或替换。
 * 各入口经 [claim] 打开，有冲突时由主界面弹一次确认（[ClaimDialog]）。
 *
 * 谁在占用不另存，开打时从各会话的状态读（[Occupant.active]）：会话另有许多结束的路径（换账号、保存成功、继续刷），
 * 另存一份会与它们对不上。
 */
@Stable
class TaskSlot {
    enum class Task { DUPLICATES, ADD_LINK, FEED }

    class Occupant(
        val task: Task,
        /** 对话框标题里的名字：「结束{name}并…」。 */
        val name: String,
        /** 结束后丢掉什么，对话框正文。 */
        val loss: String,
        val active: () -> Boolean,
        val end: () -> Unit,
    )

    class Claim(val title: String, val body: String, val confirmLabel: String, val proceed: () -> Unit)

    /** 只在窄窗口里排他；宽窗口里 [claim] 直接放行。由主界面按窗口设。 */
    var exclusive by mutableStateOf(false)

    /** 由主界面登记，开打时才读，不必是状态。 */
    var occupants: List<Occupant> = emptyList()

    var pending by mutableStateOf<Claim?>(null)
        private set

    /**
     * 查找重复的 sheet 展开着没有。窄窗口里切到别的底部标签时网盘页离开组合，回来要还是原来那一档，所以记在这里。
     * 添加链接的那一档是 InstantSession.isSheetOpen，宽窗口的侧边面板也认它，不另记。
     */
    var duplicatesExpanded by mutableStateOf(true)

    /**
     * 查找重复所在的位置：开始时的路径栈，进了结果页换成结果页。当前的栈以它开头就算还在（进起点的子文件夹不算离开），
     * 人往树外走之前先确认（[allowsLeaving]），换账号这类不是人走的照旧静默结束。为 null 时由主界面取当前的栈补上（从宽窗口缩过来的）。
     */
    var duplicatesAnchor: List<PikoPathBreadcrumb>? = null

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
        val blocking = if (!exclusive) {
            emptyList()
        } else {
            occupants.filter { it.task in conflictsWith && (it.task != task || replacesSame) && it.active() }
        }
        if (blocking.isEmpty()) {
            start()
            return
        }
        pending = Claim(
            title = "结束${blocking.first().name}并$action？",
            body = blocking.joinToString("") { it.loss },
            confirmLabel = confirmLabel,
        ) {
            blocking.forEach { it.end() }
            start()
        }
    }

    /**
     * 网盘的路径栈要换成 [next] 之前问一声（PikoDriveRepository.leaveGuard）。窄窗口里离开查找重复所在的那棵树会结束查找：
     * 宽窗口里结束查找是关掉它的标签，一个显式的动作；窄窗口里离开是顺手的一下，所以先确认，把它变成显式的。
     * 确认就结束查找并走完这一步（[retry]），取消就留在原地。返回 false 即拦下。
     */
    fun allowsLeaving(next: List<PikoPathBreadcrumb>, retry: () -> Unit): Boolean {
        if (!exclusive) return true
        val anchor = duplicatesAnchor ?: return true
        val duplicates = occupants.firstOrNull { it.task == Task.DUPLICATES }?.takeIf { it.active() } ?: return true
        if (next.size >= anchor.size && next.take(anchor.size).map { it.id } == anchor.map { it.id }) return true
        pending = Claim(title = "结束${duplicates.name}？", body = duplicates.loss, confirmLabel = "结束") {
            duplicates.end()
            retry()
        }
        return false
    }

    fun confirm() {
        val claim = pending ?: return
        pending = null
        claim.proceed()
    }

    fun dismiss() {
        pending = null
    }
}

/** 默认一份不排他的，截图与预览里不经主界面也能用。 */
val LocalTaskSlot = staticCompositionLocalOf { TaskSlot() }

/** 切换前的确认，主界面里只画这一处。 */
@Composable
fun ClaimDialog(slot: TaskSlot) {
    val claim = slot.pending ?: return
    PikoDialog(
        onDismissRequest = slot::dismiss,
        title = { Text(claim.title) },
        text = { Text(claim.body) },
        confirmButton = { PikoDialogConfirm(claim.confirmLabel, slot::confirm) },
        dismissButton = { TextButton(onClick = slot::dismiss) { Text("取消") } },
    )
}
