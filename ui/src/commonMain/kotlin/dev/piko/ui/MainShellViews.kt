package dev.piko.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SwapVerticalCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.SnailMode
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.QuickAccessState
import dev.piko.ui.components.CommandPalette
import dev.piko.ui.components.FileDragOverlay
import dev.piko.ui.components.FileDragState
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PaletteRegistry
import dev.piko.ui.components.PikoBrand
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components. connectedToggleShapes
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.text_close_feed_alt
import dev.piko.ui.resources.text_closing_clears_the_current_queue_and_browsing_progress
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.screens.drive.LocalSidebarCollapsed
import dev.piko.ui.screens.drive.QuickAccessSection
import dev.piko.ui.screens.drive.SectionLabel
import dev.piko.ui.screens.drive.SidebarItem
import dev.piko.ui.screens.drive.icon
import dev.piko.ui.screens.drive.selectedIcon
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.settings.ProfileScreen
import dev.piko.ui.screens.transfers.TransfersScreen
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.frame
import dev.piko.ui.workbench.ClaimDialog
import dev.piko.ui.workbench.LocatingOverlay
import dev.piko.ui.workbench.OngoingWorkSheet
import dev.piko.ui.workbench.ShortcutsDialog
import dev.piko.ui.workbench.SidebarAccountRow
import dev.piko.ui.workbench.SidebarTransferReadout
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.TransferActivity
import dev.piko.ui.workbench.WorkspaceContextDialogs
import dev.piko.ui.workbench.WorkspaceContextHost
import dev.piko.ui.workbench.WorkspaceViewModel
import dev.piko.ui.workbench.rememberTransferActivity
import io.github.nihildigit.pikpak.FileStat

@Composable
internal fun MainFeedContent(
    compact: Boolean,
    visible: Boolean,
    feedOpened: Boolean,
    onHome: Boolean,
    feedSuspended: Boolean,
    onRequestCloseFeed: () -> Unit,
    onPlayFromFeed: (FileStat, Long) -> Unit,
    onLocateFromFeed: (FileStat) -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    onPopOut: (() -> Unit)?,
) {
    when {
        !visible || !feedOpened || !onHome -> Box(Modifier.fillMaxSize().background(Color.Black))
        else -> ClipFeedScreen(
            onBackClick = onRequestCloseFeed,
            onPlayFull = onPlayFromFeed,
            onLocate = onLocateFromFeed,
            compact = compact,
            pausedByHost = feedSuspended,
            onToggleFullscreen = onToggleFullscreen,
            onPopOut = onPopOut,
        )
    }
}

