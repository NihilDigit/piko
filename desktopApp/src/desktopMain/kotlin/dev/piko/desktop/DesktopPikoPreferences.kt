package dev.piko.desktop

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.data.auth.SnailMode
import dev.piko.shared.auth.SecretVault
import dev.piko.shared.log.PikoLog
import dev.piko.shared.net.ProxySetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PikoUserPreferences 的桌面实现：写穿到 ~/.piko/settings.properties。
 * 其中的机密（解压密码、上传凭据）不进这个明文文件，交给 [openSecrets] 给出的保管处，见 desktopPreferenceSecrets。
 * 保管处在第一次用到时才在 IO 线程上打开：Linux 上要先经 D-Bus 试探，macOS 每次存取都起一个 security 进程，不能压在启动路径上。
 */
class DesktopPikoPreferences(
    private val settings: DesktopSettingsStore,
    openSecrets: () -> SecretVault,
) : PikoUserPreferences {
    private val secrets by lazy(openSecrets)
    private val secretsLock = Mutex()

    private val spoiler = MutableStateFlow(settings.get(KEY_SPOILER, "true").toBoolean())
    private val autoCheckUpdates = MutableStateFlow(settings.get(KEY_AUTO_CHECK_UPDATES, "true").toBoolean())
    private val reduceMotion = MutableStateFlow(settings.get(KEY_REDUCE_MOTION) == "true")
    private val hardwareDecoding = MutableStateFlow(settings.get(KEY_HARDWARE_DECODING, "true").toBoolean())
    private val playbackMaxHeight = MutableStateFlow(settings.get(KEY_PLAYBACK_MAX_HEIGHT).toIntOrNull() ?: 0)
    private val downloadMaxHeight = MutableStateFlow(settings.get(KEY_DOWNLOAD_MAX_HEIGHT).toIntOrNull())
    private val playerSeekStepSeconds = MutableStateFlow(
        settings.get(KEY_PLAYER_SEEK_STEP_SECONDS).toIntOrNull() ?: PlayerGestureDefaults.SEEK_STEP_SECONDS,
    )
    private val playerBoostSpeed = MutableStateFlow(
        settings.get(KEY_PLAYER_BOOST_SPEED).toFloatOrNull() ?: PlayerGestureDefaults.BOOST_SPEED,
    )
    private val folderMapOpen = MutableStateFlow(settings.get(KEY_FOLDER_MAP_OPEN) == "true")
    private val folderMapPinned = MutableStateFlow(settings.get(KEY_FOLDER_MAP_PINNED) == "true")
    private val heuristic = MutableStateFlow(settings.get(KEY_HEURISTIC, "true").toBoolean())
    private val nameParsing = MutableStateFlow(settings.get(KEY_NAME_PARSING, "true").toBoolean())
    private val bundleSubtitles = MutableStateFlow(settings.get(KEY_BUNDLE_SUBTITLES, "true").toBoolean())
    private val autoCanonicalNames = MutableStateFlow(settings.get(KEY_AUTO_CANONICAL_NAMES, "false").toBoolean())
    private val autoCleanNames = MutableStateFlow(settings.get(KEY_AUTO_CLEAN_NAMES, "false").toBoolean())
    private val settingsSync = MutableStateFlow(settings.get(KEY_SETTINGS_SYNC, "true").toBoolean())
    private val syncPlayHistory = MutableStateFlow(settings.get(KEY_SYNC_PLAY_HISTORY, "true").toBoolean())
    private val themeMode = MutableStateFlow(settings.get(KEY_THEME_MODE).ifEmpty { null })
    private val themeSeed = MutableStateFlow(settings.get(KEY_THEME_SEED).ifEmpty { null })
    // 旧版只存了是否海报墙，没有新键时由它换算
    private val driveViewMode = MutableStateFlow(
        // 没选过时是海报墙，与 Android 相同；旧版只存了是否网格，明确关掉过的仍是列表
        settings.get(KEY_DRIVE_VIEW_MODE).ifEmpty { if (settings.get(KEY_GRID_VIEW, "true").toBoolean()) "POSTER" else "LIST" },
    )
    private val sidebarCollapsed = MutableStateFlow(settings.get(KEY_SIDEBAR_COLLAPSED) == "true")
    private val showExtensions = MutableStateFlow(settings.get(KEY_SHOW_EXTENSIONS, "true").toBoolean())
    private val clipPanel = MutableStateFlow(
        SidePanelPrefs(
            open = settings.get(KEY_CLIP_PANEL_OPEN, "false").toBoolean(),
            widthDp = settings.get(KEY_CLIP_PANEL_WIDTH).toFloatOrNull(),
        ),
    )
    private val pikpakDomain = MutableStateFlow(settings.get(KEY_PIKPAK_DOMAIN))
    private val snailMode = MutableStateFlow(
        SnailMode(
            enabled = settings.get(KEY_SNAIL_ENABLED, "false").toBoolean(),
            downloadKiBps = settings.get(KEY_SNAIL_DOWNLOAD).toIntOrNull() ?: SnailMode.DEFAULT_DOWNLOAD_KIBPS,
            uploadKiBps = settings.get(KEY_SNAIL_UPLOAD).toIntOrNull() ?: SnailMode.DEFAULT_UPLOAD_KIBPS,
        ),
    )
    private val acceleration = MutableStateFlow(settings.get(KEY_ACCELERATION, "true").toBoolean())
    private val connections = MutableStateFlow(settings.get(KEY_CONNECTIONS, "8").toIntOrNull() ?: 8)
    /** null 是还没从保管处读出来。 */
    private val archivePasswords = MutableStateFlow<String?>(null)
    private val recentMoveTargets = MutableStateFlow(settings.get(KEY_RECENT_MOVE_TARGETS))
    private val pinnedFolders = MutableStateFlow(settings.get(KEY_PINNED_FOLDERS))
    private val batchRename = MutableStateFlow(settings.get(KEY_BATCH_RENAME))
    private val vaultArchiveOptions = MutableStateFlow(settings.get(KEY_VAULT_ARCHIVE_OPTIONS))
    private val renameRegexTextMode = MutableStateFlow(settings.get(KEY_RENAME_REGEX_TEXT_MODE) == "true")
    private val proxySetting = MutableStateFlow(ProxySetting.decode(settings.get(KEY_PROXY_SETTING)))
    private val downloadPath = MutableStateFlow(
        settings.get(KEY_DOWNLOAD_DIR, settings.downloadDirectory.absolutePath),
    )

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        settings.set(KEY_PLAYBACK_PREFIX + fileId, positionMs.toString())
        // 播放进度无限涨，超上限时清掉最旧的一批（key 有序，fileId 不是时间序，
        // 粗粒度清理即可，丢的只是断点续播位置）。
        val keys = settings.keysWithPrefix(KEY_PLAYBACK_PREFIX)
        if (keys.size > MAX_PLAYBACK_ENTRIES) {
            keys.take(keys.size - MAX_PLAYBACK_ENTRIES).forEach(settings::remove)
        }
    }

    override suspend fun getPlaybackPosition(fileId: String): Long =
        settings.get(KEY_PLAYBACK_PREFIX + fileId, "0").toLongOrNull() ?: 0L

    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) {
        settings.set(KEY_LAST_FOLDER_ID, folderId)
        settings.set(KEY_LAST_FOLDER_NAME, folderName)
        settings.set(KEY_LAST_FOLDER_STACK, stackSerialized)
    }

    override suspend fun getLastFolder(): Triple<String, String, String> =
        Triple(
            settings.get(KEY_LAST_FOLDER_ID),
            settings.get(KEY_LAST_FOLDER_NAME, "网盘"),
            settings.get(KEY_LAST_FOLDER_STACK),
        )

    override val spoilerBlurFlow: Flow<Boolean> = spoiler.asStateFlow()
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) {
        settings.set(KEY_SPOILER, enabled.toString())
        spoiler.value = enabled
    }

    override val autoCheckUpdatesFlow: Flow<Boolean> = autoCheckUpdates.asStateFlow()
    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        settings.set(KEY_AUTO_CHECK_UPDATES, enabled.toString())
        autoCheckUpdates.value = enabled
    }

    override val reduceMotionFlow: Flow<Boolean> = reduceMotion.asStateFlow()
    override suspend fun setReduceMotion(enabled: Boolean) {
        settings.set(KEY_REDUCE_MOTION, enabled.toString())
        reduceMotion.value = enabled
    }

    override val hardwareDecodingFlow: Flow<Boolean> = hardwareDecoding.asStateFlow()
    override suspend fun setHardwareDecoding(enabled: Boolean) {
        settings.set(KEY_HARDWARE_DECODING, enabled.toString())
        hardwareDecoding.value = enabled
    }

    override val playbackMaxHeightFlow: Flow<Int> = playbackMaxHeight.asStateFlow()
    override suspend fun setPlaybackMaxHeight(height: Int) {
        settings.set(KEY_PLAYBACK_MAX_HEIGHT, height.toString())
        playbackMaxHeight.value = height
    }

    override val downloadMaxHeightFlow: Flow<Int?> = downloadMaxHeight.asStateFlow()
    override suspend fun setDownloadMaxHeight(height: Int?) {
        if (height == null) settings.remove(KEY_DOWNLOAD_MAX_HEIGHT) else settings.set(KEY_DOWNLOAD_MAX_HEIGHT, height.toString())
        downloadMaxHeight.value = height
    }

    override val playerSeekStepSecondsFlow: Flow<Int> = playerSeekStepSeconds.asStateFlow()
    override suspend fun setPlayerSeekStepSeconds(seconds: Int) {
        settings.set(KEY_PLAYER_SEEK_STEP_SECONDS, seconds.toString())
        playerSeekStepSeconds.value = seconds
    }

    override val playerBoostSpeedFlow: Flow<Float> = playerBoostSpeed.asStateFlow()
    override suspend fun setPlayerBoostSpeed(speed: Float) {
        settings.set(KEY_PLAYER_BOOST_SPEED, speed.toString())
        playerBoostSpeed.value = speed
    }

    override val folderMapOpenFlow: Flow<Boolean> = folderMapOpen.asStateFlow()
    override suspend fun setFolderMapOpen(open: Boolean) {
        settings.set(KEY_FOLDER_MAP_OPEN, open.toString())
        folderMapOpen.value = open
    }

    override val folderMapPinnedFlow: Flow<Boolean> = folderMapPinned.asStateFlow()
    override suspend fun setFolderMapPinned(pinned: Boolean) {
        settings.set(KEY_FOLDER_MAP_PINNED, pinned.toString())
        folderMapPinned.value = pinned
    }

    override val heuristicFilterFlow: Flow<Boolean> = heuristic.asStateFlow()
    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) {
        settings.set(KEY_HEURISTIC, enabled.toString())
        heuristic.value = enabled
    }

    override val nameParsingFlow: Flow<Boolean> = nameParsing.asStateFlow()
    override suspend fun setNameParsingEnabled(enabled: Boolean) {
        settings.set(KEY_NAME_PARSING, enabled.toString())
        nameParsing.value = enabled
    }

    override val bundleSubtitlesFlow: Flow<Boolean> = bundleSubtitles.asStateFlow()
    override suspend fun setBundleSubtitlesEnabled(enabled: Boolean) {
        settings.set(KEY_BUNDLE_SUBTITLES, enabled.toString())
        bundleSubtitles.value = enabled
    }
    override val autoCanonicalNamesFlow: Flow<Boolean> = autoCanonicalNames.asStateFlow()
    override suspend fun setAutoCanonicalNames(enabled: Boolean) {
        settings.set(KEY_AUTO_CANONICAL_NAMES, enabled.toString())
        autoCanonicalNames.value = enabled
    }

    override val autoCleanNamesFlow: Flow<Boolean> = autoCleanNames.asStateFlow()
    override suspend fun setAutoCleanNamesEnabled(enabled: Boolean) {
        settings.set(KEY_AUTO_CLEAN_NAMES, enabled.toString())
        autoCleanNames.value = enabled
    }

    override val settingsSyncFlow: Flow<Boolean> = settingsSync.asStateFlow()
    override suspend fun setSettingsSyncEnabled(enabled: Boolean) {
        settings.set(KEY_SETTINGS_SYNC, enabled.toString())
        settingsSync.value = enabled
    }

    override val syncPlayHistoryFlow: Flow<Boolean> = syncPlayHistory.asStateFlow()
    override suspend fun setSyncPlayHistoryEnabled(enabled: Boolean) {
        settings.set(KEY_SYNC_PLAY_HISTORY, enabled.toString())
        syncPlayHistory.value = enabled
    }

    override val themeModeFlow: Flow<String?> = themeMode.asStateFlow()
    override suspend fun setThemeMode(mode: String) {
        settings.set(KEY_THEME_MODE, mode)
        themeMode.value = mode
    }

    override val themeSeedFlow: Flow<String?> = themeSeed.asStateFlow()
    override suspend fun setThemeSeed(seed: String?) {
        settings.set(KEY_THEME_SEED, seed.orEmpty())
        themeSeed.value = seed
    }

    override val driveViewModeFlow: Flow<String> = driveViewMode.asStateFlow()
    override suspend fun setDriveViewMode(mode: String) {
        settings.set(KEY_DRIVE_VIEW_MODE, mode)
        driveViewMode.value = mode
    }

    private val tileSizes = mutableMapOf<String, MutableStateFlow<String>>()

    private fun tileSize(view: String): MutableStateFlow<String> = synchronized(tileSizes) {
        tileSizes.getOrPut(view) { MutableStateFlow(settings.get(tileSizeKey(view))) }
    }

    override fun driveTileSizeFlow(view: String): Flow<String> = tileSize(view).asStateFlow()
    override suspend fun setDriveTileSize(view: String, size: String) {
        settings.set(tileSizeKey(view), size)
        tileSize(view).value = size
    }

    override val sidebarCollapsedFlow: Flow<Boolean> = sidebarCollapsed.asStateFlow()
    override suspend fun setSidebarCollapsed(collapsed: Boolean) {
        settings.set(KEY_SIDEBAR_COLLAPSED, collapsed.toString())
        sidebarCollapsed.value = collapsed
    }

    override val showExtensionsFlow: Flow<Boolean> = showExtensions.asStateFlow()
    override suspend fun setShowExtensions(show: Boolean) {
        settings.set(KEY_SHOW_EXTENSIONS, show.toString())
        showExtensions.value = show
    }

    override val clipPanelFlow: Flow<SidePanelPrefs> = clipPanel.asStateFlow()
    override suspend fun setClipPanelOpen(open: Boolean) {
        settings.set(KEY_CLIP_PANEL_OPEN, open.toString())
        clipPanel.value = clipPanel.value.copy(open = open)
    }
    override suspend fun setClipPanelWidth(widthDp: Float) {
        settings.set(KEY_CLIP_PANEL_WIDTH, widthDp.toString())
        clipPanel.value = clipPanel.value.copy(widthDp = widthDp)
    }


    override val pikpakDomainFlow: Flow<String> = pikpakDomain.asStateFlow()
    override suspend fun setPikpakDomain(root: String) {
        settings.set(KEY_PIKPAK_DOMAIN, root)
        pikpakDomain.value = root
    }

    override val snailModeFlow: Flow<SnailMode> = snailMode.asStateFlow()
    override suspend fun setSnailMode(mode: SnailMode) {
        settings.set(KEY_SNAIL_ENABLED, mode.enabled.toString())
        settings.set(KEY_SNAIL_DOWNLOAD, mode.downloadKiBps.toString())
        settings.set(KEY_SNAIL_UPLOAD, mode.uploadKiBps.toString())
        snailMode.value = mode
    }

    override val concurrentAccelerationFlow: Flow<Boolean> = acceleration.asStateFlow()
    override val concurrentConnectionsFlow: Flow<Int> = connections.asStateFlow()
    override val downloadDirPathFlow: Flow<String> = downloadPath.asStateFlow()

    override suspend fun setDownloadDirPath(path: String) {
        // 空路径是「恢复默认」，与 Android 端同一约定
        settings.setDownloadDirectory(path)
        settings.set(KEY_DOWNLOAD_DIR, path)
        downloadPath.value = path
    }

    override suspend fun getDownloadDirPath(): String = downloadPath.value

    override suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) {
        settings.set(KEY_ACCELERATION, enabled.toString())
        acceleration.value = enabled
    }

    override suspend fun loadDownloadTasks(): String = settings.get(KEY_DOWNLOAD_TASKS)

    override suspend fun saveDownloadTasks(serialized: String) {
        settings.set(KEY_DOWNLOAD_TASKS, serialized)
    }

    override suspend fun loadUploadTasks(): String = settings.get(KEY_UPLOAD_TASKS)

    override suspend fun saveUploadTasks(serialized: String) {
        settings.set(KEY_UPLOAD_TASKS, serialized)
    }

    override suspend fun loadUploadCredentials(taskId: String): String? =
        withSecrets { secrets.read(uploadCredentialsKey(taskId))?.decodeToString() }

    override suspend fun saveUploadCredentials(taskId: String, serialized: String) =
        withSecrets { secrets.write(uploadCredentialsKey(taskId), serialized.encodeToByteArray()) }

    override suspend fun clearUploadCredentials(taskId: String) =
        withSecrets { secrets.delete(uploadCredentialsKey(taskId)) }

    override suspend fun loadOfflinePacks(): String = settings.get(KEY_OFFLINE_PACKS)

    override suspend fun saveOfflinePacks(serialized: String) {
        settings.set(KEY_OFFLINE_PACKS, serialized)
    }

    override val archivePasswordsFlow: Flow<String> =
        archivePasswords.onStart { if (archivePasswords.value == null) loadArchivePasswords() }.filterNotNull()

    override suspend fun saveArchivePasswords(serialized: String) {
        withSecrets { secrets.write(SECRET_ARCHIVE_PASSWORDS, serialized.encodeToByteArray()) }
        archivePasswords.value = serialized
    }

    /**
     * 读不出来（钥匙串锁着、拒绝授权）时先当作空表，本进程内不再重试：这份数据只供挑选，不值得反复弹解锁框。
     * 此时再存会写进兜底文件，下次启动时它比保管处里的新，照 LayeredVault 的规则胜出。
     */
    private suspend fun loadArchivePasswords() = withSecrets {
        if (archivePasswords.value != null) return@withSecrets
        archivePasswords.value = runCatching { readArchivePasswords() }
            .onFailure { PikoLog.w(TAG, "解压密码未能从系统保管处读出", it) }
            .getOrElse { settings.get(KEY_ARCHIVE_PASSWORDS) }
    }

    /**
     * 1.1.0 把解压密码明文存在 settings.properties。读到旧值就搬进保管处，读回一致才从原处删掉；
     * 保管处里已经有的话是上次搬过、没删成，以保管处为准。
     */
    private fun readArchivePasswords(): String {
        val stored = secrets.read(SECRET_ARCHIVE_PASSWORDS)?.decodeToString()
        val legacy = settings.get(KEY_ARCHIVE_PASSWORDS)
        if (legacy.isEmpty()) return stored.orEmpty()
        if (stored == null) {
            secrets.write(SECRET_ARCHIVE_PASSWORDS, legacy.encodeToByteArray())
            check(secrets.read(SECRET_ARCHIVE_PASSWORDS)?.decodeToString() == legacy) { "读回的解压密码与写入的不一致" }
        }
        settings.remove(KEY_ARCHIVE_PASSWORDS)
        return stored ?: legacy
    }

    private suspend fun <T> withSecrets(block: () -> T): T =
        secretsLock.withLock { withContext(Dispatchers.IO) { block() } }

    override val recentMoveTargetsFlow: Flow<String> = recentMoveTargets.asStateFlow()
    override suspend fun saveRecentMoveTargets(serialized: String) {
        settings.set(KEY_RECENT_MOVE_TARGETS, serialized)
        recentMoveTargets.value = serialized
    }

    override val pinnedFoldersFlow: Flow<String> = pinnedFolders.asStateFlow()
    override suspend fun savePinnedFolders(serialized: String) {
        settings.set(KEY_PINNED_FOLDERS, serialized)
        pinnedFolders.value = serialized
    }

    override val batchRenameFlow: Flow<String> = batchRename.asStateFlow()
    override suspend fun saveBatchRename(serialized: String) {
        settings.set(KEY_BATCH_RENAME, serialized)
        batchRename.value = serialized
    }

    override val vaultArchiveOptionsFlow: Flow<String> = vaultArchiveOptions.asStateFlow()
    override suspend fun saveVaultArchiveOptions(serialized: String) {
        settings.set(KEY_VAULT_ARCHIVE_OPTIONS, serialized)
        vaultArchiveOptions.value = serialized
    }

    override val renameRegexTextModeFlow: Flow<Boolean> = renameRegexTextMode.asStateFlow()
    override suspend fun setRenameRegexTextMode(enabled: Boolean) {
        settings.set(KEY_RENAME_REGEX_TEXT_MODE, enabled.toString())
        renameRegexTextMode.value = enabled
    }

    override val proxySettingFlow: Flow<ProxySetting> = proxySetting.asStateFlow()
    override suspend fun saveProxySetting(setting: ProxySetting) {
        settings.set(KEY_PROXY_SETTING, setting.encode())
        proxySetting.value = setting
    }

    override suspend fun getIgnoredUpdateVersion(): String? = settings.get(KEY_IGNORED_UPDATE).ifEmpty { null }

    override suspend fun setIgnoredUpdateVersion(version: String) {
        settings.set(KEY_IGNORED_UPDATE, version)
    }

    private val metaTubeUrl = MutableStateFlow(settings.get(KEY_METATUBE_URL))
    override val metaTubeUrlFlow: Flow<String> = metaTubeUrl.asStateFlow()
    override suspend fun setMetaTubeUrl(url: String) {
        settings.set(KEY_METATUBE_URL, url)
        metaTubeUrl.value = url
    }

    /** null 是还没从保管处读出来。读不出来时当作没填，与解压密码同一取舍。 */
    private val metaTubeToken = MutableStateFlow<String?>(null)
    override val metaTubeTokenFlow: Flow<String> = metaTubeToken.onStart {
        if (metaTubeToken.value == null) {
            val stored = withSecrets { runCatching { secrets.read(SECRET_METATUBE_TOKEN)?.decodeToString() } }
            metaTubeToken.compareAndSet(null, stored.onFailure { PikoLog.w(TAG, "MetaTube 令牌未能从系统保管处读出", it) }.getOrNull().orEmpty())
        }
    }.filterNotNull()

    override suspend fun setMetaTubeToken(token: String) {
        withSecrets {
            if (token.isEmpty()) secrets.delete(SECRET_METATUBE_TOKEN) else secrets.write(SECRET_METATUBE_TOKEN, token.encodeToByteArray())
        }
        metaTubeToken.value = token
    }

    private companion object {
        const val TAG = "credentials"
        const val SECRET_ARCHIVE_PASSWORDS = "archive-passwords"
        const val SECRET_METATUBE_TOKEN = "metatube-token"
        const val KEY_METATUBE_URL = "scrape.metaTubeUrl"

        fun uploadCredentialsKey(taskId: String) = "upload-$taskId"

        const val MAX_PLAYBACK_ENTRIES = 500
        const val KEY_DOWNLOAD_TASKS = "download.tasks"
        const val KEY_OFFLINE_PACKS = "download.offlinePacks"
        const val KEY_UPLOAD_TASKS = "upload.tasks"
        /** 1.1.0 存明文的位置，只在迁移时读。 */
        const val KEY_ARCHIVE_PASSWORDS = "drive.archivePasswords"
        const val KEY_RECENT_MOVE_TARGETS = "drive.recentMoveTargets"
        const val KEY_PINNED_FOLDERS = "drive.pinnedFolders"
        const val KEY_BATCH_RENAME = "drive.batchRename"
        const val KEY_VAULT_ARCHIVE_OPTIONS = "drive.vaultArchiveOptions"
        const val KEY_RENAME_REGEX_TEXT_MODE = "drive.renameRegexTextMode"
        const val KEY_PROXY_SETTING = "network.proxy"
        const val KEY_IGNORED_UPDATE = "update.ignoredVersion"
        const val KEY_SPOILER = "ui.spoilerBlur"
        const val KEY_AUTO_CHECK_UPDATES = "update.autoCheck"
        const val KEY_REDUCE_MOTION = "ui.reduceMotion"
        const val KEY_HARDWARE_DECODING = "player.hardwareDecoding"
        const val KEY_PLAYBACK_MAX_HEIGHT = "player.maxHeight"
        // 不沿用开发期的 download.maxHeight，理由同 Android 的 DOWNLOAD_MAX_HEIGHT
        const val KEY_DOWNLOAD_MAX_HEIGHT = "download.defaultMaxHeight"
        const val KEY_PLAYER_SEEK_STEP_SECONDS = "player.seekStepSeconds"
        const val KEY_PLAYER_BOOST_SPEED = "player.boostSpeed"
        const val KEY_FOLDER_MAP_OPEN = "ui.folderMapOpen"
        const val KEY_FOLDER_MAP_PINNED = "ui.folderMapPinned"
        const val KEY_HEURISTIC = "ui.heuristicFilter"
        const val KEY_BUNDLE_SUBTITLES = "ui.bundleSubtitles"
        const val KEY_AUTO_CANONICAL_NAMES = "ui.autoCanonicalNames"
        const val KEY_AUTO_CLEAN_NAMES = "drive.autoCleanNames"
        const val KEY_SETTINGS_SYNC = "sync.settings"
        const val KEY_SYNC_PLAY_HISTORY = "player.syncPlayHistory"
        const val KEY_NAME_PARSING = "ui.nameParsing"
        const val KEY_GRID_VIEW = "ui.gridView"
        const val KEY_DRIVE_VIEW_MODE = "ui.driveViewMode"
        fun tileSizeKey(view: String) = "ui.drive.tileSize.$view"
        const val KEY_CLIP_PANEL_OPEN = "ui.clipPanel.open"
        const val KEY_CLIP_PANEL_WIDTH = "ui.clipPanel.width"
        const val KEY_SIDEBAR_COLLAPSED = "ui.sidebar.collapsed"
        const val KEY_SHOW_EXTENSIONS = "ui.drive.showExtensions"
        const val KEY_PIKPAK_DOMAIN = "network.pikpakDomain"
        const val KEY_SNAIL_ENABLED = "transfer.snail.enabled"
        const val KEY_SNAIL_DOWNLOAD = "transfer.snail.downloadKiBps"
        const val KEY_SNAIL_UPLOAD = "transfer.snail.uploadKiBps"
        // 沿用 Fluent 版设置页的键，旧值是小写的 system、light、dark，解析时不分大小写
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_THEME_SEED = "ui.themeSeed"
        const val KEY_ACCELERATION = "download.concurrentAcceleration"
        const val KEY_CONNECTIONS = "download.concurrentConnections"
        const val KEY_DOWNLOAD_DIR = "download.directory"
        const val KEY_LAST_FOLDER_ID = "drive.lastFolderId"
        const val KEY_LAST_FOLDER_NAME = "drive.lastFolderName"
        const val KEY_LAST_FOLDER_STACK = "drive.lastFolderStack"
        const val KEY_PLAYBACK_PREFIX = "playback."
    }
}
