package dev.piko.ui.screens.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
    onOpenSelectionActions: () -> Unit,
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
                onActions = onOpenSelectionActions,
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

@Composable
internal fun DriveCompactSelectionBar(
    inTrash: Boolean,
    hasTargets: Boolean,
    availableActions: List<SheetAction>,
    onOpenSelectionActions: () -> Unit,
) {
    val coreIcons = if (inTrash) {
        listOf(Icons.Outlined.RestoreFromTrash, Icons.Outlined.DeleteForever)
    } else {
        listOf(Icons.Outlined.Download, Icons.Outlined.DriveFileMove, Icons.Outlined.DriveFileRenameOutline, Icons.Outlined.Delete)
    }
    val coreActions = coreIcons.mapNotNull { icon -> availableActions.firstOrNull { it.icon == icon } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasTargets) {
                (coreActions + SheetAction(Icons.Outlined.MoreHoriz, tr("更多", "More", "その他", "더 보기", "更多"), onOpenSelectionActions)).forEach { action ->
                    TextButton(
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f).height(56.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            Text(action.shortLabel, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
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

@Composable
internal fun DrivePaletteContribution(
    state: DriveScreenState,
    shortcuts: ShortcutModifier,
    isWorkspaceBrowser: Boolean,
    pathInTopBar: Boolean,
    folderStack: List<PathBreadcrumb>,
    activeFolder: PathBreadcrumb,
    viewMode: DriveViewMode,
    folderMapToggleLabel: String,
    mountAction: SheetAction?,
    commandTargets: List<FileStat>,
    targetCommands: ItemCommands,
    tabsAvailable: Boolean,
    tabs: List<DriveTab>,
    activeTabId: Long,
    onNewFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleFolderMap: () -> Unit,
    onEditAddress: () -> Unit,
    onFocusSearch: () -> Unit,
    onShowProperties: () -> Unit,
    onSelectViewMode: (DriveViewMode) -> Unit,
    onSelectAll: () -> Unit,
    onRefresh: () -> Unit,
    onNewRootTab: () -> Unit,
    onCloseTab: (Long) -> Unit,
    onSelectTab: (Long) -> Unit,
    onFindDuplicates: () -> Unit,
    onOpenSpaceMap: ((String, String) -> Unit)?,
    onNameTargetsByCode: (List<FileStat>) -> Unit,
    onCanonicalCurrentFolder: () -> Unit,
) {
    ContributePaletteItems("drive") {
        val label = shortcuts::label
        buildList {
            if (!state.isVirtualPlace && isWorkspaceBrowser) {
                add(PaletteItem(tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"), Icons.Outlined.CreateNewFolder, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "new folder mkdir", run = onNewFolder))
                mountAction?.let { action ->
                    add(PaletteItem(action.label, action.icon, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "mount local folder 挂载 本地 文件夹", run = action.onClick))
                }
                add(PaletteItem(tr("上传文件", "Upload files", "ファイルをアップロード", "파일 업로드", "上傳檔案"), Icons.Outlined.UploadFile, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "upload", run = onPickFiles))
                add(PaletteItem(tr("上传文件夹", "Upload folder", "フォルダーをアップロード", "폴더 업로드", "上傳資料夾"), Icons.Outlined.DriveFolderUpload, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "upload folder", run = onPickFolder))
            }
            add(
                PaletteItem(
                    folderMapToggleLabel,
                    Icons.Outlined.AccountTree,
                    tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"),
                    detail = if (shortcuts == ShortcutModifier.Command) "⌘⇧E" else "Ctrl+Shift+E",
                    keywords = "folder tree map 树",
                    run = onToggleFolderMap,
                ),
            )
            add(PaletteItem(tr("转到路径", "Go to path", "パスへ移動", "경로로 이동", "前往路徑"), Icons.Outlined.FolderOpen, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("L"), keywords = "path address 路径", run = onEditAddress))
            add(
                PaletteItem(tr("搜索文件", "Search files", "ファイルを検索", "파일 검색", "搜尋檔案"), Icons.Outlined.Search, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("F"), keywords = "search find") {
                    if (pathInTopBar) onFocusSearch() else state.openSearch()
                },
            )
            add(PaletteItem(tr("属性", "Properties", "プロパティ", "속성", "屬性"), Icons.Outlined.Info, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("I"), keywords = "properties info details", run = onShowProperties))
            DriveViewMode.entries.filter { it != viewMode }.forEach { mode ->
                val modeLabel = mode.paletteLabel
                add(
                    PaletteItem(
                        tr("切换到$modeLabel", "Switch to $modeLabel", "${modeLabel}に切り替え", "$modeLabel(으)로 전환", "切換至$modeLabel"),
                        mode.icon(),
                        tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"),
                        keywords = "视图 view ${mode.name.lowercase()}",
                    ) { onSelectViewMode(mode) },
                )
            }
            add(PaletteItem(tr("全选", "Select all", "すべて選択", "모두 선택", "全選"), Icons.Outlined.SelectAll, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = label("A"), keywords = "select all", run = onSelectAll))
            add(PaletteItem(tr("刷新", "Refresh", "更新", "새로고침", "重新整理"), Icons.Outlined.Refresh, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = "F5", keywords = "refresh reload", run = onRefresh))
            if (folderStack.size > 1) {
                add(PaletteItem(tr("上一级", "Parent folder", "上の階層", "상위 폴더", "上一層"), Icons.Outlined.ArrowUpward, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), detail = "Backspace", keywords = "up parent", run = state::navigateUp))
            }
            if (tabsAvailable) {
                add(PaletteItem(tr("新建标签页", "New tab", "新しいタブ", "새 탭", "新增分頁"), Icons.Outlined.Add, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = label("T"), keywords = "new tab", run = onNewRootTab))
                if (tabs.size > 1) {
                    add(PaletteItem(tr("关闭标签页", "Close tab", "タブを閉じる", "탭 닫기", "關閉分頁"), Icons.Outlined.Close, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = label("W"), keywords = "close tab") { onCloseTab(activeTabId) })
                    tabs.filter { it.id != activeTabId }.forEach { tab ->
                        add(PaletteItem(tab.title, Icons.Outlined.Tab, tr("标签", "Tabs", "タブ", "탭", "分頁"), detail = tab.stack.joinToString(" › ") { it.localizedName }, keywords = "tab") {
                            onSelectTab(tab.id)
                        })
                    }
                }
            }
            if (!state.isVirtualPlace) {
                add(PaletteItem(tr("在当前文件夹查找重复", "Find duplicates in current folder", "現在のフォルダーの重複を検索", "현재 폴더에서 중복 찾기", "在目前資料夾尋找重複檔案"), Icons.Outlined.FileCopy, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "duplicate dedupe", run = onFindDuplicates))
                if (onOpenSpaceMap != null && isDriveFolderId(activeFolder.id)) {
                    add(
                        PaletteItem(tr("查看当前文件夹空间分布", "View space usage of current folder", "現在のフォルダーのストレージ内訳を表示", "현재 폴더의 공간 분포 보기", "檢視目前資料夾空間分佈"), Icons.Outlined.PieChart, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "space usage storage treemap 空间 容量") {
                            onOpenSpaceMap(activeFolder.id, activeFolder.localizedName)
                        },
                    )
                }
            }
            val nameTargets = commandTargets.filterNot { it.isUploading }
            when {
                nameTargets.isNotEmpty() -> if (targetCommands.rename && nameTargets.any { it.isFolder || hasAvCode(it.name) }) {
                    add(PaletteItem(tr("按番号规范命名", "Normalize names by catalog code", "作品番号で名前を整理", "품번으로 이름 정리", "依番號規範命名"), Icons.Outlined.DriveFileRenameOutline, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "rename av code 番号 重命名") { onNameTargetsByCode(nameTargets) })
                }
                !state.isVirtualPlace -> add(
                    PaletteItem(tr("按番号规范命名当前文件夹", "Normalize this folder by catalog code", "現在のフォルダーを作品番号で命名", "현재 폴더를 품번으로 이름 정리", "依番號規範命名目前資料夾"), Icons.Outlined.DriveFileRenameOutline, tr("网盘", "Drive", "ドライブ", "드라이브", "雲端硬碟"), keywords = "rename av code 番号 重命名 整理", run = onCanonicalCurrentFolder),
                )
            }
        }
    }
}
