package dev.piko.ui.screens.drive

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
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
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocal
import dev.piko.shared.download.DriveDownloadFolderSource
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
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.FolderStructureMode
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.GallerySession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.isUploading
import dev.piko.ui.ImageViewerRequest
import dev.piko.ui.LocalDetachedImageViewer
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.ObserveMessages
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.PaletteItem
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.rememberDownloadLauncher
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.instant.DiscardAddLinkLabel
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.workbench.LocalFileWorkspace
import dev.piko.ui.workbench.LocalTaskSlot
import dev.piko.ui.workbench.LocalWorkspaceContexts
import dev.piko.ui.workbench.TaskSlot
import dev.piko.ui.workbench.WorkspaceViewModel
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val HighlightAppearTimeoutMs = 4000L
internal const val MapOpenTimeoutMs = 10_000L

/** 网盘页单向向下传递的不可变页面状态快照（遵循 Compose `architecture.md` 参数内聚规范）。 */
@Stable
internal data class DriveViewSnapshot(
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
internal class DriveViewEvents(
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
    val onOpenDocument: (FileStat) -> Unit,
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
    val onRequestFolderStructure: (List<FileStat>, FolderStructureMode) -> Unit,
)

@Stable
internal class DriveRuntimeContext(
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
    onOpenDocument: (file: FileStat) -> Unit = {},
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
                    DriveLibrary.DUPLICATES -> duplicateSession.state?.let(duplicateReport::listing)
                    else -> null
                }
            },
            archives = services.archiveBrowser,
            navigation = navigation,
            clipboard = workspace.clipboard,
            fileWriteSession = services.fileWrites,
            historyEditSession = services.historyEdits,
            vaultEditSession = services.vaultEntryEdits,
            folderVault = services.folderVaultSession,
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
    val openDocumentAction by rememberUpdatedState(onOpenDocument)
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
                is DriveUiEffect.EnqueueResolvedDownload -> {
                    downloads.downloadResolved(effect.files, effect.parentId) { notice ->
                        notice?.let(services::noticeOnTransfers)
                        openTransfers()
                    }
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
        onOpenDocument = openDocumentAction,
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
        onRequestImagePreview = run {
            val detachedImageViewer = LocalDetachedImageViewer.current
            val latestRevealInDrive by rememberUpdatedState(onRevealInDrive)
            if (detachedImageViewer != null) {
                { file ->
                    val peers = displayedFiles.filter { it.isPreviewableImage() && it.previewImageCoverUrl().isNotBlank() }
                    val images = if (peers.any { it.id == file.id }) peers else listOf(file)
                    val startIndex = images.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                    val locateCallback: ((FileStat) -> Unit)? = if (file.isLocal || file.isImportPreview || ArchiveEntryId.isEntry(file.id)) {
                        null
                    } else {
                        { target ->
                            if (gallery.shown && state.acceptsActions) {
                                val path = gallery.pathTo(target.parentId)
                                gallery.close()
                                if (path != null) {
                                    navigation.updateFolderStack(path)
                                    state.highlight(setOf(target.id))
                                } else {
                                    latestRevealInDrive?.invoke(target) ?: state.revealInDrive(target)
                                }
                            } else if (state.acceptsActions) {
                                latestRevealInDrive?.invoke(target) ?: state.revealInDrive(target)
                            } else {
                                latestRevealInDrive?.invoke(target)
                            }
                        }
                    }
                    detachedImageViewer(
                        ImageViewerRequest(
                            images = images,
                            initialIndex = startIndex,
                            onLocate = locateCallback,
                        ),
                    )
                }
            } else {
                viewModel::requestImagePreview
            }
        },
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
        onRequestFolderStructure = { folders, mode -> viewModel.requestFolderStructure(activeFolderId, folders, mode) },
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
        activeFolderName = activeFolder.localizedName,
        displayedFiles = displayedFiles,
        onDismiss = viewModel::dismissDialog,
        onConfirmNewFolder = viewModel::confirmNewFolder,
        onConfirmSingleRename = viewModel::confirmSingleRename,
        onBatchRenameFinished = viewModel::onBatchRenameFinished,
        onFolderStructureAvailability = { folders -> viewModel.folderStructureBlockReason(activeFolderId, folders) },
        onFolderStructureFinished = viewModel::onFolderStructureFinished,
        onShareCopied = viewModel::onShareCopied,
        onConfirmMove = viewModel::confirmMove,
        onConfirmCopy = viewModel::confirmCopy,
        onConfirmLibraryAction = viewModel::confirmLibraryAction,
        onConfirmCloseGallery = viewModel::confirmCloseGallery,
    )

    DriveSystemOverlays(
        state = state,
        segmentSession = services.segmentSession,
        downloads = downloads,
        onOpenTransfers = openTransfers,
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

    LaunchedEffect(gridState, loadedFolderId, navigation) {
        val folderId = loadedFolderId ?: return@LaunchedEffect
        var lastSaved: ScrollAnchor? = null
        var lastSaveMark: TimeMark? = null
        var wasScrolling = false
        var trailingJob: Job? = null
        fun flush(anchor: ScrollAnchor) {
            trailingJob?.cancel()
            trailingJob = null
            if (anchor != lastSaved) {
                lastSaved = anchor
                lastSaveMark = TimeSource.Monotonic.markNow()
                navigation.saveScrollAnchor(folderId, anchor)
            }
        }
        snapshotFlow {
            Triple(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
                gridState.isScrollInProgress,
            )
        }.collect { (index, offset, scrolling) ->
            val settled = wasScrolling && !scrolling
            wasScrolling = scrolling
            val anchor = ScrollAnchor(index, offset)
            if (anchor == lastSaved) return@collect
            val elapsedMs = lastSaveMark?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
            if (settled || elapsedMs >= 100L) {
                flush(anchor)
            } else if (trailingJob == null) {
                val remainingMs = (100L - elapsedMs).coerceAtLeast(16L)
                trailingJob = launch {
                    delay(remainingMs)
                    trailingJob = null
                    val latest = ScrollAnchor(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                    if (latest != lastSaved) {
                        lastSaved = latest
                        lastSaveMark = TimeSource.Monotonic.markNow()
                        navigation.saveScrollAnchor(folderId, latest)
                    }
                }
            }
        }
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
internal fun rememberDriveTaskSheet(
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
