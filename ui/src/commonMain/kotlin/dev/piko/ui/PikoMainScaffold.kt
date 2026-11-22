package dev.piko.ui

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.FileLocation
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.QuickAccessState
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.components.FileDragState
import dev.piko.ui.components.FocusFallback
import dev.piko.ui.components.LocalFileDrag
import dev.piko.ui.components.LocalFocusFallback
import dev.piko.ui.components.LocalPaletteRegistry
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.LocalShowExtensions
import dev.piko.ui.components.PaletteRegistry
import dev.piko.ui.components.SidePanelLayout
import dev.piko.ui.components.fileDragHost
import dev.piko.ui.components.focusFallbackRoot
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.components.sidePanelFits
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.navigation.isWorkspaceSurface
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.LocalWindowResizing
import dev.piko.ui.platform.ModalCaptionBlocker
import dev.piko.ui.screens.clips.FeedResumeBar
import dev.piko.ui.screens.drive.ImageViewer
import dev.piko.ui.screens.drive.LocalDriveAppearancePrefs
import dev.piko.ui.screens.drive.LocalSidebarTooltipsEnabled
import dev.piko.ui.screens.drive.SidebarRailWidth
import dev.piko.ui.screens.drive.SidebarWidth
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.instant.CollapseAddLinkLabel
import dev.piko.ui.screens.instant.DiscardAddLinkButton
import dev.piko.ui.screens.instant.DockedAddLink
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.reader.DocumentReaderScreen
import dev.piko.ui.screens.reader.HtmlViewerScreen
import dev.piko.ui.screens.settings.DriveAppearanceCustomizationPage
import dev.piko.ui.screens.settings.PlayerCustomizationPage
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.settings.WebDavScreen
import dev.piko.ui.screens.share.MySharesScreen
import dev.piko.ui.screens.space.SpaceMapScreen
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.frame
import dev.piko.ui.theme.islandBorderColor
import dev.piko.ui.theme.islandSurface
import dev.piko.ui.workbench.AccountSettings
import dev.piko.ui.workbench.LocalFileWorkspace
import dev.piko.ui.workbench.LocalShowOngoingWork
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.LocalWorkspaceContexts
import dev.piko.ui.workbench.LocalWorkspaceSnapshotEnabled
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.WorkspaceViewModel
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/** 一次播放请求。[playlist] 是同目录可播的视频，桌面播放器用它做选集；来自传输页时为空。 */
class VideoPlayerRequest(
    val fileId: String,
    val fileName: String,
    val localPath: String?,
    val playlist: List<FileStat> = emptyList(),
    val startMillis: Long? = null,
)

/**
 * 播放器宿主策略：Android 在应用内压栈全屏页，桌面端开独立窗口。
 */
sealed interface VideoPlayerHost {
    class InApp(
        val content: @Composable (request: Screen.VideoPlayer, onClose: () -> Unit) -> Unit,
    ) : VideoPlayerHost

    class Detached(
        val open: (VideoPlayerRequest) -> Unit,
        val openClipFeed: (ClipFeedLinks) -> Unit,
        val isClipFeedOpen: () -> Boolean,
        val closeClipFeed: () -> Unit,
        val hasActivePlayer: () -> Boolean = { false },
        val openImage: ((ImageViewerRequest) -> Unit)? = null,
    ) : VideoPlayerHost
}

/** 独立窗口里的信息流与主界面协同回调。 */
class ClipFeedLinks(
    val playFull: (file: FileStat, startMillis: Long) -> Unit,
    val locate: (FileStat) -> Unit,
    val dock: () -> Unit,
    val close: () -> Unit,
)

