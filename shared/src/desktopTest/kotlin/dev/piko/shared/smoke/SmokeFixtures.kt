package dev.piko.shared.smoke

import dev.piko.data.auth.CachePolicyDefaults
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.data.auth.SnailMode
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccounts
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** 内存偏好。只有被测路径读写的几项有真实行为，其余给固定值。 */
class MemoryPreferences : PikoUserPreferences {
    override val saveAsReferenceFlow = MutableStateFlow(false)
    override suspend fun setSaveAsReference(enabled: Boolean) { saveAsReferenceFlow.value = enabled }

    private val positions = ConcurrentHashMap<String, Long>()

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        positions[fileId] = positionMs
    }
    override suspend fun getPlaybackPosition(fileId: String): Long = positions[fileId] ?: 0L
    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) = Unit
    override suspend fun getLastFolder(): Triple<String, String, String> = Triple("", "我的网盘", "")
    override val spoilerBlurFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) = Unit
    override val autoCheckUpdatesFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setAutoCheckUpdates(enabled: Boolean) = Unit
    override val autoDetectClipboardLinksFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setAutoDetectClipboardLinks(enabled: Boolean) = Unit
    override val reduceMotionFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setReduceMotion(enabled: Boolean) = Unit
    override val hardwareDecodingFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setHardwareDecoding(enabled: Boolean) = Unit
    override val playbackMaxHeightFlow: Flow<Int> = MutableStateFlow(PlayerGestureDefaults.PLAYBACK_AUTO_HEIGHT)
    override suspend fun setPlaybackMaxHeight(height: Int) = Unit
    override val downloadMaxHeightFlow = MutableStateFlow<Int?>(null)
    override suspend fun setDownloadMaxHeight(height: Int?) { downloadMaxHeightFlow.value = height }
    override val playerSeekStepSecondsFlow: Flow<Int> = MutableStateFlow(10)
    override suspend fun setPlayerSeekStepSeconds(seconds: Int) = Unit
    override val playerBoostSpeedFlow: Flow<Float> = MutableStateFlow(2f)
    override suspend fun setPlayerBoostSpeed(speed: Float) = Unit
    override val clipAutoExtendFlow = MutableStateFlow(true)
    override suspend fun setClipAutoExtend(enabled: Boolean) { clipAutoExtendFlow.value = enabled }
    override val playerLayoutPrefsFlow = MutableStateFlow(PlayerLayoutPrefs.Default)
    override suspend fun setPlayerLayoutPrefs(prefs: PlayerLayoutPrefs) { playerLayoutPrefsFlow.value = prefs.sanitize() }
    override val driveAppearancePrefsFlow = MutableStateFlow(DriveAppearancePrefs.DEFAULT)
    override suspend fun setDriveAppearancePrefs(prefs: DriveAppearancePrefs) { driveAppearancePrefsFlow.value = prefs }
    override val skipFeedExitConfirmFlow = MutableStateFlow(false)
    override suspend fun setSkipFeedExitConfirm(skip: Boolean) { skipFeedExitConfirmFlow.value = skip }
    override val cacheMaxBytesFlow = MutableStateFlow(CachePolicyDefaults.DEFAULT_MAX_BYTES)
    override suspend fun setCacheMaxBytes(bytes: Long) { cacheMaxBytesFlow.value = bytes.coerceAtLeast(0L) }
    override val cacheMaxAgeDaysFlow = MutableStateFlow(CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS)
    override suspend fun setCacheMaxAgeDays(days: Int) { cacheMaxAgeDaysFlow.value = days.coerceAtLeast(0) }
    override val folderMapOpenFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setFolderMapOpen(open: Boolean) = Unit
    override val folderMapPinnedFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setFolderMapPinned(pinned: Boolean) = Unit
    override val heuristicFilterFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) = Unit
    override val nameParsingFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setNameParsingEnabled(enabled: Boolean) = Unit
    override val bundleSubtitlesFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setBundleSubtitlesEnabled(enabled: Boolean) = Unit
    override val autoCleanNamesFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setAutoCleanNamesEnabled(enabled: Boolean) = Unit
    override val settingsSyncFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setSettingsSyncEnabled(enabled: Boolean) = Unit
    override val syncPlayHistoryFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setSyncPlayHistoryEnabled(enabled: Boolean) = Unit
    override val themeModeFlow = MutableStateFlow<String?>(null)
    override suspend fun setThemeMode(mode: String) { themeModeFlow.value = mode }
    override val themeSeedFlow = MutableStateFlow<String?>(null)
    override suspend fun setThemeSeed(seed: String?) { themeSeedFlow.value = seed }
    override val pureBlackFlow = MutableStateFlow(false)
    override suspend fun setPureBlack(enabled: Boolean) { pureBlackFlow.value = enabled }
    override val einkModeFlow = MutableStateFlow(false)
    override suspend fun setEinkMode(enabled: Boolean) { einkModeFlow.value = enabled }
    override val driveViewModeFlow: Flow<String> = MutableStateFlow("LIST")
    override suspend fun setDriveViewMode(mode: String) = Unit
    override fun driveTileSizeFlow(view: String): Flow<String> = MutableStateFlow("")
    override suspend fun setDriveTileSize(view: String, size: String) = Unit
    override val sidebarCollapsedFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setSidebarCollapsed(collapsed: Boolean) = Unit
    override val showExtensionsFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setShowExtensions(show: Boolean) = Unit
    override val clipPanelFlow: Flow<SidePanelPrefs> = MutableStateFlow(SidePanelPrefs(open = false, widthDp = null))
    override suspend fun setClipPanelOpen(open: Boolean) = Unit
    override suspend fun setClipPanelWidth(widthDp: Float) = Unit
    override val pikpakDomainFlow: Flow<String> = MutableStateFlow("")
    override suspend fun setPikpakDomain(root: String) = Unit
    override val snailModeFlow: Flow<SnailMode> = MutableStateFlow(SnailMode())
    override suspend fun setSnailMode(mode: SnailMode) = Unit
    override val maxConcurrentDownloadsFlow = MutableStateFlow(dev.piko.data.auth.DownloadConcurrencyDefaults.DEFAULT)
    override suspend fun setMaxConcurrentDownloads(count: Int) {
        maxConcurrentDownloadsFlow.value = dev.piko.data.auth.DownloadConcurrencyDefaults.sanitize(count)
    }
    override val clipVolumeFlow = MutableStateFlow(1f)
    override val clipMutedFlow = MutableStateFlow(false)
    override suspend fun setClipAudio(volume: Float, muted: Boolean) {
        clipVolumeFlow.value = volume.coerceIn(0f, 1f)
        clipMutedFlow.value = muted
    }
    override val motionPreferenceFlow = MutableStateFlow<String?>(null)
    override suspend fun setMotionPreference(level: String?) { motionPreferenceFlow.value = level }
    override val predictiveBackFlow = MutableStateFlow(true)
    override suspend fun setPredictiveBack(enabled: Boolean) { predictiveBackFlow.value = enabled }
    override val currentSnapshot: dev.piko.data.auth.PikoPreferencesSnapshot
        get() = dev.piko.data.auth.PikoPreferencesSnapshot(
            clipVolume = clipVolumeFlow.value,
            clipMuted = clipMutedFlow.value,
            motionPreference = motionPreferenceFlow.value,
            predictiveBack = predictiveBackFlow.value,
            maxConcurrentDownloads = maxConcurrentDownloadsFlow.value,
        )
    override val downloadDirPathFlow: Flow<String> = MutableStateFlow("")
    override suspend fun setDownloadDirPath(path: String) = Unit
    override suspend fun getDownloadDirPath(): String = ""
    override val askDownloadLocationFlow = MutableStateFlow(false)
    override suspend fun setAskDownloadLocation(enabled: Boolean) { askDownloadLocationFlow.value = enabled }
    @Volatile var downloadTasks: String = ""
    override suspend fun loadDownloadTasks(): String = downloadTasks
    override suspend fun saveDownloadTasks(serialized: String) {
        downloadTasks = serialized
    }
    @Volatile var uploadTasks: String = ""
    override suspend fun loadUploadTasks(): String = uploadTasks
    override suspend fun saveUploadTasks(serialized: String) {
        uploadTasks = serialized
    }
    val uploadCredentials = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun loadUploadCredentials(taskId: String): String? = uploadCredentials[taskId]
    override suspend fun saveUploadCredentials(taskId: String, serialized: String) {
        uploadCredentials[taskId] = serialized
    }
    override suspend fun clearUploadCredentials(taskId: String) {
        uploadCredentials.remove(taskId)
    }
    @Volatile var offlinePacks: String = ""
    override suspend fun loadOfflinePacks(): String = offlinePacks
    override suspend fun saveOfflinePacks(serialized: String) {
        offlinePacks = serialized
    }
    /** 1.1.0 全机一份的压缩包密码，测迁移时预置。 */
    @Volatile var legacyArchivePasswords = ""
    override suspend fun loadLegacyArchivePasswords(): String = legacyArchivePasswords
    override suspend fun clearLegacyArchivePasswords() {
        legacyArchivePasswords = ""
    }
    private val recentMoveTargets = MutableStateFlow("")
    override val recentMoveTargetsFlow: Flow<String> = recentMoveTargets
    override suspend fun saveRecentMoveTargets(serialized: String) { recentMoveTargets.value = serialized }
    private val pinnedFolders = MutableStateFlow("")
    override val pinnedFoldersFlow: Flow<String> = pinnedFolders
    override suspend fun savePinnedFolders(serialized: String) { pinnedFolders.value = serialized }
    private val perFolderSort = MutableStateFlow(false)
    override val perFolderSortFlow: Flow<Boolean> = perFolderSort
    override suspend fun setPerFolderSort(enabled: Boolean) { perFolderSort.value = enabled }
    private val folderSortOrders = MutableStateFlow("")
    override val folderSortOrdersFlow: Flow<String> = folderSortOrders
    override suspend fun saveFolderSortOrders(serialized: String) { folderSortOrders.value = serialized }
    override val batchRenameFlow: Flow<String> = MutableStateFlow("")
    override suspend fun saveBatchRename(serialized: String) = Unit
    override val vaultArchiveOptionsFlow: Flow<String> = MutableStateFlow("")
    override suspend fun saveVaultArchiveOptions(serialized: String) = Unit
    override val renameRegexTextModeFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setRenameRegexTextMode(enabled: Boolean) = Unit
    override val proxySettingFlow: Flow<ProxySetting> = MutableStateFlow(ProxySetting())
    override suspend fun saveProxySetting(setting: ProxySetting) = Unit
    override suspend fun getIgnoredUpdateVersion(): String? = null
    override suspend fun setIgnoredUpdateVersion(version: String) = Unit
    override val metaTubeUrlFlow = MutableStateFlow("")
    override suspend fun setMetaTubeUrl(url: String) { metaTubeUrlFlow.value = url }
    private val metaTubeUrlsByAccount = ConcurrentHashMap<String, MutableStateFlow<String>>()
    override fun metaTubeUrlFlowFor(account: String): Flow<String> =
        if (account.isBlank()) MutableStateFlow("") else metaTubeUrlsByAccount.computeIfAbsent(account) { MutableStateFlow("") }
    override suspend fun setMetaTubeUrlFor(account: String, url: String) {
        if (account.isNotBlank()) {
            metaTubeUrlsByAccount.computeIfAbsent(account) { MutableStateFlow("") }.value = url
        }
    }
    override val metaTubeTokenFlow = MutableStateFlow("")
    override suspend fun setMetaTubeToken(token: String) { metaTubeTokenFlow.value = token }
    private val metaTubeTokensByAccount = ConcurrentHashMap<String, MutableStateFlow<String>>()
    override fun metaTubeTokenFlowFor(account: String): Flow<String> =
        if (account.isBlank()) MutableStateFlow("") else metaTubeTokensByAccount.computeIfAbsent(account) { MutableStateFlow("") }
    override suspend fun setMetaTubeTokenFor(account: String, token: String) {
        if (account.isNotBlank()) {
            metaTubeTokensByAccount.computeIfAbsent(account) { MutableStateFlow("") }.value = token
        }
    }
}

