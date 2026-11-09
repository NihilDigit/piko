package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.LastFolderStack
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.AddLinkViewModel
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.DriveTab
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.DuplicateReportViewModel
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.TabNavigation
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.GallerySession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.isUploading
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.ObserveMessages
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.DetailPanelState
import dev.piko.ui.components.FileDragPayload
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.LocalShowExtensions
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.PanelAnchor
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.components.rememberListScrollTint
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.instant.AddLinkBlockedAction
import dev.piko.ui.screens.instant.DiscardAddLinkLabel
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.theme.frame
import dev.piko.ui.workbench.LocalFileWorkspace
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.LocalWorkspaceContexts
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.WorkspaceViewModel
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HighlightAppearTimeoutMs = 4000L
private const val MapOpenTimeoutMs = 10_000L

/** 网盘页单向向下传递的不可变页面状态快照（遵循 Compose `architecture.md` 参数内聚规范）。 */
@Stable
private data class DriveViewSnapshot(
    val isWorkspaceBrowser: Boolean,
    val acceptsNavigation: Boolean,
    val folderStack: List<PathBreadcrumb>,
    val activeFolder: PathBreadcrumb,
    val displayedFiles: List<FileStat>,
    val clipboard: DriveClipboard?,
    val pinnedFolders: List<PathBreadcrumb>,
    val vaultedFolders: Set<String>,
    val fullyVaultedFolders: Set<String>,
    val viewMode: DriveViewMode,
    val tileSize: TileSize,
    val galleryActive: Boolean,
    val galleryShowImages: Boolean,
    val isSpoilerBlurEnabled: Boolean,
    val tabs: List<DriveTab>,
    val activeTabId: Long,
    val firstTabActive: Boolean,
    val locatedFileId: String?,
    val activeSheet: DriveSheet?,
    val mountAction: SheetAction?,
    val rootTitle: String?,
    val feedShown: Boolean,
    val feedStashed: Boolean,
    val feedBlockedReason: String?,
    val feedTabId: Long?,
    val addLinkInSidePanel: Boolean,
    val addressDestinations: List<PaletteItem>,
    val canLeaveOrigin: Boolean,
)

/** 网盘页无状态视图层向上冒泡的事件集合。 */
@Stable
private class DriveViewEvents(
    val onClearLocatedFile: () -> Unit,
    val onLibraryLeft: (() -> Unit)?,
    val onCloseContext: (() -> Unit)?,
    val onOpenDuplicateReport: (() -> Unit)?,
    val onRevealInDrive: ((FileStat) -> Unit)?,
    val onOpenSpaceMap: ((String, String) -> Unit)?,
    val onFeedShownChange: ((Boolean) -> Unit)?,
    val onOpenSelectedFeed: ((List<FileStat>) -> Unit)?,
    val onResumeFeed: (() -> Unit)?,
    val onNavigateToPlayer: (FileStat, List<FileStat>) -> Unit,
    val onOpenTransfers: () -> Unit,
    val onPickFiles: () -> Unit,
    val onPickFolder: () -> Unit,
    val onOpenLink: (String) -> Unit,
    val onOpenAddLink: () -> Unit,
    val onOpenMobileGoToPath: () -> Unit,
    val onOpenSingleItemSheet: (FileStat) -> Unit,
    val onOpenSelectionSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onSelectViewMode: (DriveViewMode) -> Unit,
    val onSelectTileSize: (TileSize) -> Unit,
    val onStepZoom: (Boolean) -> Unit,
    val onRequestCloseGallery: () -> Unit,
    val onSetGalleryShowImages: (Boolean) -> Unit,
    val onSetShowExtensions: (Boolean) -> Unit,
    val onOpenSelectedGallery: (List<FileStat>) -> Unit,
    val onRefresh: () -> Unit,
    val onRequestNewFolder: () -> Unit,
    val onRequestRename: (FileStat) -> Unit,
    val onRequestBatchRename: (List<FileStat>, Boolean) -> Unit,
    val onRequestNameByCode: (List<FileStat>) -> Unit,
    val onRequestCanonicalFolder: (PathBreadcrumb) -> Unit,
    val onRequestShare: (List<FileStat>) -> Unit,
    val onRequestMove: (Set<String>) -> Unit,
    val onRequestCopy: (Set<String>) -> Unit,
    val onRequestVault: (List<FileStat>) -> Unit,
    val onRequestRestoreVaultFolder: (FileStat) -> Unit,
    val onRestoreVaultItems: (List<FileStat>) -> Unit,
    val onRequestLibraryConfirm: (LibraryConfirm) -> Unit,
    val onRequestImagePreview: (FileStat) -> Unit,
    val onRequestDownload: (List<FileStat>, Boolean) -> Unit,
    val onExtractArchives: (List<FileStat>) -> Unit,
    val onExtractFromArchive: (List<FileStat>) -> Unit,
    val onOpenSegmentDownload: (FileStat) -> Unit,
    val onPrefetchQualities: (String) -> Unit,
    val onCopySource: (FileStat) -> Unit,
    val onCopyDownloadLink: (FileStat) -> Unit,
    val onOpenInExternalPlayer: (FileStat) -> Unit,
    val onTogglePinFolder: (FileStat) -> Unit,
    val onRevealLocalInFileManager: (String) -> Unit,
    val onUnmountLocalFolder: (String) -> Unit,
)

@Stable
private class DriveRuntimeContext(
    val state: DriveScreenState,
    val driveRepo: PikoDriveRepository,
    val gallery: GallerySession,
    val navigation: DriveNavigation,
    val workspace: FileWorkspace,
    val desktopWorkspace: DriveWorkspace?,
    val contexts: WorkspaceViewModel?,
    val duplicateSession: DuplicateSession,
    val duplicateReport: DuplicateReportViewModel,
    val folderMap: FolderMapState,
    val gridState: LazyGridState,
    val addLink: AddLinkViewModel,
    val instantState: InstantSheetState?,
    val taskSlot: TaskSlot,
    val snackbarHostState: SnackbarHostState,
)

