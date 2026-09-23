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
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.desktop.ui.components.getFileIcon
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.OfflineTasksState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.icons.regular.Flash
import io.github.composefluent.surface.Card
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase

/**
 * 云端离线任务与秒传入口。
 *
 * 任务列表与轮询在 [OfflineTasksState]，秒传与磁力解析在 [InstantDialog] 背后的
 * InstantSheetState，都与 Android 共用。这个视图只在选中「离线任务」一节时处于组合中，
 * 轮询随之启停。
 */
@Composable
fun TasksView(
    manager: PikoClientManager,
    driveRepository: PikoDriveRepository,
    preferences: PikoUserPreferences,
    onOpenFolder: (PikoPathBreadcrumb) -> Unit,
    modifier: Modifier = Modifier,
    pendingMagnet: String? = null,
    onPendingMagnetConsumed: () -> Unit = {},
) {
    val taskRepo = remember(manager, driveRepository) { TaskRepository(manager, driveRepository) }
    val instantRepo = remember(manager) { InstantMagnetRepository(manager) }
    val scope = rememberCoroutineScope()
    val tasksState = remember(taskRepo) { OfflineTasksState(taskRepo, scope) }
    val tasks = tasksState.tasks
    val isLoading = tasksState.isLoading

    // 一次性提示：拉取失败或保存成功。loadError 另作长驻提示，两者分开，免得一次成功提示
    // 把「列表不是最新的」冲掉
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    // 秒传对话框的初始输入，null 表示对话框关闭
    var instantInput by remember { mutableStateOf<String?>(null) }

    // 协议唤起带进来的磁力链只用一次。旧实现以它为 remember 的 key，每次切回这一页
    // 视图重建，同一条链又弹一次对话框
    LaunchedEffect(pendingMagnet) {
        if (pendingMagnet != null) {
            instantInput = pendingMagnet
            onPendingMagnetConsumed()
        }
    }

    LaunchedEffect(tasksState) {
        tasksState.pollWhileVisible()
    }
    LaunchedEffect(tasksState) {
        tasksState.messages.collect { errorMessage = it }
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
                    onClick = { instantInput = "" },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Regular.Flash, contentDescription = "秒传与离线", modifier = Modifier.size(16.dp))
                        Text("秒传 / 新建任务")
                    }
                }

                Button(
                    onClick = tasksState::refresh,
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

        // 轮询失败时列表停在上一次的内容，要一直挂着说明，直到下一次拉取成功
        if (errorMessage == null) {
            tasksState.loadError?.let { reason ->
                InfoBar(
                    title = { Text("任务列表可能不是最新的") },
                    message = { Text(reason) },
                    severity = io.github.composefluent.component.InfoBarSeverity.Warning,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
        }

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
                    AccentButton(onClick = { instantInput = "" }) {
                        Text("秒传或新建离线任务")
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

    instantInput?.let { initial ->
        InstantDialog(
            instantRepository = instantRepo,
            driveRepository = driveRepository,
            preferences = preferences,
            initialMagnet = initial,
            onDismiss = { instantInput = null },
            onSaved = { outcome ->
                instantInput = null
                when (outcome) {
                    is InstantSaveOutcome.InstantSaved -> {
                        // 秒传是同步完成的，文件已在网盘里，直接带用户过去
                        successMessage = "已秒传 ${outcome.createdIds.size} 项到 ${outcome.target.name}"
                        onOpenFolder(outcome.target)
                    }
                    is InstantSaveOutcome.OfflineTaskCreated -> {
                        // 离线任务要等云端下完，留在任务页看进度
                        successMessage = "离线任务已提交，完成后保存到 ${outcome.target.name}"
                        tasksState.refresh()
                    }
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
                        // 状态词与详情各占一个 Text，靠间距分开，不拼分隔符
                        val statusParts = when (task.phase) {
                            TaskPhase.COMPLETE -> listOf("已完成", "云端就绪")
                            TaskPhase.RUNNING -> listOf("云端下载中", "${task.progress}%")
                            TaskPhase.PENDING -> listOf("排队等待中")
                            TaskPhase.ERROR -> listOf("下载失败", task.message.ifEmpty { "未知错误" })
                            else -> listOf(task.phase)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            statusParts.forEach { part ->
                                Text(
                                    text = part,
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.secondary,
                                )
                            }
                        }
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
