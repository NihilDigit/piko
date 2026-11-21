package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.naming.av.hasAvCode
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.DriveTab
import dev.piko.shared.presentation.DuplicateReportViewModel
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.upload.isUploading
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.ContributePaletteItems
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PendingBadge
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.PrimaryActionFab
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.islandTopStart
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.text_new_folder
import dev.piko.ui.resources.text_paste
import dev.piko.ui.resources.text_upload_files
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.islandHeader
import io.github.nihildigit.pikpak.FileStat

@Composable
internal fun DriveAddressField(
    state: DriveScreenState,
    navigation: DriveNavigation,
    workspace: FileWorkspace,
    folderStack: List<PathBreadcrumb>,
    pinnedFolders: List<PathBreadcrumb>,
    addressDestinations: List<PaletteItem>,
    editRequests: Int,
    onOpenLink: (String) -> Unit,
) {
    // 最近访问目录仅在地址栏内部订阅，更新时不触发网盘主列表重组。
    val recentFolders by workspace.recentFoldersFlow.collectAsStateWithLifecycle()
    DrivePathTitle(
        model = AddressBarModel(
            stack = folderStack,
            onNavigate = state::navigateToBreadcrumb,
            onOpenStack = navigation::updateFolderStack,
            onSubmitPath = state::goToPath,
            completions = state::addressCompletions,
            subfoldersOf = state::subfoldersOf,
            recent = recentFolders,
            pinned = pinnedFolders,
            onOpenPinned = state::openPinned,
            onRemoveRecent = workspace::removeRecentFolder,
            onOpenLink = onOpenLink,
            onSearchHere = state::updateSearchQuery,
            onSearchAll = { term ->
                state.updateSearchQuery(term)
                state.startGlobalSearch()
            },
            destinations = addressDestinations,
        ),
        editRequests = editRequests,
    )
}