/**
 * 网盘主界面（Stateful Screen Entry）：
 * 连接 `DriveViewModel` 与持久工作区数据流，构建不可变 [DriveViewSnapshot] 与 [DriveViewEvents] 交由无状态视图层渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    currentFolderId: String = "",
    currentFolderName: String = tr("我的网盘", "My drive", "マイドライブ", "내 드라이브", "我的雲端硬碟"),
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit,
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    scrollToTopRequests: Int = 0,
    onOpenTransfers: () -> Unit = {},
    onOpenDuplicateReport: (() -> Unit)? = null,
    onCloseContext: (() -> Unit)? = null,
    rootTitle: String? = null,
    interactionEnabled: Boolean = true,
    feedShown: Boolean = false,
    onFeedShownChange: ((Boolean) -> Unit)? = null,
    onOpenSelectedFeed: ((List<FileStat>) -> Unit)? = null,
    feedStashed: Boolean = false,
    onResumeFeed: (() -> Unit)? = null,
    feedBlockedReason: String? = null,
    feedTabId: Long? = null,
    addLinkInSidePanel: Boolean = false,
    addressDestinations: List<PaletteItem> = emptyList(),
    onLibraryLeft: (() -> Unit)? = null,
    canLeaveOrigin: Boolean = false,
    browserSession: DriveBrowserSession? = null,
    onRevealInDrive: ((FileStat) -> Unit)? = null,
    onOpenSpaceMap: ((folderId: String, folderName: String) -> Unit)? = null,
    contentFrame: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val driveRepo = services.driveRepository
    val sessionManager = services.preferences
    val workspace = LocalFileWorkspace.current
    val contexts = LocalWorkspaceContexts.current
    val taskSlot = LocalTaskSlot.current
    val snackbarHostState = LocalRootSnackbar.current
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloadLauncher()

    val navigation = browserSession?.navigation ?: workspace
    val isWorkspaceBrowser = navigation is TabNavigation || navigation === workspace
    val duplicateSession = browserSession?.duplicates ?: services.duplicateSession
    val duplicateReport = browserSession?.duplicateReport ?: remember { DuplicateReportViewModel() }
    val gallery = browserSession?.gallery ?: remember(driveRepo) { GallerySession(driveRepo, scope) }
    val state = browserSession?.state ?: remember {
        DriveScreenState(
            driveRepo, sessionManager, scope,
            sessionListing = { place ->
                when (place) {
                    DriveLibrary.DUPLICATES -> duplicateSession.state?.let { finder ->
                        val folder = navigation.folderStackFlow.value.lastOrNull()?.id
                        duplicateReport.listing(finder, folder?.takeUnless { it == DriveLibrary.DUPLICATES.id } ?: finder.root.id)
                    }
                    else -> null
                }
            },
            archives = services.archiveBrowser,
            navigation = navigation,
            trashSession = services.trashSession,
            clipboard = workspace.clipboard,
            fileWriteSession = services.fileWrites,
            historyEditSession = services.historyEdits,
            vaultEditSession = services.vaultEntryEdits,
            folderVault = services.folderVaultSession,
            shareSaves = services.shareSaves,
            receiving = services.receivingActivity,
            instantSession = services.instantSession,
            supplementalFiles = { gallery.files },
            operationScope = driveRepo.currentAccount?.let(services.fileExecutionScopes::forAccount) ?: scope,
        )
    }

    val currentAccount = driveRepo.currentAccount
    val tabNav = navigation as? TabNavigation
    val tabVmOwner = if (tabNav != null && contexts != null) remember(contexts, tabNav.tabId) { contexts.tabViewModelOwner(tabNav.tabId) } else null
    val localVmOwner = remember(state) {
        if (tabVmOwner != null || (tabNav != null && currentAccount != null)) null
        else object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(localVmOwner) {
        onDispose { localVmOwner?.viewModelStore?.clear() }
    }
    val vmOwner = tabVmOwner ?: if (tabNav != null && currentAccount != null) {
        remember(services, currentAccount, services.presentationStores.generation) {
            services.presentationStores.forAccount(currentAccount)
        }
    } else {
        requireNotNull(localVmOwner)
    }
    val vmKey = if (tabNav != null && currentAccount != null) "drive-tab-${tabNav.tabId}" else "drive-local"
    val viewModel = viewModel<DriveViewModel>(viewModelStoreOwner = vmOwner, key = vmKey) {
        DriveViewModel(
            state = state,
            gallery = gallery,
            driveRepo = driveRepo,
            preferences = sessionManager,
            duplicateSession = duplicateSession,
            archiveSession = services.archiveExtractSession,
            vaultSession = services.folderVaultSession,
            downloadManager = services.downloadManager,
            uploadManager = services.uploadManager,
            mediaRepository = services.mediaRepository,
            instantSession = services.instantSession,
        )
    }

    val openTransfers by rememberUpdatedState(onOpenTransfers)
    val navigateToPlayer by rememberUpdatedState(onNavigateToVideoPlayer)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DriveUiEffect.ShowSnackbar ->
                    snackbarHostState.showSnackbar(effect.message, withDismissAction = true)
                is DriveUiEffect.CopyToClipboard -> {
                    platform.copyToClipboard(effect.label, effect.text)
                    snackbarHostState.showSnackbar(effect.message, withDismissAction = true)
                }
                is DriveUiEffect.OpenExternalPlayer -> {
                    val opened = platform.externalPlayer?.open(effect.url, effect.fileName) == true
                    if (!opened) {
                        snackbarHostState.showSnackbar(
                            tr("无法用外部播放器打开", "Unable to open in external player", "外部プレーヤーで開けません", "외부 플레이어에서 열 수 없습니다", "無法使用外部播放器開啟"),
                            withDismissAction = true,
                        )
                    }
                }
                is DriveUiEffect.OpenLocalContainingFolder ->
                    platform.localFiles.openContainingFolder(effect.localPath)
                is DriveUiEffect.ShowExtractionBlocked ->
                    taskSlot.showBlocked(
                        tr("开始解压", "Start extraction", "解凍を開始", "압축 해제 시작", "開始解壓縮"),
                        effect.reason,
                    ) { openTransfers() }
                is DriveUiEffect.EnqueuePlatformDownload -> {
                    val onQueued: (String?) -> Unit = { notice ->
                        notice?.let(services::noticeOnTransfers)
                        openTransfers()
                    }
                    val source = DriveDownloadFolderSource(driveRepo)
                    if (effect.chooseQuality) downloads.downloadAtQuality(effect.targets, source, onQueued)
                    else downloads.download(effect.targets, source, onQueued)
                }
                DriveUiEffect.OpenTransfers -> openTransfers()
            }
        }
    }

    ObserveMessages(state.messages, snackbarHostState)
    ObserveMessages(services.archiveExtractSession.messages, snackbarHostState)
    ObserveMessages(services.folderVaultSession.messages, snackbarHostState)
    ObserveMessages(services.uploadManager.messages, snackbarHostState)

    val isSpoilerBlurEnabled by viewModel.isSpoilerBlurEnabled.collectAsStateWithLifecycle()
    val galleryShowImages by viewModel.galleryShowImages.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    LaunchedEffect(gallery, galleryShowImages) { gallery.updateIncludeImages(galleryShowImages) }

    val folderStack by state.folderStack.collectAsStateWithLifecycle()
    val activeFolder = folderStack.lastOrNull() ?: PathBreadcrumb(currentFolderId, currentFolderName)
    val activeFolderId = activeFolder.id
    val inTrash = state.libraryView == DriveLibrary.TRASH
    val galleryActive = gallery.shown && gallery.root?.id == activeFolderId && !inTrash &&
        state.archiveView == null && state.libraryView == null && !state.isSearchOpen
    LaunchedEffect(gallery, activeFolderId, inTrash, state.archiveView, state.libraryView, state.isSearchOpen) {
        if (gallery.shown && !galleryActive) gallery.close()
    }
    LaunchedEffect(state) { state.updateThumbnailsOnly(false) }

    val tileSize by viewModel.tileSizeFlow(galleryActive).collectAsStateWithLifecycle()
    val tilePrefKey = if (galleryActive) GALLERY_TILE_PREF_KEY else viewMode.name
    var selectionViewMode by remember(state) { mutableStateOf(tilePrefKey) }
    LaunchedEffect(tilePrefKey) {
        if (selectionViewMode != tilePrefKey) {
            state.exitSelection()
            selectionViewMode = tilePrefKey
        }
    }

    val displayedFiles by remember(galleryActive, gallery, galleryShowImages, state) {
        derivedStateOf {
            if (galleryActive) {
                gallery.files.filter {
                    (it.isPlayableVideo() || (galleryShowImages && it.isPreviewableImage())) && !it.isUploading
                }
            } else state.displayedFiles
        }
    }
    val clipboard by state.clipboardFlow.collectAsStateWithLifecycle()
    val pinnedFolders by driveRepo.pinnedFoldersFlow.collectAsStateWithLifecycle(emptyList())
    val vaultedFolders by state.vaultedFolders.collectAsStateWithLifecycle()
    val fullyVaultedFolders by state.fullyVaultedFolders.collectAsStateWithLifecycle()

    val latestActiveFolder by rememberUpdatedState(activeFolder)
    val pickFiles = platform.uploadPicker.rememberFilesLauncher { viewModel.upload(UploadSelection(files = it), latestActiveFolder) }
    val pickFolder = platform.uploadPicker.rememberFolderLauncher { viewModel.upload(UploadSelection(folders = listOf(it)), latestActiveFolder) }
    val pickLocalFolder = platform.rememberLocalFolderLauncher { path, name -> viewModel.completeLocalMount(path, name) }
    val mountAction = pickLocalFolder?.let { launch ->
        SheetAction(Icons.Outlined.Storage, tr("挂载本地文件夹", "Mount local folder", "ローカルフォルダーをマウント", "로컬 폴더 마운트", "掛載本機資料夾"), {
            if (viewModel.beginLocalMount(latestActiveFolder.id)) launch()
        }, group = ActionGroup.Create)
    }

    val desktopWorkspace = workspace as? DriveWorkspace
    val workContexts = contexts?.let { val current by it.contexts.contextsFlow.collectAsStateWithLifecycle(); current }
    val workspaceTabs = desktopWorkspace?.let { val list by it.tabsFlow.collectAsStateWithLifecycle(); list }.orEmpty()
    val tabs = remember(workspaceTabs, workContexts) {
        workspaceTabs.filter { tab -> workContexts == null || workContexts.any { it.id == tab.id } }
    }
    val activeTabId = desktopWorkspace?.let { val current by it.activeTabId.collectAsStateWithLifecycle(); current } ?: 0L
    val activeContextId = contexts?.let { val current by it.contexts.activeContextId.collectAsStateWithLifecycle(); current } ?: activeTabId
    val firstTabActive = if (workContexts != null) workContexts.firstOrNull()?.id == activeContextId else tabs.firstOrNull()?.id == activeTabId
    val currentTabId = tabNav?.tabId ?: desktopWorkspace?.let { activeTabId }
    val addLink = services.addLink
    val ownerTabStillExists = workspaceTabs.isEmpty() || addLink.ownerTabId == null || workspaceTabs.any { it.id == addLink.ownerTabId }
    val addLinkOwnedHere = addLink.ownerTabId == null || currentTabId == null || addLink.ownerTabId == currentTabId ||
        (!ownerTabStillExists && desktopWorkspace?.activeTabId?.value == currentTabId)
    val instantState = addLink.state.takeIf { addLinkOwnedHere }

    fun openLink(link: String) {
        val replacing = addLink.state?.isBlank == false
        taskSlot.claim(
            action = if (replacing) tr("打开新链接", "Open new link", "新しいリンクを開く", "새 링크 열기", "開啟新連結") else tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            confirmLabel = if (replacing) tr("打开新链接", "Open new link", "新しいリンクを開く", "새 링크 열기", "開啟新連結") else tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            task = TaskSlot.Task.ADD_LINK,
            replacesSame = replacing,
            conflictsWith = setOf(TaskSlot.Task.ADD_LINK, TaskSlot.Task.FEED),
        ) { addLink.open(link, tabId = currentTabId) }
    }
    fun openAddLink() {
        taskSlot.claim(
            action = tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            confirmLabel = tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
            task = TaskSlot.Task.ADD_LINK,
            conflictsWith = setOf(TaskSlot.Task.ADD_LINK, TaskSlot.Task.FEED),
        ) {
            if (addLink.state != null && addLink.ownerTabId != null && ownerTabStillExists && currentTabId != null && addLink.ownerTabId != currentTabId) {
                contexts?.activateContext(addLink.ownerTabId!!) ?: desktopWorkspace?.switchTab(addLink.ownerTabId!!)
                addLink.resume()
            } else {
                if (!ownerTabStillExists) addLink.rebindTabIfMissing(workspaceTabs.mapTo(HashSet()) { it.id }, currentTabId)
                addLink.resume(tabId = currentTabId)
            }
        }
    }

    val pendingMagnet by services.instantMagnetRepository.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        val magnet = pendingMagnet
        if (contexts == null && !magnet.isNullOrBlank()) {
            services.instantMagnetRepository.clearPendingMagnet()
            openLink(magnet)
        }
    }

    val folderMap = remember(state) {
        FolderMapState(state::folderMapLevel, scope, sessionManager::setFolderMapOpen, sessionManager::setFolderMapPinned)
    }
    LaunchedEffect(folderMap) {
        folderMap.restore(open = sessionManager.folderMapOpenFlow.first(), pinned = sessionManager.folderMapPinnedFlow.first())
    }
    LaunchedEffect(folderMap) { driveRepo.folderChanges.collect(folderMap::onChange) }

    val loadedFolderId = state.loadedFolderId
    val gridState = remember(state, loadedFolderId) {
        val anchor = loadedFolderId?.let(navigation::scrollAnchor)
        LazyGridState(anchor?.index ?: 0, anchor?.offset ?: 0)
    }
    var locatedFileId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeFolderId) {
        locatedFileId = null
        if (viewModel.activeSheet is DriveSheet.SelectionActions) viewModel.dismissSheet()
    }

    DriveLifecycleEffects(
        state = state,
        browserSession = browserSession,
        isWorkspaceBrowser = isWorkspaceBrowser,
        currentFolderId = currentFolderId,
        navigation = navigation,
        sessionManager = sessionManager,
        loadedFolderId = loadedFolderId,
        gridState = gridState,
        scrollToTopRequests = scrollToTopRequests,
        acceptsNavigation = interactionEnabled,
        archiveSession = services.archiveExtractSession,
        vaultSession = services.folderVaultSession,
        instantState = instantState,
        addLink = addLink,
        snackbarHostState = snackbarHostState,
        onOpenTransfers = onOpenTransfers,
        onNavigateToPlayer = { file -> navigateToPlayer(file, emptyList()) },
        onLocatedFileChanged = { locatedFileId = it },
    )

    val runtime = DriveRuntimeContext(
        state = state,
        driveRepo = driveRepo,
        gallery = gallery,
        navigation = navigation,
        workspace = workspace,
        desktopWorkspace = desktopWorkspace,
        contexts = contexts,
        duplicateSession = duplicateSession,
        duplicateReport = duplicateReport,
        folderMap = folderMap,
        gridState = gridState,
        addLink = addLink,
        instantState = instantState,
        taskSlot = taskSlot,
        snackbarHostState = snackbarHostState,
    )
    val snapshot = DriveViewSnapshot(
        isWorkspaceBrowser = isWorkspaceBrowser,
        acceptsNavigation = interactionEnabled,
        folderStack = folderStack,
        activeFolder = activeFolder,
        displayedFiles = displayedFiles,
        clipboard = clipboard,
        pinnedFolders = pinnedFolders,
        vaultedFolders = vaultedFolders,
        fullyVaultedFolders = fullyVaultedFolders,
        viewMode = viewMode,
        tileSize = tileSize,
        galleryActive = galleryActive,
        galleryShowImages = galleryShowImages,
        isSpoilerBlurEnabled = isSpoilerBlurEnabled,
        tabs = tabs,
        activeTabId = activeTabId,
        firstTabActive = firstTabActive,
        locatedFileId = locatedFileId,
        activeSheet = viewModel.activeSheet,
        mountAction = mountAction,
        rootTitle = rootTitle,
        feedShown = feedShown,
        feedStashed = feedStashed,
        feedBlockedReason = feedBlockedReason,
        feedTabId = feedTabId,
        addLinkInSidePanel = addLinkInSidePanel,
        addressDestinations = addressDestinations,
        canLeaveOrigin = canLeaveOrigin,
    )
    val events = DriveViewEvents(
        onClearLocatedFile = { locatedFileId = null },
        onLibraryLeft = onLibraryLeft,
        onCloseContext = onCloseContext,
        onOpenDuplicateReport = onOpenDuplicateReport,
        onRevealInDrive = onRevealInDrive,
        onOpenSpaceMap = onOpenSpaceMap,
        onFeedShownChange = onFeedShownChange,
        onOpenSelectedFeed = onOpenSelectedFeed,
        onResumeFeed = onResumeFeed,
        onNavigateToPlayer = navigateToPlayer,
        onOpenTransfers = openTransfers,
        onPickFiles = { pickFiles() },
        onPickFolder = { pickFolder() },
        onOpenLink = ::openLink,
        onOpenAddLink = ::openAddLink,
        onOpenMobileGoToPath = viewModel::openMobileGoToPath,
        onOpenSingleItemSheet = { viewModel.openSingleItemActions(it, displayedFiles) },
        onOpenSelectionSheet = { viewModel.openSelectionActions(displayedFiles) },
        onDismissSheet = viewModel::dismissSheet,
        onSelectViewMode = { viewModel.selectViewMode(it, galleryActive) },
        onSelectTileSize = { viewModel.selectTileSize(it, galleryActive, viewMode) },
        onStepZoom = viewModel::stepZoom,
        onRequestCloseGallery = { viewModel.requestCloseGallery(galleryActive) },
        onSetGalleryShowImages = viewModel::setGalleryShowImages,
        onSetShowExtensions = viewModel::setShowExtensions,
        onOpenSelectedGallery = { files -> viewModel.openSelectedGallery(files, folderStack, galleryShowImages) },
        onRefresh = { viewModel.refresh(galleryActive) },
        onRequestNewFolder = viewModel::requestNewFolder,
        onRequestRename = viewModel::requestRename,
        onRequestBatchRename = viewModel::requestBatchRename,
        onRequestNameByCode = viewModel::requestNameByCode,
        onRequestCanonicalFolder = viewModel::requestCanonicalFolder,
        onRequestShare = viewModel::requestShare,
        onRequestMove = viewModel::requestMove,
        onRequestCopy = viewModel::requestCopy,
        onRequestVault = viewModel::requestVault,
        onRequestRestoreVaultFolder = viewModel::requestRestoreVault,
        onRestoreVaultItems = viewModel::restoreVaultItems,
        onRequestLibraryConfirm = viewModel::requestLibraryConfirm,
        onRequestImagePreview = viewModel::requestImagePreview,
        onRequestDownload = { files, chooseQuality -> viewModel.requestDownload(files, activeFolderId, chooseQuality) },
        onExtractArchives = viewModel::extractArchives,
        onExtractFromArchive = viewModel::extractFromArchive,
        onOpenSegmentDownload = services.segmentSession::open,
        onPrefetchQualities = viewModel::prefetchDownloadQualities,
        onCopySource = viewModel::copySource,
        onCopyDownloadLink = viewModel::copyDownloadLink,
        onOpenInExternalPlayer = { file ->
            platform.externalPlayer?.let { viewModel.openInExternalPlayer(file, it.requiresDirectUrl) }
        },
        onTogglePinFolder = { viewModel.togglePinFolder(it, pinnedFolders) },
        onRevealLocalInFileManager = viewModel::revealLocalInFileManager,
        onUnmountLocalFolder = viewModel::unmountLocalFolder,
    )

    DriveScreenContent(
        runtime = runtime,
        snapshot = snapshot,
        events = events,
        contentFrame = contentFrame,
        modifier = modifier,
    )

    DriveDialogHost(
        dialog = viewModel.activeDialog,
        state = state,
        vaultSession = services.folderVaultSession,
        metaTube = services.metaTube,
        activeFolderId = activeFolderId,
        displayedFiles = displayedFiles,
        onDismiss = viewModel::dismissDialog,
        onConfirmNewFolder = viewModel::confirmNewFolder,
        onConfirmSingleRename = viewModel::confirmSingleRename,
        onBatchRenameFinished = viewModel::onBatchRenameFinished,
        onShareCopied = viewModel::onShareCopied,
        onConfirmMove = viewModel::confirmMove,
        onConfirmCopy = viewModel::confirmCopy,
        onConfirmLibraryAction = viewModel::confirmLibraryAction,
        onConfirmCloseGallery = viewModel::confirmCloseGallery,
    )

    DriveSystemOverlays(
        state = state,
        isWorkspaceBrowser = isWorkspaceBrowser,
        currentAccount = currentAccount,
        fileWrites = services.fileWrites,
        segmentSession = services.segmentSession,
        downloadManager = services.downloadManager,
        onOpenTransfers = openTransfers,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveScreenContent(
    runtime: DriveRuntimeContext,
    snapshot: DriveViewSnapshot,
    events: DriveViewEvents,
    contentFrame: @Composable (content: @Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = runtime.state
    val driveRepo = runtime.driveRepo
    val gallery = runtime.gallery
    val navigation = runtime.navigation
    val workspace = runtime.workspace
    val desktopWorkspace = runtime.desktopWorkspace
    val contexts = runtime.contexts
    val duplicateSession = runtime.duplicateSession
    val duplicateReport = runtime.duplicateReport
    val folderMap = runtime.folderMap
    val gridState = runtime.gridState
    val addLink = runtime.addLink
    val instantState = runtime.instantState
    val taskSlot = runtime.taskSlot
    val snackbarHostState = runtime.snackbarHostState

    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    val desktop = isDesktopLayout()
    val pathInTopBar = desktop
    val twoPane = desktop
    val pointerSource = LocalPointerSource.current
    val focusManager = LocalFocusManager.current
    val shortcutFocus = remember { FocusRequester() }

    val folderStack = snapshot.folderStack
    val activeFolder = snapshot.activeFolder
    val activeFolderId = activeFolder.id
    val displayedFiles = snapshot.displayedFiles
    val libraryView = state.libraryView
    val inTrash = libraryView == DriveLibrary.TRASH
    val inDuplicates = libraryView == DriveLibrary.DUPLICATES
    val duplicateState = duplicateSession.state
    val isSearchOpen = state.isSearchOpen

    var focusedFile by remember { mutableStateOf<FileStat?>(null) }
    var commandFile by remember { mutableStateOf<FileStat?>(null) }
    var keyboardFocusTarget by remember { mutableStateOf<String?>(null) }
    var searchFocusRequests by remember { mutableIntStateOf(0) }
    var addressEditRequests by remember { mutableIntStateOf(0) }

    fun goHome() = navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
    fun backInBrowser(): Boolean {
        if (snapshot.galleryActive) { events.onRequestCloseGallery(); return true }
        if (snapshot.canLeaveOrigin && events.onLibraryLeft != null) { events.onLibraryLeft.invoke(); return true }
        if (state.goBack()) return true
        if (!snapshot.isWorkspaceBrowser && events.onLibraryLeft != null) { events.onLibraryLeft.invoke(); return true }
        return false
    }
    fun leaveLibrary() {
        if (events.onLibraryLeft != null) events.onLibraryLeft.invoke()
        else if (snapshot.isWorkspaceBrowser && !state.goBack()) goHome()
    }
    fun editAddress() {
        if (!pathInTopBar) events.onOpenMobileGoToPath()
        addressEditRequests++
    }

    val atOriginBoundary = snapshot.canLeaveOrigin || (folderStack.size <= navigation.rootDepth && (libraryView != null || !snapshot.isWorkspaceBrowser))
    BackHandler(enabled = snapshot.acceptsNavigation && folderStack.size > navigation.rootDepth && !snapshot.canLeaveOrigin) { state.navigateUp() }
    BackHandler(enabled = snapshot.acceptsNavigation && atOriginBoundary) { leaveLibrary() }
    BackHandler(enabled = snapshot.acceptsNavigation && snapshot.galleryActive, onBack = events.onRequestCloseGallery)
    BackHandler(enabled = snapshot.acceptsNavigation && isSearchOpen) { state.closeSearch() }

    val libraryEvents = state.libraryEvents
    val libraryNotes = remember(libraryView, state.files, libraryEvents) {
        when (libraryView) {
            null, DriveLibrary.DUPLICATES -> emptyMap()
            else -> state.files.mapNotNull { file -> libraryNote(libraryView, file, libraryEvents[file.id])?.let { file.id to it } }.toMap()
        }
    }
    val rowNotes = if (libraryNotes.isEmpty()) state.hitLocations else state.hitLocations + libraryNotes

    val propertyDetails = contexts?.detailPanels?.stateFor(navigation, FilePropertiesPanelKey)
        ?: remember(navigation) { DetailPanelState<PropertiesTarget>(FilePropertiesPanelKey.spec) }
    val propertiesAnchors = propertyDetails.anchors
    LaunchedEffect(activeFolderId) {
        propertiesAnchors.forgetItems()
        commandFile = null
    }
    LaunchedEffect(snapshot.activeSheet) {
        if (snapshot.activeSheet is DriveSheet.SingleItemActions) propertiesAnchors.forgetPress()
    }

    fun showProperties(files: List<FileStat>, anchor: PanelAnchor) {
        val single = files.singleOrNull()
        val target = if (single != null) {
            val item = state.displayItems.firstOrNull { it is DriveListItem.File && it.file.id == single.id } as? DriveListItem.File
            val text = item?.let { cellText(it, state.folderView(single)) }
            PropertiesTarget.Single(
                name = state.itemName(single),
                tags = listOfNotNull(text?.code) + text?.tags.orEmpty(),
                location = locationLabelOf(single, rowNotes, folderStack),
                isBlurred = snapshot.isSpoilerBlurEnabled && single.id !in state.revealedFileIds,
            )
        } else {
            PropertiesTarget.Selection(
                files,
                files.map { locationLabelOf(it, rowNotes, folderStack) }.distinct().singleOrNull()
                    ?: tr("多个位置", "Multiple locations", "複数の場所", "여러 위치", "多個位置"),
            )
        }
        propertyDetails.show(target, anchor)
    }
    fun showFolderProperties() {
        val parent = if (state.isLocalPlace) {
            val local = LocalLocation.of(activeFolder.id)
            driveRepo.localMounts.findCached(activeFolder.id)?.localRootUriOrPath.orEmpty() +
                local?.segments.orEmpty().joinToString("/", prefix = if (local?.segments?.isNotEmpty() == true) "/" else "")
        } else folderStack.dropLast(1).joinToString(" › ") { it.localizedName }
        propertyDetails.show(PropertiesTarget.Folder(activeFolder.localizedName, state.displayedFiles, parent.ifEmpty { null }, activeFolder.id))
    }

    fun findDuplicates(root: PathBreadcrumb) {
        if (!state.acceptsActions) return
        if (contexts != null) {
            contexts.openDuplicate(root)
            return
        }
        val current = duplicateSession.state
        taskSlot.claim(
            action = tr("查找「${root.localizedName}」中的重复文件", "Find duplicate files in “${root.localizedName}”", "「${root.localizedName}」の重複ファイルを検索", "“${root.localizedName}”에서 중복 파일 찾기", "尋找「${root.localizedName}」中的重複檔案"),
            confirmLabel = tr("查找重复", "Find duplicates", "重複を検索", "중복 찾기", "尋找重複檔案"),
            task = TaskSlot.Task.DUPLICATES,
            replacesSame = current != null && current.root.id != root.id,
        ) {
            if (!state.acceptsActions) return@claim
            val sameRoot = duplicateSession.state?.root?.id == root.id
            duplicateSession.open(root)
            if (sameRoot && duplicateSession.state?.isScanning == false) {
                if (events.onOpenDuplicateReport != null) events.onOpenDuplicateReport.invoke()
                else if (state.libraryView != DriveLibrary.DUPLICATES) navigation.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
            } else scope.launch {
                val result = snackbarHostState.showSnackbar(
                    tr("正在查找「${root.localizedName}」中的重复文件", "Finding duplicates in “${root.localizedName}”", "「${root.localizedName}」の重複ファイルを検索中", "“${root.localizedName}”에서 중복 파일 검색 중", "正在尋找「${root.localizedName}」中的重複檔案"),
                    actionLabel = tr("查看任务", "View tasks", "タスクを表示", "작업 보기", "檢視任務"),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) events.onOpenTransfers()
            }
        }
    }

    val selectedIdSet by remember { derivedStateOf { state.selectedFileIds.toSet() } }
    val commandTargets: List<FileStat> by remember(state, displayedFiles) {
        derivedStateOf {
            if (state.isSelectionMode) displayedFiles.filter { it.id in selectedIdSet }
            else listOfNotNull(commandFile?.let { current -> displayedFiles.firstOrNull { it.id == current.id } })
        }
    }
    val selectedFeedVideos by remember(state) {
        derivedStateOf {
            if (state.isSelectionMode) commandTargets.filter { it.isPlayableVideo() && !it.isUploading && !it.isImportPreview } else emptyList()
        }
    }
    val selectedGalleryMedia by remember(state, snapshot.galleryShowImages) {
        derivedStateOf {
            if (state.isSelectionMode) {
                commandTargets.filter {
                    (it.isPlayableVideo() || (snapshot.galleryShowImages && it.isPreviewableImage())) &&
                        !it.isUploading && !it.isImportPreview
                }
            } else emptyList()
        }
    }

    fun selectAllDisplayed() {
        if (snapshot.galleryActive) {
            val ids = displayedFiles.mapTo(LinkedHashSet()) { it.id }
            if (ids.isNotEmpty() && state.selectedFileIds.toSet() == ids) state.exitSelection() else state.selectOnly(ids)
        } else state.toggleSelectAll()
    }
    fun openSelectedFeed(files: List<FileStat>) {
        if (snapshot.feedBlockedReason != null) return
        val videos = files.filter { it.isPlayableVideo() && !it.isUploading && !it.isImportPreview }
        if (videos.isNotEmpty()) events.onOpenSelectedFeed?.invoke(videos.toList())
    }
    val changeGalleryShown: (Boolean) -> Unit = { shown ->
        if (shown) {
            if (state.isSelectionMode) events.onOpenSelectedGallery(selectedGalleryMedia)
            else gallery.open(folderStack, showImages = snapshot.galleryShowImages)
        } else events.onRequestCloseGallery()
    }
    val changeFeedShown: ((Boolean) -> Unit)? = events.onFeedShownChange?.let { change ->
        { shown -> if (shown && state.isSelectionMode) openSelectedFeed(selectedFeedVideos) else change(shown) }
    }
    val latestChangeFeedShown by rememberUpdatedState(changeFeedShown)

    fun closeTab(id: Long) { if (contexts != null) contexts.requestCloseContext(id) else desktopWorkspace?.closeTab(id) }
    fun selectTab(id: Long) { if (contexts != null) contexts.activateContext(id) else desktopWorkspace?.switchTab(id) }
    fun newTab(stack: List<PathBreadcrumb>, activate: Boolean = true) { desktopWorkspace?.openTab(stack, activate) }
    fun newRootTab() {
        if (contexts != null) contexts.newFileContext()
        else newTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
    }
    fun cycleTab(step: Int) { if (contexts != null) contexts.cycleContext(step) else desktopWorkspace?.cycleTab(step) }
    val tabsAvailable = desktopWorkspace != null && snapshot.isWorkspaceBrowser
    val hasTabBarAbove = pathInTopBar && (snapshot.isWorkspaceBrowser || libraryView == DriveLibrary.DUPLICATES)
    val openInNewTab: ((FileStat) -> Unit)? = if (tabsAvailable) {
        { folder -> if (!folder.isImportPreview) newTab(folderStack + PathBreadcrumb(folder.id, folder.name), activate = false) }
    } else null

    fun revealFromGalleryOrLibrary(file: FileStat) {
        if (snapshot.galleryActive) {
            val path = gallery.pathTo(file.parentId)
            gallery.close()
            if (path != null) {
                navigation.updateFolderStack(path)
                state.highlight(setOf(file.id))
                return
            }
        }
        events.onRevealInDrive?.invoke(file) ?: state.revealInDrive(file)
    }

    val actionIntents = DriveItemActionIntents(
        desktop = desktop,
        isWorkspaceBrowser = snapshot.isWorkspaceBrowser,
        galleryActive = snapshot.galleryActive,
        galleryShowImages = snapshot.galleryShowImages,
        isSpoilerBlurEnabled = snapshot.isSpoilerBlurEnabled,
        feedSupported = snapshot.feedBlockedReason == null && platform.videoPreview != null,
        hasExternalPlayer = platform.externalPlayer != null,
        activeFolderId = activeFolderId,
        folderStack = folderStack,
        displayedFiles = displayedFiles,
        vaultedFolders = snapshot.vaultedFolders,
        fullyVaultedFolders = snapshot.fullyVaultedFolders,
        pinnedFolders = snapshot.pinnedFolders,
        onDownload = events.onRequestDownload,
        onExtractArchives = events.onExtractArchives,
        onExtractFromArchive = events.onExtractFromArchive,
        onShare = events.onRequestShare,
        onRename = events.onRequestRename,
        onBatchRename = events.onRequestBatchRename,
        onNameByCode = events.onRequestNameByCode,
        onMove = events.onRequestMove,
        onCopy = events.onRequestCopy,
        onVault = events.onRequestVault,
        onRestoreVaultFolder = events.onRequestRestoreVaultFolder,
        onRestoreVaultItems = events.onRestoreVaultItems,
        onLibraryConfirm = events.onRequestLibraryConfirm,
        onFindDuplicates = ::findDuplicates,
        onOpenSpaceMap = events.onOpenSpaceMap,
        onOpenSegmentDownload = events.onOpenSegmentDownload,
        onPrefetchQualities = events.onPrefetchQualities,
        onCopySource = events.onCopySource,
        onOpenSourceUrl = platform::openUrl,
        onCopyDownloadLink = events.onCopyDownloadLink,
        onOpenInExternalPlayer = events.onOpenInExternalPlayer,
        onOpenInNewTab = openInNewTab,
        onTogglePin = events.onTogglePinFolder,
        onPlayVideo = events.onNavigateToPlayer,
        onPreviewImage = events.onRequestImagePreview,
        onOpenSelectedGallery = events.onOpenSelectedGallery,
        onOpenFolderInGallery = { folder ->
            val path = folderStack + PathBreadcrumb(folder.id, folder.name)
            state.openFolder(folder.id, folder.name)
            gallery.open(path, showImages = snapshot.galleryShowImages)
        },
        onOpenSelectedFeed = events.onOpenSelectedFeed?.let { { openSelectedFeed(it) } },
        onOpenFolderInFeed = changeFeedShown?.let {
            { folder ->
                state.openFolder(folder.id, folder.name)
                scope.launch {
                    withFrameNanos { }
                    latestChangeFeedShown?.invoke(true)
                }
            }
        },
        onRevealFromGalleryOrLibrary = ::revealFromGalleryOrLibrary,
        onRevealLocalInFileManager = events.onRevealLocalInFileManager,
        onUnmountLocal = events.onUnmountLocalFolder,
    )
    val latestIntents by rememberUpdatedState(actionIntents)
    val latestEvents by rememberUpdatedState(events)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestShowProperties by rememberUpdatedState<(List<FileStat>, PanelAnchor) -> Unit>(::showProperties)

    fun targetsOf(file: FileStat): List<FileStat> =
        if (state.isSelectionMode && file.id in state.selectedFileIds && state.selectedFileIds.size > 1) {
            latestIntents.displayedFiles.filter { it.id in state.selectedFileIds }
        } else listOf(file)

    fun itemActions(file: FileStat, targets: List<FileStat>, fromMenu: Boolean): List<SheetAction> =
        buildDriveOperations(state, file, targets, latestIntents) + DriveActions.properties {
            latestShowProperties(targets, if (fromMenu) propertiesAnchors.fromMenu(file.id) else propertiesAnchors.beside(file.id))
        }

    val openLeafFile: (FileStat) -> Unit = { file ->
        val currentFiles = latestIntents.displayedFiles
        when {
            file.isImportPreview && file.hash.isBlank() -> scope.launch {
                snackbarHostState.showSnackbar(
                    tr("未收录的链接项目需先通过离线下载保存至网盘", "Save unindexed link items via cloud download before opening", "未収録のリンク項目は、先にクラウドダウンロードでドライブに保存してください", "미등록 링크 항목은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요", "未收錄的連結項目需先透過離線下載儲存至雲端硬碟"),
                    withDismissAction = true,
                )
            }
            file.isPlayableVideo() -> latestEvents.onNavigateToPlayer(file, currentFiles.filter { it.isPlayableVideo() && (!it.isImportPreview || it.hash.isNotBlank()) })
            file.isPreviewableImage() && file.previewImageCoverUrl().isNotBlank() -> latestEvents.onRequestImagePreview(file)
            file.isLocal -> platform.localFiles.openExternally(file.params["local_path"].orEmpty(), false)
            else -> latestEvents.onRequestDownload(listOf(file), false)
        }
    }
    val latestOpenLeafFile by rememberUpdatedState(openLeafFile)
    val latestItemActions by rememberUpdatedState<(FileStat, List<FileStat>, Boolean) -> List<SheetAction>>(::itemActions)

    val callbacks = remember(state) {
        DriveItemCallbacks(
            onOpen = { file ->
                when {
                    state.libraryView == DriveLibrary.TRASH -> latestEvents.onOpenSingleItemSheet(file)
                    file.isFolder -> state.openFolder(file.id, file.name)
                    file.isUploading -> scope.launch {
                        snackbarHostState.showSnackbar(tr("文件仍在上传", "File is still uploading", "ファイルはアップロード中です", "파일 업로드 중입니다", "檔案仍在上傳"), withDismissAction = true)
                    }
                    file.isExtractableArchive && !file.isLocal && !file.isVaulted && !ArchiveEntryId.isEntry(file.id) ->
                        taskSlot.claim(tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"), tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"), task = null, conflictsWith = setOf(TaskSlot.Task.FEED)) { state.openArchive(file) }
                    ArchiveEntryId.isEntry(file.id) -> scope.launch { state.prepareArchiveEntry(file)?.let(latestOpenLeafFile) }
                    else -> latestOpenLeafFile(file)
                }
            },
            onRename = { file ->
                if (!file.isLocal && !file.isUploading && !file.isImportPreview && state.libraryView != DriveLibrary.TRASH) {
                    latestEvents.onRequestRename(file)
                }
            },
            onMore = { latestEvents.onOpenSingleItemSheet(it) },
            onProperties = { latestShowProperties(targetsOf(it), propertiesAnchors.beside(it.id)) },
            onPlaced = { file, coordinates -> propertiesAnchors.placed(file.id, coordinates) },
            onStartSelection = { state.enterSelection(it.id) },
            onLongClick = { file ->
                if (state.libraryView == DriveLibrary.DUPLICATES && file.isFolder) latestEvents.onOpenSingleItemSheet(file)
                else if (state.isSelectionMode) state.setSelected(file.id, file.id !in state.selectedFileIds)
                else state.enterSelection(file.id)
            },
            onSelect = { file, selected -> state.setSelected(file.id, selected) },
            onToggleSelect = { id ->
                if (latestIntents.galleryActive) {
                    val select = id !in state.selectedFileIds
                    state.setSelected(id, select)
                    if (!select && state.selectedFileIds.isEmpty()) state.exitSelection()
                } else state.toggleSelected(id)
            },
            onExtendSelect = { id ->
                if (latestIntents.galleryActive) {
                    val files = latestIntents.displayedFiles
                    val to = files.indexOfFirst { it.id == id }
                    val anchorId = state.selectedFileIds.lastOrNull() ?: commandFile?.id
                    val from = anchorId?.let { anchor -> files.indexOfFirst { it.id == anchor } } ?: -1
                    if (to >= 0 && from >= 0) files.subList(minOf(from, to), maxOf(from, to) + 1).forEach { state.setSelected(it.id, true) }
                    else state.setSelected(id, true)
                } else state.selectRange(id)
            },
            onBoxSelect = { base, boxed ->
                if (commandFile != null || focusedFile != null) {
                    commandFile = null
                    focusedFile = null
                    runCatching { shortcutFocus.requestFocus() }
                }
                state.selectBoxed(base, boxed)
            },
            onMiddleClick = { file ->
                if (file.isFolder && !file.isImportPreview && state.libraryView != DriveLibrary.TRASH) latestIntents.onOpenInNewTab?.invoke(file)
            },
            dragPayload = dragPayload@{ file ->
                if (state.libraryView == DriveLibrary.TRASH || state.archiveView != null) return@dragPayload null
                val batch = if (state.isSelectionMode && file.id in state.selectedFileIds) {
                    latestIntents.displayedFiles.filter { it.id in state.selectedFileIds }
                } else {
                    listOf(file)
                }.filterNot { it.isLocal || it.isUploading || it.isVaulted || it.isImportPreview }
                if (batch.isEmpty()) null else FileDragPayload(
                    ids = batch.map { it.id },
                    parentIds = batch.mapTo(HashSet()) { it.parentId },
                    label = batch.singleOrNull()?.let { state.itemName(it).headingText }
                        ?: tr("${batch.size} 项", enCount(batch.size, "item", "items"), "${batch.size} 項目", "${batch.size}개 항목", "${batch.size} 個項目"),
                    perform = { target, copy ->
                        val ids = batch.map { it.id }
                        if (copy) state.copy(ids, target.id, target.name) else state.move(ids, target.id, target.name)
                    },
                )
            },
            onBackgroundClick = {
                commandFile = null
                focusedFile = null
                latestEvents.onClearLocatedFile()
                if (state.isSelectionMode) state.exitSelection()
                runCatching { shortcutFocus.requestFocus() }
            },
            onFocusChanged = { file, focused ->
                if (focused) {
                    if (latestSnapshot.locatedFileId != file.id) latestEvents.onClearLocatedFile()
                    focusedFile = file
                    if (!pointerSource.isTouchLike) commandFile = file
                } else if (focusedFile?.id == file.id) {
                    focusedFile = null
                }
            },
            contextActions = { file -> latestItemActions(file, targetsOf(file), true) },
            onToggleSection = state::toggleSection,
            onFolderVisible = state::onFolderVisible,
        )
    }

    fun openFromMap(parent: List<PathBreadcrumb>, file: FileStat) {
        scope.launch {
            val folderId = parent.last().id
            if (navigation.folderStackFlow.value.lastOrNull()?.id != folderId) navigation.updateFolderStack(parent)
            val listed = withTimeoutOrNull(MapOpenTimeoutMs) {
                snapshotFlow { state.files.takeIf { state.loadedFolderId == folderId && !state.isLoading }?.firstOrNull { it.id == file.id } }
                    .first { it != null }
            }
            if (listed == null) {
                snackbarHostState.showSnackbar(tr("在所在文件夹中未找到此文件", "File not found in its containing folder", "保存先フォルダー内にファイルが見つかりません", "해당 폴더에서 파일을 찾을 수 없습니다", "在所在資料夾中找不到此檔案"), withDismissAction = true)
                return@launch
            }
            state.highlight(setOf(listed.id))
            callbacks.onOpen(listed)
        }
    }

    val foldBanner = foldBannerOrNull(state)
    val leadingItemCount = driveLeadingItemCount(foldBanner != null)
    val currentSection by remember(gridState, leadingItemCount) {
        derivedStateOf {
            val headers = state.sectionHeaders
            val first = gridState.firstVisibleItemIndex - leadingItemCount
            (headers.lastOrNull { it.index <= first } ?: headers.firstOrNull())?.value?.menuLabel
        }
    }
    fun jumpToSection(position: Int) {
        val target = state.sectionHeaders.getOrNull(position) ?: return
        state.expandSection(target.value.blockId)
        scope.launch { gridState.animateScrollToItem(leadingItemCount + target.index) }
    }

    LaunchedEffect(activeFolderId, pointerSource.isTouchLike) {
        if (!pointerSource.isTouchLike) runCatching { shortcutFocus.requestFocus() }
    }
    LaunchedEffect(state.isSelectionMode) {
        if (!state.isSelectionMode) {
            commandFile = null
            focusedFile = null
            if (!pointerSource.isTouchLike) runCatching { shortcutFocus.requestFocus() }
        }
    }
    BackHandler(enabled = snapshot.acceptsNavigation && !pointerSource.isTouchLike && commandFile != null && !state.isSelectionMode) {
        commandFile = null
        focusedFile = null
        runCatching { shortcutFocus.requestFocus() }
    }
    BackHandler(enabled = snapshot.acceptsNavigation && state.isSelectionMode) { state.exitSelection() }

    val showExtensions = LocalShowExtensions.current
    val currentPlace = commandPlaceOf(state, folderStack.size)
    val targetCommands = itemCommands(currentPlace, commandTargets, snapshot.fullyVaultedFolders)
    val commands = driveCommands(
        CommandInputs(
            place = currentPlace,
            atRoot = folderStack.size == 1 && activeFolderId.isEmpty(),
            selecting = state.isSelectionMode,
            clipboardFull = snapshot.clipboard != null,
            itemCount = displayedFiles.size,
            allSelected = displayedFiles.isNotEmpty() && displayedFiles.all { it.id in selectedIdSet },
            typeCount = state.availableTypes.size,
            filtering = state.typeFilter != null,
            feedSupported = platform.videoPreview != null && events.onFeedShownChange != null,
            nameParsing = state.isNameParsing,
            selectedVideoCount = if (events.onOpenSelectedFeed != null) selectedFeedVideos.size else 0,
            selectedGalleryCount = selectedGalleryMedia.size,
        ),
    ).let { if (snapshot.isWorkspaceBrowser) it else it.copy(findDuplicates = false) }
        .let { if (snapshot.galleryActive) it.copy(sort = false, filter = false) else it }

    val placePrimaryAction: SheetAction? = when {
        state.isSelectionMode && !pathInTopBar -> null
        inDuplicates -> duplicateState?.takeIf {
            duplicateReport.kind == DuplicateKind.IDENTICAL || duplicateReport.keepRule != DuplicateKeepRule.RECOMMENDED
        }?.let { finder -> duplicateReport.smartSelectIds(finder) }
            ?.intersect(displayedFiles.filterNot { it.isFolder }.mapTo(HashSet()) { it.id })
            ?.takeIf { it.isNotEmpty() }?.let { suggested ->
                SheetAction(Icons.Outlined.Checklist, tr("选择建议清理的 ${suggested.size} 项", "Select ${suggested.size} suggested items to clean up", "整理推奨の ${suggested.size} 項目を選択", "정리 권장 ${suggested.size}개 항목 선택", "選取建議清理的 ${suggested.size} 個項目"), { state.selectOnly(suggested) })
            }
        state.archiveView != null && displayedFiles.isNotEmpty() && !state.isArchiveInImportPreview ->
            SheetAction(Icons.Outlined.Unarchive, tr("全部解压", "Extract all", "すべて解凍", "모두 압축 해제", "全部解壓縮"), { events.onExtractFromArchive(emptyList()) })
        commands.emptyPlace -> libraryPageActions(libraryView, state.files.isEmpty(), events.onRequestLibraryConfirm) { state.files.map { it.id } }.firstOrNull()
        else -> null
    }

    val addressBar: @Composable () -> Unit = {
        DriveAddressField(
            state = state,
            navigation = navigation,
            workspace = workspace,
            folderStack = folderStack,
            pinnedFolders = snapshot.pinnedFolders,
            addressDestinations = snapshot.addressDestinations,
            editRequests = addressEditRequests,
            onOpenLink = events.onOpenLink,
        )
    }

    DrivePaletteContribution(
        state = state,
        shortcuts = platform.shortcutModifier,
        isWorkspaceBrowser = snapshot.isWorkspaceBrowser,
        pathInTopBar = pathInTopBar,
        folderStack = folderStack,
        activeFolder = activeFolder,
        viewMode = snapshot.viewMode,
        folderMapToggleLabel = folderMap.toggleLabel,
        mountAction = snapshot.mountAction,
        commandTargets = commandTargets,
        targetCommands = targetCommands,
        tabsAvailable = tabsAvailable,
        tabs = snapshot.tabs,
        activeTabId = snapshot.activeTabId,
        onNewFolder = events.onRequestNewFolder,
        onPickFiles = events.onPickFiles,
        onPickFolder = events.onPickFolder,
        onToggleFolderMap = folderMap::toggle,
        onEditAddress = ::editAddress,
        onFocusSearch = { searchFocusRequests++ },
        onShowProperties = {
            if (commandTargets.isNotEmpty()) showProperties(commandTargets, PanelAnchor.Centered) else showFolderProperties()
        },
        onSelectViewMode = events.onSelectViewMode,
        onSelectAll = ::selectAllDisplayed,
        onRefresh = events.onRefresh,
        onNewRootTab = ::newRootTab,
        onCloseTab = ::closeTab,
        onSelectTab = ::selectTab,
        onFindDuplicates = { findDuplicates(activeFolder) },
        onOpenSpaceMap = events.onOpenSpaceMap,
        onNameTargetsByCode = events.onRequestNameByCode,
        onCanonicalCurrentFolder = { events.onRequestCanonicalFolder(activeFolder) },
    )

    val topBarScrollBehavior = rememberListScrollTint {
        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
    }
    val onDuplicateKindChange: (DuplicateKind) -> Unit = { kind ->
        state.exitSelection()
        duplicateReport.selectKind(kind)
        scope.launch { gridState.scrollToItem(0) }
    }

    val taskSheet = rememberDriveTaskSheet(
        isWorkspaceBrowser = snapshot.isWorkspaceBrowser,
        addLinkInSidePanel = snapshot.addLinkInSidePanel,
        instantState = instantState,
        addLink = addLink,
        desktop = desktop,
        onTargetPicked = navigation::updateFolderStack,
        onPreview = { fileId, fileName -> events.onNavigateToPlayer(FileStat(id = fileId, name = fileName), emptyList()) },
    )
    AddLinkBlockedAction(addLink)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pageFocusTarget(shortcutFocus)
            .onPreviewKeyEvent(propertyDetails::handleKey)
            .onKeyEvent { event ->
                handleDriveKeyShortcut(
                    event = event,
                    shortcutModifier = platform.shortcutModifier,
                    state = state,
                    gridState = gridState,
                    focusManager = focusManager,
                    pathInTopBar = pathInTopBar,
                    folderStackSize = folderStack.size,
                    focusedFile = focusedFile,
                    commandFile = commandFile,
                    commandTargets = commandTargets,
                    displayedFiles = displayedFiles,
                    hasClipboard = snapshot.clipboard != null,
                    tabsAvailable = tabsAvailable,
                    canCloseActiveTab = contexts?.canCloseContext(snapshot.activeTabId) ?: desktopWorkspace?.canCloseContext(snapshot.activeTabId) ?: false,
                    onFocusFirstVisible = { keyboardFocusTarget = it },
                    onOpenItemActions = events.onOpenSingleItemSheet,
                    onOpenItem = callbacks.onOpen,
                    onStartRename = events.onRequestRename,
                    onBatchRename = { events.onRequestBatchRename(it, false) },
                    onLibraryConfirm = events.onRequestLibraryConfirm,
                    onEditAddress = ::editAddress,
                    onToggleFolderMap = folderMap::toggle,
                    onFocusDesktopSearch = { searchFocusRequests++ },
                    onNewRootTab = ::newRootTab,
                    onCloseActiveTab = { closeTab(snapshot.activeTabId) },
                    onCycleTab = ::cycleTab,
                    onShowTargetProperties = { nearId -> showProperties(commandTargets, propertiesAnchors.beside(nearId)) },
                    onShowFolderProperties = ::showFolderProperties,
                    onRefresh = events.onRefresh,
                    onSelectAll = ::selectAllDisplayed,
                    onUndoLast = { state.acceptsActions && taskSlot.requestUndoLast(driveRepo.changes, state.fileActions) },
                    onBackInBrowser = ::backInBrowser,
                )
            }
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        when {
                            event.buttons.isBackPressed -> backInBrowser()
                            event.buttons.isForwardPressed -> state.goForward()
                        }
                    }
                }
            },
    ) {
        TaskSheetScaffold(taskSheet) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = if (pathInTopBar) MaterialTheme.colorScheme.frame else MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    if (pathInTopBar) {
                        DriveDesktopHeader(
                            state = state,
                            shortcuts = platform.shortcutModifier,
                            isWorkspaceBrowser = snapshot.isWorkspaceBrowser,
                            showLegacyTabBar = tabsAvailable && contexts == null,
                            tabs = snapshot.tabs,
                            activeTabId = snapshot.activeTabId,
                            feedTabId = snapshot.feedTabId,
                            hasTabBarAbove = hasTabBarAbove,
                            firstTabActive = snapshot.firstTabActive,
                            folderStack = folderStack,
                            activeFolder = activeFolder,
                            folderMapClosed = folderMap.presence == FolderMapPresence.Closed,
                            commands = commands,
                            mountAction = snapshot.mountAction,
                            displayedFiles = displayedFiles,
                            selectedIdSet = selectedIdSet,
                            viewMode = snapshot.viewMode,
                            tileSize = snapshot.tileSize,
                            galleryActive = snapshot.galleryActive,
                            galleryShowImages = snapshot.galleryShowImages,
                            currentSection = currentSection,
                            feedShown = snapshot.feedShown,
                            feedStashed = snapshot.feedStashed,
                            feedBlockedReason = snapshot.feedBlockedReason,
                            addLinkHasCollapsedWork = addLink.hasCollapsedWork,
                            placePrimaryAction = placePrimaryAction,
                            inDuplicates = inDuplicates,
                            duplicateState = duplicateState,
                            duplicateReport = duplicateReport,
                            searchFocusRequests = searchFocusRequests,
                            addressBar = addressBar,
                            onSelectTab = ::selectTab,
                            onCloseTab = ::closeTab,
                            onNewRootTab = ::newRootTab,
                            onBack = { backInBrowser() },
                            onOpenFolderMap = { folderMap.dispatch(FolderMapEvent.Open) },
                            onNewFolder = events.onRequestNewFolder,
                            onPickFiles = events.onPickFiles,
                            onPickFolder = events.onPickFolder,
                            onSelectAll = ::selectAllDisplayed,
                            onFindDuplicates = { findDuplicates(activeFolder) },
                            onCanonicalNaming = { events.onRequestCanonicalFolder(activeFolder) },
                            onJumpToSection = ::jumpToSection,
                            onRefresh = events.onRefresh,
                            onViewModeChange = events.onSelectViewMode,
                            onTileSizeChange = events.onSelectTileSize,
                            onFeedShownChange = changeFeedShown,
                            onGalleryShownChange = changeGalleryShown,
                            onGalleryShowImagesChange = events.onSetGalleryShowImages,
                            onResumeFeed = events.onResumeFeed,
                            onOpenAddLink = events.onOpenAddLink,
                            onDuplicateKindChange = onDuplicateKindChange,
                        )
                    } else {
                        DriveCompactHeader(
                            state = state,
                            shortcuts = platform.shortcutModifier,
                            scrollBehavior = topBarScrollBehavior,
                            folderStack = folderStack,
                            rootDepth = navigation.rootDepth,
                            rootTitle = snapshot.rootTitle,
                            activeFolder = activeFolder,
                            atOriginBoundary = atOriginBoundary,
                            inDuplicates = inDuplicates,
                            duplicateState = duplicateState,
                            duplicateReport = duplicateReport,
                            commands = commands,
                            selectedCount = commandTargets.size,
                            selectedIdSet = selectedIdSet,
                            currentSection = currentSection,
                            galleryActive = snapshot.galleryActive,
                            galleryShowImages = snapshot.galleryShowImages,
                            feedShown = snapshot.feedShown,
                            feedStashed = snapshot.feedStashed,
                            hasFeedEntry = events.onFeedShownChange != null,
                            tileSize = snapshot.tileSize,
                            onSelectAll = ::selectAllDisplayed,
                            onOpenSelectionActions = events.onOpenSelectionSheet,
                            onCloseSearch = state::closeSearch,
                            onJumpToSection = ::jumpToSection,
                            onCloseContext = events.onCloseContext,
                            onEndDuplicateSession = duplicateSession::end,
                            onLeaveLibrary = ::leaveLibrary,
                            onFindDuplicates = { findDuplicates(activeFolder) },
                            onCanonicalNaming = { events.onRequestCanonicalFolder(activeFolder) },
                            onDuplicateKindChange = onDuplicateKindChange,
                            onGalleryShownChange = changeGalleryShown,
                            onFeedShownChange = changeFeedShown,
                            onTileSizeChange = events.onSelectTileSize,
                            onGalleryShowImagesChange = events.onSetGalleryShowImages,
                        )
                    }
                },
                bottomBar = {
                    if (!pathInTopBar && state.isSelectionMode) {
                        DriveCompactSelectionBar(
                            inTrash = inTrash,
                            hasTargets = commandTargets.isNotEmpty(),
                            availableActions = if (commandTargets.isNotEmpty()) {
                                buildDriveOperations(state, commandTargets.first(), commandTargets, actionIntents)
                            } else emptyList(),
                            onOpenSelectionActions = events.onOpenSelectionSheet,
                        )
                    }
                },
                floatingActionButton = {
                    if (!pathInTopBar) {
                        DriveAddFabMenu(
                            acceptsNavigation = snapshot.acceptsNavigation,
                            isSelectionMode = state.isSelectionMode,
                            commands = commands,
                            placePrimaryAction = placePrimaryAction,
                            hasActiveInstantDraft = instantState != null,
                            hasCollapsedAddLinkWork = addLink.hasCollapsedWork,
                            mountAction = snapshot.mountAction,
                            onPaste = state::paste,
                            onOpenAddLink = events.onOpenAddLink,
                            onNewFolder = events.onRequestNewFolder,
                            onPickFiles = events.onPickFiles,
                        )
                    }
                },
            ) { innerPadding ->
                DriveWorkspaceArea(
                    state = state,
                    innerPadding = innerPadding,
                    pathInTopBar = pathInTopBar,
                    folderStack = folderStack,
                    folderMap = folderMap,
                    propertyDetails = propertyDetails,
                    showHomeInMap = commands.home,
                    canLocateFromProperties = libraryView != null || state.searchQuery.isNotBlank() || state.isGlobalSearchActive || snapshot.galleryActive,
                    isSpoilerBlurEnabled = snapshot.isSpoilerBlurEnabled,
                    onUpdateFolderStack = navigation::updateFolderStack,
                    onOpenFromMap = ::openFromMap,
                    onGoHome = ::goHome,
                    onLocateFromProperties = ::revealFromGalleryOrLibrary,
                    onOpenSourceLink = events.onOpenLink,
                    contentFrame = contentFrame,
                ) { listAreaWidth ->
                    DriveContentBody(
                        state = state,
                        driveRepo = driveRepo,
                        gallery = gallery,
                        gridState = gridState,
                        innerPadding = innerPadding,
                        listAreaWidth = listAreaWidth,
                        pathInTopBar = pathInTopBar,
                        canLeaveDuplicates = twoPane || contexts != null,
                        addLinkSideOpen = snapshot.addLinkInSidePanel && instantState != null && addLink.isExpanded,
                        folderStack = folderStack,
                        activeFolderId = activeFolderId,
                        clipboard = snapshot.clipboard,
                        canPaste = commands.paste,
                        viewMode = snapshot.viewMode,
                        tileSize = snapshot.tileSize,
                        galleryActive = snapshot.galleryActive,
                        galleryShowImages = snapshot.galleryShowImages,
                        isSpoilerBlurEnabled = snapshot.isSpoilerBlurEnabled,
                        itemMoreButton = pointerSource.isTouchLike,
                        inDuplicates = inDuplicates,
                        duplicateState = duplicateState,
                        duplicateReport = duplicateReport,
                        displayedFiles = displayedFiles,
                        selectedIdSet = selectedIdSet,
                        rowNotes = rowNotes,
                        vaultedFolders = snapshot.vaultedFolders,
                        locatedFileId = snapshot.locatedFileId,
                        activeCommandItemId = commandFile?.id,
                        keyboardFocusTarget = keyboardFocusTarget,
                        callbacks = callbacks,
                        foldBanner = foldBanner,
                        backgroundActions = {
                            buildDriveBackgroundActions(
                                state = state,
                                folderStackSize = folderStack.size,
                                viewMode = snapshot.viewMode,
                                galleryActive = snapshot.galleryActive,
                                galleryShowImages = snapshot.galleryShowImages,
                                showExtensions = showExtensions,
                                hasClipboard = snapshot.clipboard != null,
                                mountAction = snapshot.mountAction,
                                onSelectViewMode = events.onSelectViewMode,
                                onToggleGallery = changeGalleryShown,
                                onToggleGalleryImages = events.onSetGalleryShowImages,
                                onToggleExtensions = events.onSetShowExtensions,
                                onRefresh = events.onRefresh,
                                onNewFolder = events.onRequestNewFolder,
                                onPickFiles = events.onPickFiles,
                                onPickFolder = events.onPickFolder,
                                onOpenAddLink = events.onOpenAddLink,
                                onSelectAll = ::selectAllDisplayed,
                                onLibraryConfirm = events.onRequestLibraryConfirm,
                                onShowFolderProperties = ::showFolderProperties,
                            )
                        },
                        onSelectViewMode = events.onSelectViewMode,
                        onSelectTileSize = events.onSelectTileSize,
                        onStepZoom = events.onStepZoom,
                        onGalleryShowImagesChange = events.onSetGalleryShowImages,
                        onRefresh = events.onRefresh,
                        onDuplicateKindChange = onDuplicateKindChange,
                        onEndDuplicates = events.onCloseContext ?: duplicateSession::end,
                        onLeaveDuplicates = events.onCloseContext ?: ::goHome,
                        onKeyboardFocusMoved = { keyboardFocusTarget = null },
                    )
                }
            }
        }
    }

    DriveSheetHost(
        sheet = snapshot.activeSheet,
        state = state,
        driveRepo = driveRepo,
        inTrash = inTrash,
        pathInTopBar = pathInTopBar,
        displayedFiles = displayedFiles,
        rowNotes = rowNotes,
        folderStack = folderStack,
        folderMap = folderMap,
        addressBar = addressBar,
        itemActionsOf = { target -> itemActions(target, listOf(target), fromMenu = false) },
        selectionActionsOf = { files ->
            buildSelectionSheetActions(state, files, actionIntents) {
                showProperties(files, PanelAnchor.Centered)
            }
        },
        onCopySource = events.onCopySource,
        onUpdateFolderStack = navigation::updateFolderStack,
        onOpenFromMap = ::openFromMap,
        onDismissSheet = events.onDismissSheet,
    )
}

@Composable
private fun DriveLifecycleEffects(
    state: DriveScreenState,
    browserSession: DriveBrowserSession?,
    isWorkspaceBrowser: Boolean,
    currentFolderId: String,
    navigation: DriveNavigation,
    sessionManager: PikoUserPreferences,
    loadedFolderId: String?,
    gridState: LazyGridState,
    scrollToTopRequests: Int,
    acceptsNavigation: Boolean,
    archiveSession: ArchiveExtractSession,
    vaultSession: FolderVaultSession,
    instantState: InstantSheetState?,
    addLink: AddLinkViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenTransfers: () -> Unit,
    onNavigateToPlayer: (FileStat) -> Unit,
    onLocatedFileChanged: (String?) -> Unit,
) {
    LaunchedEffect(state) {
        state.pasteNotices.collect { notice ->
            val result = snackbarHostState.showSnackbar(
                notice.message,
                actionLabel = tr("返回原文件夹", "Return to source folder", "元のフォルダーに戻る", "원래 폴더로 돌아가기", "返回原資料夾"),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed && state.acceptsActions) {
                navigation.updateFolderStack(notice.originStack)
            }
        }
    }

    LaunchedEffect(state) {
        if (browserSession?.initialized == true) return@LaunchedEffect
        if (!isWorkspaceBrowser) {
            state.load()
            browserSession?.initialized = true
            return@LaunchedEffect
        }
        val startStack = navigation.folderStackFlow.value
        val legacyStack = if (startStack.size == 1 && startStack[0].id.isEmpty() && currentFolderId.isEmpty()) {
            val (lastId, lastName, serialized) = sessionManager.getLastFolder()
            when {
                lastId.isEmpty() -> emptyList()
                serialized.isEmpty() -> listOf(PathBreadcrumb(lastId, lastName))
                else -> LastFolderStack.decode(serialized).ifEmpty { listOf(PathBreadcrumb(lastId, lastName)) }
            }
        } else emptyList()
        if (legacyStack.isNotEmpty()) sessionManager.saveLastFolder("", "", "")

        when {
            currentFolderId.isEmpty() && state.restoreLocation() -> Unit
            legacyStack.isNotEmpty() && navigation.folderStackFlow.value === startStack && state.highlightedFileIds.isEmpty() ->
                state.restoreFolderStack(legacyStack)
            else -> state.load()
        }
        browserSession?.initialized = true
    }

    LaunchedEffect(state.preparingEntryId) {
        if (state.preparingEntryId == null) return@LaunchedEffect
        delay(400)
        snackbarHostState.showSnackbar(
            tr("正在读取压缩包内容…", "Reading archive contents…", "圧縮ファイルの内容を読み込み中…", "압축 파일 내용 읽는 중…", "正在讀取壓縮檔內容…"),
            duration = SnackbarDuration.Indefinite,
        )
    }

    val latestNavigateToPlayer by rememberUpdatedState(onNavigateToPlayer)
    LaunchedEffect(instantState) {
        if (instantState == null) return@LaunchedEffect
        launch {
            instantState.previewRequests.collect { req ->
                if (!addLink.isExpanded) latestNavigateToPlayer(FileStat(id = req.fileId, name = req.fileName))
            }
        }
        launch {
            instantState.messages.collect { msg ->
                if (!addLink.isExpanded) snackbarHostState.showSnackbar(msg, withDismissAction = true)
            }
        }
    }

    val latestAcceptsNavigation by rememberUpdatedState(acceptsNavigation)
    val openTransfers by rememberUpdatedState(onOpenTransfers)
    val latestOnLocated by rememberUpdatedState(onLocatedFileChanged)
    LaunchedEffect(Unit) {
        fun announce(message: String, stillRunning: () -> Boolean) {
            val shownJob = launch {
                val result = snackbarHostState.showSnackbar(message, actionLabel = tr("查看", "View", "表示", "보기", "檢視"), withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) openTransfers()
            }
            launch {
                snapshotFlow { stillRunning() }.first { !it }
                shownJob.cancel()
            }
        }
        var wasExtracting = archiveSession.jobs.isNotEmpty()
        var wasVaulting = vaultSession.isRunning
        snapshotFlow { archiveSession.jobs.isNotEmpty() to vaultSession.isRunning }.collect { (extracting, vaulting) ->
            if (latestAcceptsNavigation) {
                if (extracting && !wasExtracting) {
                    announce(tr("已开始解压", "Extraction started", "解凍を開始しました", "압축 해제 시작됨", "已開始解壓縮")) {
                        archiveSession.jobs.isNotEmpty()
                    }
                }
                if (vaulting && !wasVaulting) {
                    val label = if (vaultSession.restoreProgress != null) {
                        tr("已开始取消归档", "Unarchiving started", "アーカイブの解除を開始しました", "보관 해제 시작됨", "已開始取消歸檔")
                    } else {
                        tr("已开始归档", "Archiving started", "アーカイブを開始しました", "보관 시작됨", "已開始歸檔")
                    }
                    announce(label) { vaultSession.isRunning }
                }
            }
            wasExtracting = extracting
            wasVaulting = vaulting
        }
    }

    LaunchedEffect(gridState) {
        val folderId = loadedFolderId ?: return@LaunchedEffect
        snapshotFlow { ScrollAnchor(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { navigation.saveScrollAnchor(folderId, it) }
    }
    val latestScrollToTop by rememberUpdatedState(scrollToTopRequests)
    LaunchedEffect(gridState) {
        snapshotFlow { latestScrollToTop }.drop(1).collect { gridState.animateScrollToItem(0) }
    }

    val highlightedFileIds = state.highlightedFileIds
    LaunchedEffect(highlightedFileIds, state.highlightRevision) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        withTimeoutOrNull(HighlightAppearTimeoutMs) {
            snapshotFlow { state.isDisplayStructureReady && state.highlightedRowIds.isNotEmpty() }.first { it }
        }
        delay(8000)
        state.clearHighlight()
    }
    LaunchedEffect(highlightedFileIds, state.highlightRevision) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        val found = snapshotFlow {
            if (state.isDisplayStructureReady) state.highlightedRowIds.firstOrNull() else null
        }.first { it != null }
        latestOnLocated(found)
    }
}

@Composable
private fun rememberDriveTaskSheet(
    isWorkspaceBrowser: Boolean,
    addLinkInSidePanel: Boolean,
    instantState: InstantSheetState?,
    addLink: AddLinkViewModel,
    desktop: Boolean,
    onTargetPicked: (List<PathBreadcrumb>) -> Unit,
    onPreview: (String, String) -> Unit,
): TaskSheetModel? {
    val discardAddLink = rememberDiscardAddLink(addLink)
    return when {
        !isWorkspaceBrowser -> null
        addLinkInSidePanel -> null
        instantState != null -> TaskSheetModel(
            key = instantState,
            expanded = addLink.isExpanded,
            onExpandedChange = { expand -> if (expand) addLink.resume() else addLink.collapse() },
            header = {
                val summary = instantState.summary()
                TaskSheetHeader(summary.title, summary.status, DiscardAddLinkLabel, discardAddLink, closeEnabled = !summary.busy)
            },
            body = {
                InstantSheetContent(
                    state = instantState,
                    headerShown = true,
                    onTargetPicked = onTargetPicked,
                    onPreview = { fileId, fileName ->
                        if (!desktop) addLink.collapse()
                        onPreview(fileId, fileName)
                    },
                )
            },
        )
        else -> null
    }
}
