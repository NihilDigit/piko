package dev.piko.shared.smoke

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.data.auth.SnailMode
import dev.piko.shared.net.ProxySetting
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccounts
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** 内存偏好。只有被测路径读写的几项有真实行为，其余给固定值。 */
class MemoryPreferences : PikoUserPreferences {
    private val positions = ConcurrentHashMap<String, Long>()

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        positions[fileId] = positionMs
    }
    override suspend fun getPlaybackPosition(fileId: String): Long = positions[fileId] ?: 0L
    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) = Unit
    override suspend fun getLastFolder(): Triple<String, String, String> = Triple("", "网盘", "")
    override val spoilerBlurFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) = Unit
    override val autoCheckUpdatesFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setAutoCheckUpdates(enabled: Boolean) = Unit
    override val reduceMotionFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setReduceMotion(enabled: Boolean) = Unit
    override val hardwareDecodingFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setHardwareDecoding(enabled: Boolean) = Unit
    override val playbackMaxHeightFlow: Flow<Int> = MutableStateFlow(0)
    override suspend fun setPlaybackMaxHeight(height: Int) = Unit
    override val downloadMaxHeightFlow = MutableStateFlow(0)
    override suspend fun setDownloadMaxHeight(height: Int) { downloadMaxHeightFlow.value = height }
    override val playerSeekStepSecondsFlow: Flow<Int> = MutableStateFlow(10)
    override suspend fun setPlayerSeekStepSeconds(seconds: Int) = Unit
    override val playerBoostSpeedFlow: Flow<Float> = MutableStateFlow(2f)
    override suspend fun setPlayerBoostSpeed(speed: Float) = Unit
    override val folderMapOpenFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setFolderMapOpen(open: Boolean) = Unit
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
    override val themeModeFlow: Flow<String?> = MutableStateFlow(null)
    override suspend fun setThemeMode(mode: String) = Unit
    override val themeSeedFlow: Flow<String?> = MutableStateFlow(null)
    override suspend fun setThemeSeed(seed: String?) = Unit
    override val driveViewModeFlow: Flow<String> = MutableStateFlow("LIST")
    override suspend fun setDriveViewMode(mode: String) = Unit
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
    private val acceleration = MutableStateFlow(true)
    override val concurrentAccelerationFlow: Flow<Boolean> = acceleration
    override val concurrentConnectionsFlow: Flow<Int> = acceleration.map { if (it) 4 else 1 }
    override val downloadDirPathFlow: Flow<String> = MutableStateFlow("")
    override suspend fun setDownloadDirPath(path: String) = Unit
    override suspend fun getDownloadDirPath(): String = ""
    override suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) {
        acceleration.value = enabled
    }
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
    override val archivePasswordsFlow = MutableStateFlow("")
    override suspend fun saveArchivePasswords(serialized: String) {
        archivePasswordsFlow.value = serialized
    }
    override val recentMoveTargetsFlow: Flow<String> = MutableStateFlow("")
    override suspend fun saveRecentMoveTargets(serialized: String) = Unit
    override val pinnedFoldersFlow: Flow<String> = MutableStateFlow("")
    override suspend fun savePinnedFolders(serialized: String) = Unit
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
    override val metaTubeTokenFlow = MutableStateFlow("")
    override suspend fun setMetaTubeToken(token: String) { metaTubeTokenFlow.value = token }
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
}

/**
 * 冒烟测试的运行环境。状态类跑在真实的 Dispatchers.Default 上：仓库层自己会切到
 * Default，虚拟时间管不到那里，与其半真半假，不如全用真实时间并按条件等待。
 */
fun smoke(block: suspend CoroutineScope.(scope: CoroutineScope) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        withTimeout(30_000) { block(scope) }
    } finally {
        scope.cancel()
    }
}

suspend fun awaitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
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
