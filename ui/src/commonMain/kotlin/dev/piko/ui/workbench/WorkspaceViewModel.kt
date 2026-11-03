package dev.piko.ui.workbench

import dev.piko.shared.i18n.tr

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
import dev.piko.shared.presentation.NavigationEntryToken
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
import dev.piko.ui.navigation.isWorkspaceSurface
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
    var blockedCloseMessage by mutableStateOf<String?>(null)
        private set
    fun dismissBlockedClose() { blockedCloseMessage = null }

    private fun blockCloseIfSaving(ids: List<Long>): Boolean {
        if (services.addLink.ownerTabId in ids &&
            services.addLink.replacementAvailability is dev.piko.shared.presentation.ActionAvailability.Blocked
        ) {
            blockedCloseMessage = tr("当前标签页中的链接正在保存，请等待保存完成后再关闭标签页。", "Links in this tab are being saved. Wait before closing it.", "このタブのリンクを保存中です。完了してからタブを閉じてください。", "이 탭의 링크를 저장 중입니다. 완료 후 탭을 닫으세요.", "目前分頁中的連結正在儲存，請等待儲存完成後再關閉分頁。")
            return true
        }
        return false
    }

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
    private val contextOrigins = mutableMapOf<Long, ExternalOrigin>()
    private val fileOrigins = mutableStateMapOf<Long, ExternalOrigin>()
    var returnedOrigin by mutableStateOf<ExternalOrigin?>(null)
        private set

    data class ExternalOrigin(
        val tab: MainTab,
        val screens: List<Screen> = listOf(Screen.Home),
        val sourceContextId: Long? = null,
        val token: NavigationEntryToken? = null,
    )

    fun consumeReturnedOrigin(): ExternalOrigin? = returnedOrigin.also { returnedOrigin = null }

    fun enterFromOutside(
        stack: List<PikoPathBreadcrumb>, tab: MainTab, screens: List<Screen>,
        sourceContextId: Long? = contexts.activeContextId.value,
    ) {
        if (!available) return
        val token = driveWorkspace.enterPrimaryWithToken(stack)
        fileOrigins[token.tabId] = ExternalOrigin(tab, screens.toList(), sourceContextId, token)
    }

    fun canLeaveFileOrigin(currentStack: List<PikoPathBreadcrumb>): Boolean {
        val tabId = driveWorkspace.activeTabId.value
        if (contexts.activeContextId.value != tabId) return false
        val token = fileOrigins[tabId]?.token ?: return false
        return driveWorkspace.isAtEntryRoot(token, currentStack)

    }

    fun returnFromFileEntry(): ExternalOrigin? {
        if (!available) return null
        val tabId = driveWorkspace.activeTabId.value
        val origin = fileOrigins[tabId] ?: return null
        val token = origin.token ?: return null
        if (!driveWorkspace.returnAcross(token)) return null
        fileOrigins.remove(tabId)
        origin.sourceContextId?.takeIf { id -> contexts.contextsFlow.value.any { it.id == id } }
            ?.let(driveWorkspace::switchContext)
        return origin
    }

    val hasFileEntry: Boolean get() = driveWorkspace.activeTabId.value in fileOrigins
    val pageSnapshots = WorkspaceSnapshots<ImageBitmap>()
    val detailPanels = dev.piko.ui.components.DetailPanelStore()

    fun retainPageSnapshot(id: Long, image: ImageBitmap) {
        if (available && contexts.contextsFlow.value.any { it.id == id }) pageSnapshots.put(id, image, image.width, image.height)
    }

    fun activateContext(id: Long) = activateContext(id, rememberOrigin = false)

    fun activateContext(id: Long, rememberOrigin: Boolean) {
        if (!available) return
        if (rememberOrigin && contexts.activeContextId.value != id) {
            rememberContextOrigin(id, lastMainTab, lastNavigation)
        }
        if (!contexts.activate(id)) return
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

    fun activatePrimaryFileBrowser(): Long = driveWorkspace.activatePrimaryFileBrowser()
    fun canCloseContext(id: Long): Boolean = driveWorkspace.canCloseContext(id)

    fun pushLibrary(library: DriveLibrary, tab: MainTab = lastMainTab, screens: List<Screen> = lastNavigation) {
        if (!available) return
        val sourceContextId = contexts.activeContextId.value
        val token = driveWorkspace.pushLibrary(
            library, reuseCurrentEntry = isWorkspaceSurface(screens.singleOrNull(), tab),
        ) ?: return
        fileOrigins[token.tabId] = ExternalOrigin(tab, screens.toList(), sourceContextId, token)
    }

    fun goToDrive() {
        if (!available) return
        fileOrigins.remove(driveWorkspace.primaryTabId)
        driveWorkspace.goToDrive()
    }

    fun minimizeContext(id: Long) {
        if (!available) return
        val origin = contextOrigins.remove(id)
        if (contexts.activeContextId.value != id) return
        val source = origin?.sourceContextId?.takeIf { sourceId -> contexts.contextsFlow.value.any { it.id == sourceId } }
        if (source != null) driveWorkspace.switchContext(source) else driveWorkspace.switchTab(fileContextId())
        if (origin != null && (origin.tab != MainTab.FILES || origin.screens.size > 1)) returnedOrigin = origin
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
        if (existing != null) activateContext(existing.id, rememberOrigin = true) else createDuplicate(root)
    }

    private fun createDuplicate(root: PikoPathBreadcrumb) {
        val session = services.newDuplicateSession().also { it.open(root) }
        val id = contexts.openDuplicate(root, activate = false)
        duplicateSessions[id] = session
        activateContext(id, rememberOrigin = true)
    }

    fun openImport(link: String = "", target: PikoPathBreadcrumb = fileWorkspace.saveTargetContext.currentFolder()) {
        if (!available) return
        val destination = SaveTargetContext({ target }, kotlinx.coroutines.flow.flowOf(target), fileCommands::receivingAvailability, fileCommands.receivingChanges)
        val draft = services.instantSession.openDraft(link, destination)
        val id = contexts.openImportDraft(activate = false)
        importDrafts[id] = draft
        importModels[id] = AddLinkViewModel(draft)
        activateContext(id, rememberOrigin = true)
    }

    fun requestCloseContext(id: Long) {
        if (!available || !canCloseContext(id)) return
        if (blockCloseIfSaving(listOf(id))) return
        if (closeNeedsConfirmation(id)) pendingCloseId = id else closeContext(id)
    }

    fun hasUncommittedAddLinkOnTab(id: Long): Boolean =
        services.addLink.ownerTabId == id && services.addLink.state != null && services.addLink.tabCloseNeedsConfirm

    fun hasGalleryOnTab(id: Long): Boolean = tabs.peek(id)?.gallery?.shown == true

    private fun closeNeedsConfirmation(id: Long): Boolean {
        val model = importModels[id]
        val draft = model?.state
        val unsubmittedInput = draft != null && !draft.isBlank && !draft.isSaving && draft.batch?.isSaving != true &&
            !draft.hasRetainedInstantAttempt && !draft.hasRetainedShareWork && !draft.hasRetainedOfflineSubmission
        val isDuplicateContext = contexts.contextsFlow.value.any { it.id == id && it is WorkspaceContext.Duplicate }
        val hasAddLinkOnTab = hasUncommittedAddLinkOnTab(id)
        val hasGalleryOnTab = hasGalleryOnTab(id)
        return model?.tabCloseNeedsConfirm == true || unsubmittedInput || isDuplicateContext || hasAddLinkOnTab || hasGalleryOnTab
    }

    fun requestCloseOtherContexts(keepId: Long) {
        if (!available || contexts.contextsFlow.value.none { it.id == keepId }) return
        val ids = contexts.contextsFlow.value.map { it.id }.filter { it != keepId && canCloseContext(it) }
        if (blockCloseIfSaving(ids)) return
        pendingCloseOtherNeedsAttention = ids.any(::closeNeedsConfirmation)
        pendingCloseOtherIds = ids
    }

    fun confirmCloseOtherContexts() {
        val ids = pendingCloseOtherIds
        pendingCloseOtherIds = emptyList()
        if (!available) return
        ids.filter(::canCloseContext).forEach(::closeContext)
    }
    fun cancelCloseOtherContexts() { pendingCloseOtherIds = emptyList() }

    fun confirmCloseContext() { pendingCloseId?.let(::closeContext); pendingCloseId = null }
    fun cancelCloseContext() { pendingCloseId = null }

    fun closeContext(id: Long) {
        if (!canCloseContext(id)) return
        if (contexts.contextsFlow.value.any { it.id == id && it !is WorkspaceContext.File } && contexts.activeContextId.value == id) minimizeContext(id)
        driveWorkspace.closeContext(id)
    }

    private fun releaseContext(id: Long) {
        if (services.addLink.ownerTabId == id) services.addLink.forceDiscard()
        detailPanels.forgetWhere { it is dev.piko.shared.presentation.TabNavigation && it.tabId == id }
        pageSnapshots.remove(id)
        contextOrigins.remove(id)
        fileOrigins.remove(id)
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

    fun createLibraryBrowser(library: DriveLibrary): DriveBrowserSession =
        createBrowser(library, null, null)

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
            instantSession = services.instantSession,
            addLinkOwnerTabId = { services.addLink.ownerTabId },
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
    fun rememberContextOrigin(
        id: Long, tab: MainTab, screens: List<Screen>,
        sourceContextId: Long? = contexts.activeContextId.value.takeIf { it != id },
    ) {
        if (contexts.activeContextId.value != id || sourceContextId != null) {
            contextOrigins[id] = ExternalOrigin(tab, screens.toList(), sourceContextId)
        }
    }
    var lastNavigation: List<Screen> = listOf(Screen.Home)
        private set
    var lastMainTab: MainTab = MainTab.FILES
        private set

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
        contextOrigins.clear()
        fileOrigins.clear()
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
