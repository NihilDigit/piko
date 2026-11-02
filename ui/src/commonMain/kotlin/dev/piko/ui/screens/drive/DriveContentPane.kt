package dev.piko.ui.screens.drive

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.library
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.DuplicateReportViewModel
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.GallerySession
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.DetailHost
import dev.piko.ui.components.DetailPanelState
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TreeIndexProgressBanner
import dev.piko.ui.components.itemPanelAnchorArea
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_retry
import dev.piko.ui.theme.FrameCardBottomMargin
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.IslandInnerCorner
import dev.piko.ui.theme.islandHeader
import io.github.nihildigit.pikpak.FileStat

// 列表页眉那一行（排序与视图切换）的高度，骨架据此空出位置。与 DriveListHeader 的最小行高一致
private val DriveListHeaderHeight = 48.dp

// 列表末尾为 Extended FAB 留出的空间：56dp 高度加 16dp 外边距，再留一段让最后一项能完整滚出 FAB 遮挡
private val FabClearance = 88.dp

/** 网盘页首屏三态。失败文案随态带进 Crossfade 的 target：淡出未完时旧分支仍在组合，那一刻 loadError 可能已清空。 */
private sealed interface DrivePhase {
    data object Loading : DrivePhase

    data class Failed(val message: String) : DrivePhase

    data object Content : DrivePhase
}

internal fun foldBannerOrNull(state: DriveScreenState): (@Composable () -> Unit)? {
    val hiddenCount = state.potentialHiddenCount
    val applicable = state.isHeuristicFilterEnabled && hiddenCount > 0 &&
        state.searchQuery.isBlank() && !state.isGlobalSearchActive && state.typeFilter == null
    if (!applicable) return null
    val isFolded = !state.showAllFilesTemporarily
    return {
        FoldBanner(
            isFolded = isFolded,
            hiddenCount = hiddenCount,
            onToggle = { state.setShowAllFiles(isFolded) },
        )
    }
}

private fun searchSummary(state: DriveScreenState, files: List<FileStat>): String? = when {
    state.isGlobalSearchActive ->
        if (state.isGlobalSearching) tr("全盘搜索中，已找到 ${files.size} 项", "Searching drive: ${enCount(files.size, "item", "items")} found", "ドライブ全体を検索中、${files.size} 項目発見", "드라이브 검색 중, ${files.size}개 항목 찾음", "正在搜尋整個雲端硬碟，已找到 ${files.size} 個項目")
        else tr("全盘找到 ${files.size} 项", "${enCount(files.size, "item", "items")} found in drive", "ドライブ全体で ${files.size} 項目発見", "전체 드라이브에서 ${files.size}개 항목 찾음", "在整個雲端硬碟找到 ${files.size} 個項目")
    state.searchQuery.isNotBlank() -> tr("当前文件夹找到 ${files.size} 项", "${enCount(files.size, "item", "items")} found in folder", "現在のフォルダーで ${files.size} 項目発見", "현재 폴더에서 ${files.size}개 항목 찾음", "在目前資料夾找到 ${files.size} 個項目")
    else -> null
}

/**
 * 叶子级树索引构建进度横幅：将高频的 `treeBuildProgressFlow` 订阅严格限制在本叶子节点内，
 * 全盘树拉取期间每百毫秒的进度跳动不会触发 `DriveFileGrid` 与顶栏重组。
 */
@Composable
private fun DriveTreeProgressSlot(
    driveRepo: PikoDriveRepository,
    isVirtualPlace: Boolean,
    isGlobalSearchActive: Boolean,
    sortOrder: PikoFileSortOrder,
) {
    if (isVirtualPlace) return
    val progress by driveRepo.treeBuildProgressFlow.collectAsStateWithLifecycle()
    val sortReliesOnTree = sortOrder == PikoFileSortOrder.SIZE_DESC ||
        sortOrder == PikoFileSortOrder.SIZE_ASC ||
        sortOrder == PikoFileSortOrder.DURATION_DESC ||
        sortOrder == PikoFileSortOrder.DURATION_ASC
    if (progress.isBuilding && (progress.isInitialBlocking || isGlobalSearchActive || sortReliesOnTree)) {
        TreeIndexProgressBanner(progress = progress)
    }
}

