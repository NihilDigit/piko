package dev.piko.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveBrowserStore
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.shared.presentation.AddLinkViewModel
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantDraft
import dev.piko.shared.state.SaveTargetContext
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.TransfersState
import dev.piko.ui.PikoServices
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** 界面重建和隐藏窗口不销毁浏览上下文；账号退出或工作区释放才清除。 */
internal class WorkspaceViewModel(private val services: PikoServices, private val account: String) : ViewModel() {
    private var closed = false
    private val repository = services.driveRepository
    private val available: Boolean get() = !closed && repository.currentAccount == account
    val fileCommands = dev.piko.shared.presentation.FileCommands(repository, services.fileWrites, account, services.trashSession, services.folderVaultSession, services.vaultEntryEdits, services.shareSaves, services.receivingActivity) {
        !closed && repository.currentAccount == account
    }
    init {
        check(services.driveRepository.currentAccount == account)
        repository.enterAccount(account)
    }
    val driveWorkspace = services.driveWorkspace.also { it.enterAccount(account) }
    val fileWorkspace: FileWorkspace get() = driveWorkspace
    val contexts = driveWorkspace.contexts
    var tabCreationRevision by mutableStateOf(0L)
        private set
    var tabCountBeforeCreation by mutableStateOf(contexts.contextsFlow.value.size)
        private set
    var tabButtonEmphasized by mutableStateOf(false)
        private set
    private var tabEmphasisJob: Job? = null
    var activationRevision by mutableStateOf(0L)
        private set
    private var handledActivationRevision = 0L
    var pendingCloseId by mutableStateOf<Long?>(null)
        private set
    var pendingCloseOtherIds by mutableStateOf<List<Long>>(emptyList())
        private set
    var pendingCloseOtherNeedsAttention by mutableStateOf(false)
        private set
    val duplicateSessions = mutableStateMapOf<Long, DuplicateSession>()
    val importDrafts = mutableStateMapOf<Long, InstantDraft>()
    val importModels = mutableStateMapOf<Long, AddLinkViewModel>()
    private val importViews = mutableMapOf<Long, dev.piko.ui.screens.instant.ImportWorkspaceViewState>()
    fun importView(id: Long) = importViews.getOrPut(id) { dev.piko.ui.screens.instant.ImportWorkspaceViewState() }
    private val duplicateBrowsers = mutableMapOf<Long, DriveBrowserSession>()
    private val contextOrigins = mutableMapOf<Long, List<Screen>>()
    val pageSnapshots = WorkspaceSnapshots<ImageBitmap>()
    val detailPanels = dev.piko.ui.components.DetailPanelStore()

    fun retainPageSnapshot(id: Long, image: ImageBitmap) {
        if (available && contexts.contextsFlow.value.any { it.id == id }) pageSnapshots.put(id, image, image.width, image.height)
    }

    fun activateContext(id: Long) {
        if (!available || !contexts.activate(id)) return
        activationRevision++
        importModels[id]?.resume()
    }

    private fun fileContextId(): Long = driveWorkspace.activeTabId.value

    fun newFileContext() {
        if (!available) return
        val id = driveWorkspace.openTab(listOf(dev.piko.shared.data.PikoDriveRepository.ROOT_BREADCRUMB), activate = false)
        activateContext(id)
    }

    fun activateFileBrowser() {
        driveWorkspace.switchTab(fileContextId())
        activateContext(fileContextId())
    }

    fun minimizeContext(id: Long) {
        if (!available) return
        if (contexts.activeContextId.value == id && contexts.contextsFlow.value.none { it is WorkspaceContext.File }) {
            driveWorkspace.switchTab(fileContextId())
        }
        contexts.minimizeToFile(id, fileContextId())
    }

    fun consumeContextActivation(): Boolean {
        if (activationRevision == handledActivationRevision) return false
        handledActivationRevision = activationRevision
        return true
    }

    fun openDuplicate(root: PikoPathBreadcrumb) {
        if (!available) return
        val existing = contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.Duplicate>()
            .firstOrNull { it.root.id == root.id }
        if (existing != null) activateContext(existing.id) else createDuplicate(root)
    }

    private fun createDuplicate(root: PikoPathBreadcrumb) {
        val session = services.newDuplicateSession().also { it.open(root) }
        val id = contexts.openDuplicate(root, activate = false)
        duplicateSessions[id] = session
        activateContext(id)
    }

    fun openImport(link: String = "", target: PikoPathBreadcrumb = fileWorkspace.saveTargetContext.currentFolder()) {
        if (!available) return
        val destination = SaveTargetContext({ target }, kotlinx.coroutines.flow.flowOf(target), fileCommands::receivingAvailability, fileCommands.receivingChanges)
        val draft = services.instantSession.openDraft(link, destination)
        val id = contexts.openImportDraft(activate = false)
        importDrafts[id] = draft
        importModels[id] = AddLinkViewModel(draft)
        activateContext(id)
    }