@Composable
internal fun MainHomeContent(
    workspaceModel: WorkspaceViewModel,
    workspaceUiStates: SaveableStateHolder,
    fileBrowser: DriveBrowserSession,
    currentTab: MainTab,
    sidebarMode: Boolean,
    desktop: Boolean,
    heightCompact: Boolean,
    onHome: Boolean,
    panelFits: Boolean,
    feedShown: Boolean,
    feedOpened: Boolean,
    feedSuspended: Boolean,
    feedPausedByHost: Boolean,
    feedFullScreen: Boolean,
    feedBlockedReason: String?,
    feedDetourTab: Long?,
    canLeaveOrigin: Boolean,
    filesScrollToTop: Int,
    transfersScrollToTop: Int,
    addressDestinations: List<PaletteItem>,
    feedFrame: @Composable (@Composable () -> Unit) -> Unit,
    onSelectMainTab: (MainTab) -> Unit,
    onTabClick: (MainTab) -> Unit,
    onMeasuredContentSize: (IntSize) -> Unit,
    onPlayVideo: (FileStat, List<FileStat>) -> Unit,
    onPlayLocal: (String, String, String?) -> Unit,
    onRevealById: (String) -> Unit,
    onOpenCloudFile: suspend (String, String) -> Boolean,
    onOpenTransfers: () -> Unit,
    onOpenDuplicateReport: () -> Unit,
    onOpenSpaceMap: (String, String) -> Unit,
    onLeaveLibrary: () -> Unit,
    onFeedShownChange: (Boolean) -> Unit,
    onOpenSelectedFeed: (List<FileStat>) -> Unit,
    onResumeFeed: () -> Unit,
    onRequestCloseFeed: () -> Unit,
    onPlayFromFeed: (FileStat, Long) -> Unit,
    onLocateFromFeed: (FileStat) -> Unit,
    onToggleFeedFullscreen: (() -> Unit)?,
    onPopOutFeed: (() -> Unit)?,
    onCreateImportFromTransfers: (String) -> Unit,
    onLogout: () -> Unit,
    onOpenProfilePane: (Screen) -> Unit,
    onOpenLibraryFromProfile: (DriveLibrary) -> Unit,
) {
    val motion = LocalPikoMotion.current
    val mainContent: @Composable () -> Unit = {
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = { motion.topLevel() },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged(onMeasuredContentSize),
            label = "mainTab",
        ) { tab ->
            when (tab) {
                MainTab.FILES -> {
                    WorkspaceContextHost(
                        model = workspaceModel,
                        onPlay = onPlayVideo,
                        onReveal = { file -> onRevealById(file.id) },
                        onTransfers = onOpenTransfers,
                        onOpenSpaceMap = onOpenSpaceMap,
                        onMinimize = { workspaceModel.minimizeContext(workspaceModel.contexts.activeContextId.value) },
                        retainedStates = workspaceUiStates.takeIf { tab == currentTab },
                    ) {
                        key(fileBrowser) {
                            FilesScreen(
                                onNavigateToVideoPlayer = onPlayVideo,
                                scrollToTopRequests = filesScrollToTop,
                                onOpenTransfers = onOpenTransfers,
                                onOpenDuplicateReport = onOpenDuplicateReport,
                                feedShown = feedShown && !feedSuspended,
                                onFeedShownChange = onFeedShownChange,
                                onOpenSelectedFeed = onOpenSelectedFeed,
                                feedStashed = feedSuspended,
                                onResumeFeed = onResumeFeed,
                                feedBlockedReason = feedBlockedReason,
                                feedTabId = feedDetourTab,
                                addLinkInSidePanel = panelFits,
                                feedFrame = feedFrame,
                                addressDestinations = addressDestinations,
                                browserSession = fileBrowser,
                                onLibraryLeft = onLeaveLibrary,
                                canLeaveOrigin = canLeaveOrigin,
                                onRevealInDrive = { file -> onRevealById(file.id) },
                                onOpenSpaceMap = onOpenSpaceMap,
                            )
                        }
                    }
                }
                MainTab.TRANSFERS -> {
                    TransfersScreen(
                        state = workspaceModel.transfers,
                        onCreateImport = onCreateImportFromTransfers,
                        scrollToTopRequests = transfersScrollToTop,
                        onOpenCloudFile = onOpenCloudFile,
                        onNavigateToVideoPlayer = onPlayLocal,
                    )
                }
                MainTab.SETTINGS -> {
                    ProfileScreen(
                        onLogout = onLogout,
                        onOpenPane = onOpenProfilePane,
                        selectedPane = null,
                        onOpenLibrary = onOpenLibraryFromProfile,
                    )
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        BackHandler(enabled = currentTab != MainTab.FILES && !feedFullScreen) {
            onSelectMainTab(MainTab.FILES)
        }

        val activeContextId by workspaceModel.contexts.activeContextId.collectAsStateWithLifecycle()
        val activeContexts by workspaceModel.contexts.contextsFlow.collectAsStateWithLifecycle()
        val activeContext = activeContexts.firstOrNull { it.id == activeContextId }
        val activeDriveState = when (activeContext) {
            is WorkspaceContext.Duplicate -> workspaceModel.duplicateBrowser(activeContext.id).state
            is WorkspaceContext.File -> fileBrowser.state
            else -> null
        }
        val selectingFiles = currentTab == MainTab.FILES && activeDriveState?.isSelectionMode == true
        val navigationSuiteType = when {
            sidebarMode || selectingFiles -> NavigationSuiteType.None
            heightCompact -> NavigationSuiteType.WideNavigationRailCollapsed
            else -> NavigationSuiteType.ShortNavigationBarCompact
        }

        NavigationSuiteScaffold(
            containerColor = if (sidebarMode) MaterialTheme.colorScheme.frame else MaterialTheme.colorScheme.background,
            navigationItems = {
                val activity = if (sidebarMode) null else rememberTransferActivity(pollCloud = false)
                MainTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    val badgeCount = activity?.count?.takeIf { tab == MainTab.TRANSFERS && !selected && it > 0 }
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = { onTabClick(tab) },
                        navigationSuiteType = navigationSuiteType,
                        icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                        label = { Text(tab.title, fontWeight = if (selected) FontWeight.Bold else null) },
                        badge = badgeCount?.let { count -> { Badge { Text(if (count > 99) "99+" else "$count") } } },
                    )
                }
            },
            navigationSuiteType = navigationSuiteType,
            navigationItemVerticalArrangement = Arrangement.Center,
            content = mainContent,
        )

        BackHandler(enabled = feedFullScreen, onBack = onRequestCloseFeed)
        AnimatedVisibility(
            visible = feedFullScreen,
            enter = motion.overlayEnter(),
            exit = motion.overlayExit(),
        ) {
            if (!desktop && heightCompact && feedFullScreen) LocalPikoPlatform.current.HideSystemBars()
            MainFeedContent(
                compact = false,
                visible = feedFullScreen,
                feedOpened = feedOpened,
                onHome = onHome,
                feedSuspended = feedPausedByHost,
                onRequestCloseFeed = onRequestCloseFeed,
                onPlayFromFeed = onPlayFromFeed,
                onLocateFromFeed = onLocateFromFeed,
                onToggleFullscreen = onToggleFeedFullscreen,
                onPopOut = onPopOutFeed,
            )
        }
    }
}