/**
 * 页眉下方的网盘工作区容器：将浮动目录图、浮动属性卡的叠放次序（`propertiesOnTop`）和列表净宽（`listAreaWidth`）
 * 内聚在本层，不向上泄漏到屏幕顶层。
 */
@Composable
internal fun DriveWorkspaceArea(
    state: DriveScreenState,
    innerPadding: PaddingValues,
    pathInTopBar: Boolean,
    folderStack: List<PathBreadcrumb>,
    folderMap: FolderMapState,
    propertyDetails: DetailPanelState<PropertiesTarget>,
    showHomeInMap: Boolean,
    canLocateFromProperties: Boolean,
    isSpoilerBlurEnabled: Boolean,
    onUpdateFolderStack: (List<PathBreadcrumb>) -> Unit,
    onOpenFromMap: (List<PathBreadcrumb>, FileStat) -> Unit,
    onGoHome: () -> Unit,
    onLocateFromProperties: (FileStat) -> Unit,
    onOpenSourceLink: (String) -> Unit,
    contentFrame: @Composable (content: @Composable () -> Unit) -> Unit,
    content: @Composable (listAreaWidth: Dp?) -> Unit,
) {
    val density = LocalDensity.current
    var listAreaWidth by remember { mutableStateOf<Dp?>(null) }
    var propertiesOnTop by remember { mutableStateOf(true) }
    val properties = propertyDetails.target
    LaunchedEffect(properties) { if (properties != null) propertiesOnTop = true }
    LaunchedEffect(folderMap.expanded) { if (folderMap.expanded) propertiesOnTop = false }

    Box(
        Modifier
            .fillMaxSize()
            .padding(
                top = innerPadding.calculateTopPadding(),
                end = if (pathInTopBar) IslandGap else 0.dp,
                bottom = if (pathInTopBar) maxOf(innerPadding.calculateBottomPadding(), FrameCardBottomMargin) else 0.dp,
            )
            .consumeWindowInsets(innerPadding)
            .onSizeChanged { listAreaWidth = with(density) { it.width.toDp() } },
    ) {
        contentFrame {
            Box(
                Modifier
                    .fillMaxSize()
                    .itemPanelAnchorArea(propertyDetails.anchors)
                    .then(if (pathInTopBar) Modifier.folderMapPointer(folderMap) else Modifier),
            ) {
                content(listAreaWidth)
                if (pathInTopBar && folderMap.presence != FolderMapPresence.Closed) {
                    FolderMapPanel(
                        state = folderMap,
                        current = folderStack,
                        onOpen = onUpdateFolderStack,
                        onOpenFile = onOpenFromMap,
                        onHome = if (showHomeInMap) onGoHome else null,
                        modifier = Modifier.zIndex(if (propertiesOnTop) 0f else 1f),
                        avoid = { propertyDetails.panel.bounds },
                        onActivate = { propertiesOnTop = false },
                    )
                }
                DetailHost(
                    state = propertyDetails,
                    modifier = Modifier.zIndex(2f),
                    onActivate = { propertiesOnTop = true },
                ) { shown, contentModifier ->
                    PropertiesPane(
                        target = shown,
                        modifier = contentModifier,
                        onLocate = if (canLocateFromProperties) {
                            { file ->
                                propertyDetails.close()
                                onLocateFromProperties(file)
                            }
                        } else null,
                        onParseSource = { url ->
                            propertyDetails.close()
                            onOpenSourceLink(url)
                        },
                        onToggleBlur = if (isSpoilerBlurEnabled) {
                            { file -> state.toggleSpoiler(file.id) }
                        } else null,
                        isRevealed = { file -> file.id in state.revealedFileIds },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DriveContentBody(
    state: DriveScreenState,
    driveRepo: PikoDriveRepository,
    gallery: GallerySession,
    gridState: LazyGridState,
    innerPadding: PaddingValues,
    listAreaWidth: Dp?,
    pathInTopBar: Boolean,
    canLeaveDuplicates: Boolean,
    addLinkSideOpen: Boolean,
    folderStack: List<PathBreadcrumb>,
    activeFolderId: String,
    clipboard: DriveClipboard?,
    canPaste: Boolean,
    viewMode: DriveViewMode,
    tileSize: TileSize,
    galleryActive: Boolean,
    galleryShowImages: Boolean,
    isSpoilerBlurEnabled: Boolean,
    itemMoreButton: Boolean,
    inDuplicates: Boolean,
    duplicateState: DuplicateFinderState?,
    duplicateReport: DuplicateReportViewModel,
    displayedFiles: List<FileStat>,
    selectedIdSet: Set<String>,
    rowNotes: Map<String, String>,
    vaultedFolders: Set<String>,
    locatedFileId: String?,
    activeCommandItemId: String?,
    keyboardFocusTarget: String?,
    callbacks: DriveItemCallbacks,
    foldBanner: (@Composable () -> Unit)?,
    backgroundActions: () -> List<SheetAction>,
    onSelectViewMode: (DriveViewMode) -> Unit,
    onSelectTileSize: (TileSize) -> Unit,
    onStepZoom: (Boolean) -> Unit,
    onGalleryShowImagesChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDuplicateKindChange: (DuplicateKind) -> Unit,
    onEndDuplicates: () -> Unit,
    onLeaveDuplicates: () -> Unit,
    onKeyboardFocusMoved: () -> Unit,
) {
    val folderEmptiness by state.folderEmptiness.collectAsStateWithLifecycle()
    val ancestorCrumbs = remember(folderStack) { folderStack.dropLast(1) }
    val breadcrumbs: @Composable () -> Unit = {
        if (ancestorCrumbs.size > 1 && !pathInTopBar) {
            BreadcrumbBar(
                breadcrumbs = ancestorCrumbs,
                endsWithCurrent = false,
                firstIcon = folderStack.library?.icon ?: Icons.Outlined.Home,
                onBreadcrumbClick = state::navigateToBreadcrumb,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (pathInTopBar) {
                    Modifier
                        .clip(
                            RoundedCornerShape(
                                bottomStart = IslandCorner,
                                bottomEnd = if (addLinkSideOpen) 0.dp else IslandCorner,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.islandHeader)
                        .padding(
                            start = if (addLinkSideOpen) 8.dp else 0.dp,
                            bottom = if (addLinkSideOpen) 8.dp else 0.dp,
                        )
                        .clip(
                            RoundedCornerShape(
                                topStart = IslandInnerCorner,
                                topEnd = IslandInnerCorner,
                                bottomStart = if (addLinkSideOpen) IslandInnerCorner else IslandCorner,
                                bottomEnd = if (addLinkSideOpen) IslandInnerCorner else IslandCorner,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.surface)
                } else {
                    Modifier
                },
            ),
    ) {
        clipboard?.let { clip -> ClipboardBanner(clip, canPaste, state::clearClipboard, state::paste) }
        DriveTreeProgressSlot(
            driveRepo = driveRepo,
            isVirtualPlace = state.isVirtualPlace,
            isGlobalSearchActive = state.isGlobalSearchActive,
            sortOrder = state.sortOrder,
        )

        val phase = when {
            galleryActive -> DrivePhase.Content
            state.isLoading -> DrivePhase.Loading
            state.files.isEmpty() && state.loadError != null -> DrivePhase.Failed(state.loadError.orEmpty())
            else -> DrivePhase.Content
        }
        Crossfade(
            targetState = phase,
            modifier = Modifier.weight(1f),
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "drive_phase",
        ) { current ->
            if (galleryActive) {
                Column(Modifier.fillMaxSize()) {
                    breadcrumbs()
                    GalleryPane(
                        files = displayedFiles,
                        tileSize = tileSize,
                        onTileSizeChange = onSelectTileSize,
                        viewMode = viewMode,
                        showViewSwitcher = false,
                        onViewModeChange = onSelectViewMode,
                        onZoom = { larger ->
                            val next = tileSize.step(larger)
                            if (next != tileSize) onSelectTileSize(next)
                        },
                        loading = gallery.loading,
                        complete = gallery.isComplete,
                        hasMore = gallery.hasMore,
                        failureCount = gallery.failedFolders.size,
                        onRetry = gallery::retry,
                        onRequestMore = gallery::requestMore,
                        onVisibleFilesChanged = gallery::onVisibleFilesChanged,
                        isBlurred = { isSpoilerBlurEnabled && it.id !in state.revealedFileIds },
                        isSelectionMode = state.isSelectionMode,
                        selectedIds = selectedIdSet,
                        highlightedIds = state.highlightedRowIds,
                        callbacks = callbacks,
                        folderName = gallery::folderName,
                        rootKey = listOf(activeFolderId, state.libraryView, state.isSearchOpen, state.searchQuery, state.isGlobalSearchActive),
                        showImages = galleryShowImages,
                        onShowImagesChange = onGalleryShowImagesChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                return@Crossfade
            }
            if (current is DrivePhase.Loading) {
                Column(modifier = Modifier.fillMaxSize()) {
                    breadcrumbs()
                    if (!pathInTopBar) Spacer(modifier = Modifier.height(DriveListHeaderHeight))
                    DriveGridSkeleton(viewMode = viewMode, tileSize = tileSize, modifier = Modifier.weight(1f))
                }
                return@Crossfade
            }
            if (current is DrivePhase.Failed) {
                Column(modifier = Modifier.fillMaxSize()) {
                    breadcrumbs()
                    PikoErrorState(
                        message = current.message,
                        onRetry = { state.load() },
                        modifier = Modifier.weight(1f),
                    )
                }
                return@Crossfade
            }
            RefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    state.loadError?.let { reason ->
                        StaleDataBanner(reason = reason, onRetry = onRefresh)
                    }
                    val bottomPadding = innerPadding.calculateBottomPadding() + FabClearance
                    if (inDuplicates && duplicateState != null && !duplicateState.isScanning) {
                        DuplicatesBanner(
                            duplicateState,
                            selectedKind = duplicateReport.kind,
                            onKindChange = onDuplicateKindChange,
                            onEnd = onEndDuplicates,
                            reportView = duplicateReport,
                            selectedIds = selectedIdSet,
                            onSelectIds = { ids -> if (ids.isEmpty()) state.exitSelection() else state.selectOnly(ids) },
                            controlsInCommandBar = true,
                        )
                    }
                    if (inDuplicates && state.files.isEmpty()) {
                        breadcrumbs()
                        DuplicatesEmptyState(
                            finder = duplicateState,
                            selectedKind = duplicateReport.kind,
                            onLeave = onLeaveDuplicates,
                            canLeave = canLeaveDuplicates,
                            cleaning = state.trashProgress != null,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (state.displayItems.isEmpty() && state.typeFilter == null && !state.thumbnailsOnly) {
                        breadcrumbs()
                        DriveEmptyState(state = state, modifier = Modifier.weight(1f))
                    } else {
                        DriveFileGrid(
                            cutIds = clipboard?.takeIf { it.cut }?.sources?.keys.orEmpty(),
                            items = state.displayItems,
                            folderView = state::folderView,
                            viewMode = viewMode,
                            tileSize = tileSize,
                            onZoom = onStepZoom,
                            gridState = gridState,
                            isSelectionMode = state.isSelectionMode,
                            selectedIds = selectedIdSet,
                            highlightedIds = state.highlightedRowIds,
                            highlightRevision = state.highlightRevision,
                            structureReady = state.isDisplayStructureReady,
                            locatedItemId = locatedFileId,
                            isBlurred = { isSpoilerBlurEnabled && it.id !in state.revealedFileIds },
                            hitLocations = rowNotes,
                            callbacks = callbacks,
                            bottomPadding = bottomPadding,
                            moreButton = itemMoreButton,
                            backgroundActions = backgroundActions,
                            columnReferenceWidth = listAreaWidth,
                            activeItemId = activeCommandItemId,
                            emptyFolders = folderEmptiness,
                            vaultedFolders = vaultedFolders,
                            keyboardFocusTarget = keyboardFocusTarget,
                            onKeyboardFocusMoved = onKeyboardFocusMoved,
                            header = {
                                Column {
                                    breadcrumbs()
                                    Box(modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
                                        DriveListHeader(
                                            summary = searchSummary(state, displayedFiles),
                                            sortOrder = state.sortOrder,
                                            onSortChange = state::changeSortOrder,
                                            typeFilter = state.typeFilter,
                                            availableTypes = state.availableTypes,
                                            onTypeFilterChange = state::updateTypeFilter,
                                            viewMode = viewMode,
                                            onViewModeChange = onSelectViewMode,
                                            tileSize = tileSize,
                                            onTileSizeChange = onSelectTileSize,
                                            perFolderSort = state.perFolderSort,
                                            onPerFolderSortChange = state::setPerFolderSortEnabled,
                                            foldersFirst = state.foldersFirst,
                                            onFoldersFirstChange = state::updateFoldersFirst,
                                            showControls = !pathInTopBar && !inDuplicates,
                                        )
                                    }
                                }
                            },
                            foldBanner = foldBanner,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StaleDataBanner(reason: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = tr("内容可能不是最新的：$reason", "Contents may be out of date: $reason", "内容が最新でない可能性があります：$reason", "최신 내용이 아닐 수 있습니다: $reason", "內容可能不是最新的：$reason"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
        }
    }
}

@Composable
private fun DriveEmptyState(state: DriveScreenState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.searchQuery.isNotBlank()) {
                    PikoEmptyState(
                        title = if (state.isGlobalSearching) tr("正在搜索全盘", "Searching entire drive", "ドライブ全体を検索中", "전체 드라이브 검색 중", "正在搜尋整個雲端硬碟") else tr("无匹配结果", "No matches", "一致する項目はありません", "일치하는 항목이 없습니다", "沒有相符結果"),
                        description = when {
                            state.isGlobalSearching -> tr("正在逐层扫描文件夹，匹配结果将实时显示", "Scanning folders; matches appear as they are found", "フォルダーを順にスキャンし、見つかった項目から表示します", "폴더를 차례로 스캔하며 찾은 항목을 실시간으로 표시합니다", "正在逐層掃描資料夾，相符結果將即時顯示")
                            state.isGlobalSearchActive -> tr("全盘没有名称包含「${state.searchQuery}」的文件", "No drive files with “${state.searchQuery}” in their name", "ドライブ内に「${state.searchQuery}」を含む名前のファイルはありません", "전체 드라이브에 이름에 “${state.searchQuery}”이(가) 포함된 파일이 없습니다", "整個雲端硬碟沒有名稱包含「${state.searchQuery}」的檔案")
                            else -> tr("当前文件夹中无名称包含「${state.searchQuery}」的项目，可点击顶部「全盘」搜索整个网盘", "No items matching “${state.searchQuery}” in this folder. Click Entire drive to search everywhere", "このフォルダーに「${state.searchQuery}」を含む項目はありません。上部の「ドライブ全体」から全体を検索できます", "현재 폴더에 “${state.searchQuery}”이(가) 포함된 항목이 없습니다. 상단의 '전체 드라이브'로 전체를 검색할 수 있습니다", "目前資料夾中無名稱包含「${state.searchQuery}」的項目，可點選頂部「全碟」搜尋整個雲端硬碟")
                        },
                        icon = Icons.Outlined.SearchOff,
                    )
                } else if (state.libraryView != null) {
                    val empty = state.libraryView!!.empty
                    PikoEmptyState(title = empty.title, description = empty.description, icon = empty.icon)
                } else if (state.archiveView != null) {
                    val error = state.loadError
                    PikoEmptyState(
                        title = if (error != null) tr("无法读取压缩包", "Unable to read archive", "圧縮ファイルを読み込めません", "압축 파일을 읽을 수 없습니다", "無法讀取壓縮檔") else tr("此文件夹为空", "This folder is empty", "このフォルダーは空です", "이 폴더는 비어 있습니다", "此資料夾為空"),
                        description = error ?: tr("此压缩包目录为空", "This archive folder is empty", "圧縮ファイル内のこのフォルダーは空です", "압축 파일의 이 폴더는 비어 있습니다", "此壓縮檔目錄為空"),
                        icon = if (error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.FolderOpen,
                    )
                } else if (state.importPreviewView != null) {
                    PikoEmptyState(
                        title = tr("此待保存文件夹为空", "This unsaved folder is empty", "この未保存フォルダーは空です", "저장 대기 폴더가 비어 있습니다", "此待儲存資料夾為空"),
                        description = "",
                        icon = Icons.Outlined.FolderOpen,
                    )
                } else {
                    PikoEmptyState(
                        title = tr("此文件夹为空", "This folder is empty", "このフォルダーは空です", "이 폴더는 비어 있습니다", "此資料夾為空"),
                        description = "",
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
            }
        }
    }
}
