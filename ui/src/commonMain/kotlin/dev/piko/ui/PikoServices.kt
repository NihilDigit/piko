package dev.piko.ui

import dev.piko.shared.sync.ArchivePasswordSync
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.sync.PinnedTabSync
import dev.piko.shared.sync.SyncCipher
import dev.piko.shared.sync.SourceLedgerSync
import dev.piko.shared.sync.VaultTreeSync
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.ui.components.SegmentSession
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MoveHistory
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoTreeDatabaseFactory
import dev.piko.shared.data.PikoAccountRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.ArchiveBrowser
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.ClipFeedSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.launchOnAccountLeave
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.net.PikPakDomainSelector
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.scrape.MetaTubeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * 界面用到的进程级对象。各端在入口按自己的偏好、会话存储与下载实现拼好一份，
 * 经 [LocalPikoServices] 交给界面。这些对象的生命周期是进程，不是某个屏幕。
 */
class PikoServices(
    platformPreferences: PikoUserPreferences,
    val clientManager: PikoClientManager,
    val downloadManager: PikoDownloadCoordinator,
    val mediaRepository: PikoMediaRepository,
    uploadSources: PikoUploadSources,
    /** 记下的文件夹内容跨进程保留在这里，见 FolderContentMemory。 */
    internal val cacheStore: PikoCacheStore? = null,
    /** 压缩包密码同步进网盘前的加密，两端都传 JvmSyncCipher；为 null 时不同步它们（截图环境）。 */
    syncCipher: SyncCipher? = null,
    /** 用户自己部署的 MetaTube，两端传入；为 null 时设置里没有刮削一组（截图环境）。 */
    val metaTube: MetaTubeService? = null,
    localFileAccess: (((String) -> Unit) -> dev.piko.shared.data.LocalFileAccess)? = null,
    treeDatabaseFactory: PikoTreeDatabaseFactory? = null,
) {
    val memoryGovernor = dev.piko.shared.memory.PikoMemoryGovernor.Shared
    val workloadGovernor = dev.piko.shared.runtime.PikoWorkloadGovernor.Shared
    val appPresence = dev.piko.shared.runtime.AppPresence()

    private val appNoticesChannel = Channel<String>(Channel.BUFFERED)
    val appNotices: Flow<String> = appNoticesChannel.receiveAsFlow()
    fun postAppNotice(message: String) {
        appNoticesChannel.trySend(message)
    }

    // 不随任何界面结束的后台工作：离线任务的跟踪与 Piko-Temp 的清理
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 平台偏好，其中记着文件夹 ID 的几项换成按账号存的，见 AccountScopedPreferences。 */
    val preferences: PikoUserPreferences =
        cacheStore?.let { AccountScopedPreferences(platformPreferences, clientManager, it) } ?: platformPreferences

    val driveRepository: DriveRepository = DriveRepository(clientManager, preferences, cacheStore, treeDatabaseFactory = treeDatabaseFactory).also {
        val repository = it
        it.localMounts.access = localFileAccess?.invoke { folder ->
            repository.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(setOf(folder)))
        }
        mediaRepository.localMounts = it.localMounts
        mediaRepository.metadataSink = it.metadataSink
    }
    internal val presentationStores = dev.piko.ui.workbench.AccountPresentationStores()
    val fileExecutionScopes = dev.piko.shared.state.AccountExecutionScopes(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val fileWrites = dev.piko.shared.state.FileWriteSession(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val historyEdits = dev.piko.shared.state.HistoryMutations(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val vaultEntryEdits = dev.piko.shared.state.VaultEntryMutations(
        driveRepository, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Main.immediate),
    )
    val driveWorkspace by lazy { dev.piko.shared.presentation.DriveWorkspace(driveRepository, cacheStore, backgroundScope) }
    internal val saveTargets = dev.piko.shared.state.SaveTargetBinding({ driveRepository.currentAccount }, driveRepository.accountChanges)
    private val _openDriveRequested = MutableStateFlow(false)
    val openDriveRequested: StateFlow<Boolean> = _openDriveRequested.asStateFlow()
    fun requestOpenDrive() {
        _openDriveRequested.value = true
        driveRepository.refreshActiveListings()
        onAppForegrounded()
    }
    fun consumeOpenDriveRequest() { _openDriveRequested.value = false }
    class FileHighlightRequest internal constructor(val account: String, val ids: Set<String>)
    private val _fileHighlightRequested = MutableStateFlow<FileHighlightRequest?>(null)
    val fileHighlightRequested: StateFlow<FileHighlightRequest?> = _fileHighlightRequested.asStateFlow()
    fun requestFileHighlight(ids: Set<String>) {
        val account = driveRepository.currentAccount ?: return
        _fileHighlightRequested.value = FileHighlightRequest(account, ids.toSet())
    }
    fun consumeFileHighlight(request: FileHighlightRequest) { _fileHighlightRequested.compareAndSet(request, null) }
    val accountRepository: PikoAccountRepository = PikoAccountRepository(clientManager, driveRepository, backgroundScope)
    val instantMagnetRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)
    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository, instantMagnetRepository)

    val previewTempFolder = PreviewTempFolder(clientManager, driveRepository, instantMagnetRepository, backgroundScope)

    init {
        // 打开归档条目时借的对象放进 Piko-Temp：取到直链就删，偶有删不掉的也随 Piko-Temp 一起清走
        mediaRepository.leaseFolder = { owner -> previewTempFolder.folderId(owner).getOrThrow() }
        // 绑定默认文件夹下载源，使在递归列目录（Listing）阶段退出的文件夹下载可在下次冷启动后自动接续展开
        downloadManager.bindDefaultFolderSource(DriveDownloadFolderSource(driveRepository))
    }

    val vaultStore: VaultStore get() = driveRepository.vault

    val offlinePacks = OfflinePackTracker(instantMagnetRepository, driveRepository, preferences)

    private var taskCenterAccount: String? = null
    private var taskCenterState: dev.piko.shared.state.TransfersState? = null
    val taskCenter: dev.piko.shared.state.TransfersState
        get() {
            val account = requireNotNull(driveRepository.currentAccount)
            if (taskCenterAccount != account || taskCenterState == null) {
                taskCenterAccount = account
                taskCenterState = dev.piko.shared.state.TransfersState(
                    downloadManager, taskRepository, offlinePacks, fileExecutionScopes.forAccount(account),
                    driveRepository, uploadManager, instantSaveRecords, account, archiveExtractSession,
                    folderVaultSession, listsServerWork = true, fileWrites = fileWrites,
                    receiving = receivingActivity,
                )
            }
            return requireNotNull(taskCenterState)
        }

    val moveHistory = MoveHistory(preferences, driveRepository, backgroundScope)

    /** 部分设置同步到网盘的 .piko 文件夹，登录后自己开始，见 PikoSettingsSync。 */
    val settingsSync = PikoSettingsSync(clientManager, driveRepository, preferences, cacheStore, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val vaultTreeSync = VaultTreeSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val vaultStarSync = dev.piko.shared.sync.VaultStarSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val sourceLedgerSync = SourceLedgerSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    val pinnedTabSync = PinnedTabSync(clientManager, driveRepository, driveWorkspace.pinnedTabs, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }

    // 进程刚启动时各 Sync.start() 已经发起了首轮同步，初始化为当前时刻可避免开屏窗口首次获得焦点时立刻重复跑第二轮同步
    @Volatile
    private var lastForegroundSyncMs = Clock.System.now().toEpochMilliseconds()

    /** 设置页「立即同步」：一并拉取与推送偏好设置、固定标签、归档树、归档星标与来源账本。 */
    suspend fun syncAllNow() {
        kotlinx.coroutines.coroutineScope {
            launch { settingsSync.syncNow() }
            launch { pinnedTabSync.syncNow() }
            launch { vaultTreeSync.syncNow() }
            launch { vaultStarSync.syncNow() }
            launch { sourceLedgerSync.syncNow() }
            launch { archivePasswordSync?.syncNow() }
            launch { metaTubeSync?.syncNow() }
        }
    }

    fun onWindowFocusChanged(windowId: String, focused: Boolean) {
        if (appPresence.onWindowFocusChanged(windowId, focused)) {
            onAppForegrounded()
        }
    }

    fun onWindowClosed(windowId: String) {
        appPresence.onWindowClosed(windowId)
    }

    /** 窗口重新获得焦点或应用回到前台时轻量增量拉取固定标签与设置（10 秒冷却防抖），无需重启应用即可跨端实时同步。 */
    fun onAppForegrounded() {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastForegroundSyncMs < 10_000L) return
        lastForegroundSyncMs = now
        backgroundScope.launch {
            workloadGovernor.awaitBackgroundSyncAllowed()
            if (clientManager.currentClient.value != null) {
                pinnedTabSync.syncNow()
                settingsSync.syncNow()
                metaTubeSync?.syncNow()
            }
        }
    }
    /** 当前账号存过的压缩包密码，解压、浏览压缩包与设置页共用这一个，见 ArchivePasswordVault。 */
    val archivePasswords = ArchivePasswordVault(clientManager, clientManager.archivePasswordStore, preferences)
        .also { it.start(backgroundScope) }

    private val archivePasswordSync = syncCipher?.let {
        ArchivePasswordSync(clientManager, driveRepository, archivePasswords, it, backgroundScope, preferences.settingsSyncFlow).also { sync -> sync.start() }
    }

    private val metaTubeSync = syncCipher?.let {
        dev.piko.shared.sync.MetaTubeSync(clientManager, driveRepository, preferences, cacheStore, it, backgroundScope, preferences.settingsSyncFlow)
            .also { sync -> sync.start() }
    }

    /** API 走哪个根域名：用户固定的，或登录后测速自动挑的（冷启动先沿用上次测通的域名），见 PikPakDomainSelector。 */
    val domainSelector = PikPakDomainSelector(clientManager, preferences, backgroundScope, cacheStore).also { it.start() }

    val uploadManager = PikoUploadCoordinator(clientManager, preferences, uploadSources, driveRepository, backgroundScope)

    val instantSaveRecords = InstantSaveRecords(clientManager, cacheStore, backgroundScope)
    val receivingActivity by lazy { dev.piko.shared.presentation.ReceivingActivity(instantSaveRecords, uploadManager, instantMagnetRepository.offlineSubmissions, archiveExtractSession, offlinePacks, backgroundScope) }

    val instantSession: InstantSession by lazy {
        InstantSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, magnet ->
                InstantSheetState(
                    instantMagnetRepository,
                    driveRepository,
                    preferences,
                    previewTempFolder,
                    offlinePacks,
                    instantSaveRecords,
                    scope,
                    magnet,
                    saveTargetContext = saveTargets.context,
                )
            },
            newStateForDraft = { scope, saveScope, magnet, target ->
                InstantSheetState(
                    instantMagnetRepository, driveRepository, preferences, previewTempFolder,
                    offlinePacks, instantSaveRecords, scope, magnet,
                    saveTargetContext = target, saveScope = saveScope,
                )
            },
        )
    }

    val addLink by lazy { dev.piko.shared.presentation.AddLinkViewModel(instantSession) }

    /** 挂在进程级服务上，网盘页被压栈页临时盖住而离开组合时状态不丢；主动关掉面板即结束会话，见 [SegmentSession]。 */
    val segmentSession: SegmentSession by lazy { SegmentSession() }

    val duplicateSessions = androidx.compose.runtime.mutableStateListOf<DuplicateSession>()

    fun newDuplicateSession(): DuplicateSession =
        DuplicateSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, root -> DuplicateFinderState(clientManager, driveRepository, scope, root) },
        ).also(duplicateSessions::add)

    fun releaseDuplicateSession(session: DuplicateSession) {
        session.end()
        duplicateSessions.remove(session)
    }

    val duplicateSession: DuplicateSession by lazy { newDuplicateSession() }

    // 主线程且与进程同寿：会话在这个作用域上改 Compose 状态，离开网盘页后解压仍要继续
    val archiveExtractSession: ArchiveExtractSession by lazy {
        ArchiveExtractSession(
            clientProvider = clientManager,
            driveRepository = driveRepository,
            vault = archivePasswords,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    // 网盘页重建后，进过的压缩包的密码与列过的层还在，见 ArchiveBrowser
    val archiveBrowser: ArchiveBrowser by lazy {
        ArchiveBrowser(clientManager, archivePasswords, scratchFolder = { owner -> previewTempFolder.folderId(owner) })
    }

    // 主线程且与进程同寿：离开网盘页后归档仍要继续
    val folderVaultSession: FolderVaultSession by lazy {
        FolderVaultSession(driveRepository, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { session ->
            session.conflictReason = { targets ->
                val commands = dev.piko.shared.presentation.FileCommands(driveRepository, fileWrites, vault = session, receiving = receivingActivity)
                (commands.vaultAvailability(targets) as? dev.piko.shared.presentation.ActionAvailability.Blocked)?.reason
            }
        }
    }

    // 点进行中的系统通知要看传输页。应用可能正冷启动、还没登录，所以是待办而不是事件，由主界面组合时取走，
    // 外部通知要求打开任务页，主界面出现后再消费。
    private val _openTransfersRequested = MutableStateFlow(false)
    val openTransfersRequested: StateFlow<Boolean> = _openTransfersRequested.asStateFlow()

    fun requestOpenTransfers() {
        _openTransfersRequested.value = true
    }

    fun consumeOpenTransfersRequest() {
        _openTransfersRequested.value = false
    }

    // 切到传输页时一并给的提示（如刚存下默认下载画质）。发出时传输页还没组合出来，SharedFlow 没有订阅者会丢掉，
    // 所以用 Channel 存着，等传输页取走
    private val transfersNotices = Channel<String>(Channel.BUFFERED)
    val transfersNotice: Flow<String> = transfersNotices.receiveAsFlow()

    fun noticeOnTransfers(message: String) {
        transfersNotices.trySend(message)
    }

    // 主线程且与进程同寿：打开完整播放器时随机片段页可能被销毁，队列要留着回来接着看
    val clipFeedSession: ClipFeedSession by lazy {
        ClipFeedSession(clientManager, driveRepository, mediaRepository, cacheStore, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    init {
        // 未完成的添加链接、查重、解压、片段下载、信息流与归档都属于上一个账号：保存目标、文件 ID 都是那边的。
        // 主线程上当场结束，会话的状态只在主线程上改
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launchOnAccountLeave(clientManager) { left ->
            val transition = if (clientManager.currentClient.value == null) "Signed out" else "Switched account"
            PikoLog.i(TAG, "$transition: ending process-scoped sessions for previous account")
            presentationStores.leave(left)
            if (taskCenterAccount == left) { taskCenterAccount = null; taskCenterState = null }
            fileWrites.clear(left)
            historyEdits.clear(left)
            vaultEntryEdits.clear(left)
            instantSaveRecords.clearAttempts(left)
            instantMagnetRepository.offlineSubmissions.clear(left)
            driveRepository.localMounts.access?.cancelPending()
            driveRepository.enterAccount(clientManager.currentClient.value?.account)
            _fileHighlightRequested.value?.takeIf { it.account == left }?.let(::consumeFileHighlight)
            instantSession.clear()
            duplicateSessions.toList().forEach { it.end() }
            duplicateSessions.clear()
            archiveExtractSession.clear()
            archiveBrowser.clear()
            segmentSession.end()
            clipFeedSession.endAccount(left)
            folderVaultSession.cancel()
        }
        backgroundScope.launch {
            val cleanedAccounts = mutableSetOf<String>()
            clientManager.currentClient.collectLatest { client ->
                if (client == null) return@collectLatest
                // 上次进程被杀时面板来不及清 Piko-Temp，登录后补上。每个账号只清一次：断线重连
                // 也会换一个新的 client，那时面板可能正开着，预览的文件还要用
                if (cleanedAccounts.add(client.account)) {
                    previewTempFolder.clear(client).logFailure(TAG, "Failed to clean up Piko-Temp after sign-in")
                }
                // 换号或退出登录时 collectLatest 取消它，换成新账号的记录重来
                offlinePacks.run(client.account)
            }
        }
    }
}

private const val TAG = "Services"

val LocalPikoServices = staticCompositionLocalOf<PikoServices> {
    error("PikoServices 未提供，入口要用 PikoApp 包一层")
}