/**
 * 顶层侧边栏插槽：`pinnedFolders` 仅在侧边栏可见时在此处订阅，窄屏或未显示侧边栏时不产生根级重组。
 */
@Composable
internal fun MainSidebarSlot(
    width: Dp,
    collapsed: Boolean,
    currentTab: MainTab,
    quickAccess: QuickAccessState,
    folderStack: List<PikoPathBreadcrumb>,
    selectedPage: Screen?,
    onTabClick: (MainTab) -> Unit,
    onOpenQuickAccess: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenPage: (Screen) -> Unit,
    onToggleLibrary: (DriveLibrary) -> Unit,
    onToggleCollapsed: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val pinnedFolders by quickAccess.pinnedFolders.collectAsStateWithLifecycle(emptyList())
    MainSidebar(
        width = width,
        currentTab = currentTab,
        onTabClick = onTabClick,
        quickAccess = quickAccess,
        pinned = pinnedFolders,
        folderStack = folderStack,
        onOpenQuickAccess = onOpenQuickAccess,
        selectedPage = selectedPage,
        onOpenPage = onOpenPage,
        onToggleLibrary = onToggleLibrary,
        collapsed = collapsed,
        onToggleCollapsed = onToggleCollapsed,
        scrollState = scrollState,
    )
}

/**
 * 主壳层低频全局弹窗宿主（关闭信息流确认、快捷键一览、任务抢占确认、工作总览、命令面板、路径查询遮罩）。
 */
