package dev.piko.ui.screens.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.piko.shared.state.MySharesState
import dev.piko.shared.state.ShareCreateState
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
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ShareStatus
import io.github.nihildigit.pikpak.ShareSummary
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 我的分享：自己创建的分享链接，与官方客户端共用。单击复制链接与提取码，
 * 更多面板里另有在浏览器打开与取消分享。取消后链接即失效、无法恢复，先确认一次。
 *
 * [onBackClick] 为 null 时不显示返回按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySharesScreen(
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val state = remember { MySharesState(services.driveRepository, scope) }
    val listState = rememberLazyListState()
    var detailsFor by remember { mutableStateOf<ShareSummary?>(null) }
    var confirmCancel by remember { mutableStateOf<ShareSummary?>(null) }

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

    fun copy(share: ShareSummary) {
        platform.copyToClipboard("分享链接", ShareCreateState.shareText(share.title, share.shareUrl, share.passCode))
        scope.launch { snackbarHostState.showSnackbar("已复制分享链接", withDismissAction = true) }
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
                title = "我的分享",
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
                isEmpty = state.shares.isEmpty(),
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
                        if (state.shares.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    PikoEmptyState(
                                        title = "暂无分享",
                                        description = "在文件菜单中选择「分享」后显示于此",
                                    )
                                }
                            }
                        } else {
                            items(items = state.shares, key = { it.shareId }) { share ->
                                FileListItem(
                                    headline = share.title,
                                    leading = { FileLeadingVisual(file = share.leadingFile(), isSpoilerBlurred = false) },
                                    supporting = {
                                        MetaRow(
                                            parts = share.metaParts(),
                                            color = if (share.isOk) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    },
                                    onClick = { copy(share) },
                                    onMoreClick = { detailsFor = share },
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
                    platform.ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    detailsFor?.let { share ->
        ItemDetailsSheet(
            title = share.title,
            headerIcon = { FileTypeIcon(file = share.leadingFile(), iconSize = 24.dp, modifier = Modifier.fillMaxSize()) },
            metaParts = share.metaParts(),
            onDismiss = { detailsFor = null },
            extraLines = {
                if (share.passCode.isNotEmpty()) {
                    Text("提取码 ${share.passCode}", color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = listOf(
                SheetAction(Icons.Outlined.Link, if (share.passCode.isEmpty()) "复制链接" else "复制链接与提取码", onClick = { copy(share) }),
                SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在浏览器中打开", onClick = { platform.openUrl(share.shareUrl) }),
                SheetAction(Icons.Outlined.LinkOff, "取消分享", onClick = { confirmCancel = share }, destructive = true),
            ),
        )
    }

    confirmCancel?.let { share ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            icon = { Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("取消分享") },
            text = { Text("链接将立即失效且无法恢复，网盘中的文件不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = null
                        state.cancel(share)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("取消分享")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = null }) { Text("保留") }
            },
        )
    }
}

private const val LOAD_MORE_THRESHOLD = 5

/** 只为借用文件类型图标。多项的分享服务端给的是第一项的类型。 */
private fun ShareSummary.leadingFile() = FileStat(kind = fileKind, name = title)

/**
 * 失效的分享只写原因：链接已打不开，浏览与转存次数没有意义。
 * 正常的写项数或大小、有效期、浏览与转存次数。
 */
private fun ShareSummary.metaParts(): List<String> {
    if (!isOk) return listOf(statusLabel())
    val count = fileNum.toIntOrNull() ?: 1
    val size = fileSize.toLongOrNull() ?: 0L
    return listOfNotNull(
        if (count > 1) "$count 项" else size.takeIf { it > 0 }?.toReadableSize(),
        expirationLabel(),
        "浏览 $viewCount",
        "转存 $restoreCount",
    )
}

private fun ShareSummary.statusLabel(): String = when (shareStatus) {
    ShareStatus.DELETED -> "已失效"
    ShareStatus.EXPIRED -> "已过期"
    ShareStatus.AUDITING -> "审核中"
    ShareStatus.SENSITIVE_RESOURCE, ShareStatus.SENSITIVE_WORD, ShareStatus.PROHIBITED -> "已被屏蔽"
    else -> shareStatusText.ifBlank { "不可用" }
}

private fun ShareSummary.expirationLabel(): String? {
    if (expirationAt == "-1" || expirationDays == "-1") return "永久有效"
    return runCatching {
        OffsetDateTime.parse(expirationAt).format(DateTimeFormatter.ofPattern("M 月 d 日到期"))
    }.getOrNull()
}
