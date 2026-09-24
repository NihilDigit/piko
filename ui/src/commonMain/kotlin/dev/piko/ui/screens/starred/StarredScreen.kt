package dev.piko.ui.screens.starred

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.StarredScreenState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.components.FileLeadingVisual
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.metaParts
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat

/**
 * 星标页：全盘加了星标的文件与文件夹，与官方客户端共用。
 *
 * 单击打开：文件夹进入、视频播放、其余文件跳到网盘里它所在的位置，由 [onOpen] 交给调用方，
 * 因为这些都要动网盘页的导航栈或播放器。更多面板里另有「在网盘中显示」与「取消星标」。
 * 下拉刷新只有触屏能用，顶栏另给刷新按钮。
 *
 * [onBackClick] 为 null 时不显示返回按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(
    onBackClick: (() -> Unit)?,
    onOpen: (FileStat) -> Unit,
    onLocate: (FileStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val services = LocalPikoServices.current
    val state = remember { StarredScreenState(services.driveRepository, scope) }

    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val revealedIds = remember { mutableStateListOf<String>() }
    var detailsFor by remember { mutableStateOf<FileStat?>(null) }

    LaunchedEffect(state) {
        state.load()
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
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
                title = "星标",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding),
        ) {
            Crossfade(targetState = state.isLoading, animationSpec = PikoMotion.StateCrossfadeSpec, label = "starred_loading") { loading ->
                if (loading) {
                    FullScreenLoading()
                } else {
                    var containerWidth by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { state.load(refresh = true) },
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
                    ) {
                        val sidePadding = readableSidePadding(containerWidth)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = sidePadding,
                                end = sidePadding,
                                bottom = innerPadding.calculateBottomPadding() + 16.dp,
                            ),
                        ) {
                            if (state.files.isEmpty()) {
                                item {
                                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        PikoEmptyState(
                                            title = if (state.loadError != null) "加载失败" else "暂无星标",
                                            description = state.loadError ?: "在文件菜单中添加星标后显示于此",
                                        )
                                    }
                                }
                            } else {
                                items(items = state.files, key = { it.id }, contentType = { if (it.isFolder) "folder" else "file" }) { file ->
                                    val blurred = isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty() && file.id !in revealedIds
                                    FileListItem(
                                        headline = file.displayTitle(),
                                        headlineFontWeight = if (file.isFolder) FontWeight.Medium else null,
                                        leading = { FileLeadingVisual(file = file, isSpoilerBlurred = blurred) },
                                        supporting = { MetaRow(parts = file.metaParts()) },
                                        onClick = { onOpen(file) },
                                        onMoreClick = { detailsFor = file },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detailsFor?.let { file ->
        val canReveal = isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty()
        val hidden = file.id !in revealedIds
        ItemDetailsSheet(
            title = file.name,
            headerIcon = { FileTypeIcon(file = file, iconSize = 24.dp, modifier = Modifier.fillMaxSize()) },
            metaParts = file.metaParts(),
            onDismiss = { detailsFor = null },
            actions = buildList {
                if (canReveal) {
                    add(
                        SheetAction(
                            icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            label = if (hidden) "显示预览" else "隐藏预览",
                            onClick = { if (!revealedIds.remove(file.id)) revealedIds.add(file.id) },
                        ),
                    )
                }
                add(SheetAction(Icons.Outlined.FolderOpen, "在网盘中显示", onClick = { onLocate(file) }))
                add(SheetAction(Icons.Outlined.StarOutline, "取消星标", onClick = { state.unstar(file) }))
            },
        )
    }
}
