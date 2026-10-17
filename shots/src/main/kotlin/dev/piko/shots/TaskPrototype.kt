package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.piko.ui.theme.frame
import dev.piko.ui.theme.islandHeader
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.LocalPikoServices
import dev.piko.data.auth.SnailMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class TaskGroup(val title: String) { Attention("需要处理"), Running("进行中"), Done("已完成") }
private data class TaskSample(val id: String, val title: String, val kind: String, val status: String, val supporting: String, val group: TaskGroup, val icon: ImageVector, val progress: Float? = null)
private val taskSamples = listOf(
    TaskSample("password", "摄影素材合集.zip", "解压", "需要解压密码", "目标：我的文件 / 摄影素材", TaskGroup.Attention, Icons.Outlined.FolderZip),
    TaskSample("uncertain", "旅行影像 · 复制 12 项", "文件处理", "等待核对结果", "连接中断，服务端可能已完成部分操作", TaskGroup.Attention, Icons.Outlined.ContentCopy),
    TaskSample("archive", "旅行影像", "归档", "正在补查引用", "已确认 320 项 · 还有 12 项待核对", TaskGroup.Running, Icons.Outlined.Inventory2),
    TaskSample("upload", "秋日照片 · 24 张", "上传", "8 / 24 项 · 4.2 MB/s", "650 MB / 1.8 GB", TaskGroup.Running, Icons.Outlined.Upload, .36f),
    TaskSample("download", "山间的一年 · 第一季", "下载", "正在下载 · 12.6 MB/s", "2.1 GB / 4.8 GB", TaskGroup.Running, Icons.Outlined.Download, .44f),
    TaskSample("reference", "山间的一年", "保存引用", "已保存 32 项引用", "旅行影像 / 山间的一年 · 10 分钟前", TaskGroup.Done, Icons.Outlined.CheckCircle),
)
internal class TaskPrototypeState {
    var snail by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)
    var filter by mutableStateOf("全部")
    var kind by mutableStateOf("全部类型")
    var kindMenu by mutableStateOf(false)
    var checked by mutableStateOf(false)
    var password by mutableStateOf("")
    var passwordSubmitted by mutableStateOf(false)
    var paused by mutableStateOf(false)
}