/** 内存会话存储，行为与两端的实现一致：会话、上次账号、密码三份各自独立。 */
class MemorySessionStore : PikoSessionStore {
    val sessions = ConcurrentHashMap<String, Session>()
    val passwords = ConcurrentHashMap<String, String>()
    @Volatile var accounts = SavedAccounts()

    override suspend fun load(account: String): Session? = sessions[account]
    override suspend fun save(account: String, session: Session) {
        sessions[account] = session
    }
    override suspend fun clear(account: String) {
        sessions.remove(account)
    }
    override suspend fun loadAccounts(): SavedAccounts = accounts
    override suspend fun saveAccounts(accounts: SavedAccounts) {
        this.accounts = accounts
    }
    override suspend fun loadCredentials(account: String): PikoCredentials? =
        passwords[account]?.let { PikoCredentials(account, it) }
    override suspend fun saveCredentials(account: String, password: String) {
        passwords[account] = password
    }
    override suspend fun clearCredentials(account: String) {
        passwords.remove(account)
    }
    val syncSecrets = ConcurrentHashMap<String, String>()
    override suspend fun loadSyncSecret(account: String): String? = syncSecrets[account]
    override suspend fun saveSyncSecret(account: String, secret: String) {
        if (secret.isEmpty()) syncSecrets.remove(account) else syncSecrets[account] = secret
    }
    override suspend fun clearSyncSecret(account: String) {
        syncSecrets.remove(account)
    }
    val archivePasswords = ConcurrentHashMap<String, String>()
    override suspend fun loadArchivePasswords(account: String): String = archivePasswords[account].orEmpty()
    override suspend fun saveArchivePasswords(account: String, serialized: String) {
        archivePasswords[account] = serialized
    }
}