@Composable
internal fun DriveDesktopHeader(
    state: DriveScreenState,
    shortcuts: ShortcutModifier,
    isWorkspaceBrowser: Boolean,
    showLegacyTabBar: Boolean,
    tabs: List<DriveTab>,
    activeTabId: Long,
    feedTabId: Long?,
    hasTabBarAbove: Boolean,
    firstTabActive: Boolean,
    folderStack: List<PathBreadcrumb>,
    activeFolder: PathBreadcrumb,
    folderMapClosed: Boolean,
    commands: DriveCommands,
    mountAction: SheetAction?,
    displayedFiles: List<FileStat>,
    selectedIdSet: Set<String>,
    viewMode: DriveViewMode,
    tileSize: TileSize,
    galleryActive: Boolean,
    galleryShowImages: Boolean,
    currentSection: String?,
    feedShown: Boolean,
    feedStashed: Boolean,
    feedBlockedReason: String?,
    addLinkHasCollapsedWork: Boolean,
    placePrimaryAction: SheetAction?,
    inDuplicates: Boolean,
    duplicateState: DuplicateFinderState?,
    duplicateReport: DuplicateReportViewModel,
    searchFocusRequests: Int,
    addressBar: @Composable () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewRootTab: () -> Unit,
    onBack: () -> Unit,
    onOpenFolderMap: () -> Unit,
    onNewFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onFindDuplicates: () -> Unit,
    onCanonicalNaming: () -> Unit,
    onJumpToSection: (Int) -> Unit,
    onRefresh: () -> Unit,
    onViewModeChange: (DriveViewMode) -> Unit,
    onTileSizeChange: (TileSize) -> Unit,
    onFeedShownChange: ((Boolean) -> Unit)?,
    onGalleryShownChange: ((Boolean) -> Unit)?,
    onGalleryShowImagesChange: (Boolean) -> Unit,
    onResumeFeed: (() -> Unit)?,
    onOpenAddLink: () -> Unit,
    onDuplicateKindChange: (DuplicateKind) -> Unit,
) {
    // 浏览历史后退/前进能力仅在桌面导航栏订阅。
    val history by state.history.collectAsStateWithLifecycle()
    val selectedBytes by remember(state, displayedFiles, selectedIdSet) {
        derivedStateOf {
            if (!state.isSelectionMode) 0L
            else displayedFiles.sumOf { if (!it.isFolder && it.id in selectedIdSet) it.sizeBytes else 0L }
        }
    }
    val language = PikoLocale.effectiveLanguage
    val sectionLabels = remember(galleryActive, state.sectionHeaders, language) {
        if (galleryActive) emptyList() else state.sectionHeaders.map { it.value.menuLabel }
    }
    val bars: @Composable () -> Unit = {
        val activeFolderName = activeFolder.localizedName
        ExplorerNavBar(
            shortcuts = shortcuts,
            canGoBack = history.canGoBack || !isWorkspaceBrowser,
            canGoForward = history.canGoForward,
            canGoUp = folderStack.size > 1,
            onBack = onBack,
            onForward = state::goForward,
            onUp = state::navigateUp,
            showFolderMap = if (folderMapClosed) onOpenFolderMap else null,
            address = addressBar,
            search = {
                ExplorerSearchField(
                    query = state.searchQuery,
                    onQueryChange = state::updateSearchQuery,
                    placeholder = tr("搜索 $activeFolderName", "Search $activeFolderName", "$activeFolderName を検索", "$activeFolderName 검색", "搜尋 $activeFolderName"),
                    shortcut = shortcuts.label("F"),
                    isGlobalSearching = state.isGlobalSearching,
                    isGlobalSearchActive = state.isGlobalSearchActive,
                    onStartGlobalSearch = state::startGlobalSearch,
                    onCancelGlobalSearch = state::cancelGlobalSearch,
                    focusRequests = searchFocusRequests,
                )
            },
        )
        ExplorerCommandBar(
            shortcuts = shortcuts,
            commands = commands,
            newActions = listOfNotNull(
                SheetAction(Icons.Outlined.CreateNewFolder, tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"), onNewFolder),
                mountAction,
                SheetAction(Icons.Outlined.UploadFile, tr("上传文件", "Upload files", "ファイルをアップロード", "파일 업로드", "上傳檔案"), onPickFiles),
                SheetAction(Icons.Outlined.DriveFolderUpload, tr("上传文件夹", "Upload folder", "フォルダーをアップロード", "폴더 업로드", "上傳資料夾"), onPickFolder),
            ),
            selectedCount = if (state.isSelectionMode) state.selectedFileIds.size else 0,
            selectedBytes = selectedBytes,
            onExitSelection = state::exitSelection,
            onPaste = state::paste,
            onSelectAll = onSelectAll,
            onFindDuplicates = onFindDuplicates,
            onCanonicalNaming = onCanonicalNaming,
            sortOrder = state.sortOrder,
            onSortChange = state::changeSortOrder,
            perFolderSort = state.perFolderSort,
            onPerFolderSortChange = state::setPerFolderSortEnabled,
            foldersFirst = state.foldersFirst,
            onFoldersFirstChange = state::updateFoldersFirst,
            typeFilter = state.typeFilter,
            availableTypes = state.availableTypes,
            onTypeFilterChange = state::updateTypeFilter,
            sectionJumper = {
                SectionJumper(
                    currentSection = currentSection.takeUnless { galleryActive },
                    sections = sectionLabels,
                    onSectionSelected = onJumpToSection,
                )
            },
            onRefresh = onRefresh,
            viewSwitcherOverflow = viewOverflowActions(
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                tileSize = tileSize,
                onTileSizeChange = onTileSizeChange,
                galleryShown = galleryActive && !state.isSelectionMode,
                onGalleryShownChange = onGalleryShownChange.takeIf { commands.gallery || galleryActive },
                feedShown = feedShown && !state.isSelectionMode,
                onFeedShownChange = onFeedShownChange.takeIf { commands.feed && !feedStashed && feedBlockedReason == null },
                galleryShowImages = galleryShowImages,
                onGalleryShowImagesChange = onGalleryShowImagesChange,
            ),
            viewSwitcher = {
                ViewSwitcher(
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    tileSize = tileSize,
                    onTileSizeChange = onTileSizeChange,
                    feedShown = feedShown && !state.isSelectionMode,
                    onFeedShownChange = onFeedShownChange.takeIf { commands.feed && !feedStashed },
                    feedSuspended = false,
                    feedBlockedReason = feedBlockedReason,
                    galleryShown = galleryActive && !state.isSelectionMode,
                    onGalleryShownChange = onGalleryShownChange.takeIf { commands.gallery || galleryActive },
                    galleryShowImages = galleryShowImages,
                    onGalleryShowImagesChange = onGalleryShowImagesChange,
                )
            },
            primaryAction = placePrimaryAction ?: if (feedStashed && onResumeFeed != null) {
                SheetAction(Icons.Outlined.SwipeVertical, tr("继续浏览", "Resume", "再開", "계속 보기", "繼續瀏覽"), onResumeFeed, group = ActionGroup.Refresh)
            } else if (commands.addLink) {
                SheetAction(Icons.Outlined.Bolt, tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"), onOpenAddLink, group = ActionGroup.Refresh, pending = addLinkHasCollapsedWork)
            } else null,
            customLeading = if (inDuplicates && duplicateState != null && !duplicateState.isScanning) {
                duplicateCommandBarLeading(
                    finder = duplicateState,
                    selectedKind = duplicateReport.kind,
                    onKindChange = onDuplicateKindChange,
                    reportView = duplicateReport,
                    selectedIds = selectedIdSet,
                    onSelectIds = { ids -> if (ids.isEmpty()) state.exitSelection() else state.selectOnly(ids) },
                )
            } else emptyList(),
        )
    }

    Column {
        if (showLegacyTabBar) {
            DriveTabBar(
                tabs = tabs,
                activeId = activeTabId,
                onSelect = onSelectTab,
                onClose = onCloseTab,
                sessionTab = { tab ->
                    when (tab.session) {
                        DriveLibrary.DUPLICATES -> duplicateState?.let(::duplicatesTab)
                        else -> null
                    }
                },
                feedTabId = feedTabId,
                onNewTab = onNewRootTab,
                newTabShortcut = shortcuts.label("T"),
            )
        }
        Column(
            Modifier
                .then(if (!hasTabBarAbove) Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets) else Modifier)
                .padding(top = if (hasTabBarAbove) 0.dp else IslandGap, end = IslandGap)
                .clip(
                    RoundedCornerShape(
                        topStart = if (hasTabBarAbove) islandTopStart(firstActive = firstTabActive) else IslandCorner,
                        topEnd = IslandCorner,
                    ),
                )
                .background(MaterialTheme.colorScheme.islandHeader)
                .padding(bottom = 8.dp),
        ) {
            CompositionLocalProvider(LocalHeaderOnIsland provides true) { bars() }
        }
    }
}

@Composable
internal fun DriveCompactHeader(
    state: DriveScreenState,
    shortcuts: ShortcutModifier,
    scrollBehavior: TopAppBarScrollBehavior,
    folderStack: List<PathBreadcrumb>,
    rootDepth: Int,
    rootTitle: String?,
    activeFolder: PathBreadcrumb,
    atOriginBoundary: Boolean,
    inDuplicates: Boolean,
    duplicateState: DuplicateFinderState?,
    duplicateReport: DuplicateReportViewModel,
    commands: DriveCommands,
    selectedCount: Int,
    selectedIdSet: Set<String>,
    currentSection: String?,
    galleryActive: Boolean,
    galleryShowImages: Boolean,
    feedShown: Boolean,
    feedStashed: Boolean,
    hasFeedEntry: Boolean,
    tileSize: TileSize,
    onSelectAll: () -> Unit,
    onCloseSearch: () -> Unit,
    onJumpToSection: (Int) -> Unit,
    onCloseContext: (() -> Unit)?,
    onEndDuplicateSession: () -> Unit,
    onLeaveLibrary: () -> Unit,
    onFindDuplicates: () -> Unit,
    onCanonicalNaming: () -> Unit,
    onDuplicateKindChange: (DuplicateKind) -> Unit,
    onGalleryShownChange: ((Boolean) -> Unit)?,
    onFeedShownChange: ((Boolean) -> Unit)?,
    onTileSizeChange: (TileSize) -> Unit,
    onGalleryShowImagesChange: (Boolean) -> Unit,
) {
    val isSearchOpen = state.isSearchOpen
    val language = PikoLocale.effectiveLanguage
    val sectionLabels = remember(galleryActive, state.sectionHeaders, language) {
        if (galleryActive) emptyList() else state.sectionHeaders.map { it.value.menuLabel }
    }
    Column {
        when {
            state.isSelectionMode -> DriveSelectionTopBar(
                scrollBehavior = scrollBehavior,
                selectedCount = selectedCount,
                onExit = state::exitSelection,
                onSelectAll = onSelectAll.takeIf { commands.selectAll },
            )
            isSearchOpen -> DriveSearchTopBar(
                query = state.searchQuery,
                onQueryChange = state::updateSearchQuery,
                onClose = onCloseSearch,
                isGlobalSearching = state.isGlobalSearching,
                isGlobalSearchActive = state.isGlobalSearchActive,
                onStartGlobalSearch = state::startGlobalSearch,
                onCancelGlobalSearch = state::cancelGlobalSearch,
            )
            else -> DriveBrowseTopBar(
                scrollBehavior = scrollBehavior,
                title = if (folderStack.size <= rootDepth) rootTitle ?: activeFolder.localizedName else activeFolder.localizedName,
                currentSection = currentSection.takeUnless { galleryActive },
                sections = sectionLabels,
                onSectionSelected = onJumpToSection,
                moreActions = emptyList(),
                navigationIcon = when {
                    inDuplicates && onCloseContext != null && folderStack.size <= rootDepth -> {
                        { TooltipIconButton(Icons.Outlined.Close, tr("结束查重", "End duplicate search", "重複検索を終了", "중복 검색 종료", "結束重複檔案搜尋"), onCloseContext) }
                    }
                    atOriginBoundary -> {
                        { TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back", "戻る", "뒤로", "返回"), onLeaveLibrary) }
                    }
                    folderStack.size > rootDepth -> {
                        {
                            TooltipIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                label = tr("返回上一级", "Go to parent", "上の階層へ戻る", "상위 폴더로 이동", "返回上一層"),
                                onClick = state::navigateUp,
                                shortcut = if (shortcuts == ShortcutModifier.Command) "⌘↑" else "Alt+↑",
                            )
                        }
                    }
                    else -> null
                },
                actions = listOf(
                    iconBarItem(Icons.Outlined.Search, tr("搜索", "Search", "検索", "검색", "搜尋"), state::openSearch, priority = 30, shortcut = shortcuts.label("F"), key = "search"),
                ),
            )
        }
        val hasMobileCommands = commands.paste || commands.findDuplicates || commands.canonicalNaming ||
            commands.gallery || galleryActive || (commands.feed && hasFeedEntry)
        val showMobileCommandBar = !isSearchOpen &&
            if (inDuplicates) duplicateState != null && !duplicateState.isScanning else hasMobileCommands
        if (showMobileCommandBar) {
            AdaptiveBar(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                leading = if (inDuplicates && duplicateState != null && !duplicateState.isScanning) {
                    duplicateCommandBarLeading(
                        finder = duplicateState,
                        selectedKind = duplicateReport.kind,
                        onKindChange = onDuplicateKindChange,
                        reportView = duplicateReport,
                        selectedIds = selectedIdSet,
                        onSelectIds = { ids -> if (ids.isEmpty()) state.exitSelection() else state.selectOnly(ids) },
                    )
                } else buildList {
                    if (commands.paste) {
                        add(
                            BarItem("paste", 80, listOf(SheetAction(Icons.Outlined.ContentPaste, tr("粘贴", "Paste", "貼り付け", "붙여넣기", "貼上"), state::paste, group = ActionGroup.Edit))) {
                                BarTextButton(Icons.Outlined.ContentPaste, tr("粘贴", "Paste", "貼り付け", "붙여넣기", "貼上"), state::paste)
                            },
                        )
                    }
                    if (commands.findDuplicates) {
                        val action = DriveActions.findDuplicates(onFindDuplicates)
                        add(BarItem("findDuplicates", 60, listOf(action)) { BarTextButton(action.icon, action.label, onFindDuplicates) })
                    }
                    if (commands.canonicalNaming) {
                        val action = DriveActions.canonicalName(onCanonicalNaming)
                        add(BarItem("canonicalNaming", 55, listOf(action)) { BarTextButton(action.icon, action.label, onCanonicalNaming) })
                    }
                },
                moreActions = if (inDuplicates && duplicateState != null) {
                    listOf(
                        SheetAction(Icons.Outlined.Refresh, tr("重新扫描", "Scan again", "再検索", "다시 검색", "重新掃描"), duplicateState::rescan, group = ActionGroup.Refresh),
                        SheetAction(Icons.Outlined.Close, tr("结束查重", "End duplicate search", "重複検索を終了", "중복 검색 종료", "結束重複檔案搜尋"), onCloseContext ?: onEndDuplicateSession, group = ActionGroup.Refresh),
                    )
                } else emptyList(),
                trailing = buildList {
                    if (commands.gallery || galleryActive || (commands.feed && hasFeedEntry)) {
                        add(
                            BarItem(key = "explore", priority = PinnedPriority,
                                overflow = viewOverflowActions(
                                    tileSize = tileSize,
                                    onTileSizeChange = onTileSizeChange,
                                    galleryShown = galleryActive && !state.isSelectionMode,
                                    onGalleryShownChange = onGalleryShownChange.takeIf { commands.gallery || galleryActive },
                                    feedShown = feedShown && !state.isSelectionMode,
                                    onFeedShownChange = onFeedShownChange.takeIf { commands.feed && !feedStashed },
                                    galleryShowImages = galleryShowImages,
                                    onGalleryShowImagesChange = onGalleryShowImagesChange,
                                ),
                            ) {
                                ExploreToggleGroup(
                                    galleryShown = galleryActive && !state.isSelectionMode,
                                    onGalleryShownChange = onGalleryShownChange.takeIf { commands.gallery || galleryActive },
                                    feedShown = feedShown && !state.isSelectionMode,
                                    onFeedShownChange = onFeedShownChange.takeIf { commands.feed && !feedStashed },
                                    tileSize = tileSize,
                                    onTileSizeChange = onTileSizeChange,
                                    galleryShowImages = galleryShowImages,
                                    onGalleryShowImagesChange = onGalleryShowImagesChange,
                                )
                            },
                        )
                    }
                },
                moreAfterLeading = true,
                moreIcon = Icons.Outlined.MoreHoriz,
                gap = 4.dp,
            )
        }
    }
}

/**
 * 多选上下文浮动工具栏：遵循 M3 Expressive Toolbars（Standard Floating Toolbar）规范，
 * 选中至少一项时从底部中央升起（覆盖手机、平板与桌面全形态），保留 16dp 边缘净距（FloatingToolbarDefaults.ScreenOffset），
 * 采用柔和的 `standardFloatingToolbarColors`（`surfaceContainerHigh`）避免抢夺网盘内容焦点，
 * 并通过竖向分隔线将破坏性删除与常规批量动作自然分组，保持工具栏内控件规格统一。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DriveSelectionFloatingToolbar(
    visible: Boolean,
    inTrash: Boolean,
    inDuplicates: Boolean = false,
    applyNavBarsPadding: Boolean,
    availableActions: List<SheetAction>,
    onOpenSelectionActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nonDestructiveIcons = if (inTrash) {
        listOf(Icons.Outlined.RestoreFromTrash)
    } else if (inDuplicates) {
        listOf(Icons.Outlined.DriveFileMove, Icons.Outlined.Download)
    } else {
        listOf(
            Icons.Outlined.Download,
            Icons.Outlined.DriveFileMove,
            Icons.Outlined.CallSplit,
            Icons.Outlined.CallMerge,
            Icons.Outlined.Inventory2,
            Icons.Outlined.DriveFileRenameOutline,
        )
    }
    val nonDestructiveActions = remember(inTrash, inDuplicates, availableActions) {
        nonDestructiveIcons.mapNotNull { icon ->
            availableActions.firstOrNull { !it.destructive && it.icon == icon }
        }
    }
    val destructiveAction = remember(inTrash, availableActions) {
        val preferredIcon = if (inTrash) Icons.Outlined.DeleteForever else Icons.Outlined.Delete
        availableActions.firstOrNull { it.destructive && it.icon == preferredIcon }
            ?: availableActions.firstOrNull { it.destructive && (it.icon == Icons.Outlined.Delete || it.icon == Icons.Outlined.DeleteForever) }
    }
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toolbarColors = if (isEInk) {
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = Color.White,
            toolbarContentColor = Color.Black,
        )
    } else {
        FloatingToolbarDefaults.standardFloatingToolbarColors()
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it / 2 } + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier
            .then(if (applyNavBarsPadding) Modifier.navigationBarsPadding() else Modifier)
            .padding(
                horizontal = FloatingToolbarDefaults.ScreenOffset,
                vertical = FloatingToolbarDefaults.ScreenOffset,
            ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = toolbarColors,
            expandedShadowElevation = if (isEInk) 0.dp else 3.dp,
            collapsedShadowElevation = if (isEInk) 0.dp else 2.dp,
            modifier = if (isEInk) Modifier.border(1.dp, Color.Black, FloatingToolbarDefaults.ContainerShape) else Modifier,
        ) {
            val contentTint = LocalContentColor.current
            nonDestructiveActions.forEach { action ->
                TooltipIconButton(
                    icon = action.icon,
                    label = action.shortLabel,
                    onClick = action.onClick,
                    tint = contentTint,
                )
            }
            if (destructiveAction != null) {
                if (nonDestructiveActions.isNotEmpty()) {
                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 2.dp)
                            .height(20.dp),
                        color = if (isEInk) Color.Black else colors.outlineVariant.copy(alpha = 0.7f),
                    )
                }
                if (inDuplicates || inTrash) {
                    Spacer(Modifier.width(2.dp))
                    Surface(
                        onClick = destructiveAction.onClick,
                        shape = CircleShape,
                        color = if (isEInk) Color.White else colors.errorContainer,
                        contentColor = if (isEInk) Color.Black else colors.onErrorContainer,
                        border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = destructiveAction.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = destructiveAction.shortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                } else {
                    TooltipIconButton(
                        icon = destructiveAction.icon,
                        label = destructiveAction.shortLabel,
                        onClick = destructiveAction.onClick,
                        tint = if (isEInk) Color.Black else colors.error,
                    )
                }
            }
            TooltipIconButton(
                icon = Icons.Outlined.MoreHoriz,
                label = tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"),
                onClick = onOpenSelectionActions,
                tint = contentTint,
            )
        }
    }
}

/**
 * 移动端添加菜单 FAB：菜单展开状态（UI element state）完全内聚于本叶子组件内部（遵循 Compose State Hoisting 最小公共祖先原则），
 * 展开/收起时不触发外层网盘列表重组。
 */
@Composable
internal fun DriveAddFabMenu(
    acceptsNavigation: Boolean,
    isSelectionMode: Boolean,
    commands: DriveCommands,
    placePrimaryAction: SheetAction?,
    hasActiveInstantDraft: Boolean,
    hasCollapsedAddLinkWork: Boolean,
    mountAction: SheetAction?,
    onPaste: () -> Unit,
    onOpenAddLink: () -> Unit,
    onNewFolder: () -> Unit,
    onPickFiles: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = acceptsNavigation && expanded) { expanded = false }

    if (placePrimaryAction != null) {
        PrimaryActionFab(placePrimaryAction)
    } else if (!isSelectionMode && (commands.addLink || commands.create || commands.paste)) {
        FloatingActionButtonMenu(
            expanded = expanded,
            modifier = Modifier.offset(x = 16.dp, y = 16.dp),
            button = {
                ToggleFloatingActionButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                ) {
                    val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                    Icon(
                        imageVector = icon,
                        contentDescription = if (expanded) tr("收起", "Collapse", "折りたたむ", "접기", "收合") else tr("快捷操作", "Quick actions", "クイック操作", "빠른 작업", "快速操作"),
                        modifier = Modifier.animateIcon({ checkedProgress }),
                    )
                }
            },
        ) {
            if (commands.paste) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        onPaste()
                    },
                    icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                    text = { Text(stringResource(Res.string.text_paste)) },
                )
            }
            if (commands.addLink) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        onOpenAddLink()
                    },
                    icon = { PendingBadge(shown = hasCollapsedAddLinkWork) { Icon(Icons.Outlined.Bolt, contentDescription = null) } },
                    text = { Text(if (hasActiveInstantDraft) tr("继续添加链接", "Add more links", "リンクを追加する", "링크 계속 추가", "繼續新增連結") else tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結")) },
                )
            }
            if (commands.create) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        onNewFolder()
                    },
                    icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    text = { Text(stringResource(Res.string.text_new_folder)) },
                )
                mountAction?.let { action ->
                    FloatingActionButtonMenuItem(
                        onClick = {
                            expanded = false
                            action.onClick()
                        },
                        icon = { Icon(action.icon, contentDescription = null) },
                        text = { Text(action.label) },
                    )
                }
                FloatingActionButtonMenuItem(
                    onClick = {
                        expanded = false
                        onPickFiles()
                    },
                    icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                    text = { Text(stringResource(Res.string.text_upload_files)) },
                )
            }
        }
    }
}