    fun requestCloseContext(id: Long) {
        if (!available || contexts.contextsFlow.value.none { it.id == id }) return
        if (closeNeedsConfirmation(id)) pendingCloseId = id else closeContext(id)
    }

    private fun closeNeedsConfirmation(id: Long): Boolean {
        val model = importModels[id]
        val draft = model?.state
        val unsubmittedInput = draft != null && !draft.isBlank && !draft.isSaving && draft.batch?.isSaving != true &&
            !draft.hasRetainedInstantAttempt && !draft.hasRetainedShareWork && !draft.hasRetainedOfflineSubmission
        val duplicate = duplicateSessions[id]?.state
        val hasReport = duplicate?.report?.let { it.identical.isNotEmpty() || it.versions.isNotEmpty() } == true
        val scanning = duplicate?.phase == DuplicateFinderState.Phase.SCANNING || duplicate?.phase == DuplicateFinderState.Phase.ANALYZING
        return model?.discardNeedsConfirm == true || unsubmittedInput || scanning || hasReport
    }

    fun requestCloseOtherContexts(keepId: Long) {
        if (!available || contexts.contextsFlow.value.none { it.id == keepId }) return
        val ids = contexts.contextsFlow.value.map { it.id }.filter { it != keepId }
        pendingCloseOtherNeedsAttention = ids.any(::closeNeedsConfirmation)
        pendingCloseOtherIds = ids
    }

    fun confirmCloseOtherContexts() {
        val ids = pendingCloseOtherIds
        pendingCloseOtherIds = emptyList()
        if (!available) return
        ids.forEach(::closeContext)
    }
    fun cancelCloseOtherContexts() { pendingCloseOtherIds = emptyList() }

    fun confirmCloseContext() { pendingCloseId?.let(::closeContext); pendingCloseId = null }
    fun cancelCloseContext() { pendingCloseId = null }

    fun closeContext(id: Long) {
        driveWorkspace.closeContext(id)
    }

    private fun releaseContext(id: Long) {
        detailPanels.forgetWhere { it is dev.piko.shared.presentation.TabNavigation && it.tabId == id }
        pageSnapshots.remove(id)
        contextOrigins.remove(id)
        duplicateBrowsers.remove(id)?.let { detailPanels.forget(it.navigation); it.close() }
        duplicateSessions.remove(id)?.let(services::releaseDuplicateSession)
        importModels.remove(id)
        importViews.remove(id)
        importDrafts.remove(id)?.close()
        if (pendingCloseId == id) pendingCloseId = null
    }

    fun duplicateBrowser(id: Long): DriveBrowserSession = duplicateBrowsers.getOrPut(id) {
        createBrowser(DriveLibrary.DUPLICATES, null, null, requireNotNull(duplicateSessions[id]))
    }

