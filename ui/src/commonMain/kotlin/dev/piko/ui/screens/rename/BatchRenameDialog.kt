package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.state.BatchRenameState
import dev.piko.shared.state.BatchRenameState.Phase
import dev.piko.shared.state.RenameProblem
import dev.piko.shared.state.RenameRow
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat

/**
 * 批量重命名。规则与逐行预览在同一页，点「重命名」即确认，此前不发任何改名请求。
 *
 * [onFinished] 在执行结束时收到一句结果，由调用方显示并退出多选。全部成功时对话框随即关闭；
 * 有失败或中途停止时留着，列出没改成的项，由用户点「完成」关闭。执行期间不能关闭，
 * 状态的协程作用域随对话框走，关掉就断在半路。
 */
@Composable
fun BatchRenameDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
    onFinished: (message: String) -> Unit,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val scope = rememberCoroutineScope()
    val state = remember(files) { BatchRenameState(driveRepo, scope, files) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    // 结果与关闭放在同一个协程里依次做：分成两个 effect 的话，对话框可能先关掉，结果就收不到了
    LaunchedEffect(state) {
        state.messages.collect { message ->
            latestOnFinished(message)
            if (state.failures.isEmpty() && !state.wasStopped) latestOnDismiss()
        }
    }
    val dismiss = { if (state.phase != Phase.RUNNING) onDismiss() }

    // 与目录选择器相同：compact 下全屏，更宽时是居中的对话框
    if (currentWidthClass() == WidthClass.Compact) {
        LocalPikoPlatform.current.FullscreenDialog(
            onDismiss = dismiss,
            immersive = false,
            systemBarsVisible = true,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                // 底部由底栏自己让开手势横条，底色才能铺到横条下面
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    BatchRenameContent(state, dismiss)
                }
            }
        }
    } else {
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.85f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                BatchRenameContent(state, dismiss)
            }
        }
    }
}

@Composable
private fun BatchRenameContent(state: BatchRenameState, onClose: () -> Unit) {
    val listState = rememberLazyListState()
    // 有问题的排在前面：几百项里只有一两项冲突时，不必滚到底去找
    val rows by remember(state) {
        derivedStateOf {
            if (state.phase == Phase.DONE) state.failures.toList()
            else state.plan.rows.sortedBy { it.problem == null }
        }
    }
    val editable = state.phase == Phase.EDITING

    Column(modifier = Modifier.fillMaxSize()) {
        PikoTopBar(
            title = "批量重命名 ${state.sources.size} 项",
            navigationIcon = {
                IconButton(onClick = onClose, enabled = state.phase != Phase.RUNNING) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭")
                }
            },
            // 宽窗口下底色是对话框的容器色，顶栏自带的 surface 色会显出一条色带
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (state.phase != Phase.DONE) {
                    item(key = "rules") { RenameRules(state, enabled = editable) }
                }
                item(key = "header") { PreviewHeader(state) }
                items(rows, key = { it.source.id }) { row -> PreviewRow(row) }
            }
            LocalPikoPlatform.current.ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RenameActionBar(state, onClose)
    }
}

@Composable
private fun RenameRules(state: BatchRenameState, enabled: Boolean) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val detected = state.detected
        if (detected.prefix.isEmpty() && detected.suffix.isEmpty()) {
            Text(
                text = "所选名称没有共同的开头或结尾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detected.prefix.isNotEmpty()) {
            AffixToggle("去掉共同开头", detected.prefix, state.stripPrefix, enabled) { state.stripPrefix = it }
        }
        if (detected.suffix.isNotEmpty()) {
            AffixToggle("去掉共同结尾", detected.suffix, state.stripSuffix, enabled) { state.stripSuffix = it }
        }
        OutlinedTextField(
            value = state.find,
            onValueChange = { state.find = it },
            label = { Text("查找") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
        )
        OutlinedTextField(
            value = state.replacement,
            onValueChange = { state.replacement = it },
            label = { Text("替换为") },
            singleLine = true,
            enabled = enabled,
            supportingText = { Text("只改扩展名之前的部分，留空即删除查找到的文字") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
        )
    }
}

/** 识别出的前缀或后缀原样显示，用户据此判断该不该去掉。 */
@Composable
private fun AffixToggle(label: String, affix: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                // 首尾的空格在界面上看不出来，用引号框住
                text = "「$affix」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewHeader(state: BatchRenameState) {
    val text = when {
        state.phase != Phase.DONE -> "预览"
        state.failures.isNotEmpty() -> "以下 ${state.failures.size} 项未能重命名"
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PreviewRow(row: RenameRow) {
    val problem = row.problem
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.source.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.isChanged) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.isChanged) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = "改为",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = row.newName.ifEmpty { "（空）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (problem != null) {
                Text(problemLabel(problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (!row.isChanged) {
            Text("不改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun problemLabel(problem: RenameProblem): String = when (problem) {
    RenameProblem.EMPTY -> "去掉后名称为空"
    RenameProblem.TAKEN -> "与同目录已有的名称重复"
    RenameProblem.DUPLICATE -> "与所选其他项的新名称重复"
    RenameProblem.CYCLE -> "与所选其他项的名称互换，无法依次改名"
}

@Composable
private fun RenameActionBar(state: BatchRenameState, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state.phase) {
            Phase.EDITING -> EditingStatus(state)
            Phase.RUNNING -> {
                Text(
                    text = "正在重命名 ${state.processed}/${state.total}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.processed.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Phase.DONE -> {
                val succeeded = state.processed - state.failures.size
                Text(
                    text = if (state.wasStopped) "已停止，重命名了 $succeeded 项" else "已重命名 $succeeded 项，${state.failures.size} 项失败",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            when (state.phase) {
                Phase.EDITING -> {
                    OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.medium) { Text("取消") }
                    Button(onClick = state::rename, enabled = state.canRename, shape = MaterialTheme.shapes.medium) {
                        Text(if (state.plan.order.isEmpty()) "重命名" else "重命名 ${state.plan.order.size} 项")
                    }
                }
                Phase.RUNNING -> OutlinedButton(onClick = state::stop, shape = MaterialTheme.shapes.medium) { Text("停止") }
                Phase.DONE -> Button(onClick = onClose, shape = MaterialTheme.shapes.medium) { Text("完成") }
            }
        }
    }
}

@Composable
private fun EditingStatus(state: BatchRenameState) {
    val problemCount = state.plan.problemCount
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when {
                state.isCheckingSiblings -> "正在检查同目录的名称"
                state.siblingsFailed -> "无法检查同目录的名称"
                problemCount > 0 -> "$problemCount 项有问题，处理后才能重命名"
                state.plan.order.isEmpty() -> "按当前规则，没有需要改名的项"
                else -> {
                    val unchanged = state.sources.size - state.plan.order.size
                    "将重命名 ${state.plan.order.size} 项" + if (unchanged > 0) "，$unchanged 项不改" else ""
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.siblingsFailed || problemCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.siblingsFailed) TextButton(onClick = state::loadSiblings) { Text("重试") }
    }
}
