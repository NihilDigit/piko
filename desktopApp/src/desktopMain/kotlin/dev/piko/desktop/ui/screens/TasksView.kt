package dev.piko.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.desktop.ui.components.getFileIcon
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.TaskRepository
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.surface.Card
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.launch

@Composable
fun TasksView(
    manager: PikoClientManager,
    modifier: Modifier = Modifier,
    initialMagnetUrl: String? = null,
) {
    val taskRepo = remember(manager) { TaskRepository(manager) }
    val instantRepo = remember(manager) { InstantMagnetRepository(manager) }
    val scope = rememberCoroutineScope()

    var tasks by remember { mutableStateOf<List<OfflineTask>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    var showNewTaskDialog by remember(initialMagnetUrl) { mutableStateOf(initialMagnetUrl != null) }
    var inputUrl by remember(initialMagnetUrl) { mutableStateOf(initialMagnetUrl ?: "") }
    var isSubmitting by remember { mutableStateOf(false) }

    val fetchTasks: () -> Unit = {
        scope.launch {
            isLoading = true
            errorMessage = null
            taskRepo.getTasks()
                .onSuccess { response ->
                    tasks = response.tasks
                    isLoading = false
                }
                .onFailure { err ->
                    errorMessage = "加载离线任务失败: ${err.message ?: "网络错误"}"
                    isLoading = false
                }
        }
    }

    LaunchedEffect(Unit) {
        fetchTasks()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "云端离线下载",
                    style = FluentTheme.typography.title,
                )
                Text(
                    text = "添加磁力链或 HTTP/HTTPS 链接，由云端服务器秒传转存",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(
                    onClick = { showNewTaskDialog = true },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Regular.Add, contentDescription = "新建任务", modifier = Modifier.size(16.dp))
                        Text("新建任务")
                    }
                }

                Button(
                    onClick = { fetchTasks() },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Regular.ArrowSync, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                        Text("刷新")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Error or Success Bar
        errorMessage?.let { msg ->
            InfoBar(
                title = { Text("错误") },
                message = { Text(msg) },
                severity = io.github.composefluent.component.InfoBarSeverity.Critical,
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { errorMessage = null })
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }

        successMessage?.let { msg ->
            InfoBar(
                title = { Text("成功") },
                message = { Text(msg) },
                severity = io.github.composefluent.component.InfoBarSeverity.Success,
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { successMessage = null })
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }

        // Main content
        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProgressRing(size = ProgressRingSize.Medium)
                    Text("加载云端离线任务中…", color = FluentTheme.colors.text.text.secondary)
                }
            }
        } else if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Regular.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = FluentTheme.colors.text.text.tertiary,
                    )
                    Text(
                        text = "暂无云端离线任务",
                        style = FluentTheme.typography.bodyLarge,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    AccentButton(onClick = { showNewTaskDialog = true }) {
                        Text("新建离线任务")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task = task)
                }
            }
        }
    }

    // New Task ContentDialog
    if (showNewTaskDialog) {
        ContentDialog(
            title = "新建离线下载任务",
            visible = showNewTaskDialog,
            primaryButtonText = if (isSubmitting) "添加中…" else "立即转存",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        val url = inputUrl.trim()
                        if (url.isNotBlank() && !isSubmitting) {
                            isSubmitting = true
                            scope.launch {
                                instantRepo.enqueueOfflineTask(url)
                                    .onSuccess {
                                        successMessage = "离线任务已提交至云端"
                                        inputUrl = ""
                                        showNewTaskDialog = false
                                        isSubmitting = false
                                        fetchTasks()
                                    }
                                    .onFailure { err ->
                                        errorMessage = "提交离线任务失败: ${err.message ?: "未知错误"}"
                                        isSubmitting = false
                                    }
                            }
                        }
                    }
                    ContentDialogButton.Close -> {
                        if (!isSubmitting) {
                            showNewTaskDialog = false
                        }
                    }
                    else -> {}
                }
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "支持 magnet:?xt=urn:btih: 磁力链接或 http/https 直链",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    TextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        placeholder = { Text("粘贴下载链接…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

@Composable
private fun TaskCard(task: OfflineTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val displayName = task.name.ifEmpty { "离线任务 #${task.id.take(8)}" }
                    Icon(
                        imageVector = getFileIcon(name = displayName, isFolder = false),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Column {
                        Text(
                            text = displayName,
                            style = FluentTheme.typography.bodyStrong,
                        )
                        val statusDesc = when (task.phase) {
                            TaskPhase.COMPLETE -> "已完成 · 云端就绪"
                            TaskPhase.RUNNING -> "云端下载中 · ${task.progress}%"
                            TaskPhase.PENDING -> "排队等待中…"
                            TaskPhase.ERROR -> "下载失败 · ${task.message.ifEmpty { "未知错误" }}"
                            else -> task.phase
                        }
                        Text(
                            text = statusDesc,
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    }
                }

                // Phase badge
                val phaseText = when (task.phase) {
                    TaskPhase.COMPLETE -> "已完成"
                    TaskPhase.RUNNING -> "${task.progress}%"
                    TaskPhase.PENDING -> "排队"
                    TaskPhase.ERROR -> "失败"
                    else -> task.phase
                }
                Text(
                    text = phaseText,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }

            if (task.phase == TaskPhase.RUNNING) {
                ProgressBar(
                    progress = (task.progress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
