package dev.piko.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.FileLocation
import dev.piko.shared.presentation.WorkspaceContext
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
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PaletteRegistry
import dev.piko.ui.components.SidePanelLayout
import dev.piko.ui.components.fileDragHost
import dev.piko.ui.components.focusFallbackRoot
import dev.piko.ui.components.sidePanelFits
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import dev.piko.ui.navigation.isWorkspaceSurface
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.LocalWindowCaption
import dev.piko.ui.platform.LocalWindowResizing
import dev.piko.ui.screens.clips.FeedResumeBar
import dev.piko.ui.screens.drive.SidebarRailWidth
import dev.piko.ui.screens.drive.SidebarWidth
import dev.piko.ui.screens.drive.icon
import dev.piko.ui.screens.files.FilesScreen
import dev.piko.ui.screens.instant.CollapseAddLinkLabel
import dev.piko.ui.screens.instant.DiscardAddLinkButton
import dev.piko.ui.screens.instant.DockedAddLink
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.settings.WebDavScreen
import dev.piko.ui.screens.share.MySharesScreen
import dev.piko.ui.screens.space.SpaceMapScreen
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.IslandGap
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.frame
import dev.piko.ui.workbench.AccountSettings
import dev.piko.ui.workbench.LocalFileWorkspace
import dev.piko.ui.workbench.LocalShowOngoingWork
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.LocalWorkspaceContexts
import dev.piko.ui.workbench.LocalWorkspaceSnapshotEnabled
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.WorkspaceViewModel
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

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
    ) : VideoPlayerHost
}

/** 独立窗口里的信息流与主界面协同回调。 */
class ClipFeedLinks(
    val playFull: (file: FileStat, startMillis: Long) -> Unit,
    val locate: (FileStat) -> Unit,
    val dock: () -> Unit,
    val close: () -> Unit,
)