@Composable
internal fun MainShellOverlays(
    desktop: Boolean,
    services: PikoServices,
    workspaceModel: WorkspaceViewModel,
    fileWorkspace: FileWorkspace,
    quickAccess: QuickAccessState,
    folderStack: List<PikoPathBreadcrumb>,
    fileDrag: FileDragState,
    taskSlot: TaskSlot,
    palette: PaletteRegistry,
    shortcutModifier: ShortcutModifier,
    feedShown: Boolean,
    feedSuspended: Boolean,
    feedBlockedReason: String?,
    tabDestinations: () -> List<PaletteItem>,
    pageDestinations: () -> List<PaletteItem>,
    onConfirmCloseFeed: () -> Unit,
    onGoToDrive: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenAddLink: () -> Unit,
    onUndoLast: () -> Unit,
    onResumeFeedFromPalette: () -> Unit,
    onToggleFeedFromPalette: () -> Unit,
    onSyncSettingsNow: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    FileDragOverlay(fileDrag)

    if (workspaceModel.askingCloseFeed) {
        PikoDialog(
            onDismissRequest = { workspaceModel.askingCloseFeed = false },
            title = { Text(stringResource(Res.string.text_close_feed_alt)) },
            text = { Text(stringResource(Res.string.text_closing_clears_the_current_queue_and_browsing_progress)) },
            confirmButton = {
                PikoDialogConfirm(
                    tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                    destructive = true,
                    onClick = {
                        workspaceModel.askingCloseFeed = false
                        onConfirmCloseFeed()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { workspaceModel.askingCloseFeed = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    if (workspaceModel.shortcutsOpen) {
        ShortcutsDialog(shortcutModifier, onDismiss = { workspaceModel.shortcutsOpen = false })
    }

    ClaimDialog(taskSlot)

    if (!desktop && workspaceModel.ongoingWorkShown) {
        OngoingWorkSheet(workspaceModel) { workspaceModel.ongoingWorkShown = false }
    }

    WorkspaceContextDialogs(workspaceModel)

    if (workspaceModel.paletteOpen) {
        val recent by fileWorkspace.recentFoldersFlow.collectAsStateWithLifecycle()
        val pinned by quickAccess.pinnedFolders.collectAsStateWithLifecycle(emptyList())
        val subfolders = folderStack.lastOrNull()
            ?.let { here ->
                services.driveRepository.cachedFiles(here.id, PikoFileSortOrder.NAME_ASC)
                    ?.filter { it.isFolder && !isPikoInternalFolder(it, here.id) }
            }
            .orEmpty()
        CommandPalette(
            items = buildMainPaletteItems(
                shortcutModifier = shortcutModifier,
                folderStack = folderStack,
                recent = recent,
                pinned = pinned,
                subfolders = subfolders,
                contributed = palette.items(),
                feedShown = feedShown,
                feedSuspended = feedSuspended,
                feedBlockedReason = feedBlockedReason,
                tabDestinations = tabDestinations(),
                pageDestinations = pageDestinations(),
                onLocatePinned = { folder -> quickAccess.locate(folder, onGoToDrive) },
                onOpenFolderStack = onGoToDrive,
                onOpenAddLink = onOpenAddLink,
                onUndoLast = onUndoLast,
                onResumeFeed = onResumeFeedFromPalette,
                onToggleFeed = onToggleFeedFromPalette,
                onOpenShortcuts = { workspaceModel.shortcutsOpen = true },
                onSyncSettingsNow = onSyncSettingsNow,
                onSelectThemeMode = onSetThemeMode,
            ),
            onDismiss = { workspaceModel.paletteOpen = false },
        )
    }

    if (workspaceModel.pathLookup != null) {
        LocatingOverlay(onCancel = workspaceModel::cancelPathLookup)
    }
}

private fun buildMainPaletteItems(
    shortcutModifier: ShortcutModifier,
    folderStack: List<PikoPathBreadcrumb>,
    recent: List<List<PikoPathBreadcrumb>>,
    pinned: List<PikoPathBreadcrumb>,
    subfolders: List<FileStat>,
    contributed: List<PaletteItem>,
    feedShown: Boolean,
    feedSuspended: Boolean,
    feedBlockedReason: String?,
    tabDestinations: List<PaletteItem>,
    pageDestinations: List<PaletteItem>,
    onLocatePinned: (PikoPathBreadcrumb) -> Unit,
    onOpenFolderStack: (List<PikoPathBreadcrumb>) -> Unit,
    onOpenAddLink: () -> Unit,
    onUndoLast: () -> Unit,
    onResumeFeed: () -> Unit,
    onToggleFeed: () -> Unit,
    onOpenShortcuts: () -> Unit,
    onSyncSettingsNow: () -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
): List<PaletteItem> = buildList {
    val here = folderStack.lastOrNull()?.id
    fun path(stack: List<PikoPathBreadcrumb>) = stack.joinToString(" › ") { it.localizedName }
    recent.filter { it.last().id != here }.forEach { stack ->
        add(PaletteItem(stack.last().localizedName, Icons.Outlined.History, tr("最近", "Recent", "最近", "최근", "最近"), detail = path(stack)) { onOpenFolderStack(stack) })
    }
    val label = shortcutModifier::label
    addAll(tabDestinations)
    pinned.forEach { folder ->
        add(PaletteItem(folder.localizedName, Icons.Outlined.PushPin, tr("快速访问", "Quick access", "クイックアクセス", "빠른 액세스", "快速存取")) { onLocatePinned(folder) })
    }
    folderStack.takeIf { it.isNotEmpty() }?.let { stack ->
        subfolders.forEach { folder ->
            val target = stack + PikoPathBreadcrumb(folder.id, folder.name)
            add(PaletteItem(folder.name, Icons.Outlined.Folder, tr("当前文件夹", "Current folder", "現在のフォルダー", "현재 폴더", "目前資料夾"), detail = path(target)) { onOpenFolderStack(target) })
        }
    }
    addAll(contributed)
    add(PaletteItem(tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"), Icons.Outlined.Bolt, tr("操作", "Actions", "操作", "작업", "操作"), keywords = "magnet link offline 磁力 离线", run = onOpenAddLink))
    add(PaletteItem(tr("撤销", "Undo", "元に戻す", "실행 취소", "復原"), Icons.AutoMirrored.Outlined.Undo, tr("操作", "Actions", "操作", "작업", "操作"), detail = label("Z"), keywords = "undo", run = onUndoLast))
    if (feedSuspended) {
        add(PaletteItem(tr("继续浏览信息流", "Resume feed", "フィードを再開", "피드 계속 보기", "繼續瀏覽資訊流"), Icons.Outlined.SwipeVertical, tr("操作", "Actions", "操作", "작업", "操作"), keywords = "feed clips resume", disabledReason = feedBlockedReason, run = onResumeFeed))
    }
    if (feedShown || isDriveFolderId(folderStack.lastOrNull()?.id.orEmpty())) {
        add(
            PaletteItem(
                if (feedShown) tr("关闭信息流", "Close feed", "フィードを閉じる", "피드 닫기", "關閉資訊流") else tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"),
                Icons.Outlined.SwipeVertical,
                tr("操作", "Actions", "操作", "작업", "操作"),
                keywords = "feed clips",
                disabledReason = feedBlockedReason.takeIf { !feedShown },
                run = onToggleFeed,
            ),
        )
    }
    add(PaletteItem(tr("快捷键一览", "Keyboard shortcuts", "キーボードショートカット", "키보드 단축키", "鍵盤快速鍵一覽"), Icons.Outlined.Keyboard, tr("操作", "Actions", "操作", "작업", "操作"), detail = "F1", keywords = "shortcuts keyboard help 帮助", run = onOpenShortcuts))
    add(PaletteItem(tr("立即同步设置", "Sync settings now", "設定を今すぐ同期", "지금 설정 동기화", "立即同步設定"), Icons.Outlined.CloudSync, tr("操作", "Actions", "操作", "작업", "操作"), keywords = "sync settings", run = onSyncSettingsNow))
    ThemeMode.entries.forEach { mode ->
        add(PaletteItem(tr("主题：${mode.label}", "Theme: ${mode.label}", "テーマ：${mode.label}", "테마: ${mode.label}", "主題：${mode.label}"), Icons.Outlined.Palette, tr("操作", "Actions", "操作", "작업", "操作"), keywords = "theme ${mode.name.lowercase()}") {
            onSelectThemeMode(mode)
        })
    }
    addAll(pageDestinations)
}

internal fun MainTab.icon(selected: Boolean) = when (this) {
    MainTab.FILES -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    MainTab.TRANSFERS -> if (selected) Icons.Filled.SwapVerticalCircle else Icons.Outlined.SwapVerticalCircle
    MainTab.SETTINGS -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}

private val SidebarFadeLength = 24.dp

private fun Modifier.scrollFadeEdges(state: ScrollState, length: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = length.toPx().coerceAtMost(size.height / 2)
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun MainSidebar(
    width: Dp,
    currentTab: MainTab,
    onTabClick: (MainTab) -> Unit,
    quickAccess: QuickAccessState,
    pinned: List<PikoPathBreadcrumb>,
    folderStack: List<PikoPathBreadcrumb>,
    onOpenQuickAccess: (List<PikoPathBreadcrumb>) -> Unit,
    selectedPage: Screen?,
    onOpenPage: (Screen) -> Unit,
    onToggleLibrary: (DriveLibrary) -> Unit,
    collapsed: Boolean,
    onToggleCollapsed: (() -> Unit)?,
    scrollState: ScrollState = rememberScrollState(),
) {
    CompositionLocalProvider(LocalSidebarCollapsed provides collapsed) {
        Column(modifier = Modifier.width(width).fillMaxHeight()) {
            if (onToggleCollapsed != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                ) {
                    if (!collapsed) {
                        PikoBrand(Modifier.weight(1f).fillMaxHeight().windowDragArea().padding(start = 24.dp))
                    }
                    TooltipIconButton(
                        icon = if (collapsed) Icons.Outlined.Menu else Icons.AutoMirrored.Outlined.MenuOpen,
                        label = if (collapsed) tr("展开侧边栏", "Expand sidebar", "サイドバーを展開", "사이드바 펼치기", "展開側邊欄") else tr("收起侧边栏", "Collapse sidebar", "サイドバーを折りたたむ", "사이드바 접기", "收合側邊欄"),
                        onClick = onToggleCollapsed,
                        shortcut = LocalPikoPlatform.current.shortcutModifier.label("B"),
                        modifier = if (collapsed) Modifier else Modifier.padding(end = 8.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .scrollFadeEdges(scrollState, SidebarFadeLength)
                    .verticalScroll(scrollState)
                    .padding(start = if (collapsed) 0.dp else 12.dp, end = if (collapsed) 0.dp else 12.dp, bottom = 16.dp),
            ) {
                MainTabSwitch(
                    currentTab = currentTab.takeIf { selectedPage == null },
                    onTabClick = onTabClick,
                    activity = rememberTransferActivity(),
                    collapsed = collapsed,
                )
                QuickAccessSection(
                    state = quickAccess,
                    pinned = pinned,
                    currentStack = folderStack,
                    onFilesTab = selectedPage == null && currentTab == MainTab.FILES,
                    onOpen = onOpenQuickAccess,
                )
                SectionLabel(tr("库", "Library", "ライブラリ", "라이브러리", "資料庫"))
                for (entry in LibraryEntries) {
                    val library = entry.library
                    val targetScreen = entry.screen ?: library?.let { Screen.Library(it.id) }
                    SidebarItem(
                        icon = entry.icon,
                        selectedIcon = entry.selectedIcon,
                        label = entry.label,
                        selected = selectedPage == targetScreen,
                        onClick = { if (library != null) onToggleLibrary(library) else entry.screen?.let(onOpenPage) },
                    )
                }
            }
            SidebarAccountRow(selected = selectedPage == Screen.Settings, onOpenSettings = { onOpenPage(Screen.Settings) }, collapsed = collapsed)
        }
    }
}

@Composable
private fun MainTabSwitch(currentTab: MainTab?, onTabClick: (MainTab) -> Unit, activity: TransferActivity, collapsed: Boolean) {
    val tabs = listOf(MainTab.FILES, MainTab.TRANSFERS)
    val snail by LocalPikoServices.current.preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
    if (collapsed) {
        tabs.forEach { tab ->
            val selected = tab == currentTab
            val snailed = tab == MainTab.TRANSFERS && snail.enabled
            val isTransfers = tab == MainTab.TRANSFERS
            val showSpeedOnRail = isTransfers && activity.maxSpeed > 0L
            SidebarItem(
                icon = tab.icon(false),
                selectedIcon = tab.icon(true),
                label = if (snailed) tr("任务（蜗牛模式）", "Tasks (snail mode)", "タスク（低速モード）", "작업 (저속 모드)", "任務（蝸牛模式）") else tab.title,
                selected = selected,
                onClick = { onTabClick(tab) },
                trailing = if (showSpeedOnRail) {
                    { SidebarTransferReadout(activity, checked = selected, railCompact = true) }
                } else null,
                accent = snailed,
                shortcut = shortcutModifier.label(if (tab == MainTab.FILES) "1" else "2"),
                tooltipDetail = if (isTransfers && activity.hasReadout) activity.summaryText() else null,
                badgeCount = if (isTransfers && !showSpeedOnRail) activity.attentionCount else 0,
                badgeDot = isTransfers && !showSpeedOnRail && activity.attentionCount == 0 && activity.count > 0,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            TonalToggleButton(
                checked = selected,
                onCheckedChange = { onTabClick(tab) },
                shapes = connectedToggleShapes(index, tabs.size),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                val snailed = tab == MainTab.TRANSFERS && snail.enabled && !selected
                CompositionLocalProvider(LocalContentColor provides if (snailed) MaterialTheme.colorScheme.tertiary else LocalContentColor.current) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tab.icon(selected), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tab.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        if (tab == MainTab.TRANSFERS && activity.hasReadout) SidebarTransferReadout(activity, checked = selected, compact = true)
                    }
                }
            }
        }
    }
}

private class LibraryEntry(
    private val labelText: () -> String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val library: DriveLibrary? = null,
    val screen: Screen? = null,
) {
    val label: String get() = labelText()
}

private val LibraryEntries = listOf(
    LibraryEntry(DriveLibrary.RECENT),
    LibraryEntry(DriveLibrary.STARRED),
    LibraryEntry(DriveLibrary.HISTORY),
    LibraryEntry({ tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享") }, Icons.Outlined.FolderShared, Icons.Filled.FolderShared, screen = Screen.MyShares),
    LibraryEntry({ tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈") }, Icons.Outlined.PieChart, Icons.Filled.PieChart, screen = Screen.SpaceMap()),
    LibraryEntry(DriveLibrary.TRASH),
)

private fun LibraryEntry(library: DriveLibrary) = LibraryEntry({ library.title }, library.icon, library.selectedIcon, library = library)
