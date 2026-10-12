package dev.piko.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveBrowserStore
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.MobileFileWorkspace
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.shared.presentation.AddLinkViewModel
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

/** 界面重建和隐藏窗口不销毁浏览上下文；账号退出或工作区释放才清除。 */
internal class WorkspaceViewModel(private val services: PikoServices, private val account: String, val desktop: Boolean) : ViewModel() {
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
    val fileWorkspace: FileWorkspace = if (desktop) services.driveWorkspace.also { it.enterAccount(account) }
        else MobileFileWorkspace(services.driveRepository, account, services.cacheStore, viewModelScope)
    val desktopWorkspace: DriveWorkspace? get() = fileWorkspace as? DriveWorkspace
    val contexts = desktopWorkspace?.contexts ?: WorkspaceContexts()
    var sheetExpanded by mutableStateOf(false)
    var sheetDetailed by mutableStateOf(false)
        private set
    var sheetContextId by mutableStateOf<Long?>(null)
    var activationRevision by mutableStateOf(0L)
        private set
    private var handledActivationRevision = 0L
    var pendingDuplicateRoot by mutableStateOf<PikoPathBreadcrumb?>(null)
        private set
    var pendingCloseId by mutableStateOf<Long?>(null)
        private set
    val duplicateSessions = mutableStateMapOf<Long, DuplicateSession>()
    val importDrafts = mutableStateMapOf<Long, InstantDraft>()
    val importModels = mutableStateMapOf<Long, AddLinkViewModel>()
    private val duplicateBrowsers = mutableMapOf<Long, DriveBrowserSession>()

    fun activateContext(id: Long) {
        if (!available || !contexts.activate(id)) return
        activationRevision++
        importModels[id]?.resume()
        if (!desktop && contexts.contextsFlow.value.any { it.id == id && it !is WorkspaceContext.File }) {
            sheetContextId = id
            sheetExpanded = false
            sheetDetailed = false
        }
    }

    fun activateFileBrowser() {
        val id = desktopWorkspace?.activeTabId?.value
            ?: contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.File>().first().id
        activateContext(id)
        sheetExpanded = false
        sheetDetailed = false
    }

    fun setSheetExpansion(expanded: Boolean) {
        if (expanded) sheetContextId?.let(contexts::activate)
        sheetExpanded = expanded
    }

    fun viewSheetDetails(id: Long) {
        if (!available || !contexts.activate(id)) return
        sheetContextId = id
        sheetExpanded = true
        sheetDetailed = true
    }

    fun returnToSheet() { sheetDetailed = false }

    fun consumeContextActivation(): Boolean {
        if (activationRevision == handledActivationRevision) return false
        handledActivationRevision = activationRevision
        return true
    }

    fun openDuplicate(root: PikoPathBreadcrumb) {
        if (!available) return
        if (!desktop) {
            val existing = contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.Duplicate>().firstOrNull()
            if (existing != null) {
                if (existing.root.id == root.id) activateContext(existing.id)
                else pendingDuplicateRoot = root
                return
            }
        }
        createDuplicate(root)
    }

    private fun createDuplicate(root: PikoPathBreadcrumb) {
        val session = services.newDuplicateSession().also { it.open(root) }
        val id = contexts.openDuplicate(root, activate = false)
        duplicateSessions[id] = session
        activateContext(id)
    }

    fun confirmDuplicateReplacement() {
        val root = pendingDuplicateRoot ?: return
        pendingDuplicateRoot = null
        if (!available) return
        contexts.contextsFlow.value.filterIsInstance<WorkspaceContext.Duplicate>().forEach { closeContext(it.id) }
        createDuplicate(root)
    }

