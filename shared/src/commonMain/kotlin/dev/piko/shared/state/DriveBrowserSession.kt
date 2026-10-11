package dev.piko.shared.state

import dev.piko.data.auth.PikoUserPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.LibraryNavigation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.piko.shared.presentation.TabNavigation

/** 留在返回栈中的浏览页保留筛选、多选和加载状态；出栈才释放它的订阅。 */
class DriveBrowserSession(
    repository: PikoDriveRepository,
    preferences: PikoUserPreferences,
    duplicates: DuplicateSession,
    archives: ArchiveBrowser?,
    trash: TrashSession,
    parentScope: CoroutineScope,
    library: DriveLibrary? = null,
    initialStack: List<PikoPathBreadcrumb>? = null,
    fileNavigation: DriveNavigation? = null,
    clipboard: dev.piko.shared.presentation.DriveClipboardState = dev.piko.shared.presentation.DriveClipboardState(),
    operationScope: CoroutineScope = parentScope,
    fileWrites: FileWriteSession = FileWriteSession(repository, operationScope),
    historyEdits: HistoryEditSession = HistoryEditSession(repository, operationScope),
    vaultEdits: VaultEntryEditSession = VaultEntryEditSession(repository, operationScope),
    folderVault: FolderVaultSession? = null,
    shareSaves: ShareSaveSession? = null,
    receiving: dev.piko.shared.presentation.ReceivingActivity? = null,
) {
    val duplicateReport = dev.piko.shared.presentation.DuplicateReportViewModel()
    private val account = repository.currentAccount
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    val navigation: DriveNavigation = when {
        initialStack != null -> LibraryNavigation(repository, library, scope, initialStack)
        library != null -> LibraryNavigation(repository, library, scope)
        else -> requireNotNull(fileNavigation) { "文件页需要由工作区提供导航上下文" }
    }
    var initialized by mutableStateOf(false)
    val state = DriveScreenState(
        repository, preferences, scope,
        sessionListing = { place ->
            if (place == DriveLibrary.DUPLICATES) duplicates.state?.let { finder ->
                val folder = navigation.folderStackFlow.value.lastOrNull()?.id
                duplicateReport.listing(finder, folder?.takeUnless { it == place.id } ?: finder.root.id)
            } else null
        },
        archives = archives,
        navigation = navigation,
        trashSession = trash,
        clipboard = clipboard,
        operationScope = operationScope,
        fileWriteSession = fileWrites,
        historyEditSession = historyEdits,
        vaultEditSession = vaultEdits,
        folderVault = folderVault,
        shareSaves = shareSaves,
        receiving = receiving,
    )

    init {
        job.invokeOnCompletion {
            (navigation as? LibraryNavigation)?.close()
            (navigation as? TabNavigation)?.close()
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.accountChanges.first { it != account }
            close()
        }
    }

    fun close() {
        (navigation as? LibraryNavigation)?.close()
        (navigation as? TabNavigation)?.close()
        scope.cancel()
    }
}