/**
 * 应用顶层主界面：连接账号级 `WorkspaceViewModel` 与 Navigation 3 返回栈，
 * 将顶层侧栏、主工作区、信息流右栏/全屏及低频全局弹窗分发至顶层无状态可跳过组件。
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PikoMainScaffold(
    account: String,
    onLogout: () -> Unit,
    videoPlayer: VideoPlayerHost,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    if (services.driveRepository.currentAccount != account) return
    val coroutineScope = rememberCoroutineScope()
    val desktop = isDesktopLayout()
    val latestDesktop by rememberUpdatedState(desktop)
    val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
    val presentationOwner = remember(services, account, services.presentationStores.generation) {
        services.presentationStores.forAccount(account, activeAccount = services.driveRepository.currentAccount)
    }
    val workspaceModel = viewModel<WorkspaceViewModel>(
        viewModelStoreOwner = presentationOwner,
        key = "workspace",
    ) { WorkspaceViewModel(services, account) }
    val fileWorkspace = workspaceModel.fileWorkspace
    val driveWorkspace = workspaceModel.driveWorkspace

    var currentTab by rememberSaveable { mutableStateOf(workspaceModel.lastMainTab) }
    var libraryResetRequests by remember { mutableIntStateOf(0) }
    var spaceMapResetRequests by remember { mutableIntStateOf(0) }
    var filesScrollToTop by remember { mutableIntStateOf(0) }
    var transfersScrollToTop by remember { mutableIntStateOf(0) }
    val taskSlot = remember { TaskSlot() }

    val nav = rememberMainBackStackCoordinator(
        account = account,
        services = services,
        workspaceModel = workspaceModel,
        videoPlayer = videoPlayer,
        isDesktopProvider = { latestDesktop },
        currentTabProvider = { currentTab },
        onSetCurrentTab = { currentTab = it },
        onIncrementLibraryReset = { libraryResetRequests++ },
        onIncrementSpaceMapReset = { spaceMapResetRequests++ },
    )
    val backStack = nav.backStack
    if (services.driveRepository.currentAccount != account) return

    val workspaceUiStates = rememberSaveableStateHolder()
    val workspaceContexts by workspaceModel.contexts.contextsFlow.collectAsStateWithLifecycle()
    val retainedWorkIds = remember { mutableSetOf<Long>() }
    LaunchedEffect(workspaceContexts.map { it.id }) {
        val live = workspaceContexts.mapTo(mutableSetOf()) { it.id }
        (retainedWorkIds - live).forEach(workspaceUiStates::removeState)
        retainedWorkIds.clear()
        retainedWorkIds.addAll(live)
    }
    LaunchedEffect(workspaceModel.activationRevision) {
        if (workspaceModel.consumeContextActivation()) {
            currentTab = MainTab.FILES
            nav.resetToHome()
        }
    }
    LaunchedEffect(desktop) {
        if (desktop) {
            workspaceModel.ongoingWorkShown = false
            if (currentTab == MainTab.SETTINGS) currentTab = MainTab.FILES
        }
    }
    LaunchedEffect(workspaceModel.returnedOrigin) {
        workspaceModel.consumeReturnedOrigin()?.let { origin ->
            currentTab = if (desktop && origin.tab == MainTab.SETTINGS) MainTab.FILES else origin.tab
            nav.resetToHome()
            origin.screens.drop(1).forEach(backStack::add)
        }
    }

    val activeFileTab by driveWorkspace.activeTabId.collectAsStateWithLifecycle()
    val addLink = services.addLink
    val liveFileTabIds: Set<Long> = run {
        val tabs by driveWorkspace.tabsFlow.collectAsStateWithLifecycle()
        val ids = remember(tabs) { tabs.mapTo(HashSet()) { it.id } }
        SideEffect { addLink.rebindTabIfMissing(ids, activeFileTab) }
        LaunchedEffect(tabs.map { it.id }) {
            addLink.rebindTabIfMissing(ids, driveWorkspace.activeTabId.value)
            workspaceModel.tabs.retainTabs(ids)
        }
        ids
    }
    val fileBrowser = workspaceModel.mainBrowser(activeFileTab)
    SideEffect { workspaceModel.rememberNavigation(backStack.filterIsInstance<Screen>(), currentTab) }

    val topScreen = backStack.lastOrNull() as? Screen
    val workspaceSurface = isWorkspaceSurface(topScreen, currentTab)
    LaunchedEffect(workspaceSurface) { if (!workspaceSurface) workspaceModel.ongoingWorkShown = false }
    val onHome = backStack.size <= 1

    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val heightCompact = isHeightCompact()
    val sidebarWindow = windowWidth >= 600.dp && !heightCompact

    val openDriveRequested by services.openDriveRequested.collectAsStateWithLifecycle()
    val fileHighlightRequested by services.fileHighlightRequested.collectAsStateWithLifecycle()
    val openTransfersRequested by services.openTransfersRequested.collectAsStateWithLifecycle()
    LaunchedEffect(fileHighlightRequested) {
        val request = fileHighlightRequested ?: return@LaunchedEffect
        if (request.account != account || services.driveRepository.currentAccount != account) return@LaunchedEffect
        fileWorkspace.requestHighlight(request.ids)
        services.consumeFileHighlight(request)
    }
    LaunchedEffect(openDriveRequested) {
        if (openDriveRequested) {
            workspaceModel.activateFileBrowser()
            currentTab = MainTab.FILES
            nav.resetToHome()
            services.consumeOpenDriveRequest()
        }
    }
    LaunchedEffect(openTransfersRequested) {
        if (openTransfersRequested) {
            nav.openTransfers()
            services.consumeOpenTransfersRequest()
        }
    }

    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val resizing = LocalWindowResizing.current
    val latestResizing by rememberUpdatedState(resizing)
    var pendingContentSize by remember { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(resizing) {
        if (!resizing) pendingContentSize?.let { contentSize = it }
        pendingContentSize = null
    }

    val clipFeedSession = services.clipFeedSession
    val preferences = services.preferences
    val initialPrefs = remember(preferences) { preferences.currentSnapshot }
    val sidebarCollapsed by preferences.sidebarCollapsedFlow.collectAsStateWithLifecycle(initialPrefs.sidebarCollapsed)
    val showExtensions by preferences.showExtensionsFlow.collectAsStateWithLifecycle(initialPrefs.showExtensions)
    val loadedPanelPrefs by preferences.clipPanelFlow.collectAsStateWithLifecycle(initialValue = null)
    val panelPrefs = loadedPanelPrefs ?: SidePanelPrefs(open = false, widthDp = null)

    val contentWidth = with(density) { contentSize.width.toDp() }.takeIf { contentSize != IntSize.Zero }
    val sidebarMode = sidebarWindow && (onHome || isProfilePane(topScreen) || topScreen in SettingsSubpages || isReaderScreen(topScreen))
    val sidebarPushes = windowWidth >= SidebarPushMinWindowWidth
    val inlineCollapsed = inlineSidebarIsCollapsed(windowWidth.value, sidebarCollapsed)
    val animatedSidebarWidth by animateDpAsState(
        if (inlineCollapsed) SidebarRailWidth else SidebarWidth,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "sidebarWidth",
    )
    val inlineSidebarWidth = if (sidebarPushes) animatedSidebarWidth else SidebarRailWidth
    var drawerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarPushes, sidebarMode) {
        if (sidebarPushes || !sidebarMode) drawerOpen = false
    }
    LaunchedEffect(currentTab, topScreen) { drawerOpen = false }
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val horizontalInsets = with(density) {
        safeDrawing.getLeft(this, layoutDirection).toDp() + safeDrawing.getRight(this, layoutDirection).toDp()
    }
    val panelAvailableWidth = if (sidebarMode) {
        windowWidth - horizontalInsets - inlineSidebarWidth - IslandGap * 2
    } else contentWidth
    val panelFits = contentWidth != null && panelAvailableWidth != null && !heightCompact &&
        sidePanelFits(panelAvailableWidth, ClipPanelMinWidth)

    var feedShownState by workspaceModel.feed::shown
    LaunchedEffect(contentWidth != null, loadedPanelPrefs != null) {
        val prefs = loadedPanelPrefs
        if (feedShownState == null && contentWidth != null && prefs != null) {
            feedShownState = prefs.open && panelFits &&
                clipFeedSession.reopensFor(services.clientManager.currentClient.value?.account)
        }
    }
    val feedShown = feedShownState == true
    val detachedHost = videoPlayer as? VideoPlayerHost.Detached
    val feedPoppedOut = detachedHost?.isClipFeedOpen?.invoke() == true
    val hasDetachedPlayer = detachedHost?.hasActivePlayer?.invoke() == true
    var feedDetour by workspaceModel.feed::returnLocation
    val feedSuspended = feedShown && feedDetour != null
    val feedPausedByHost = feedSuspended || hasDetachedPlayer
    var feedDetourTab by workspaceModel.feed::detourTab
    val feedOpened by workspaceModel.feed::opened

    fun setFeedShown(shown: Boolean, selected: List<FileStat>? = null) {
        feedShownState = shown
        if (shown) workspaceModel.feed.prepare(snapshotFlow { fileBrowser.initialized }, selected)
        if (!shown) {
            if (feedPoppedOut) detachedHost?.closeClipFeed?.invoke()
            workspaceModel.feed.close()
        }
        coroutineScope.launch { preferences.setClipPanelOpen(shown) }
    }
    val skipFeedExitConfirm by preferences.skipFeedExitConfirmFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.skipFeedExitConfirm,
    )
    fun requestCloseFeed() {
        if (feedShown && !skipFeedExitConfirm) workspaceModel.askingCloseFeed = true else setFeedShown(false)
    }

    val addLinkDocked = panelFits && addLink.state != null && addLink.isExpanded &&
        (addLink.ownerTabId == null || addLink.ownerTabId == activeFileTab ||
            (liveFileTabIds.isNotEmpty() && addLink.ownerTabId !in liveFileTabIds))
    val feedOnFilesTab = feedShown && !feedSuspended && !feedPoppedOut && currentTab == MainTab.FILES
    val feedBlockedReason: String? = null
    val feedInPanel = feedOnFilesTab && panelFits && !workspaceModel.feed.fullscreen && !addLinkDocked
    val feedFullScreen = feedOnFilesTab && (!panelFits || workspaceModel.feed.fullscreen)
    fun toggleSidebar() {
        if (sidebarPushes) coroutineScope.launch { preferences.setSidebarCollapsed(!sidebarCollapsed) }
        else drawerOpen = !drawerOpen
    }

    val folderStack by fileWorkspace.folderStackFlow.collectAsStateWithLifecycle()
    val quickAccess = remember(fileWorkspace) {
        QuickAccessState(services.driveRepository, services.clientManager, coroutineScope) { stack ->
            driveWorkspace.openTab(stack, activate = false)
        }
    }
    LaunchedEffect(feedShown, fileBrowser) {
        if (feedShown) workspaceModel.feed.prepare(snapshotFlow { fileBrowser.initialized })
    }
    var feedHome by workspaceModel.feed::home

    fun suspendFeed(from: FileLocation) {
        if (!feedShown || !feedOpened || feedDetour != null || feedPoppedOut) return
        feedDetour = from
    }
    LaunchedEffect(folderStack, feedOpened) {
        val feedRoot = clipFeedSession.root ?: return@LaunchedEffect
        if (!feedShown || feedDetour != null) return@LaunchedEffect
        if (folderStack.any { it.id == feedRoot.id }) feedHome = fileWorkspace.currentLocation()
        else suspendFeed(feedHome ?: fileWorkspace.currentLocation())
    }

    fun playFromFeed(file: FileStat, startMillis: Long) = nav.playVideo(file, listOf(file), startMillis)
    fun locateFromFeed(file: FileStat) {
        if (services.driveRepository.currentAccount != account) return
        val origin = fileWorkspace.currentLocation()
        coroutineScope.launch {
            val stack = clipFeedSession.pathTo(file.parentId)
                ?: workspaceModel.awaitPathLookup(file.id)?.getOrNull()
                ?: return@launch
            if (services.driveRepository.currentAccount != account) return@launch
            suspendFeed(origin)
            val detourTab = feedDetourTab?.takeIf { id -> driveWorkspace.tabsFlow.value.any { it.id == id } }
            when {
                detourTab != null -> driveWorkspace.switchTab(detourTab)
                feedDetour != null -> feedDetourTab = driveWorkspace.openTab(stack)
            }
            nav.showInDrive(stack, file.id, activatePrimary = false)
        }
    }
    fun popOutFeed() {
        detachedHost?.openClipFeed(
            ClipFeedLinks(
                playFull = ::playFromFeed,
                locate = ::locateFromFeed,
                dock = { detachedHost.closeClipFeed() },
                close = ::requestCloseFeed,
            ),
        )
    }
    fun resumeFeed() {
        val detour = workspaceModel.feed.resume() ?: return
        fileWorkspace.returnTo(detour)
        nav.resetToHome()
        currentTab = MainTab.FILES
        if (feedPoppedOut) popOutFeed()
    }

    val canLeaveOrigin = workspaceModel.canLeaveFileOrigin(folderStack) ||
        (feedSuspended && !desktop && folderStack.size <= 1)

    fun leaveLibrary() {
        if (workspaceModel.canLeaveFileOrigin(folderStack) && nav.leaveFileEntry()) return
        if (feedSuspended && !desktop) {
            resumeFeed()
            return
        }
        if (!nav.leaveFileEntry() && !driveWorkspace.goBack()) {
            driveWorkspace.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        }
    }

    fun openAddLinkWithConfirm(initialLink: String = "") {
        val replacing = initialLink.isNotBlank() && addLink.state?.isBlank == false && addLink.state?.input != initialLink
        taskSlot.claim(
            action = if (replacing) tr("打开新链接", "Open new link", "新しいリンクを開く", "새 링크 열기", "開啟新連結") else tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            confirmLabel = if (replacing) tr("打开新链接", "Open new link", "新しいリンクを開く", "새 링크 열기", "開啟新連結") else tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            task = TaskSlot.Task.ADD_LINK,
            replacesSame = replacing,
            conflictsWith = setOf(TaskSlot.Task.ADD_LINK, TaskSlot.Task.FEED),
        ) {
            currentTab = MainTab.FILES
            nav.resetToHome()
            val validOwnerTabId = addLink.ownerTabId?.takeIf { liveFileTabIds.isEmpty() || it in liveFileTabIds }
            if (addLink.state != null && validOwnerTabId != null) workspaceModel.activateContext(validOwnerTabId)
            else workspaceModel.activateFileBrowser()
            val targetTabId = driveWorkspace.activeTabId.value
            if (initialLink.isNotBlank()) addLink.open(initialLink, tabId = targetTabId) else addLink.resume(tabId = targetTabId)
        }
    }

    val pendingLink by services.instantMagnetRepository.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        pendingLink?.takeIf { it.isNotBlank() }?.let { link ->
            services.instantMagnetRepository.clearPendingMagnet()
            ClipboardLinkDetection.session.handled(link)
            openAddLinkWithConfirm(link)
        }
    }

    SideEffect {
        taskSlot.exclusive = true
        taskSlot.occupants = listOf(
            TaskSlot.Occupant(
                task = TaskSlot.Task.FEED,
                name = tr("信息流", "Feed", "フィード", "피드", "資訊流"),
                loss = tr("当前播放队列与浏览进度将清空。", "The current queue and browsing progress will be cleared.", "現在の再生キューと閲覧位置を消去します。", "현재 재생 대기열과 탐색 진행 상황이 지워집니다.", "目前播放佇列與瀏覽進度將會清空。"),
                active = { feedShown },
                end = { setFeedShown(false) },
            ),
            TaskSlot.Occupant(
                task = TaskSlot.Task.ADD_LINK,
                name = tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
                loss = tr("当前输入的链接和勾选的文件不会保存。", "Entered links and selected files will not be saved.", "入力したリンクと選択したファイルは保存されません。", "입력한 링크와 선택한 파일은 저장되지 않습니다.", "目前輸入的連結和勾選的檔案不會儲存。"),
                active = { addLink.state != null },
                end = { addLink.discard() },
                replacementAvailability = { addLink.replacementAvailability },
            ),
        )
    }

    val columnContent = when {
        addLinkDocked -> SideColumnContent.AddLink
        feedInPanel -> SideColumnContent.Feed
        else -> null
    }
    var lastColumnContent by remember { mutableStateOf(SideColumnContent.Feed) }
    SideEffect { if (columnContent != null) lastColumnContent = columnContent }
    val columnShows = columnContent ?: lastColumnContent

    val feedFrame: @Composable (@Composable () -> Unit) -> Unit = { drive ->
        val showAddLink = columnShows == SideColumnContent.AddLink
        val discardAddLink = rememberDiscardAddLink(addLink)
        SidePanelLayout(
            open = columnContent != null,
            savedWidthDp = panelPrefs.widthDp,
            title = if (showAddLink) tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結") else tr("信息流", "Feed", "フィード", "피드", "資訊流"),
            closeDescription = if (showAddLink) CollapseAddLinkLabel else tr("关闭信息流", "Close feed", "フィードを閉じる", "피드 닫기", "關閉資訊流"),
            onClose = if (showAddLink) ({ addLink.collapse() }) else ::requestCloseFeed,
            endActions = {
                val addLinkState = addLink.state
                if (showAddLink && addLinkState != null) DiscardAddLinkButton(addLinkState, discardAddLink)
            },
            onWidthChange = { coroutineScope.launch { preferences.setClipPanelWidth(it) } },
            defaultWidth = ClipPanelDefaultWidth,
            minWidth = ClipPanelMinWidth,
            ready = feedShownState != null,
            showHeader = showAddLink,
            bottomMargin = 0.dp,
            main = {
                Box(Modifier.fillMaxSize()) {
                    drive()
                    FeedResumeBar(
                        visible = feedSuspended && !desktop && !fileBrowser.state.isSelectionMode && (addLink.state == null || !addLink.isExpanded),
                        folderName = clipFeedSession.root?.localizedName,
                        onResume = ::resumeFeed,
                        onClose = ::requestCloseFeed,
                        modifier = Modifier.align(Alignment.BottomStart).padding(end = FeedResumeBarFabWidth),
                    )
                }
            },
            panel = {
                when (columnShows) {
                    SideColumnContent.AddLink -> EscClosesWhenFocused(onClose = discardAddLink) {
                        addLink.state?.let { state ->
                            DockedAddLink(
                                state = state,
                                onTargetPicked = { path -> fileWorkspace.updateFolderStack(path) },
                                onPreview = { fileId, fileName -> nav.playVideo(FileStat(id = fileId, name = fileName), emptyList()) },
                            )
                        }
                    }
                    SideColumnContent.Feed -> MainFeedContent(
                        compact = true,
                        visible = feedInPanel,
                        feedOpened = feedOpened,
                        onHome = onHome,
                        feedSuspended = feedPausedByHost,
                        onRequestCloseFeed = ::requestCloseFeed,
                        onPlayFromFeed = ::playFromFeed,
                        onLocateFromFeed = ::locateFromFeed,
                        onToggleFullscreen = if (panelFits) ({ workspaceModel.feed.fullscreen = !workspaceModel.feed.fullscreen }) else null,
                        onPopOut = detachedHost?.let { ::popOutFeed },
                    )
                }
            },
        )
    }

    fun onTabClick(tab: MainTab) {
        if (tab == MainTab.FILES) workspaceModel.goToDrive()
        if (tab == currentTab) {
            when (tab) {
                MainTab.FILES -> filesScrollToTop++
                MainTab.TRANSFERS -> transfersScrollToTop++
                MainTab.SETTINGS -> Unit
            }
        }
        currentTab = tab
    }

    val shortcutFocus = remember { FocusRequester() }
    val pointerSource = LocalPointerSource.current
    LaunchedEffect(currentTab, onHome, pointerSource.isTouchLike) {
        if (!pointerSource.isTouchLike && (currentTab != MainTab.FILES || !onHome)) runCatching { shortcutFocus.requestFocus() }
    }

    val paneBack: (() -> Unit)? = if (sidebarWindow) null else nav::popBack
    val activePaneScreen = if (isReaderScreen(topScreen)) backStack.dropLast(1).lastOrNull() as? Screen else topScreen
    val selectedPane = when {
        activePaneScreen in SettingsSubpages -> Screen.Settings
        activePaneScreen is Screen.SpaceMap -> Screen.SpaceMap()
        isProfilePane(activePaneScreen) -> activePaneScreen
        else -> null
    }

    val fileDrag = remember { FileDragState() }
    val palette = remember { PaletteRegistry() }
    val focusFallback = remember { FocusFallback(shortcutFocus) }
    val rootSnackbar = remember { SnackbarHostState() }
    ObserveMessages(services.appNotices, rootSnackbar)

    MainOutcomeEffects(
        account = account,
        services = services,
        workspaceModel = workspaceModel,
        taskSlot = taskSlot,
        rootSnackbar = rootSnackbar,
        onTransfersTab = currentTab == MainTab.TRANSFERS,
        isViewingActiveImport = { currentTab == MainTab.FILES && backStack.size == 1 && workspaceModel.contexts.activeContextId.value in workspaceModel.importModels },
        isViewingDuplicateReport = { id -> currentTab == MainTab.FILES && backStack.size == 1 && workspaceModel.contexts.activeContextId.value == id },
        onOpenTransfers = nav::openTransfers,
        onOpenAddLink = ::openAddLinkWithConfirm,
        onRevealInstantSaved = { target, createdIds ->
            nav.resetToHome()
            currentTab = MainTab.FILES
            fileWorkspace.navigateToFolder(target)
            workspaceModel.activateFileBrowser()
            fileWorkspace.requestHighlight(createdIds)
        },
    )

    CompositionLocalProvider(
        LocalFileWorkspace provides fileWorkspace,
        LocalRootSnackbar provides rootSnackbar,
        LocalFileDrag provides fileDrag,
        LocalPaletteRegistry provides palette,
        LocalFocusFallback provides focusFallback,
        LocalShowExtensions provides showExtensions,
        LocalTaskSlot provides taskSlot,
        LocalWorkspaceContexts provides workspaceModel,
        LocalWorkspaceSnapshotEnabled provides !workspaceModel.ongoingWorkShown,
        LocalShowOngoingWork provides if (desktop || !workspaceSurface) null else ({ workspaceModel.ongoingWorkShown = true }),
        LocalDetachedImageViewer provides detachedHost?.openImage,
        LocalInAppImageViewer provides { images, initialIndex, onDismiss ->
            ImageViewer(images = images, initialIndex = initialIndex, onDismiss = onDismiss)
        },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .trackInputModality()
                .fileDragHost(fileDrag)
                .focusFallbackRoot(focusFallback)
                .focusRequester(shortcutFocus)
                .focusable(!pointerSource.isTouchLike)
                .mainShellShortcuts(
                    shortcutModifier = shortcutModifier,
                    workspaceModel = workspaceModel,
                    workspaceSurface = workspaceSurface,
                    desktop = desktop,
                    sidebarMode = sidebarMode,
                    drawerOpen = drawerOpen,
                    onDismissDrawer = { drawerOpen = false },
                    onToggleSidebar = ::toggleSidebar,
                    onOpenSettings = { nav.openPage(Screen.Settings) },
                    onSelectMainTab = { tab ->
                        currentTab = tab
                        nav.resetToHome()
                    },
                ),
        ) {
            val sidebarScrollState = rememberScrollState()
            val floatingDrawerTarget = drawerOpen && sidebarMode && !sidebarPushes
            val frameModifier = (if (sidebarMode) Modifier.background(MaterialTheme.colorScheme.frame) else Modifier)
                .then(if ((workspaceModel.ongoingWorkShown && !desktop) || floatingDrawerTarget) Modifier.clearAndSetSemantics { } else Modifier)
            if (sidebarMode) LocalWindowCaption.current?.Host()

            val playerLayoutPrefs by services.preferences.playerLayoutPrefsFlow.collectAsStateWithLifecycle(
                initialValue = services.preferences.currentSnapshot.playerLayout,
            )
            val driveAppearancePrefs by services.preferences.driveAppearancePrefsFlow.collectAsStateWithLifecycle(
                initialValue = services.preferences.currentSnapshot.driveAppearance,
            )

            CompositionLocalProvider(
                LocalFramed provides sidebarMode,
                LocalPlayerLayoutPrefs provides playerLayoutPrefs,
                LocalDriveAppearancePrefs provides driveAppearancePrefs,
            ) {
                Row(Modifier.fillMaxSize().then(frameModifier)) {
                    if (sidebarMode) {
                        CompositionLocalProvider(LocalSidebarTooltipsEnabled provides !floatingDrawerTarget) {
                            val sidebarColors = MaterialTheme.colorScheme
                            val sidebarBorder = sidebarColors.islandBorderColor
                            Box(
                                Modifier
                                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                                    .padding(start = IslandGap, top = IslandGap, bottom = IslandGap)
                                    .then(if (sidebarBorder != null) Modifier.border(1.dp, sidebarBorder, FrameCardShape) else Modifier)
                                    .clip(FrameCardShape)
                                    .background(sidebarColors.islandSurface),
                            ) {
                                MainSidebarSlot(
                                    width = inlineSidebarWidth,
                                    collapsed = inlineCollapsed,
                                    currentTab = currentTab,
                                    quickAccess = quickAccess,
                                    folderStack = folderStack,
                                    selectedPage = selectedPane,
                                    onTabClick = { tab ->
                                        nav.resetToHome()
                                        onTabClick(tab)
                                    },
                                    onOpenQuickAccess = nav::goToDrive,
                                    onOpenPage = nav::openPage,
                                    onToggleLibrary = nav::pushLibrary,
                                    onToggleCollapsed = ::toggleSidebar,
                                    scrollState = sidebarScrollState,
                                )
                            }
                        }
                        Spacer(Modifier.width(IslandGap))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val motion = LocalPikoMotion.current
                            val predictiveBack by preferences.predictiveBackFlow.collectAsStateWithLifecycle(
                                initialValue = preferences.currentSnapshot.predictiveBack,
                            )
                            val predictiveBackState = rememberUpdatedState(predictiveBack)
                            val popBackState = rememberUpdatedState(nav::popBack)
                            val predictiveBackToggleDecorator = remember(backStack) {
                                NavEntryDecorator<NavKey>(
                                    decorate = { entry ->
                                        BackHandler(enabled = !predictiveBackState.value && backStack.size > 1) {
                                            popBackState.value()
                                        }
                                        entry.Content()
                                    },
                                    onPop = {},
                                )
                            }
                            NavDisplay(
                                backStack = backStack,
                                entryDecorators = listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    predictiveBackToggleDecorator,
                                ),
                                onBack = nav::popBack,
                                transitionSpec = {
                                    if (sidebarMode && isDesktopTopLevelContentKey(initialState.entries.lastOrNull()?.contentKey) &&
                                        isDesktopTopLevelContentKey(targetState.entries.lastOrNull()?.contentKey)
                                    ) motion.topLevel() else motion.forward()
                                },
                                popTransitionSpec = {
                                    if (sidebarMode && isDesktopTopLevelContentKey(initialState.entries.lastOrNull()?.contentKey) &&
                                        isDesktopTopLevelContentKey(targetState.entries.lastOrNull()?.contentKey)
                                    ) motion.topLevel() else motion.backward()
                                },
                                predictivePopTransitionSpec = { _ ->
                                    if (sidebarMode && isDesktopTopLevelContentKey(initialState.entries.lastOrNull()?.contentKey) &&
                                        isDesktopTopLevelContentKey(targetState.entries.lastOrNull()?.contentKey)
                                    ) motion.topLevel() else motion.backward()
                                },
                                entryProvider = entryProvider {
                                    entry<Screen.Home> {
                                        MainHomeContent(
                                            workspaceModel = workspaceModel,
                                            workspaceUiStates = workspaceUiStates,
                                            fileBrowser = fileBrowser,
                                            currentTab = currentTab,
                                            sidebarMode = sidebarMode,
                                            desktop = desktop,
                                            heightCompact = heightCompact,
                                            onHome = onHome,
                                            panelFits = panelFits,
                                            feedShown = feedShown,
                                            feedOpened = feedOpened,
                                            feedSuspended = feedSuspended,
                                            feedPausedByHost = feedPausedByHost,
                                            feedFullScreen = feedFullScreen,
                                            feedBlockedReason = feedBlockedReason,
                                            feedDetourTab = feedDetourTab,
                                            canLeaveOrigin = canLeaveOrigin,
                                            filesScrollToTop = filesScrollToTop,
                                            transfersScrollToTop = transfersScrollToTop,
                                            addressDestinations = nav.tabDestinations().drop(1) + nav.pageDestinations(),
                                            feedFrame = feedFrame,
                                            onSelectMainTab = { currentTab = it },
                                            onTabClick = ::onTabClick,
                                            onMeasuredContentSize = { if (latestResizing) pendingContentSize = it else contentSize = it },
                                            onPlayVideo = { file, playlist -> nav.playVideo(file, playlist) },
                                            onOpenDocument = nav::openDocument,
                                            onPlayLocal = nav::playLocal,
                                            onRevealById = { id -> coroutineScope.launch { nav.revealInDrive(id) } },
                                            onOpenCloudFile = { fileId, _ -> nav.revealInDrive(fileId) },
                                            onOpenTransfers = nav::openTransfers,
                                            onOpenDuplicateReport = nav::openDuplicateReport,
                                            onOpenSpaceMap = nav::openSpaceMapFromDrive,
                                            onLeaveLibrary = ::leaveLibrary,
                                            onFeedShownChange = { shown ->
                                                when {
                                                    shown && feedSuspended -> taskSlot.claim(tr("继续浏览信息流", "Resume feed", "フィードを再開", "피드 계속 보기", "繼續瀏覽資訊流"), tr("继续浏览", "Resume", "再開", "계속 보기", "繼續瀏覽"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) { resumeFeed() }
                                                    shown -> taskSlot.claim(tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) { setFeedShown(true) }
                                                    else -> requestCloseFeed()
                                                }
                                            },
                                            onOpenSelectedFeed = { selected ->
                                                taskSlot.claim(tr("在信息流中浏览所选视频", "Browse selected videos in feed", "選択した動画をフィードで表示", "선택한 동영상을 피드에서 보기", "在資訊流中瀏覽所選影片"), tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) {
                                                    setFeedShown(true, selected.toList())
                                                }
                                            },
                                            onResumeFeed = {
                                                taskSlot.claim(tr("继续浏览信息流", "Resume feed", "フィードを再開", "피드 계속 보기", "繼續瀏覽資訊流"), tr("继续浏览", "Resume", "再開", "계속 보기", "繼續瀏覽"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) {
                                                    resumeFeed()
                                                }
                                            },
                                            onRequestCloseFeed = ::requestCloseFeed,
                                            onPlayFromFeed = ::playFromFeed,
                                            onLocateFromFeed = ::locateFromFeed,
                                            onToggleFeedFullscreen = if (panelFits) ({ workspaceModel.feed.fullscreen = !workspaceModel.feed.fullscreen }) else null,
                                            onPopOutFeed = detachedHost?.let { ::popOutFeed },
                                            onCreateImportFromTransfers = {
                                                ClipboardLinkDetection.session.handled(it)
                                                openAddLinkWithConfirm(it)
                                            },
                                            onLogout = onLogout,
                                            onOpenProfilePane = nav::openProfilePane,
                                            onOpenLibraryFromProfile = nav::pushLibrary,
                                        )
                                    }
                                    entry<Screen.Library> { screen ->
                                        val library = remember(screen.libraryId) { DriveLibrary.of(screen.libraryId) ?: DriveLibrary.TRASH }
                                        val session = remember(workspaceModel, library) { workspaceModel.createLibraryBrowser(library) }
                                        DisposableEffect(session) {
                                            onDispose {
                                                workspaceModel.detailPanels.forget(session.navigation)
                                                session.close()
                                            }
                                        }
                                        LaunchedEffect(libraryResetRequests) {
                                            if (session.navigation.folderStackFlow.value.size > 1) {
                                                session.navigation.updateFolderStack(listOf(library.crumb))
                                            }
                                        }
                                        FilesScreen(
                                            onNavigateToVideoPlayer = { file, playlist -> nav.playVideo(file, playlist) },
                                            onOpenDocument = nav::openDocument,
                                            onOpenTransfers = nav::openTransfers,
                                            onOpenDuplicateReport = nav::openDuplicateReport,
                                            addressDestinations = nav.tabDestinations() + nav.pageDestinations(),
                                            browserSession = session,
                                            onLibraryLeft = nav::popBack,
                                            onRevealInDrive = { file -> coroutineScope.launch { nav.revealInDrive(file.id) } },
                                            onOpenSpaceMap = nav::openSpaceMapFromDrive,
                                        )
                                    }
                                    entry<Screen.MyShares> { MySharesScreen(onBackClick = paneBack, onLocate = nav::revealInDrive) }
                                    entry<Screen.SpaceMap> { screen ->
                                        SpaceMapScreen(
                                            rootFolderId = screen.rootFolderId,
                                            rootFolderName = screen.rootFolderName,
                                            resetRequests = spaceMapResetRequests,
                                            onBackClick = if (screen.rootFolderId.isNotEmpty()) nav::popBack else paneBack,
                                            onOpenFolderInDrive = { folderId, folderName, breadcrumbs ->
                                                if (folderId.isEmpty()) {
                                                    nav.goToDrive(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
                                                } else if (breadcrumbs.isNotEmpty() && breadcrumbs.first().id.isEmpty() && breadcrumbs.last().id == folderId) {
                                                    nav.goToDrive(breadcrumbs)
                                                } else {
                                                    coroutineScope.launch {
                                                        val parentStack = (workspaceModel.awaitPathLookup(folderId) ?: return@launch).getOrElse {
                                                            rootSnackbar.showSnackbar(
                                                                tr(
                                                                    "找不到目标文件夹，可能已被移动或删除",
                                                                    "Folder not found; it may have moved or been deleted",
                                                                    "対象のフォルダーが見つかりません。移動または削除された可能性があります",
                                                                    "폴더를 찾을 수 없습니다. 이동되었거나 삭제되었을 수 있습니다",
                                                                    "找不到目標資料夾，可能已被移動或刪除",
                                                                ),
                                                                withDismissAction = true,
                                                            )
                                                            return@launch
                                                        }
                                                        val targetStack = if (parentStack.lastOrNull()?.id == folderId) parentStack
                                                        else parentStack + PikoPathBreadcrumb(folderId, folderName)
                                                        nav.goToDrive(targetStack)
                                                    }
                                                }
                                            },
                                            onLocate = { fileId, knownParentBreadcrumbs ->
                                                nav.revealInDrive(fileId, knownParentBreadcrumbs)
                                            },
                                        )
                                    }
                                    entry<Screen.Settings> {
                                        SettingsScreen(
                                            onBackClick = paneBack,
                                            onOpenWebDav = { backStack.add(Screen.WebDav) },
                                            onOpenPlayerCustomization = { backStack.add(Screen.PlayerCustomization) },
                                            onOpenDriveAppearanceCustomization = { backStack.add(Screen.DriveAppearanceCustomization) },
                                            account = if (sidebarWindow) ({ AccountSettings(onLogout, onOpenSpaceMap = { nav.openPage(Screen.SpaceMap()) }) }) else null,
                                            showAbout = sidebarWindow,
                                        )
                                    }
                                    entry<Screen.WebDav> { WebDavScreen(onBackClick = nav::popBack) }
                                    entry<Screen.PlayerCustomization> {
                                        PlayerCustomizationPage(
                                            prefs = playerLayoutPrefs,
                                            onUpdate = { transform ->
                                                val next = transform(playerLayoutPrefs)
                                                coroutineScope.launch { services.preferences.setPlayerLayoutPrefs(next) }
                                            },
                                            onBackClick = nav::popBack,
                                        )
                                    }
                                    entry<Screen.DriveAppearanceCustomization> {
                                        DriveAppearanceCustomizationPage(
                                            prefs = driveAppearancePrefs,
                                            onUpdate = { next ->
                                                coroutineScope.launch { services.preferences.setDriveAppearancePrefs(next) }
                                            },
                                            onBackClick = nav::popBack,
                                        )
                                    }
                                    entry<Screen.VideoPlayer> { screen ->
                                        (videoPlayer as? VideoPlayerHost.InApp)?.content?.invoke(screen, nav::popBack)
                                    }
                                    entry<Screen.DocumentReader> { screen ->
                                        val isLocal = !screen.localPath.isNullOrBlank() || LocalLocation.isLocal(screen.fileId)
                                        val docDownloads = rememberDownloadLauncher()
                                        DocumentReaderScreen(
                                            fileId = screen.fileId,
                                            fileName = screen.fileName,
                                            localPath = screen.localPath,
                                            sizeBytes = screen.sizeBytes,
                                            onBackClick = nav::popBack,
                                            onDownloadFullFile = if (isLocal) null else ({
                                                docDownloads.download(
                                                    listOf(
                                                        FileStat(
                                                            id = screen.fileId,
                                                            name = screen.fileName,
                                                            parentId = screen.parentFolderId.orEmpty(),
                                                            size = screen.sizeBytes.toString(),
                                                        ),
                                                    ),
                                                ) { notice ->
                                                    notice?.let(services::noticeOnTransfers)
                                                    nav.openTransfers()
                                                }
                                            }),
                                        )
                                    }
                                    entry<Screen.HtmlViewer> { screen ->
                                        HtmlViewerScreen(
                                            fileId = screen.fileId,
                                            fileName = screen.fileName,
                                            parentFolderId = screen.parentFolderId,
                                            localPath = screen.localPath,
                                            onBackClick = nav::popBack,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            BackHandler(enabled = drawerOpen) { drawerOpen = false }
            ModalCaptionBlocker(enabled = floatingDrawerTarget)
            val floatingSidebar = updateTransition(targetState = floatingDrawerTarget, label = "floatingSidebar")
            if (floatingSidebar.currentState || floatingSidebar.targetState) {
                val motionScheme = MaterialTheme.motionScheme
                val scrimAlpha by floatingSidebar.animateFloat(
                    transitionSpec = { if (targetState) motionScheme.defaultEffectsSpec() else motionScheme.fastEffectsSpec() },
                    label = "floatingSidebarScrim",
                ) { if (it) 0.22f else 0f }
                val drawerWidth by floatingSidebar.animateDp(
                    transitionSpec = { if (targetState) motionScheme.defaultSpatialSpec() else motionScheme.fastSpatialSpec() },
                    label = "floatingSidebarWidth",
                ) { if (it) SidebarWidth else SidebarRailWidth }
                val drawerAlpha by floatingSidebar.animateFloat(
                    transitionSpec = { motionScheme.fastEffectsSpec() },
                    label = "floatingSidebarAlpha",
                ) { if (it) 1f else 0f }
                val drawerFocus = remember { FocusRequester() }
                LaunchedEffect(floatingSidebar.targetState) {
                    if (floatingSidebar.targetState && !pointerSource.isTouchLike) {
                        drawerFocus.requestFocus()
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                        .then(
                            if (floatingSidebar.targetState) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = tr("收起侧边栏", "Collapse sidebar", "サイドバーを折りたたむ", "사이드바 접기", "收合側邊欄"),
                                    onClick = { drawerOpen = false },
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                        .padding(IslandGap)
                        .width(drawerWidth)
                        .graphicsLayer { alpha = drawerAlpha }
                        .focusRequester(drawerFocus)
                        .focusable(!pointerSource.isTouchLike),
                    shape = FrameCardShape,
                    color = MaterialTheme.colorScheme.islandSurface,
                    tonalElevation = 2.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Box(Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true)) {
                        MainSidebarSlot(
                            width = SidebarWidth,
                            collapsed = false,
                            currentTab = currentTab,
                            quickAccess = quickAccess,
                            folderStack = folderStack,
                            selectedPage = selectedPane,
                            onTabClick = { tab -> drawerOpen = false; nav.resetToHome(); onTabClick(tab) },
                            onOpenQuickAccess = { stack -> drawerOpen = false; nav.goToDrive(stack) },
                            onOpenPage = { screen -> drawerOpen = false; nav.openPage(screen) },
                            onToggleLibrary = { library -> drawerOpen = false; nav.pushLibrary(library) },
                            onToggleCollapsed = { drawerOpen = false },
                            scrollState = sidebarScrollState,
                        )
                    }
                }
            }

            MainShellOverlays(
                desktop = desktop,
                services = services,
                workspaceModel = workspaceModel,
                fileWorkspace = fileWorkspace,
                quickAccess = quickAccess,
                folderStack = folderStack,
                fileDrag = fileDrag,
                taskSlot = taskSlot,
                palette = palette,
                shortcutModifier = shortcutModifier,
                feedShown = feedShown,
                feedSuspended = feedSuspended,
                feedBlockedReason = feedBlockedReason,
                tabDestinations = nav::tabDestinations,
                pageDestinations = nav::pageDestinations,
                onConfirmCloseFeed = { setFeedShown(false) },
                onGoToDrive = { stack -> nav.goToDrive(stack) },
                onOpenAddLink = { openAddLinkWithConfirm() },
                onUndoLast = {
                    if (services.driveRepository.currentAccount == account) {
                        taskSlot.requestUndoLast(services.driveRepository.changes, workspaceModel.fileCommands)
                    }
                },
                onResumeFeedFromPalette = {
                    taskSlot.claim(tr("继续浏览信息流", "Resume feed", "フィードを再開", "피드 계속 보기", "繼續瀏覽資訊流"), tr("继续浏览", "Resume", "再開", "계속 보기", "繼續瀏覽"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) { resumeFeed() }
                },
                onToggleFeedFromPalette = {
                    if (feedShown) {
                        currentTab = MainTab.FILES
                        nav.resetToHome()
                        requestCloseFeed()
                    } else {
                        taskSlot.claim(tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) {
                            currentTab = MainTab.FILES
                            nav.resetToHome()
                            workspaceModel.activateFileBrowser()
                            setFeedShown(true)
                        }
                    }
                },
                onSyncSettingsNow = { coroutineScope.launch { services.settingsSync.syncNow() } },
                onSetThemeMode = { mode -> coroutineScope.launch { preferences.setThemeMode(mode.name) } },
            )
        }
    }
}

private enum class SideColumnContent { Feed, AddLink }

internal val SidebarPushMinWindowWidth = 1000.dp

private fun inlineSidebarIsCollapsed(windowWidthDp: Float, persistedCollapsed: Boolean): Boolean =
    windowWidthDp < SidebarPushMinWindowWidth.value || persistedCollapsed

private val ClipPanelMinWidth = 360.dp
private val ClipPanelDefaultWidth = 420.dp
private val FeedResumeBarFabWidth = 56.dp + 16.dp