    fun cancelDuplicateReplacement() { pendingDuplicateRoot = null }

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
        if (importModels[id]?.discardNeedsConfirm == true) pendingCloseId = id else closeContext(id)
    }

    fun confirmCloseContext() { pendingCloseId?.let(::closeContext); pendingCloseId = null }
    fun cancelCloseContext() { pendingCloseId = null }

    fun closeContext(id: Long) {
        if (desktop) desktopWorkspace?.closeContext(id) else contexts.close(id)?.let { releaseContext(it.id) }
    }

    private fun releaseContext(id: Long) {
        duplicateBrowsers.remove(id)?.close()
        duplicateSessions.remove(id)?.let(services::releaseDuplicateSession)
        importModels.remove(id)
        importDrafts.remove(id)?.close()
        if (sheetContextId == id) {
            val remaining = contexts.contextsFlow.value.filter { it !is WorkspaceContext.File }
            sheetContextId = remaining.firstOrNull { it.id == contexts.activeContextId.value }?.id ?: remaining.firstOrNull()?.id
            sheetContextId?.let(contexts::activate)
            if (sheetContextId == null) {
                sheetExpanded = false
                sheetDetailed = false
            }
        }
    }

    fun duplicateBrowser(id: Long): DriveBrowserSession = duplicateBrowsers.getOrPut(id) {
        createBrowser(DriveLibrary.DUPLICATES, null, null, requireNotNull(duplicateSessions[id]))
    }

    fun cycleContext(step: Int) {
        val values = contexts.contextsFlow.value
        val index = values.indexOfFirst { it.id == contexts.activeContextId.value }
        activateContext(values[(index + step + values.size) % values.size].id)
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
    val tabs = desktopWorkspace?.let { workspace -> DriveBrowserStore { id -> createBrowser(null, null, workspace.navigationForTab(id)) } }
    private val mobileBrowser = if (!desktop) createBrowser(null, null, fileWorkspace) else null
    fun mainBrowser(tabId: Long?): DriveBrowserSession = mobileBrowser ?: requireNotNull(tabs).forTab(requireNotNull(tabId))
    fun lookupPath(fileId: String): Deferred<Result<List<PikoPathBreadcrumb>>> = viewModelScope.async {
        if (closed || repository.currentAccount != account) throw CancellationException("工作区已结束")
        val result = repository.locateFolder(fileId)
        if (closed || repository.currentAccount != account) throw CancellationException("账号已改变")
        result
    }
    init {
        desktopWorkspace?.onContextClosed = { releaseContext(it.id) }
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
            continuations = dev.piko.shared.presentation.TaskContinuations(
                drafts = {
                    val models = importModels.toMap()
                    contexts.contextsFlow.value.mapNotNull { context -> models[context.id]?.let { model ->
                        dev.piko.shared.presentation.TaskContinuations.DraftContinuation(context.id, model, { activateContext(context.id) }, { closeContext(context.id) })
                    } }
                },
                duplicates = {
                    val sessions = duplicateSessions.toMap()
                    contexts.contextsFlow.value.mapNotNull { context -> sessions[context.id]?.state?.let {
                        dev.piko.shared.presentation.TaskContinuations.DuplicateContinuation(context.id, it, { activateContext(context.id) }, { closeContext(context.id) })
                    } }
                },
                cleanup = services.trashSession,
            ),
            fileWrites = services.fileWrites,
            historyEdits = services.historyEdits,
            vaultEdits = services.vaultEntryEdits,
            shareSaves = services.shareSaves,
            receiving = services.receivingActivity,
        )
    }
    private val pages = mutableMapOf<Screen, DriveBrowserSession>()
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
        pages.keys.filter { it !in screens }.forEach { pages.remove(it)?.close() }
    }

    fun rememberNavigation(screens: List<Screen>, tab: MainTab) {
        if (closed) return
        lastNavigation = screens.toList()
        lastMainTab = tab
    }

    override fun onCleared() {
        closed = true
        contexts.contextsFlow.value.filter { it !is WorkspaceContext.File }.toList().forEach { closeContext(it.id) }
        desktopWorkspace?.onContextClosed = {}
        releaseSaveTarget()
        tabs?.close()
        mobileBrowser?.close()
        (fileWorkspace as? MobileFileWorkspace)?.close()
        pages.values.forEach { it.close() }
        pages.clear()
    }
}

internal val LocalFileWorkspace = staticCompositionLocalOf<FileWorkspace> { error("文件工作区未提供") }

internal val LocalWorkspaceContexts = staticCompositionLocalOf<WorkspaceViewModel?> { null }
