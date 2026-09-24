package dev.piko.ui.screens.transfers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.piko.download.DownloadTask
import dev.piko.shared.data.sourceUrl
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransfersState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import io.github.nihildigit.pikpak.OfflineTask
import kotlinx.coroutines.launch

/**
 * 传输页：本地下载与云端离线任务合为一个列表，按「进行中」「需要处理」「已完成」分段。
 * 分段与合并在 [TransfersState]，这里只负责渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onNavigateToInstant: () -> Unit = {},
    /** 跳到网盘里该文件所在目录。找不到（已移动或删除）时返回 false，由本页提示。 */
    onOpenCloudFile: suspend (fileId: String, fileName: String) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val services = LocalPikoServices.current
    val state = remember {
        TransfersState(services.downloadManager, services.taskRepository, services.offlinePacks, scope, services.driveRepository)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // 找不到文件的提示走本页的 Snackbar，不用系统 Toast：Toast 不跟随 M3 主题与配色
    val openCloudFileById = { fileId: String, fileName: String ->
        scope.launch {
            if (!onOpenCloudFile(fileId, fileName)) {
                snackbarHostState.showSnackbar("文件已不存在", withDismissAction = true)
            }
        }
        Unit
    }
    val openCloudFile = { task: OfflineTask -> openCloudFileById(task.fileId, task.fileName) }
    // 下载与网盘同受防窥开关约束。逐项揭示只在本次查看内有效，与网盘页的做法一致
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val revealedKeys = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun hasPreview(task: DownloadTask) = task.thumbnailLink.isNotEmpty() || task.destinationPath.isNotEmpty()

    // 只在本页可见期间轮询。挂在 STARTED 上：应用退到后台时 LaunchedEffect
    // 并不会取消，只靠它的话后台每 4 秒照样发一次请求
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.whileVisible()
        }
    }
    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 只记本次查看：这一组是低价值的历史，默认收起
    var deletedExpanded by rememberSaveable { mutableStateOf(false) }

    // 片段的 fileId 是源视频的，播放器找不到本地文件时会按它退回云端，放出来的是整段原片。
    // 片段只该播本地文件，不给 fileId，打不开就报错
    val playLocal = { task: DownloadTask ->
        onNavigateToVideoPlayer(if (task.isSegment) "" else task.fileId, task.fileName, task.destinationPath)
    }
    val resubmitAction = { task: OfflineTask -> task.sourceUrl?.let { { state.resubmitCloud(task) } } }

    // 记 key 而不是条目本身：进度每半秒刷新，面板要跟着显示最新状态；条目被移除时面板随之关闭
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }

    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = { PikoTopBar(title = "传输", scrollBehavior = topBarScrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            !state.isEmpty -> BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = readableSidePadding(maxWidth), vertical = 8.dp),
                ) {
                    val renderItem: @Composable (TransferItem, Modifier) -> Unit = { item, itemModifier ->
                        when (item) {
                            is TransferItem.Local -> LocalTransferRow(
                                task = item.task,
                                onPlay = { playLocal(item.task) },
                                onStart = { state.resumeLocal(item.task.taskId) },
                                onPause = { state.pauseLocal(item.task.taskId) },
                                onMoreClick = { detailsKey = item.key },
                                isSpoilerBlurred = isSpoilerBlurEnabled && item.key !in revealedKeys,
                                modifier = itemModifier,
                            )
                            is TransferItem.Cloud -> CloudTransferRow(
                                task = item.task,
                                thumbnail = state.thumbnailOf(item.task.fileId),
                                isSpoilerBlurred = isSpoilerBlurEnabled && item.key !in revealedKeys,
                                onResubmit = resubmitAction(item.task),
                                onOpen = { openCloudFile(item.task) },
                                onMoreClick = { detailsKey = item.key },
                                modifier = itemModifier,
                            )
                            is TransferItem.Pack -> PackTransferRow(
                                item = item,
                                thumbnail = state.thumbnailOf(item.job.outputId),
                                isSpoilerBlurred = isSpoilerBlurEnabled && item.key !in revealedKeys,
                                onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
                                onRetry = { state.retryPack(item.job.taskId) },
                                onMoreClick = { detailsKey = item.key },
                                modifier = itemModifier,
                            )
                        }
                    }
                    transferSection("进行中", state.inProgress, renderItem)
                    transferSection("需要处理", state.needsAttention, renderItem)
                    transferSection("已完成", state.completed, renderItem)
                    deletedOutputSection(
                        items = state.outputDeleted,
                        expanded = deletedExpanded,
                        onToggle = { deletedExpanded = !deletedExpanded },
                        renderItem = renderItem,
                    )
                }
            }
            // 云端列表首次取回之前不下结论，免得空状态一闪而过
            state.isLoading -> Unit
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                PikoEmptyState(
                    title = "暂无传输任务",
                    description = "本地下载与云端离线任务将显示于此",
                    icon = Icons.Outlined.SyncAlt,
                    actionText = "新建离线任务",
                    onActionClick = onNavigateToInstant,
                )
            }
        }
    }

    val detailsItem = detailsKey?.let { key ->
        sequenceOf(state.inProgress, state.needsAttention, state.completed, state.outputDeleted)
            .flatten()
            .firstOrNull { it.key == key }
    }
    val closeDetails = { detailsKey = null }
    when (detailsItem) {
        null -> Unit
        is TransferItem.Local -> LocalTransferSheet(
            task = detailsItem.task,
            onPlay = { playLocal(detailsItem.task) },
            onStart = { state.resumeLocal(detailsItem.task.taskId) },
            onPause = { state.pauseLocal(detailsItem.task.taskId) },
            onRemove = { state.removeLocal(detailsItem.task.taskId) },
            onDismiss = closeDetails,
            previewHidden = if (isSpoilerBlurEnabled && hasPreview(detailsItem.task)) {
                detailsItem.key !in revealedKeys
            } else {
                null
            },
            onTogglePreview = {
                if (!revealedKeys.remove(detailsItem.key)) revealedKeys.add(detailsItem.key)
            },
        )
        is TransferItem.Cloud -> CloudTransferSheet(
            task = detailsItem.task,
            onResubmit = resubmitAction(detailsItem.task),
            onDelete = { state.deleteCloud(detailsItem.task.id) },
            onOpen = { openCloudFile(detailsItem.task) },
            onDismiss = closeDetails,
        )
        is TransferItem.Pack -> PackTransferSheet(
            item = detailsItem,
            onOpen = { openCloudFileById(detailsItem.job.outputId, detailsItem.job.folderName) },
            onRetry = { state.retryPack(detailsItem.job.taskId) },
            onDiscard = { state.discardPack(detailsItem.job.taskId) },
            onDismiss = closeDetails,
        )
    }
}


private fun LazyListScope.transferSection(
    title: String,
    items: List<TransferItem>,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header:$title", contentType = "header") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 4.dp),
        )
    }
    items(items, key = { it.key }) { item ->
        renderItem(item, Modifier.animateItem())
    }
}

/**
 * 「文件已删除」组，排在最后，标题弱化且可收起。
 *
 * 展开与收起靠条目进出列表，由各项的 animateItem 做淡入淡出与位移。没有把条目包进
 * AnimatedVisibility：那样收起后条目仍留在列表里，只是高度为零。
 */
private fun LazyListScope.deletedOutputSection(
    items: List<TransferItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header:deleted", contentType = "header") {
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "chevron",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(top = 8.dp)
                .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件已删除",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${items.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
    if (expanded) {
        items(items, key = { it.key }) { item ->
            renderItem(item, Modifier.animateItem())
        }
    }
}
