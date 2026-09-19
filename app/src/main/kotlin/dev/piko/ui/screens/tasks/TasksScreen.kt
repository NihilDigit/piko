package dev.piko.ui.screens.tasks

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.PikoApplication
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.launch

/**
 * TasksScreen displays cloud offline download tasks with real-time status and pull-to-refresh.
 *
 * Documentation references:
 * - Lazy lists with keys: `android-docs-mirror/pages/develop/ui/compose/lists.md`
 * - Snackbars and user feedback: `m3-material-mirror/pages/components/snackbar.md`
 * - Material 3 cards & pull-to-refresh: `m3-material-mirror/pages/components/card.md`
 * - Coroutine error handling: `kotlin-docs-mirror/pages/docs/exception-handling.md`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onNavigateToInstant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskRepo = PikoApplication.instance.taskRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tasks by remember { mutableStateOf<List<OfflineTask>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    val fetchTasks = {
        scope.launch {
            val result = taskRepo.getTasks()
            isLoading = false
            isRefreshing = false
            result.onSuccess { response ->
                tasks = response.tasks
            }.onFailure { err ->
                snackbarHostState.showSnackbar("加载离线任务失败: ${err.message ?: "网络错误"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        fetchTasks()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = "云端离线任务",
                actions = {
                    IconButton(onClick = {
                        isRefreshing = true
                        fetchTasks()
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { innerPadding ->
        Crossfade(
            targetState = isLoading,
            animationSpec = PikoMotion.StateCrossfadeSpec,
            label = "tasks_crossfade",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { loading ->
            if (loading) {
                FullScreenLoading()
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        fetchTasks()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (tasks.isEmpty()) {
                        PikoEmptyState(
                            title = "当前无离线任务",
                            description = "添加磁力链接或链接开启云端高速离线下载",
                            actionText = "新建离线任务",
                            onActionClick = onNavigateToInstant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            items(tasks, key = { it.id }) { task ->
                                Box(modifier = Modifier.animateItem()) {
                                    TaskItemCard(task = task)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskItemCard(task: OfflineTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (statusText, statusColor, statusIcon) = when (task.phase) {
                    TaskPhase.COMPLETE -> Triple("已完成", LocalFixedColors.current.InstantMatchGreen, Icons.Outlined.CheckCircle)
                    TaskPhase.RUNNING -> Triple("下载中 ${task.progress}%", MaterialTheme.colorScheme.primary, Icons.Outlined.CloudSync)
                    TaskPhase.PENDING -> Triple("排队等待", LocalFixedColors.current.OfflinePendingBlue, Icons.Outlined.HourglassEmpty)
                    TaskPhase.ERROR -> Triple("失败", MaterialTheme.colorScheme.error, Icons.Outlined.ErrorOutline)
                    else -> Triple(task.phase, MaterialTheme.colorScheme.outline, Icons.Outlined.HourglassEmpty)
                }

                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.name.ifEmpty { "离线任务 #${task.id.take(8)}" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = statusColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (task.phase == TaskPhase.RUNNING) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (task.message.isNotEmpty() && task.phase == TaskPhase.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
