package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.piko.PikoApplication
import dev.piko.shared.state.OfflineTasksState
import dev.piko.ui.screens.download.DownloadsScreen
import dev.piko.ui.screens.tasks.CloudTasksSheetContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onNavigateToInstant: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tasksState = remember { OfflineTasksState(PikoApplication.instance.taskRepository, scope) }
    val runningTasks = tasksState.activeTasks
    var showCloudTasksSheet by remember { mutableStateOf(false) }

    // 只在本页可见期间轮询。云端离线与本地下载同属「传输」，放在这里也免去了文件页常驻
    // 一个轮询。挂在 STARTED 上：应用退到后台时 LaunchedEffect 并不会取消，只靠它的话
    // 后台每 4 秒照样发一次请求
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(tasksState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tasksState.pollWhileVisible()
        }
    }

    DownloadsScreen(
        onPlayVideo = { task ->
            onNavigateToVideoPlayer(task.fileId, task.fileName, task.destinationPath)
        },
        topBarActions = {
            IconButton(onClick = { showCloudTasksSheet = true }) {
                BadgedBox(
                    badge = {
                        if (runningTasks.isNotEmpty()) {
                            Badge { Text("${runningTasks.size}") }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = "云端离线任务",
                        tint = if (runningTasks.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        modifier = modifier,
    )

    if (showCloudTasksSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCloudTasksSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = MaterialTheme.shapes.large,
        ) {
            CloudTasksSheetContent(
                runningTasks = runningTasks,
                onNewTask = {
                    showCloudTasksSheet = false
                    // 秒传入口在文件页的 FAB 上，这里只负责切过去
                    onNavigateToInstant()
                },
            )
        }
    }
}