    fun cycleContext(step: Int) {
        val values = contexts.contextsFlow.value
        val index = values.indexOfFirst { it.id == contexts.activeContextId.value }
        activateContext(values[(index + step + values.size) % values.size].id)
    }
    fun moveContext(id: Long, targetIndex: Int) {
        if (available) driveWorkspace.moveContext(id, targetIndex)
    }
    fun moveActiveContext(step: Int) {
        val id = contexts.activeContextId.value
        val index = contexts.contextsFlow.value.indexOfFirst { it.id == id }
        if (index >= 0) moveContext(id, index + step)
    }
    val feed = FeedNavigationState(
        viewModelScope, fileWorkspace::currentLocation, { fileWorkspace.folderStackFlow.value },
        services.clipFeedSession::open, services.clipFeedSession::close,
        { !closed && repository.currentAccount == account },
    )
    private val releaseSaveTarget = services.saveTargets.bind(account, dev.piko.shared.state.SaveTargetContext(
        fileWorkspace.saveTargetContext.currentFolder, fileWorkspace.saveTargetContext.folders,
        fileCommands::receivingAvailability, fileCommands.receivingChanges,
    ))
    private fun createBrowser(library: DriveLibrary?, path: List<PikoPathBreadcrumb>?, navigation: DriveNavigation?, duplicates: DuplicateSession = services.duplicateSession): DriveBrowserSession {
        check(!closed && services.driveRepository.currentAccount == account) { "工作区已释放或账号已改变" }
        return DriveBrowserSession(
            services.driveRepository, services.preferences, duplicates,
            services.archiveBrowser, services.trashSession, viewModelScope, library, path,
            fileNavigation = navigation,
            clipboard = fileWorkspace.clipboard,
            operationScope = services.fileExecutionScopes.forAccount(account),
            fileWrites = services.fileWrites,
            historyEdits = services.historyEdits,
            vaultEdits = services.vaultEntryEdits,
            folderVault = services.folderVaultSession,
            shareSaves = services.shareSaves,
            receiving = services.receivingActivity,
        )
    }
    val tabs = DriveBrowserStore { id -> createBrowser(null, null, driveWorkspace.navigationForTab(id)) }
    fun mainBrowser(tabId: Long?): DriveBrowserSession = tabs.forTab(requireNotNull(tabId))
    fun lookupPath(fileId: String): Deferred<Result<List<PikoPathBreadcrumb>>> = viewModelScope.async {
        if (closed || repository.currentAccount != account) throw CancellationException("工作区已结束")
        val result = repository.locateFolder(fileId)
        if (closed || repository.currentAccount != account) throw CancellationException("账号已改变")
        result
    }
    init {
        driveWorkspace.onContextClosed = { releaseContext(it.id) }
        contexts.onCreated = {
            tabCountBeforeCreation = (contexts.contextsFlow.value.size - 1).coerceAtLeast(1)
            tabCreationRevision++
            tabButtonEmphasized = true
            tabEmphasisJob?.cancel()
            tabEmphasisJob = viewModelScope.launch {
                delay(700)
                tabButtonEmphasized = false
            }
        }
        viewModelScope.launch {
            snapshotFlow { services.instantSession.drafts.toList() }.collect { live ->
                importDrafts.toMap().filterValues { it !in live }.keys.forEach(::closeContext)
            }
        }
    }
    val transfers: TransfersState by lazy {
        check(!closed && services.driveRepository.currentAccount == account)
        TransfersState(
            services.downloadManager, services.taskRepository, services.offlinePacks,
            viewModelScope, services.driveRepository, services.uploadManager,
            services.instantSaveRecords, account, services.archiveExtractSession,
            services.folderVaultSession, listsServerWork = true,
            cleanup = services.trashSession,
            fileWrites = services.fileWrites,
            historyEdits = services.historyEdits,
            vaultEdits = services.vaultEntryEdits,
            shareSaves = services.shareSaves,
            receiving = services.receivingActivity,
        )
    }
    private val pages = mutableMapOf<Screen, DriveBrowserSession>()
    fun rememberContextOrigin(id: Long, screens: List<Screen>) { if (id !in contextOrigins) contextOrigins[id] = screens.toList() }
    fun contextOrigin(id: Long): List<Screen> = dev.piko.ui.navigation.retainWorkspaceRoutes(
        contextOrigins[id].orEmpty().ifEmpty { listOf(Screen.Home) }, contexts.contextsFlow.value.mapTo(HashSet()) { it.id },
    )
    var lastNavigation: List<Screen> = listOf(Screen.Home)
        private set
    var lastMainTab: MainTab = MainTab.FILES
        private set

    fun browser(screen: Screen.Library): DriveBrowserSession = pages.getOrPut(screen) {
        createBrowser(requireNotNull(DriveLibrary.of(screen.libraryId)), null, null)
    }

    fun browser(screen: Screen.Browse): DriveBrowserSession = pages.getOrPut(screen) {
        createBrowser(null, screen.path.map { PikoPathBreadcrumb(it.first, it.second) }, null).also {
            it.navigation.requestHighlight(setOf(screen.highlightId))
        }
    }

    fun retainPages(screens: List<Screen>) {
        val retained = screens + contextOrigins.values.flatten()
        pages.keys.filter { it !in retained }.forEach { pages.remove(it)?.let { session -> detailPanels.forget(session.navigation); session.close() } }
    }

    fun rememberNavigation(screens: List<Screen>, tab: MainTab) {
        if (closed) return
        lastNavigation = screens.toList()
        lastMainTab = tab
    }

    override fun onCleared() {
        closed = true
        contexts.onCreated = null
        contexts.contextsFlow.value.filter { it !is WorkspaceContext.File }.toList().forEach { closeContext(it.id) }
        driveWorkspace.onContextClosed = {}
        releaseSaveTarget()
        tabs.close()
        detailPanels.clear()
        importViews.clear()
        pageSnapshots.clear()
        pages.values.forEach { it.close() }
        pages.clear()
    }
}

internal val LocalFileWorkspace = staticCompositionLocalOf<FileWorkspace> { error("文件工作区未提供") }

internal val LocalWorkspaceContexts = staticCompositionLocalOf<WorkspaceViewModel?> { null }

/** Minimizing an inactive view must not steal focus from another retained work item. */
internal fun WorkspaceContexts.minimizeToFile(id: Long, fileId: Long) {
    if (activeContextId.value == id && contextsFlow.value.any { it.id == fileId && it is WorkspaceContext.File }) {
        activate(fileId)
    }
}
