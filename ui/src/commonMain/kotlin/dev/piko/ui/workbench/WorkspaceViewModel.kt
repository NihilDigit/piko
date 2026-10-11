package dev.piko.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveBrowserStore
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FileWorkspace
import dev.piko.shared.presentation.MobileFileWorkspace
import dev.piko.shared.presentation.DriveNavigation
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.TransfersState
import dev.piko.ui.PikoServices
import dev.piko.ui.navigation.MainTab
import dev.piko.ui.navigation.Screen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException

/** 界面重建和隐藏窗口不销毁浏览上下文；账号退出或工作区释放才清除。 */
internal class WorkspaceViewModel(services: PikoServices, private val account: String, desktop: Boolean) : ViewModel() {
    private var closed = false
    private val repository = services.driveRepository
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
    val feed = FeedNavigationState(
        viewModelScope, fileWorkspace::currentLocation, { fileWorkspace.folderStackFlow.value },
        services.clipFeedSession::open, services.clipFeedSession::close,
        { !closed && repository.currentAccount == account },
    )
    private val releaseSaveTarget = services.saveTargets.bind(account, dev.piko.shared.state.SaveTargetContext(
        fileWorkspace.saveTargetContext.currentFolder, fileWorkspace.saveTargetContext.folders,
        fileCommands::receivingAvailability, fileCommands.receivingChanges,
    ))
    private val createBrowser: (DriveLibrary?, List<PikoPathBreadcrumb>?, DriveNavigation?) -> DriveBrowserSession = { library, path, navigation ->
        check(!closed && services.driveRepository.currentAccount == account) { "工作区已释放或账号已改变" }
        DriveBrowserSession(
            services.driveRepository, services.preferences, services.duplicateSession,
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
    private val heldSession: (DriveLibrary) -> Boolean = { place ->
        place == DriveLibrary.DUPLICATES && services.duplicateSession.state != null
    }
    init {
        desktopWorkspace?.let { workspace ->
            workspace.sessionTabHeld = heldSession
            viewModelScope.launch {
                combine(workspace.tabsFlow, snapshotFlow { services.duplicateSession.state != null }) { tabs, live -> tabs to live }
                    .collect { (tabs, live) ->
                        if (!live && tabs.size > 1) tabs.filter { it.session == DriveLibrary.DUPLICATES }.forEach { workspace.closeTab(it.id) }
                    }
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
            continuations = dev.piko.shared.presentation.TaskContinuations(services.addLink, services.duplicateSession, services.trashSession),
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
        desktopWorkspace?.let { if (it.sessionTabHeld === heldSession) it.sessionTabHeld = { false } }
        releaseSaveTarget()
        tabs?.close()
        mobileBrowser?.close()
        (fileWorkspace as? MobileFileWorkspace)?.close()
        pages.values.forEach { it.close() }
        pages.clear()
    }
}

internal val LocalFileWorkspace = staticCompositionLocalOf<FileWorkspace> { error("文件工作区未提供") }
