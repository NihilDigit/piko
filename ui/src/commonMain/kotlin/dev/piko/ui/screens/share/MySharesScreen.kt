package dev.piko.ui.screens.share

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr
import dev.piko.ui.ObserveMessages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.backhandler.BackHandler
import dev.piko.ui.components.marqueeSelection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import dev.piko.ui.components.PikoItemGrid
import dev.piko.ui.components.fullLineItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.IslandHeaderSpace
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
import dev.piko.ui.components.ContextMenuArea
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
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.rememberListScrollTint
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
 * 我的分享：自己创建的分享链接，与官方客户端共用。每个分享是一张摊开的卡片：链接、提取码、浏览与转存次数、
 * 有效期都直接写在上面，复制、打开与取消是卡片上的按钮。原先是一行文件名加一串小字，要点开面板才看得到链接，
 * 而来这一页多半就是为了拿链接或看有没有人转存。取消后链接即失效、无法恢复，先确认一次。
 *
 * [onBackClick] 为 null 时不显示返回按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySharesScreen(
    onBackClick: (() -> Unit)?,
    /** 在网盘里打开源文件所在的文件夹并标出它，找不到返回 false。 */
    onLocate: suspend (fileId: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val state = remember { MySharesState(services.driveRepository, scope) }
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
    val gridState = rememberLazyGridState()
    var confirmCancel by remember { mutableStateOf<ShareSummary?>(null) }

    LaunchedEffect(state) {
        state.load()
    }
    ObserveMessages(state.messages, snackbarHostState)
    // 离底部还有几行时就取下一页，滚到底时新内容已经在了
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= gridState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    // 连同「能不能取」一起看：只看 nearEnd 的话，一页接完仍停在底部时它一直是 true、不再发出，
    // 后面的页就取不到了。取失败后 canAutoLoadMore 为 false，改由底部的「重试」接着取
    LaunchedEffect(state, gridState) {
        snapshotFlow { nearEnd && state.canAutoLoadMore }.collect { if (it) state.loadMore() }
    }

    // 有提取码时一并复制，按钮统一叫「复制链接」：链接不带提取码发出去对方打不开，没有只要链接的时候。
    // 提取码另起一行而不拼进链接：SDK 与现有代码里都没有见到服务端认链接上的提取码参数
    fun copy(share: ShareSummary) {
        platform.copyToClipboard(tr("分享链接", "Share link", "共有リンク", "공유 링크", "分享連結"), ShareCreateState.shareText(share.title, share.shareUrl, share.passCode))
        scope.launch { snackbarHostState.showSnackbar(tr("已复制分享链接", "Share link copied", "共有リンクをコピーしました", "공유 링크를 복사했습니다", "已複製分享連結"),withDismissAction = true) }
    }

    fun locate(share: ShareSummary) {
        val id = state.sourceId(share)
        scope.launch {
            if (id == null || !onLocate(id)) snackbarHostState.showSnackbar(tr("找不到源文件，可能已被移动或删除", "Source file not found; it may have moved or been deleted", "元のファイルが見つかりません。移動または削除された可能性があります", "원본 파일을 찾을 수 없습니다. 이동되거나 삭제되었을 수 있습니다", "找不到來源檔案，可能已被移動或刪除"), withDismissAction = true)
        }
    }

    // 一项的全部操作，卡片与右键菜单共用
    fun actionsFor(share: ShareSummary): List<SheetAction> = listOf(
        SheetAction(Icons.Outlined.FolderOpen, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onClick = { locate(share) }),
        SheetAction(Icons.Outlined.Link, tr("复制链接", "Copy link", "リンクをコピー", "링크 복사", "複製連結"), onClick = { copy(share) }),
        SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在浏览器中打开", "Open in browser", "ブラウザーで開く", "브라우저에서 열기", "在瀏覽器中開啟"), onClick = { platform.openUrl(share.shareUrl) }),
        SheetAction(Icons.Outlined.LinkOff, tr("取消分享", "Revoke share", "共有を解除", "공유 취소", "取消分享"), onClick = { confirmCancel = share }, destructive = true),
    )

    val selecting = state.selectedIds.isNotEmpty()
    var confirmCancelSelected by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { state.clearSelection() }

    // 按列表眼下的位置换色，取消几项后列表变短回到顶端，顶栏跟着回来，见 rememberListScrollTint
    val topBarScrollBehavior = rememberListScrollTint {
        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
    }
    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        // 有外框时：没有分类，不放标签，页名写在岛的页眉开头；有选中项时整行换成「已选 N 项」与批量操作
        island = IslandPage(
            header = {
                if (selecting) {
                    IslandTitle(tr("已选 ${state.selectedIds.size} 项", "${enCount(state.selectedIds.size, "item", "items")} selected", "${state.selectedIds.size} 項目を選択中", "${state.selectedIds.size}개 선택됨", "已選 ${state.selectedIds.size} 項"))
                    IslandHeaderSpace()
                    TooltipIconButton(
                        icon = Icons.Outlined.LinkOff,
                        label = tr("取消所选分享", "Revoke selected shares", "選択した共有を解除", "선택한 공유 취소", "取消所選分享"),
                        onClick = { confirmCancelSelected = true },
                        tint = MaterialTheme.colorScheme.error,
                    )
                    TooltipIconButton(Icons.Outlined.Close, tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"), state::clearSelection, shortcut = "Esc")
                } else {
                    IslandTitle(tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享"))
                    if (state.shares.isNotEmpty()) {
                        Text(
                            tr("${state.shares.size} 个", "${enCount(state.shares.size, "share", "shares")}", "${state.shares.size} 件", "공유 ${state.shares.size}개", "${state.shares.size} 個"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    IslandHeaderSpace()
                    TooltipIconButton(
                        icon = Icons.Outlined.Refresh,
                        label = tr("刷新", "Refresh", "更新", "새로고침", "重新整理"),
                        onClick = { state.load(refresh = true) },
                        enabled = !state.isRefreshing,
                    )
                }
            },
        ),
        topBar = {
            PikoTopBar(
                scrollBehavior = topBarScrollBehavior,
                // 框选或点选了几个分享时页头换成批量操作，照网盘页的多选
                title = if (selecting) tr("已选 ${state.selectedIds.size} 项", "${enCount(state.selectedIds.size, "item", "items")} selected", "${state.selectedIds.size} 項目を選択中", "${state.selectedIds.size}개 선택됨", "已選 ${state.selectedIds.size} 項") else tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享"),
                navigationIcon = {
                    if (selecting) {
                        TooltipIconButton(Icons.Outlined.Close, tr("退出多选", "Exit selection", "選択モードを終了", "선택 모드 종료", "結束多選"), state::clearSelection, shortcut = "Esc")
                    } else if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回", "Back", "戻る", "뒤로", "返回"))
                        }
                    }
                },
                actions = listOf(
                    if (selecting) {
                        iconBarItem(Icons.Outlined.LinkOff, tr("取消所选分享", "Revoke selected shares", "選択した共有を解除", "선택한 공유 취소", "取消所選分享"), { confirmCancelSelected = true }, priority = PinnedPriority, destructive = true)
                    } else {
                        iconBarItem(Icons.Outlined.Refresh, tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), { state.load(refresh = true) }, priority = PinnedPriority, enabled = !state.isRefreshing)
                    },
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding),
        ) {
            FirstScreenState(
                isLoading = state.isLoading,
                error = state.loadError,
                isEmpty = state.shares.isEmpty(),
                onRetry = { state.load() },
                skeleton = { FileListSkeleton(Modifier.padding(horizontal = 8.dp)) },
            ) {
                RefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { state.load(refresh = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.shares.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PikoEmptyState(title = tr("暂无分享", "No shares", "共有はありません", "공유가 없습니다", "暫無分享"), description = tr("在文件菜单中选择「分享」后显示于此", "Choose Share from the file menu to see links here", "ファイルメニューから共有すると、ここに表示します", "파일 메뉴에서 공유하면 여기에 표시됩니다", "在檔案選單中選擇「分享」後顯示於此"))
                        }
                    } else {
                        val shareIds = remember(state.shares) { state.shares.mapTo(HashSet()) { it.shareId } }
                        PikoItemGrid(
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                            // 照网盘页的框选：在空白或卡片上按住拖动拉框。分享没有拖放，按在卡片上拖也是框选
                            gridModifier = Modifier.marqueeSelection(
                                gridState = gridState,
                                selectedIds = state.selectedIds,
                                boxedKey = { key -> (key as? String)?.takeIf { it in shareIds } },
                                onSelect = state::selectBoxed,
                                onBackgroundClick = state::clearSelection,
                                movable = { false },
                            ),
                        ) {
                            items(items = state.shares, key = { it.shareId }) { share ->
                                ContextMenuArea(
                                    actions = { actionsFor(share) },
                                    onSelect = if (selecting) null else ({ state.toggleSelected(share.shareId) }),
                                    modifier = Modifier.animateItem().padding(4.dp),
                                ) {
                                    LaunchedEffect(share.shareId) { state.loadPreview(share) }
                                    val preview = state.previews[share.shareId]
                                    ShareCard(
                                        share = share,
                                        selected = share.shareId in state.selectedIds,
                                        // 多选时点卡片是勾选或取消，与网盘页多选时点条目一样
                                        onClick = if (selecting) ({ state.toggleSelected(share.shareId) }) else null,
                                        preview = preview,
                                        isSpoilerBlurred = isSpoilerBlurEnabled && preview?.thumbnailLink?.isNotEmpty() == true,
                                        onLocate = { locate(share) },
                                        onCopy = { copy(share) },
                                        onOpen = { platform.openUrl(share.shareUrl) },
                                        onCancel = { confirmCancel = share },
                                    )
                                }
                            }
                            if (state.isLoadingMore) {
                                fullLineItem(key = "loading_more") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        InlineLoadingIndicator()
                                    }
                                }
                            } else if (state.loadMoreFailed) {
                                fullLineItem(key = "load_more_failed") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(Res.string.text_failed_to_load_more_shares),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = state::loadMore) { Text(stringResource(Res.string.common_retry)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmCancelSelected) {
        val count = state.selectedIds.size
        PikoDialog(
            onDismissRequest = { confirmCancelSelected = false },
            icon = { Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(tr("取消所选的 $count 个分享", "Revoke $count selected shares", "選択した $count 件の共有を解除", "선택한 공유 ${count}개 취소", "取消所選的 $count 個分享")) },
            text = { Text(stringResource(Res.string.text_these_links_will_stop_working_immediately_and_cannot_be_restored_drive_files)) },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("取消分享", "Revoke share", "共有を解除", "공유 취소", "取消分享"),
                    destructive = true,
                    onClick = {
                        confirmCancelSelected = false
                        state.cancelSelected()
                    },
                )
            },
            dismissButton = { TextButton(onClick = { confirmCancelSelected = false }) { Text(stringResource(Res.string.text_keep)) } },
        )
    }

    confirmCancel?.let { share ->
        PikoDialog(
            onDismissRequest = { confirmCancel = null },
            icon = { Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.text_revoke_share)) },
            text = { Text(stringResource(Res.string.text_the_link_will_stop_working_immediately_and_cannot_be_restored_drive_files_ar)) },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("取消分享", "Revoke share", "共有を解除", "공유 취소", "取消分享"),
                    destructive = true,
                    onClick = {
                        confirmCancel = null
                        state.cancel(share)
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = null }) { Text(stringResource(Res.string.text_keep)) }
            },
        )
    }
}

private const val LOAD_MORE_THRESHOLD = 5

/** 只为借用文件类型图标。多项的分享服务端给的是第一项的类型。 */
private fun ShareSummary.leadingFile() = FileStat(kind = fileKind, name = title)

/**
 * 一个分享摊开的样子。失效的分享链接打不开，链接一栏淡下去，也不给复制与打开，只留取消（即从列表里删掉）；
 * 次数照写，那是它失效之前的战绩。
 */
@Composable
private fun ShareCard(
    share: ShareSummary,
    /** 框选或多选时选中了它：换成选中的容器色，与网盘页选中的条目一样。 */
    selected: Boolean,
    /** 多选时点卡片是勾选或取消；平时为 null，卡片本身不接点击，操作都在按钮上。 */
    onClick: (() -> Unit)?,
    /** 打开分享后的第一项，有缩略图时用它；还没取到或取不到时为 null，画类型图标。 */
    preview: FileStat?,
    isSpoilerBlurred: Boolean,
    onLocate: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileLeadingVisual(file = preview ?: share.leadingFile(), isSpoilerBlurred = isSpoilerBlurred, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(share.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (share.isOk) share.contentLabel() else share.statusLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (share.isOk) colors.onSurfaceVariant else colors.error,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 链接与提取码放在一块底色里：它们是一起发出去的一件东西
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHighest) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val linkColor = if (share.isOk) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f)
                    Icon(Icons.Outlined.Link, contentDescription = null, tint = linkColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(share.shareUrl, style = MaterialTheme.typography.bodyMedium, color = linkColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (share.passCode.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(Res.string.text_access_code), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        SelectionContainer {
                            Text(
                                share.passCode,
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                color = if (share.isOk) colors.primary else linkColor,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 卡片最窄 360dp，四项一行放不下时折到下一行
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareStat(tr("浏览", "Browse", "閲覧", "둘러보기", "瀏覽"), share.viewCount)
                ShareStat(tr("转存", "Save share", "共有を保存", "공유 저장", "轉存"), share.restoreCount)
                ShareStat(tr("有效期", "Expiration", "有効期限", "유효 기간", "有效期"), share.expirationLabel() ?: tr("未知", "Unknown", "不明", "알 수 없음", "未知"))
                share.createdLabel()?.let { ShareStat(tr("创建", "Create", "作成", "만들기", "建立"), it) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                    Text(if (share.isOk) tr("取消分享", "Revoke share", "共有を解除", "공유 취소", "取消分享") else tr("删除记录", "Delete record", "記録を削除", "기록 삭제", "刪除紀錄"))
                }
                if (share.isOk) {
                    TooltipIconButton(Icons.Outlined.FolderOpen, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기", "在雲端硬碟中顯示"), onLocate)
                    TooltipIconButton(Icons.AutoMirrored.Outlined.OpenInNew, tr("在浏览器中打开", "Open in browser", "ブラウザーで開く", "브라우저에서 열기", "在瀏覽器中開啟"), onOpen)
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = onCopy) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.text_copy_link))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
    }
}

/** 几项或多大。多项的分享服务端不给总大小。 */
private fun ShareSummary.contentLabel(): String {
    val count = fileNum.toIntOrNull() ?: 1
    val size = fileSize.toLongOrNull() ?: 0L
    return when {
        count > 1 -> tr("$count 项", "${enCount(count, "item", "items")}", "$count 項目", "${count}개 항목", "$count 項")
        size > 0 -> size.toReadableSize()
        else -> tr("1 项", "1 item", "1 項目", "1개 항목", "1 項")
    }
}

private fun ShareSummary.createdLabel(): String? = runCatching {
    OffsetDateTime.parse(createTime).format(DateTimeFormatter.ofPattern(tr("M 月 d 日", "MMM d", "M月d日", "M월 d일", "M 月 d 日")))
}.getOrNull()

private fun ShareSummary.statusLabel(): String = when (shareStatus) {
    ShareStatus.DELETED -> tr("已失效", "Invalid", "無効", "무효", "已失效")
    ShareStatus.EXPIRED -> tr("已过期", "Expired", "期限切れ", "만료됨", "已過期")
    ShareStatus.AUDITING -> tr("审核中", "Under review", "審査中", "검토 중", "審核中")
    ShareStatus.SENSITIVE_RESOURCE, ShareStatus.SENSITIVE_WORD, ShareStatus.PROHIBITED -> tr("已被屏蔽", "Blocked", "ブロック済み", "차단됨", "已遭封鎖")
    else -> shareStatusText.ifBlank { tr("不可用", "Unavailable", "利用不可", "사용 불가", "無法使用") }
}

private fun ShareSummary.expirationLabel(): String? {
    if (expirationAt == "-1" || expirationDays == "-1") return tr("永久", "Never expires", "無期限", "영구", "永久")
    return runCatching {
        OffsetDateTime.parse(expirationAt).format(DateTimeFormatter.ofPattern(tr("至 M 月 d 日", "'Until' MMM d", "M月d日まで", "M월 d일까지", "至 M 月 d 日")))
    }.getOrNull()
}