/**
 * 呈现状态与断言在同一个串行作用域执行，贴近生产环境的 Main 读写约束。
 * 仓库、SDK 与下载的 IO 仍在真实 Default/IO 调度器上运行，不使用虚拟时间。
 */
private val smokeLogDirectory by lazy { java.nio.file.Files.createTempDirectory("piko-smoke-log").toString() }

fun smoke(block: suspend CoroutineScope.(scope: CoroutineScope) -> Unit) {
    dev.piko.shared.log.PikoLog.install(smokeLogDirectory)
    java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "piko-smoke-presentation").apply { isDaemon = true }
    }.asCoroutineDispatcher().use { presentationDispatcher ->
        runBlocking(presentationDispatcher) {
            val scope = CoroutineScope(SupervisorJob() + presentationDispatcher)
            try {
                withTimeout(30_000) { block(scope) }
            } catch (error: Throwable) {
                val logs = kotlinx.coroutines.withTimeoutOrNull(2_000) { dev.piko.shared.log.PikoLog.export() }
                System.err.println(logs?.lines()?.takeLast(40)?.joinToString("\n"))
                throw error
            } finally {
                withTimeout(30_000) { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
            }
        }
    }
}

suspend fun awaitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        // 无 Recomposer 的呈现状态测试也要推进全局通知，snapshotFlow 才能观察异步写入。
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) throw AssertionError("超时仍未满足：$description")
        delay(20)
    }
}

/** 缓存目录的内存版：同一个实例交给两个仓库，就是「重启后读到上次写的」。 */
class MemoryCacheStore : dev.piko.shared.data.PikoCacheStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun read(key: String): String? = map[key]
    override suspend fun write(key: String, value: String) {
        map[key] = value
    }
}
