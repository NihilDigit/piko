package dev.piko.ui.workbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.ui.WorkMeter
import dev.piko.ui.WorkProgress
import dev.piko.ui.extractProgress
import dev.piko.ui.workProgress
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton

/**
 * 浮在窗口右下角的一摞任务卡片：解压、归档、取消归档、收起的添加链接。每样一张，收起时一行，展开就地长成一块面板，
 * 同一时刻只展开一张，其余照旧一行。整摞可以收成角落里的一颗胶囊。挂在主界面这一层，切到哪一页都在。
 * 只在宽窗口：查重在那里开在网盘页自己的标签里，不在这里。窄窗口里查重与添加链接在网盘页底部的 sheet 里，一次一件（TaskSlot）。
 *
 * 原来这些叠在网盘页底部的一条里，查重扫完又挪到命令栏右端的菜单。放在页面里的一栏（底部条、右侧栏、bottom sheet）
 * 一次只容得下一样，东西多了就得在栏里导航；浮动卡片相当于在一个窗口里开几个小窗口，互不挤占。
 * 照 M3 浮动工具栏：离窗口边缘 16dp、带阴影、浮在内容上而不占位。多项并行时不合成一张：
 * 每张各有自己的停止与结果，合起来反而要点开才找得到。
 */
@Composable
internal fun FloatingTasks(
    archive: ArchiveExtractSession,
    vault: FolderVaultSession,
    /** 收起的添加链接面板：一张卡片，点「继续」展开回面板，× 放弃。 */
    instant: InstantSession,
    modifier: Modifier = Modifier,
) {
    val discardInstant = rememberDiscardAddLink(instant)
    val tasks = buildList {
        if (archive.jobs.isNotEmpty()) add(extractTask(archive))
        vault.workProgress()?.let { add(vaultTask(vault, it)) }
        val instantState = instant.state
        if (instantState != null && !instant.isSheetOpen) add(instantTask(instantState, instant, discardInstant))
    }
    if (tasks.isEmpty()) return
    var minimized by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.width(CardWidth), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (minimized) {
            Surface(
                onClick = { minimized = false },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tasks.any { it.running }) InlineLoadingIndicator() else Icon(tasks.first().icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${tasks.size} 项", style = MaterialTheme.typography.labelLarge)
                }
            }
            return@Column
        }
        // 收起整摞的按钮在最上面那张卡片的右端：单独浮在卡片上方时不属于哪一张，像是多出来的一道横线
        tasks.forEachIndexed { index, task ->
            TaskCard(
                task,
                expanded = expanded == task.key,
                onToggle = { expanded = if (expanded == task.key) null else task.key },
                onMinimize = if (index == 0) ({ minimized = true }) else null,
            )
        }
    }
}

/** 一张卡片的内容。[icon] 在不转圈时显示；[detail] 为 null 时卡片不能展开。 */
private class FloatingTask(
    val key: String,
    val icon: ImageVector,
    val running: Boolean,
    val title: String,
    val status: String,
    val progress: Float? = null,
    val action: Pair<String, () -> Unit>? = null,
    val actionEnabled: Boolean = true,
    /** 丢掉这张卡片代表的东西，标签说清丢掉的是什么。在跑的不给，只给停止。 */
    val dismiss: Pair<String, () -> Unit>? = null,
    val detail: (@Composable () -> Unit)? = null,
)

@Composable
private fun TaskCard(task: FloatingTask, expanded: Boolean, onToggle: () -> Unit, onMinimize: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        // 尺寸动画放在卡片里面：animateContentSize 会把内容裁到自己的边界，挂在卡片外面时连阴影一起裁掉，右下角成了直角
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .then(if (task.detail != null) Modifier.clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle) else Modifier)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (task.running) InlineLoadingIndicator() else Icon(task.icon, contentDescription = null, tint = colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        task.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.progress?.let { value ->
                        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                task.action?.let { (label, onClick) -> TextButton(onClick = onClick, enabled = task.actionEnabled) { Text(label) } }
                if (task.detail != null) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(horizontal = 4.dp))
                }
                task.dismiss?.let { (label, onClick) -> TooltipIconButton(Icons.Outlined.Close, label, onClick) }
                onMinimize?.let { TooltipIconButton(Icons.Outlined.KeyboardArrowDown, "收到角落", it) }
            }
            AnimatedVisibility(expanded && task.detail != null) {
                Column(
                    Modifier
                        .heightIn(max = DetailMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    task.detail?.invoke()
                }
            }
        }
    }
}

@Composable
private fun DetailLine(title: String, supporting: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 卡片一行状态：主状态加次要说明，与传输页的行、Android 的通知同一份描述。 */
private val WorkProgress.cardStatus: String get() = listOfNotNull(status, detail).joinToString("，")

/** 卡片只画确定进度；不定的由行首转圈表示。 */
private val WorkProgress.cardProgress: Float? get() = (meter as? WorkMeter.Determinate)?.fraction

// 解压没有停止：服务端的任务提交后取消不了
private fun extractTask(archive: ArchiveExtractSession): FloatingTask {
    val jobs = archive.jobs
    val work = jobs.extractProgress()!!
    return FloatingTask(
        key = "extract",
        icon = Icons.Outlined.Lock,
        running = work.meter != WorkMeter.None,
        title = work.title,
        status = work.cardStatus,
        progress = work.cardProgress,
        detail = if (jobs.size > 1) ({ jobs.forEach { job -> DetailLine(job.file.name, job.workProgress().status) } }) else null,
    )
}

// 归档与取消归档同一时刻只有一件，卡片的 key 仍按种类分开，换了一种时展开状态不沿用
private fun vaultTask(vault: FolderVaultSession, work: WorkProgress): FloatingTask = FloatingTask(
    key = if (vault.progress != null) "archive" else "restore",
    icon = Icons.Outlined.FileCopy,
    running = true,
    title = work.title,
    status = work.cardStatus,
    progress = work.cardProgress,
    action = "停止" to vault::stop,
    actionEnabled = !vault.stopping,
)

// 不是后台任务，是关了但没做完的面板：解析、挑文件都在面板里，卡片只负责找回与放弃
private fun instantTask(state: InstantSheetState, session: InstantSession, discard: () -> Unit): FloatingTask {
    val summary = state.summary()
    return FloatingTask(
        key = "instant",
        icon = Icons.Outlined.Bolt,
        running = summary.resolving,
        title = summary.title,
        status = summary.status ?: "添加链接",
        action = "继续" to session::reopen,
        dismiss = ("放弃这次添加" to discard).takeIf { !summary.busy },
    )
}

private val CardWidth = 380.dp
private val DetailMaxHeight = 320.dp
