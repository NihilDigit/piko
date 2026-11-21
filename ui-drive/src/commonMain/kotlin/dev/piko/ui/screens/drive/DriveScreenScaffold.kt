package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.Alignment
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
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.i18n.tr
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DuplicateKeepRule
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.upload.isUploading
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.DetailPanelState
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.LocalShowExtensions
import dev.piko.ui.components.PanelAnchor
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.rememberListScrollTint
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.instant.AddLinkBlockedAction
import dev.piko.ui.theme.frame
import dev.piko.ui.workbench.TaskSlot
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveScreenContent(
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

    val selectedIdSet = state.selectedIdSet
    val commandTargets: List<FileStat> by remember(state, displayedFiles) {
        derivedStateOf {
            if (state.isSelectionMode) displayedFiles.filter { it.id in state.selectedIdSet }
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
            if (ids.isNotEmpty() && state.selectedIdSet == ids) state.exitSelection() else state.selectOnly(ids)
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
        onOpenDocument = events.onOpenDocument,
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
        onFolderStructure = events.onRequestFolderStructure,
    )

    val (callbacks, itemActions) = rememberDriveItemCallbacks(
        state = state,
        actionIntents = actionIntents,
        events = events,
        snapshot = snapshot,
        taskSlot = taskSlot,
        snackbarHostState = snackbarHostState,
        propertiesAnchors = propertiesAnchors,
        pointerSource = pointerSource,
        shortcutFocus = shortcutFocus,
        focusedFileProvider = { focusedFile },
        commandFileProvider = { commandFile },
        onFocusedFileChange = { focusedFile = it },
        onCommandFileChange = { commandFile = it },
        onShowProperties = ::showProperties,
    )

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
    val allSelected by remember(state, displayedFiles) {
        derivedStateOf { displayedFiles.isNotEmpty() && displayedFiles.all { it.id in state.selectedIdSet } }
    }
    val currentPlace = commandPlaceOf(state, folderStack.size)
    val targetCommands = itemCommands(currentPlace, commandTargets, snapshot.fullyVaultedFolders)
    val commands = driveCommands(
        CommandInputs(
            place = currentPlace,
            atRoot = folderStack.size == 1 && activeFolderId.isEmpty(),
            selecting = state.isSelectionMode,
            clipboardFull = snapshot.clipboard != null,
            itemCount = displayedFiles.size,
            allSelected = allSelected,
            typeCount = state.availableTypes.size,
            filtering = state.typeFilter != null,
            feedSupported = platform.videoPreview != null && events.onFeedShownChange != null,
            nameParsing = state.isNameParsing,
            selectedVideoCount = if (events.onOpenSelectedFeed != null) selectedFeedVideos.size else 0,
            selectedGalleryCount = selectedGalleryMedia.size,
        ),
    ).let { if (snapshot.isWorkspaceBrowser) it else it.copy(findDuplicates = false) }
        .let { if (snapshot.galleryActive) it.copy(sort = false, filter = false) else it }

    val suggestedDuplicateIds by remember(inDuplicates, duplicateState, duplicateReport, displayedFiles) {
        derivedStateOf {
            if (!inDuplicates) emptySet()
            else duplicateState?.takeIf {
                duplicateReport.kind == DuplicateKind.IDENTICAL || duplicateReport.keepRule != DuplicateKeepRule.RECOMMENDED
            }?.let { finder -> duplicateReport.smartSelectIds(finder) }
                ?.intersect(displayedFiles.filterNot { it.isFolder }.mapTo(HashSet()) { it.id })
                .orEmpty()
        }
    }

    val placePrimaryAction: SheetAction? = when {
        state.isSelectionMode && !pathInTopBar -> null
        inDuplicates -> suggestedDuplicateIds.takeIf { it.isNotEmpty() && selectedIdSet != it }?.let { suggested ->
            val count = suggested.size
            SheetAction(Icons.Outlined.Checklist, tr("选择建议清理的 $count 项", "Select $count suggested items to clean up", "整理推奨の $count 項目を選択", "정리 권장 ${count}개 항목 선택", "選取建議清理的 $count 個項目"), { state.selectOnly(suggested) })
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
                    selectionToolbar = {
                        val toolbarActions = if (state.isSelectionMode && commandTargets.isNotEmpty()) {
                            buildDriveOperations(state, commandTargets.first(), commandTargets, actionIntents)
                        } else emptyList()
                        DriveSelectionFloatingToolbar(
                            visible = state.isSelectionMode && commandTargets.isNotEmpty(),
                            inTrash = inTrash,
                            inDuplicates = inDuplicates,
                            applyNavBarsPadding = !pathInTopBar,
                            availableActions = toolbarActions,
                            onOpenSelectionActions = events.onOpenSelectionSheet,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    },
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
        itemActionsOf = { target -> itemActions(target, listOf(target), false) },
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
