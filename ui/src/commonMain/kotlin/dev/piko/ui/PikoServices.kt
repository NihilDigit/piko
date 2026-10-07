package dev.piko.ui

import dev.piko.shared.sync.ArchivePasswordSync
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.sync.SyncCipher
import dev.piko.shared.sync.VaultTreeSync
import dev.piko.ui.components.SegmentSession
import androidx.compose.runtime.staticCompositionLocalOf
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MoveHistory
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
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
import dev.piko.shared.state.CanonicalNamingState
import dev.piko.shared.state.FolderTaskSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.net.PikPakDomainSelector
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.scrape.MetaTubeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
    /** Android 在这里拉起前台服务。 */
    onUploadStarted: (() -> Unit)? = null,
    /** 记下的文件夹内容跨进程保留在这里，见 FolderContentMemory。 */
    cacheStore: PikoCacheStore? = null,
    /** 压缩包密码同步进网盘前的加密，两端都传 JvmSyncCipher；为 null 时不同步它们（截图环境）。 */
    syncCipher: SyncCipher? = null,
    /** 用户自己部署的 MetaTube，两端传入；为 null 时设置里没有刮削一组（截图环境）。 */
    val metaTube: MetaTubeService? = null,
) {
    // 不随任何界面结束的后台工作：离线任务的跟踪与 Piko-Temp 的清理
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 平台偏好，其中记着文件夹 ID 的几项换成按账号存的，见 AccountScopedPreferences。 */
    val preferences: PikoUserPreferences =
        cacheStore?.let { AccountScopedPreferences(platformPreferences, clientManager, it) } ?: platformPreferences

    val driveRepository: DriveRepository = DriveRepository(clientManager, preferences, cacheStore)
    val accountRepository: PikoAccountRepository = PikoAccountRepository(clientManager, driveRepository, backgroundScope)
    val instantMagnetRepository: InstantMagnetRepository = InstantMagnetRepository(clientManager)
    val taskRepository: TaskRepository = TaskRepository(clientManager, driveRepository)

    val previewTempFolder = PreviewTempFolder(driveRepository, instantMagnetRepository, backgroundScope)

    init {
        // 打开归档条目时借的对象放进 Piko-Temp：取到直链就删，偶有删不掉的也随 Piko-Temp 一起清走
        mediaRepository.leaseFolder = { previewTempFolder.folderId().getOrThrow() }
    }

    val vaultStore: VaultStore get() = driveRepository.vault

    val offlinePacks = OfflinePackTracker(instantMagnetRepository, driveRepository, preferences)

    val moveHistory = MoveHistory(preferences, driveRepository, backgroundScope)

    /** 部分设置同步到网盘的 .piko 文件夹，登录后自己开始，见 PikoSettingsSync。 */
    val settingsSync = PikoSettingsSync(clientManager, driveRepository, preferences, cacheStore, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val vaultTreeSync = VaultTreeSync(clientManager, driveRepository, backgroundScope, preferences.settingsSyncFlow)
        .also { it.start() }
    private val archivePasswordSync = syncCipher?.let {
        ArchivePasswordSync(clientManager, driveRepository, preferences, it, backgroundScope, preferences.settingsSyncFlow).also { sync -> sync.start() }
    }

    /** API 走哪个根域名：用户固定的，或登录后测速自动挑的，见 PikPakDomainSelector。 */
    val domainSelector = PikPakDomainSelector(clientManager, preferences, backgroundScope).also { it.start() }

    val uploadManager = PikoUploadCoordinator(clientManager, preferences, uploadSources, driveRepository, backgroundScope, onUploadStarted)

    val instantSaveRecords = InstantSaveRecords(clientManager, cacheStore, backgroundScope)

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
                    metaTube,
                )
            },
        )
    }

    /** 下载片段的面板收起后选好的区间还在，见 [SegmentSession]。 */
    val segmentSession: SegmentSession by lazy { SegmentSession() }

    val duplicateSession: DuplicateSession by lazy {
        DuplicateSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, root -> DuplicateFinderState(clientManager, driveRepository, scope, root) },
        )
    }

    /** 按番号规范命名一个文件夹，与查找重复同一种会话，见 CanonicalNamingState。 */
    val canonicalNamingSession: FolderTaskSession<CanonicalNamingState> by lazy {
        FolderTaskSession(
            newScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
            newState = { scope, root -> CanonicalNamingState(clientManager, driveRepository, metaTube, scope, root) },
        )
    }

    // 主线程且与进程同寿：会话在这个作用域上改 Compose 状态，离开网盘页后解压仍要继续
    val archiveExtractSession: ArchiveExtractSession by lazy {
        ArchiveExtractSession(
            clientProvider = clientManager,
            driveRepository = driveRepository,
            preferences = preferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    // 网盘页重建后，进过的压缩包的密码与列过的层还在，见 ArchiveBrowser
    val archiveBrowser: ArchiveBrowser by lazy {
        ArchiveBrowser(clientManager, preferences, scratchFolder = { previewTempFolder.folderId() })
    }

    // 主线程且与进程同寿：离开网盘页后归档仍要继续
    val folderVaultSession: FolderVaultSession by lazy {
        FolderVaultSession(driveRepository, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    // 点进行中的系统通知要看传输页。应用可能正冷启动、还没登录，所以是待办而不是事件，由主界面组合时取走，
    // 同 driveRepository.openDriveRequested
    private val _openTransfersRequested = MutableStateFlow(false)
    val openTransfersRequested: StateFlow<Boolean> = _openTransfersRequested.asStateFlow()

    fun requestOpenTransfers() {
        _openTransfersRequested.value = true
    }

    fun consumeOpenTransfersRequest() {
        _openTransfersRequested.value = false
    }

    // 主线程且与进程同寿：打开完整播放器时随机片段页可能被销毁，队列要留着回来接着看
    val clipFeedSession: ClipFeedSession by lazy {
        ClipFeedSession(driveRepository, mediaRepository, cacheStore, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    init {
        // 未完成的添加链接、查重、规范命名、解压、信息流与归档都属于上一个账号：保存目标、文件 ID 都是那边的。
        // 退出到登录页也算，同一账号登回来不算
        backgroundScope.launch {
            var previous: String? = null
            clientManager.currentClient.map { it?.account }.distinctUntilChanged().collect { account ->
                if (previous != null && account != previous) {
                    PikoLog.i(TAG, "${if (account == null) "退出登录" else "换号"}：结束上一账号的添加链接、查重、规范命名、解压、信息流与归档会话")
                    withContext(Dispatchers.Main) {
                        instantSession.end()
                        duplicateSession.end()
                        canonicalNamingSession.end()
                        archiveExtractSession.clear()
                        archiveBrowser.clear()
                        clipFeedSession.close()
                        folderVaultSession.cancel()
                    }
                }
                if (account != null) previous = account
            }
        }
        backgroundScope.launch {
            val cleanedAccounts = mutableSetOf<String>()
            clientManager.currentClient.collectLatest { client ->
                if (client == null) return@collectLatest
                // 上次进程被杀时面板来不及清 Piko-Temp，登录后补上。每个账号只清一次：断线重连
                // 也会换一个新的 client，那时面板可能正开着，预览的文件还要用
                if (cleanedAccounts.add(client.account)) previewTempFolder.clear().logFailure(TAG, "登录后清理 Piko-Temp 失败")
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