private val NavKeyConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.Home::class)
            subclass(Screen.Library::class)
            subclass(Screen.MyShares::class)
            subclass(Screen.SpaceMap::class)
            subclass(Screen.Settings::class)
            subclass(Screen.WebDav::class)
            subclass(Screen.VideoPlayer::class)
        }
    }
}

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
    val coroutineScope = rememberCoroutineScope()
    val desktop = isDesktopLayout()
    val localFiles = LocalPikoPlatform.current.localFiles
    val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
    val presentationOwner = remember(services, account, services.presentationStores.generation) {
        services.presentationStores.forAccount(account)
    }
    val workspaceModel = viewModel<WorkspaceViewModel>(
        viewModelStoreOwner = presentationOwner,
        key = "workspace",
    ) { WorkspaceViewModel(services, account) }
    val fileWorkspace = workspaceModel.fileWorkspace
    val driveWorkspace = workspaceModel.driveWorkspace

    var currentTab by rememberSaveable { mutableStateOf(workspaceModel.lastMainTab) }
    val backStack = rememberNavBackStack(NavKeyConfiguration, *workspaceModel.lastNavigation.toTypedArray())
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
            while (backStack.size > 1) backStack.removeLastOrNull()
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
            while (backStack.size > 1) backStack.removeLastOrNull()
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

    var libraryResetRequests by remember { mutableIntStateOf(0) }
    var spaceMapResetRequests by remember { mutableIntStateOf(0) }
    var filesScrollToTop by remember { mutableIntStateOf(0) }
    var transfersScrollToTop by remember { mutableIntStateOf(0) }
    val taskSlot = remember { TaskSlot() }

    val topScreen = backStack.lastOrNull() as? Screen
    val workspaceSurface = isWorkspaceSurface(topScreen, currentTab)
    LaunchedEffect(workspaceSurface) { if (!workspaceSurface) workspaceModel.ongoingWorkShown = false }
    val onHome = backStack.size <= 1

    val density = LocalDensity.current
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val heightCompact = isHeightCompact()
    val sidebarWindow = windowWidth >= 600.dp && !heightCompact

    fun resetToHome() {
        while (backStack.size > 1) backStack.removeLastOrNull()
    }
    fun popBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
    fun leaveFileEntry(): Boolean {
        val origin = workspaceModel.returnFromFileEntry() ?: return false
        currentTab = origin.tab
        resetToHome()
        origin.screens.drop(1).forEach(backStack::add)
        return true
    }
    fun openProfilePane(screen: Screen) {
        while (backStack.size > 1 && backStack.lastOrNull() in SettingsSubpages) backStack.removeLastOrNull()
        val top = backStack.lastOrNull()
        if (top == screen) {
            if (screen is Screen.SpaceMap) spaceMapResetRequests++
            return
        }
        if (isProfilePane(top) && backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(screen)
    }
    fun openSpaceMapFromDrive(folderId: String, folderName: String) {
        openProfilePane(Screen.SpaceMap(folderId, folderName))
    }
    fun pushLibrary(library: DriveLibrary) {
        val target = Screen.Library(library.id)
        if (backStack.lastOrNull() == target) {
            libraryResetRequests++
            return
        }
        openProfilePane(target)
    }
    fun openTransfers() {
        currentTab = MainTab.TRANSFERS
        resetToHome()
    }

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
            resetToHome()
            services.consumeOpenDriveRequest()
        }
    }
    LaunchedEffect(openTransfersRequested) {
        if (openTransfersRequested) {
            openTransfers()
            services.consumeOpenTransfersRequest()
        }
    }

    fun playVideo(file: FileStat, playlist: List<FileStat>, startMillis: Long? = null) {
        val localTask = services.downloadManager.tasks.value.values.find {
            it.fileId == file.id && it.status == DownloadStatus.COMPLETED && !it.isSegment
        }
        val localPath = localTask?.destinationPath?.takeIf(localFiles::exists)
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(file.id, file.name, localPath, startMillis))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(file.id, file.name, localPath, playlist, startMillis))
        }
    }
    fun playLocal(fileId: String, fileName: String, localPath: String?) {
        when (videoPlayer) {
            is VideoPlayerHost.InApp -> backStack.add(Screen.VideoPlayer(fileId, fileName, localPath))
            is VideoPlayerHost.Detached -> videoPlayer.open(VideoPlayerRequest(fileId, fileName, localPath))
        }
    }

    fun goToDrive(stack: List<PikoPathBreadcrumb>, then: () -> Unit = {}) {
        if (services.driveRepository.currentAccount != account) return
        workspaceModel.activatePrimaryFileBrowser()
        fileWorkspace.navigateThen(stack) {
            if (services.driveRepository.currentAccount != account) return@navigateThen
            fileWorkspace.updateFolderStack(stack)
            then()
            resetToHome()
            currentTab = MainTab.FILES
        }
    }
    fun showInDrive(stack: List<PikoPathBreadcrumb>, fileId: String, activatePrimary: Boolean = true) {
        if (services.driveRepository.currentAccount != account) return
        if (activatePrimary) workspaceModel.activatePrimaryFileBrowser()
        fileWorkspace.navigateThen(stack) {
            if (services.driveRepository.currentAccount != account) return@navigateThen
            fileWorkspace.updateFolderStack(stack)
            fileWorkspace.requestHighlight(setOf(fileId))
            resetToHome()
            currentTab = MainTab.FILES
        }
    }
    suspend fun revealInDrive(fileId: String, knownParentStack: List<PikoPathBreadcrumb> = emptyList()): Boolean {
        val originTab = currentTab
        val originScreens = backStack.filterIsInstance<Screen>()
        val originContextId = workspaceModel.contexts.activeContextId.value
        val stack = when {
            fileId.isEmpty() -> listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            knownParentStack.isNotEmpty() && knownParentStack.first().id.isEmpty() -> knownParentStack
            else -> (workspaceModel.awaitPathLookup(fileId) ?: return true).getOrElse { return false }
        }
        if (services.driveRepository.currentAccount != account) return true
        workspaceModel.enterFromOutside(stack, originTab, originScreens, originContextId)
        if (fileId.isNotEmpty()) driveWorkspace.requestHighlight(setOf(fileId))
        resetToHome()
        currentTab = MainTab.FILES
        return true
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
    val initialSidebarCollapsed = remember { runBlocking { preferences.sidebarCollapsedFlow.first() } }
    val sidebarCollapsed by preferences.sidebarCollapsedFlow.collectAsStateWithLifecycle(initialSidebarCollapsed)
    val initialShowExtensions = remember { runBlocking { preferences.showExtensionsFlow.first() } }
    val showExtensions by preferences.showExtensionsFlow.collectAsStateWithLifecycle(initialShowExtensions)
    val initialPanelPrefs = remember { runBlocking { preferences.clipPanelFlow.first() } }
    val panelPrefs by preferences.clipPanelFlow.collectAsStateWithLifecycle(initialPanelPrefs)

    val contentWidth = with(density) { contentSize.width.toDp() }.takeIf { contentSize != IntSize.Zero }
    val sidebarMode = sidebarWindow && (onHome || isProfilePane(topScreen) || topScreen in SettingsSubpages)
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
    LaunchedEffect(contentWidth != null) {
        if (feedShownState == null && contentWidth != null) {
            feedShownState = initialPanelPrefs.open && panelFits &&
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
    fun requestCloseFeed() {
        if (feedShown) workspaceModel.askingCloseFeed = true else setFeedShown(false)
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

    fun playFromFeed(file: FileStat, startMillis: Long) = playVideo(file, listOf(file), startMillis)
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
            showInDrive(stack, file.id, activatePrimary = false)
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
        resetToHome()
        currentTab = MainTab.FILES
        if (feedPoppedOut) popOutFeed()
    }

    val canLeaveOrigin = workspaceModel.canLeaveFileOrigin(folderStack) ||
        (feedSuspended && !desktop && folderStack.size <= 1)

    fun leaveLibrary() {
        if (workspaceModel.canLeaveFileOrigin(folderStack) && leaveFileEntry()) return
        if (feedSuspended && !desktop) {
            resumeFeed()
            return
        }
        if (!leaveFileEntry() && !driveWorkspace.goBack()) {
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
            resetToHome()
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
                                onPreview = { fileId, fileName -> playVideo(FileStat(id = fileId, name = fileName), emptyList()) },
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

    fun openPage(screen: Screen) {
        if (!desktop) currentTab = MainTab.SETTINGS
        resetToHome()
        openProfilePane(screen)
    }

    fun tabDestinations(): List<PaletteItem> {
        val label = shortcutModifier::label
        return listOfNotNull(
            PaletteItem(tr("文件", "Files", "ファイル", "파일", "檔案"), Icons.Outlined.Folder, tr("前往", "Go", "移動", "이동", "前往"), detail = label("1"), keywords = "files drive") { currentTab = MainTab.FILES; resetToHome() },
            PaletteItem(tr("任务", "Tasks", "タスク", "작업", "任務"), Icons.Outlined.SyncAlt, tr("前往", "Go", "移動", "이동", "前往"), detail = label("2"), keywords = "tasks transfers downloads uploads") { openTransfers() },
            if (desktop) null else PaletteItem(tr("我的", "Me", "マイページ", "내 정보", "我的"), Icons.Outlined.Person, tr("前往", "Go", "移動", "이동", "前往"), detail = label("3"), keywords = "profile me") { currentTab = MainTab.SETTINGS; resetToHome() },
        )
    }

    fun openDuplicateReport() {
        val reports = workspaceModel.contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.Duplicate>()
        if (reports.size == 1) workspaceModel.activateContext(reports.single().id, rememberOrigin = true)
        else if (reports.isNotEmpty()) {
            currentTab = MainTab.FILES
            resetToHome()
            if (!desktop) workspaceModel.ongoingWorkShown = true
        }
    }

    fun libraryDestination(library: DriveLibrary, keywords: String) =
        PaletteItem(library.title, library.icon, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = keywords) { pushLibrary(library) }

    fun pageDestinations(): List<PaletteItem> = listOf(
        libraryDestination(DriveLibrary.STARRED, "starred"),
        libraryDestination(DriveLibrary.RECENT, "recent added uploads 新增"),
        libraryDestination(DriveLibrary.HISTORY, "history"),
        PaletteItem(tr("我的分享", "My shares", "自分の共有", "내 공유", "我的分享"), Icons.Outlined.Share, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "shares") { openPage(Screen.MyShares) },
        PaletteItem(tr("空间分布", "Space usage", "ストレージ内訳", "공간 분포", "空間分佈"), Icons.Outlined.PieChart, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "space usage storage disk treemap 容量 空间 占用") { openPage(Screen.SpaceMap()) },
        libraryDestination(DriveLibrary.TRASH, "trash bin"),
        PaletteItem(tr("设置", "Settings", "設定", "설정", "設定"), Icons.Outlined.Settings, tr("页面", "Page", "ページ", "페이지", "頁面"), keywords = "settings preferences") { openPage(Screen.Settings) },
    )

    val paneBack: (() -> Unit)? = if (sidebarWindow) null else ::popBack
    val selectedPane = when {
        topScreen in SettingsSubpages -> Screen.Settings
        topScreen is Screen.SpaceMap -> Screen.SpaceMap()
        isProfilePane(topScreen) -> topScreen
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
        onOpenTransfers = ::openTransfers,
        onOpenAddLink = ::openAddLinkWithConfirm,
        onRevealInstantSaved = { target, createdIds ->
            resetToHome()
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
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .trackInputModality()
                .fileDragHost(fileDrag)
                .focusFallbackRoot(focusFallback)
                .focusRequester(shortcutFocus)
                .focusable(!pointerSource.isTouchLike)
                .onPreviewKeyEvent { event ->
                    if (drawerOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        drawerOpen = false
                        return@onPreviewKeyEvent true
                    }
                    if (!workspaceSurface || event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) false
                    else when (event.key) {
                        Key.Tab -> { workspaceModel.cycleContext(if (event.isShiftPressed) -1 else 1); true }
                        Key.T -> { workspaceModel.newFileContext(); true }
                        Key.W -> {
                            val id = workspaceModel.contexts.activeContextId.value
                            if (workspaceModel.canCloseContext(id)) workspaceModel.requestCloseContext(id)
                            true
                        }
                        Key.PageUp -> if (event.isShiftPressed) { workspaceModel.moveActiveContext(-1); true } else false
                        Key.PageDown -> if (event.isShiftPressed) { workspaceModel.moveActiveContext(1); true } else false
                        else -> false
                    }
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.F1 || (shortcutModifier.isPressed(event) && event.key == Key.Slash))) {
                        workspaceModel.shortcutsOpen = true
                        return@onKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown || !shortcutModifier.isPressed(event)) return@onKeyEvent false
                    if (event.key == Key.K) {
                        workspaceModel.paletteOpen = true
                        return@onKeyEvent true
                    }
                    if (event.key == Key.Comma) {
                        openPage(Screen.Settings)
                        return@onKeyEvent true
                    }
                    if (event.key == Key.B && sidebarMode) {
                        toggleSidebar()
                        return@onKeyEvent true
                    }
                    val tab = when (event.key) {
                        Key.One -> MainTab.FILES
                        Key.Two -> MainTab.TRANSFERS
                        Key.Three -> if (desktop) return@onKeyEvent false else MainTab.SETTINGS
                        else -> return@onKeyEvent false
                    }
                    currentTab = tab
                    resetToHome()
                    true
                },
        ) {
            val frameModifier = (if (sidebarMode) Modifier.background(MaterialTheme.colorScheme.frame) else Modifier)
                .then(if (workspaceModel.ongoingWorkShown && !desktop) Modifier.clearAndSetSemantics { } else Modifier)
            if (sidebarMode) LocalWindowCaption.current?.Host()

            CompositionLocalProvider(LocalFramed provides sidebarMode) {
                Row(Modifier.fillMaxSize().then(frameModifier)) {
                    if (sidebarMode) {
                        Box(
                            Modifier
                                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                                .padding(start = IslandGap, top = IslandGap, bottom = IslandGap)
                                .clip(FrameCardShape)
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            MainSidebarSlot(
                                width = inlineSidebarWidth,
                                collapsed = inlineCollapsed,
                                currentTab = currentTab,
                                quickAccess = quickAccess,
                                folderStack = folderStack,
                                selectedPage = selectedPane,
                                onTabClick = { tab ->
                                    resetToHome()
                                    onTabClick(tab)
                                },
                                onOpenQuickAccess = ::goToDrive,
                                onOpenPage = ::openPage,
                                onToggleLibrary = ::pushLibrary,
                                onToggleCollapsed = ::toggleSidebar,
                            )
                        }
                        Spacer(Modifier.width(IslandGap))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val motion = LocalPikoMotion.current
                            NavDisplay(
                                backStack = backStack,
                                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                                onBack = ::popBack,
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
                                            addressDestinations = tabDestinations().drop(1) + pageDestinations(),
                                            feedFrame = feedFrame,
                                            onSelectMainTab = { currentTab = it },
                                            onTabClick = ::onTabClick,
                                            onMeasuredContentSize = { if (latestResizing) pendingContentSize = it else contentSize = it },
                                            onPlayVideo = { file, playlist -> playVideo(file, playlist) },
                                            onPlayLocal = ::playLocal,
                                            onRevealById = { id -> coroutineScope.launch { revealInDrive(id) } },
                                            onOpenCloudFile = { fileId, _ -> revealInDrive(fileId) },
                                            onOpenTransfers = ::openTransfers,
                                            onOpenDuplicateReport = ::openDuplicateReport,
                                            onOpenSpaceMap = ::openSpaceMapFromDrive,
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
                                            onOpenProfilePane = ::openProfilePane,
                                            onOpenLibraryFromProfile = ::pushLibrary,
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
                                            onNavigateToVideoPlayer = { file, playlist -> playVideo(file, playlist) },
                                            onOpenTransfers = ::openTransfers,
                                            onOpenDuplicateReport = ::openDuplicateReport,
                                            addressDestinations = tabDestinations() + pageDestinations(),
                                            browserSession = session,
                                            onLibraryLeft = ::popBack,
                                            onRevealInDrive = { file -> coroutineScope.launch { revealInDrive(file.id) } },
                                            onOpenSpaceMap = ::openSpaceMapFromDrive,
                                        )
                                    }
                                    entry<Screen.MyShares> { MySharesScreen(onBackClick = paneBack, onLocate = ::revealInDrive) }
                                    entry<Screen.SpaceMap> { screen ->
                                        SpaceMapScreen(
                                            rootFolderId = screen.rootFolderId,
                                            rootFolderName = screen.rootFolderName,
                                            resetRequests = spaceMapResetRequests,
                                            onBackClick = if (screen.rootFolderId.isNotEmpty()) ::popBack else paneBack,
                                            onOpenFolderInDrive = { folderId, folderName, breadcrumbs ->
                                                if (folderId.isEmpty()) {
                                                    goToDrive(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
                                                } else if (breadcrumbs.isNotEmpty() && breadcrumbs.first().id.isEmpty() && breadcrumbs.last().id == folderId) {
                                                    goToDrive(breadcrumbs)
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
                                                        goToDrive(targetStack)
                                                    }
                                                }
                                            },
                                            onLocate = { fileId, knownParentBreadcrumbs ->
                                                revealInDrive(fileId, knownParentBreadcrumbs)
                                            },
                                        )
                                    }
                                    entry<Screen.Settings> {
                                        SettingsScreen(
                                            onBackClick = paneBack,
                                            onOpenWebDav = { backStack.add(Screen.WebDav) },
                                            account = if (sidebarWindow) ({ AccountSettings(onLogout, onOpenSpaceMap = { openPage(Screen.SpaceMap()) }) }) else null,
                                            showAbout = sidebarWindow,
                                        )
                                    }
                                    entry<Screen.WebDav> { WebDavScreen(onBackClick = ::popBack) }
                                    entry<Screen.VideoPlayer> { screen ->
                                        (videoPlayer as? VideoPlayerHost.InApp)?.content?.invoke(screen, ::popBack)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            BackHandler(enabled = drawerOpen) { drawerOpen = false }
            if (drawerOpen && sidebarMode) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable { drawerOpen = false })
                Box(
                    Modifier.align(Alignment.TopStart).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start))
                        .padding(IslandGap).clip(FrameCardShape).background(MaterialTheme.colorScheme.surface),
                ) {
                    MainSidebarSlot(
                        width = SidebarWidth,
                        collapsed = false,
                        currentTab = currentTab,
                        quickAccess = quickAccess,
                        folderStack = folderStack,
                        selectedPage = selectedPane,
                        onTabClick = { tab -> drawerOpen = false; resetToHome(); onTabClick(tab) },
                        onOpenQuickAccess = { stack -> drawerOpen = false; goToDrive(stack) },
                        onOpenPage = { screen -> drawerOpen = false; openPage(screen) },
                        onToggleLibrary = { library -> drawerOpen = false; pushLibrary(library) },
                        onToggleCollapsed = { drawerOpen = false },
                    )
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
                tabDestinations = ::tabDestinations,
                pageDestinations = ::pageDestinations,
                onConfirmCloseFeed = { setFeedShown(false) },
                onGoToDrive = { stack -> goToDrive(stack) },
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
                        resetToHome()
                        requestCloseFeed()
                    } else {
                        taskSlot.claim(tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), tr("打开信息流", "Open feed", "フィードを開く", "피드 열기", "開啟資訊流"), TaskSlot.Task.FEED, conflictsWith = setOf(TaskSlot.Task.ADD_LINK)) {
                            currentTab = MainTab.FILES
                            resetToHome()
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

@Composable
private fun EscClosesWhenFocused(onClose: () -> Unit, content: @Composable () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = hasFocus, onBack = onClose)
    Box(Modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
}

internal val SidebarPushMinWindowWidth = 1000.dp

private fun inlineSidebarIsCollapsed(windowWidthDp: Float, persistedCollapsed: Boolean): Boolean =
    windowWidthDp < SidebarPushMinWindowWidth.value || persistedCollapsed

private val ClipPanelMinWidth = 360.dp
private val ClipPanelDefaultWidth = 420.dp
private val FeedResumeBarFabWidth = 56.dp + 16.dp

private val LibraryScreens: Set<NavKey?> = DriveLibrary.entries.mapTo(mutableSetOf()) { Screen.Library(it.id) }
private val ProfilePanes = setOf<NavKey?>(Screen.MyShares, Screen.Settings) + LibraryScreens
private fun isProfilePane(key: NavKey?): Boolean = key in ProfilePanes || key is Screen.SpaceMap
private val DesktopTopLevelScreens = ProfilePanes + Screen.Home
private val DesktopTopLevelContentKeys = DesktopTopLevelScreens.mapTo(mutableSetOf()) { it.toString() }
private fun isDesktopTopLevelContentKey(key: Any?): Boolean =
    key in DesktopTopLevelContentKeys || key?.toString()?.startsWith("SpaceMap(") == true
private val SettingsSubpages = setOf<NavKey?>(Screen.WebDav)