@Composable internal fun TaskPrototype(state: TaskPrototypeState) {
    val snail by LocalPikoServices.current.preferences.snailModeFlow.collectAsState(initial = SnailMode())
    val samples = taskSamples.map {
        when {
            it.id == "uncertain" && state.checked -> it.copy(status = "已核对 · 12 项复制完成", group = TaskGroup.Done)
            it.id == "password" && state.passwordSubmitted -> it.copy(status = "密码已提交 · 服务端解压中", group = TaskGroup.Running)
            it.id == "upload" && state.paused -> it.copy(status = "已暂停 · 8 / 24 项")
            it.id == "upload" && snail.enabled -> it.copy(status = "8 / 24 项 · 512 KB/s")
            it.id == "download" && snail.enabled -> it.copy(status = "正在下载 · 1 MB/s")
            else -> it
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.frame)) {
        val wide = maxWidth >= 1000.dp
        val rail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (rail) DemoRail(1, snail.enabled)
            Column(Modifier.weight(1f).fillMaxHeight().padding(if (rail) 12.dp else 0.dp)) {
                Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.frame) {
                    val selected = samples.firstOrNull { it.id == state.selected }
                    if (!wide && selected != null) TaskDetails(selected, state, Modifier.fillMaxSize(), false)
                    else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskList(samples, state, Modifier.weight(if (wide) 1.15f else 1f))
                        if (wide) {
                            if (selected != null) TaskDetails(selected, state, Modifier.weight(1f).fillMaxHeight(), true)
                            else Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("选择任务，查看进度与结果", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (!rail) DemoBottomNav(1, snail.enabled)
            }
        }
    }
}

@Composable private fun TaskList(samples: List<TaskSample>, state: TaskPrototypeState, modifier: Modifier) {
    val snail by LocalPikoServices.current.preferences.snailModeFlow.collectAsState(initial = SnailMode())
    Column(modifier.fillMaxHeight().clip(FrameCardShape).background(MaterialTheme.colorScheme.islandHeader)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("任务", style = MaterialTheme.typography.titleLarge)
                Text("${samples.count { it.group == TaskGroup.Running }} 项进行中 · ${samples.count { it.group == TaskGroup.Attention }} 项需要处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "任务选项") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (snail.enabled) "↓ 1 MB/s  ↑ 512 KB/s" else "↓ 12.6 MB/s  ↑ 4.2 MB/s", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SnailModeToggle()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "已完成").forEach { title -> FilterChip(state.filter == title, { state.filter = title }, label = { Text(title) }) }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { state.kindMenu = true }) { Text(state.kind); Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp)) }
                DropdownMenu(state.kindMenu, { state.kindMenu = false }) {
                    listOf("全部类型", "上传", "下载", "解压", "归档", "保存引用", "文件处理").forEach { kind -> DropdownMenuItem(text = { Text(kind) }, onClick = { state.kind = kind; state.kindMenu = false }) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 20.dp)) {
            TaskGroup.entries.forEach { group ->
                val rows = samples.filter { it.group == group && (state.filter != "已完成" || group == TaskGroup.Done) && (state.kind == "全部类型" || state.kind == it.kind) }
                if (rows.isNotEmpty()) {
                    Row(Modifier.padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall, color = if (group == TaskGroup.Attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  ${rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rows.forEach { task -> TaskSummary(task, state.selected == task.id) { state.selected = task.id } }
                    }
                }
            }
        }
    }
}

@Composable private fun TaskSummary(task: TaskSample, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Icon(task.icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = if (task.group == TaskGroup.Attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${task.kind} · ${task.status}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp), color = if (task.group == TaskGroup.Attention) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.progress != null) LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp))
                Text(task.supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TaskDetails(task: TaskSample, state: TaskPrototypeState, modifier: Modifier, wide: Boolean) {
    Column(modifier.clip(FrameCardShape).background(MaterialTheme.colorScheme.islandHeader).padding(horizontal = 20.dp)) {
        Row(Modifier.height(64.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) IconButton(onClick = { state.selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回任务") }
            Text("任务详情", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (wide) IconButton(onClick = { state.selected = null }) { Icon(Icons.Outlined.Close, "关闭详情") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(task.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleLarge)
                    Text(task.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.status, style = MaterialTheme.typography.titleSmall)
                    Text(when (task.id) {
                        "archive" -> "已从来源链接取得 320 项引用，正在补查其余 12 项。确认引用清单后再处理原文件。"
                        "uncertain" -> if (state.checked) "已确认 12 项均已复制，无需重新提交。" else "请求后连接中断。先核对服务端结果，避免重复复制。"
                        "password" -> if (state.passwordSubmitted) "服务端正在处理，离开此页面不会结束解压。" else "压缩包已找到，输入密码后继续解压。"
                        "reference" -> "32 项已直接写入归档记录，本次未执行秒传。"
                        else -> task.supporting
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.id == "archive") {
                Text("执行阶段", style = MaterialTheme.typography.titleSmall)
                listOf("读取来源" to "已完成 · 取得批量引用", "核对文件" to "进行中 · 已确认 320 / 332 项", "写入归档" to "等待引用核对完成", "处理原文件" to "只处理已成功归档的项目").forEachIndexed { i, (title, description) ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (i == 0) Icons.Outlined.CheckCircle else if (i == 1) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(22.dp), tint = if (i < 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Column(Modifier.padding(start = 12.dp)) { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (i == 1) FontWeight.Medium else FontWeight.Normal); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            if (task.id == "password" && !state.passwordSubmitted) OutlinedTextField(state.password, { state.password = it }, label = { Text("解压密码") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(4.dp))
            DetailField("来源", if (task.id == "archive") "我的文件 / 影像 / 旅行影像" else "我的文件 / 待整理")
            DetailField("目标", if (task.id == "reference" || task.id == "archive") "归档 / 旅行影像" else "我的文件 / 旅行影像")
            DetailField("开始时间", "今天 09:42")
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
            when (task.id) {
                "uncertain" -> Button(onClick = { state.checked = true }) { Text(if (state.checked) "核对完成" else "重新核对") }
                "password" -> Button(onClick = { state.passwordSubmitted = true }, enabled = state.password.isNotBlank() && !state.passwordSubmitted) { Text("继续解压") }
                "upload" -> FilledTonalButton(onClick = { state.paused = !state.paused }) { Text(if (state.paused) "继续上传" else "暂停上传") }
                "archive" -> Text("任务继续在后台执行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> FilledTonalButton(onClick = {}) { Text(if (task.group == TaskGroup.Done) "查看文件" else "查看下载位置") }
            }
        }
    }
}

@Composable private fun DetailField(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp)) }
}
