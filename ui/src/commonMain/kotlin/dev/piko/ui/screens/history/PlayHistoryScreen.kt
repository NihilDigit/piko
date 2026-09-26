package dev.piko.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.PlayHistoryScreenState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.components.FileLeadingVisual
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.FirstScreenState
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.player.formatTime
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.FileStat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 播放历史：PikPak 服务端记下的播放记录，与官方客户端共用，最近的在前，滚到底自动取下一页。
 *
 * 单击播放；文件已被删除时服务端不再内嵌文件，这一行只能删掉。每行写出看到哪里与上次播放的时间，
 * 下面一条细进度条。清空要确认：服务端没有撤销，官方客户端里的历史也会一起没了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayHistoryScreen(
    onBackClick: (() -> Unit)?,
    onPlay: (FileStat) -> Unit,
    onLocate: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val services = LocalPikoServices.current
    val state = remember { PlayHistoryScreenState(services.driveRepository, scope) }
    val listState = rememberLazyListState()

    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    var detailsFor by remember { mutableStateOf<DriveEvent?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.load()
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    // 离底部还有几行时就取下一页，滚到底时新内容已经在了
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(state, listState) {
        snapshotFlow { nearEnd }.collect { if (it) state.loadMore() }
    }

    fun play(event: DriveEvent) {
        val file = event.file
        if (file == null) {
            scope.launch { snackbarHostState.showSnackbar("文件已删除", withDismissAction = true) }
        } else {
            onPlay(file)
        }
    }

    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                scrollBehavior = topBarScrollBehavior,
                title = "播放历史",
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    TooltipIconButton(
                        icon = Icons.Outlined.Refresh,
                        label = "刷新",
                        onClick = { state.load(refresh = true) },
                        enabled = !state.isRefreshing,
                    )
                    if (state.events.isNotEmpty()) {
                        TooltipIconButton(
                            icon = Icons.Outlined.DeleteSweep,
                            label = "清空播放历史",
                            onClick = { confirmClear = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        var containerWidth by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val sidePadding = readableSidePadding(containerWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding)
                .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
        ) {
            FirstScreenState(
                isLoading = state.isLoading,
                error = state.loadError,
                isEmpty = state.events.isEmpty(),
                onRetry = { state.load() },
                skeleton = { FileListSkeleton(Modifier.padding(horizontal = sidePadding)) },
            ) {
                RefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { state.load(refresh = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            bottom = innerPadding.calculateBottomPadding() + 16.dp,
                        ),
                    ) {
                        if (state.events.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    PikoEmptyState(
                                        title = "暂无播放记录",
                                        description = "包含 PikPak 各客户端的播放记录",
                                        icon = Icons.Outlined.History,
                                    )
                                }
                            }
                        } else {
                            items(items = state.events, key = { it.id }) { event ->
                                HistoryRow(
                                    event = event,
                                    isSpoilerBlurred = isSpoilerBlurEnabled,
                                    onClick = { play(event) },
                                    onMoreClick = { detailsFor = event },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            if (state.isLoadingMore) {
                                item(key = "loading_more") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        InlineLoadingIndicator()
                                    }
                                }
                            }
                        }
                    }
                    LocalPikoPlatform.current.ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    detailsFor?.let { event ->
        val file = event.file
        ItemDetailsSheet(
            title = file?.name ?: event.fileName,
            headerIcon = {
                if (file != null) FileTypeIcon(file = file, iconSize = 24.dp, modifier = Modifier.fillMaxSize())
            },
            metaParts = historyMetaParts(event),
            onDismiss = { detailsFor = null },
            actions = buildList {
                if (file != null) {
                    add(SheetAction(Icons.Outlined.PlayArrow, "播放", onClick = { onPlay(file) }))
                    add(SheetAction(Icons.Outlined.FolderOpen, "在网盘中显示", onClick = { onLocate(file) }))
                }
                add(SheetAction(Icons.Outlined.DeleteOutline, "删除记录", { state.delete(event) }, destructive = true))
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空播放历史") },
            text = { Text("将删除全部播放记录，官方客户端同步清空，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        state.clearAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    event: DriveEvent,
    isSpoilerBlurred: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = event.file
    val progress = watchedFraction(event)
    FileListItem(
        headline = (file?.name ?: event.fileName).substringBeforeLast('.'),
        leading = {
            if (file != null) {
                FileLeadingVisual(file = file, isSpoilerBlurred = isSpoilerBlurred && file.thumbnailLink.isNotEmpty())
            }
        },
        supporting = {
            Column {
                MetaRow(parts = historyMetaParts(event))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                    )
                }
            }
        },
        onClick = onClick,
        onMoreClick = onMoreClick,
        dimmed = file == null,
        modifier = modifier,
    )
}

/** 看到哪里、上次播放的时间；文件已删时注明。 */
private fun historyMetaParts(event: DriveEvent): List<String> = listOfNotNull(
    "文件已删除".takeIf { event.file == null },
    event.playSeconds?.let { seconds ->
        val watched = formatTime(seconds * 1000)
        event.playDuration?.takeIf { it > 0 }?.let { "播放至 $watched / ${formatTime(it * 1000)}" } ?: "播放至 $watched"
    },
    formatPlayedAt(event.updatedTime),
)

private fun watchedFraction(event: DriveEvent): Float? {
    val duration = event.playDuration?.takeIf { it > 0 } ?: return null
    val seconds = event.playSeconds ?: return null
    return (seconds.toFloat() / duration).coerceIn(0f, 1f)
}

/** 今天、昨天写到分钟，更早的只写日期；跨年时带上年份。解析不了时不写。 */
private fun formatPlayedAt(rfc3339: String): String? = runCatching {
    val time = OffsetDateTime.parse(rfc3339).atZoneSameInstant(ZoneId.systemDefault())
    val today = LocalDate.now()
    val date = time.toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    when {
        date == today -> "今天 $clock"
        date == today.minusDays(1) -> "昨天 $clock"
        date.year == today.year -> time.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.getDefault()))
        else -> time.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.getDefault()))
    }
}.getOrNull()

private const val LOAD_MORE_THRESHOLD = 5
