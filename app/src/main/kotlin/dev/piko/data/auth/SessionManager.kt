package dev.piko.data.auth

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.piko.shared.log.PikoLog
import dev.piko.shared.net.ProxySetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 文件损坏时整份重置而不是抛 CorruptionException：首读发生在 appScope 的恢复登录里，
// 那里抛出的异常没有人接，结果是每次冷启动都崩，只能清数据
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "piko_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(ArchivePasswordEncryption) },
)

/** 解压用过的密码，经 [CredentialCipher] 加密后存。 */
private val ARCHIVE_PASSWORDS = stringPreferencesKey("archive_passwords")

/**
 * 1.1.0 把解压密码明文存在主偏好文件里，就地换成密文。密钥库不可用时原样留着，下次启动再试：
 * 丢掉它们换不来什么，明文已经在那里了。
 */
private object ArchivePasswordEncryption : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[ARCHIVE_PASSWORDS]?.let { !CredentialCipher.isEncrypted(it) } == true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val plain = currentData[ARCHIVE_PASSWORDS] ?: return currentData
        val sealed = runCatching { CredentialCipher.encrypt(plain) }
            .onFailure { PikoLog.w(TAG, "解压密码加密失败，暂留明文", it) }
            .getOrNull() ?: return currentData
        return currentData.toMutablePreferences().apply { this[ARCHIVE_PASSWORDS] = sealed }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

private const val TAG = "Preferences"

/**
 * 续播进度单独一个文件。Preferences DataStore 每次写入都整份重写文件，而进度是每个看过的
 * 视频一个键、只增不减，混在主文件里会让每一次写偏好、每一次冷启动读偏好都越来越慢。
 */
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "piko_playback",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { context -> listOf(LegacyPlaybackPositionMigration(context.dataStore)) },
)

/** 下载任务表同理单独一个文件：整张表一个键，随任务数增长，每次状态变化都整份重写。 */
private val Context.downloadsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "piko_downloads",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val DOWNLOAD_TASKS = stringPreferencesKey("download_tasks")
private val UPLOAD_TASKS = stringPreferencesKey("upload_tasks")
private val OFFLINE_PACKS = stringPreferencesKey("offline_packs")

private fun uploadCredentialsKey(taskId: String) = stringPreferencesKey("upload_credentials_$taskId")

private const val PLAYBACK_KEY_PREFIX = "playback_pos_"

private fun playbackKey(fileId: String) = longPreferencesKey("$PLAYBACK_KEY_PREFIX$fileId")

/** 把旧版本写在主偏好文件里的续播进度搬过来，并从主文件里删掉。 */
private class LegacyPlaybackPositionMigration(
    private val legacy: DataStore<Preferences>,
) : DataMigration<Preferences> {
    private suspend fun legacyEntries(): Map<Preferences.Key<*>, Any> =
        legacy.data.first().asMap().filterKeys { it.name.startsWith(PLAYBACK_KEY_PREFIX) }

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyEntries().isNotEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = currentData.toMutablePreferences()
        legacyEntries().forEach { (key, value) ->
            val playback = longPreferencesKey(key.name)
            // 新文件里已有的进度更新，不拿旧值覆盖
            if (value is Long && migrated[playback] == null) migrated[playback] = value
        }
        return migrated.toPreferences()
    }

    override suspend fun cleanUp() {
        legacy.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(PLAYBACK_KEY_PREFIX) }
                .forEach { preferences.remove(it) }
        }
    }
}

class SessionManager(private val context: Context) : PikoUserPreferences {

    private object PreferencesKeys {
        val CONCURRENT_ACCELERATION = booleanPreferencesKey("concurrent_acceleration")
        val DOWNLOAD_DIR_PATH = stringPreferencesKey("download_dir_path")
        val SPOILER_BLUR_ENABLED = booleanPreferencesKey("spoiler_blur_enabled")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val HARDWARE_DECODING = booleanPreferencesKey("hardware_decoding")
        val PLAYBACK_MAX_HEIGHT = intPreferencesKey("playback_max_height")
        val PLAYER_SEEK_STEP_SECONDS = intPreferencesKey("player_seek_step_seconds")
        val PLAYER_BOOST_SPEED = floatPreferencesKey("player_boost_speed")
        val FOLDER_MAP_OPEN = booleanPreferencesKey("folder_map_open")
        val HEURISTIC_FILTER_ENABLED = booleanPreferencesKey("heuristic_filter_enabled")
        val BUNDLE_SUBTITLES_ENABLED = booleanPreferencesKey("bundle_subtitles_enabled")
        val AUTO_CLEAN_NAMES_ENABLED = booleanPreferencesKey("auto_clean_names_enabled")
        val SETTINGS_SYNC_ENABLED = booleanPreferencesKey("settings_sync_enabled")
        val SYNC_PLAY_HISTORY_ENABLED = booleanPreferencesKey("sync_play_history_enabled")
        val NAME_PARSING_ENABLED = booleanPreferencesKey("name_parsing_enabled")
        val WATERFALL_VIEW_ENABLED = booleanPreferencesKey("waterfall_view_enabled")
        val DRIVE_VIEW_MODE = stringPreferencesKey("drive_view_mode")
        val CLIP_PANEL_OPEN = booleanPreferencesKey("clip_panel_open")
        val CLIP_PANEL_WIDTH = floatPreferencesKey("clip_panel_width")
        val SIDEBAR_COLLAPSED = booleanPreferencesKey("sidebar_collapsed")
        val SHOW_EXTENSIONS = booleanPreferencesKey("show_extensions")
        val INSPECTOR_PANEL_OPEN = booleanPreferencesKey("inspector_panel_open")
        val INSPECTOR_PANEL_WIDTH = floatPreferencesKey("inspector_panel_width")
        val PIKPAK_DOMAIN = stringPreferencesKey("pikpak_domain")
        val SNAIL_ENABLED = booleanPreferencesKey("snail_enabled")
        val SNAIL_DOWNLOAD_KIBPS = intPreferencesKey("snail_download_kibps")
        val SNAIL_UPLOAD_KIBPS = intPreferencesKey("snail_upload_kibps")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_SEED = stringPreferencesKey("theme_seed")
        val LAST_FOLDER_ID = stringPreferencesKey("last_folder_id")
        val LAST_FOLDER_NAME = stringPreferencesKey("last_folder_name")
        val LAST_FOLDER_STACK_SERIALIZED = stringPreferencesKey("last_folder_stack")
        val RECENT_MOVE_TARGETS = stringPreferencesKey("recent_move_targets")
        val PINNED_FOLDERS = stringPreferencesKey("pinned_folders")
        val BATCH_RENAME = stringPreferencesKey("batch_rename")
        val VAULT_ARCHIVE_OPTIONS = stringPreferencesKey("vault_archive_options")
        val RENAME_REGEX_TEXT_MODE = booleanPreferencesKey("rename_regex_text_mode")
        val PROXY_SETTING = stringPreferencesKey("proxy_setting")
        val IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")
    }

    /**
     * 每个 DataStore 值都从同一份 data 流映射出来，任何一个键的写入都会让所有映射重新发射。
     * 不去重的话，播放器每 5 秒写一次进度，网盘列表、下载存储、设置页的收集者就跟着每 5 秒
     * 醒一次。
     */
    private fun <T> preference(read: (Preferences) -> T): Flow<T> =
        context.dataStore.data.map(read).distinctUntilChanged()

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        context.playbackDataStore.edit { preferences ->
            preferences[playbackKey(fileId)] = positionMs
        }
    }

    override suspend fun getPlaybackPosition(fileId: String): Long {
        val prefs = context.playbackDataStore.data.first()
        return prefs[playbackKey(fileId)] ?: 0L
    }

    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_FOLDER_ID] = folderId
            preferences[PreferencesKeys.LAST_FOLDER_NAME] = folderName
            preferences[PreferencesKeys.LAST_FOLDER_STACK_SERIALIZED] = stackSerialized
        }
    }

    override suspend fun getLastFolder(): Triple<String, String, String> {
        val prefs = context.dataStore.data.first()
        return Triple(
            prefs[PreferencesKeys.LAST_FOLDER_ID] ?: "",
            prefs[PreferencesKeys.LAST_FOLDER_NAME] ?: "网盘",
            prefs[PreferencesKeys.LAST_FOLDER_STACK_SERIALIZED] ?: "",
        )
    }

    override val spoilerBlurFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.SPOILER_BLUR_ENABLED] ?: true // 默认开启 Spoiler 遮蔽
    }

    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPOILER_BLUR_ENABLED] = enabled
        }
    }

    override val autoCheckUpdatesFlow: Flow<Boolean> = preference { it[PreferencesKeys.AUTO_CHECK_UPDATES] ?: true }

    override suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_CHECK_UPDATES] = enabled
        }
    }

    override val reduceMotionFlow: Flow<Boolean> = preference { it[PreferencesKeys.REDUCE_MOTION] ?: false }

    override suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REDUCE_MOTION] = enabled
        }
    }

    override val hardwareDecodingFlow: Flow<Boolean> = preference { it[PreferencesKeys.HARDWARE_DECODING] ?: true }

    override suspend fun setHardwareDecoding(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HARDWARE_DECODING] = enabled
        }
    }

    override val playbackMaxHeightFlow: Flow<Int> = preference { it[PreferencesKeys.PLAYBACK_MAX_HEIGHT] ?: 0 }

    override suspend fun setPlaybackMaxHeight(height: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYBACK_MAX_HEIGHT] = height
        }
    }

    override val playerSeekStepSecondsFlow: Flow<Int> =
        preference { it[PreferencesKeys.PLAYER_SEEK_STEP_SECONDS] ?: PlayerGestureDefaults.SEEK_STEP_SECONDS }

    override suspend fun setPlayerSeekStepSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_SEEK_STEP_SECONDS] = seconds
        }
    }

    override val playerBoostSpeedFlow: Flow<Float> =
        preference { it[PreferencesKeys.PLAYER_BOOST_SPEED] ?: PlayerGestureDefaults.BOOST_SPEED }

    override suspend fun setPlayerBoostSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_BOOST_SPEED] = speed
        }
    }

    override val folderMapOpenFlow: Flow<Boolean> = preference { it[PreferencesKeys.FOLDER_MAP_OPEN] ?: false }

    override suspend fun setFolderMapOpen(open: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_MAP_OPEN] = open
        }
    }

    override val heuristicFilterFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] ?: true // 默认开启启发式单视频内容筛选
    }

    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] = enabled
        }
    }

    override val nameParsingFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.NAME_PARSING_ENABLED] ?: true
    }

    override suspend fun setNameParsingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NAME_PARSING_ENABLED] = enabled
        }
    }

    override val bundleSubtitlesFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.BUNDLE_SUBTITLES_ENABLED] ?: true
    }

    override suspend fun setBundleSubtitlesEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BUNDLE_SUBTITLES_ENABLED] = enabled
        }
    }

    override val autoCleanNamesFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.AUTO_CLEAN_NAMES_ENABLED] ?: false
    }

    override suspend fun setAutoCleanNamesEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_CLEAN_NAMES_ENABLED] = enabled
        }
    }

    override val settingsSyncFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.SETTINGS_SYNC_ENABLED] ?: true
    }

    override suspend fun setSettingsSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SETTINGS_SYNC_ENABLED] = enabled
        }
    }

    override val syncPlayHistoryFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.SYNC_PLAY_HISTORY_ENABLED] ?: true
    }

    override suspend fun setSyncPlayHistoryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SYNC_PLAY_HISTORY_ENABLED] = enabled
        }
    }

    // 存枚举名的原始字符串，解析在主题层，数据层不依赖界面的类型。
    override val themeModeFlow: Flow<String?> = preference { it[PreferencesKeys.THEME_MODE] }

    override suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode }
    }

    /** 内置主题的名字，null 表示系统取色。 */
    override val themeSeedFlow: Flow<String?> = preference { it[PreferencesKeys.THEME_SEED] }

    override suspend fun setThemeSeed(seed: String?) {
        context.dataStore.edit { preferences ->
            if (seed == null) preferences.remove(PreferencesKeys.THEME_SEED) else preferences[PreferencesKeys.THEME_SEED] = seed
        }
    }

    override val driveViewModeFlow: Flow<String> = preference { preferences ->
        // 默认海报墙。旧键名沿用瀑布流时期的写法，只存了是否海报墙
        preferences[PreferencesKeys.DRIVE_VIEW_MODE]
            ?: if (preferences[PreferencesKeys.WATERFALL_VIEW_ENABLED] ?: true) "POSTER" else "LIST"
    }

    override suspend fun setDriveViewMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_VIEW_MODE] = mode
        }
    }

    override val sidebarCollapsedFlow: Flow<Boolean> = preference { it[PreferencesKeys.SIDEBAR_COLLAPSED] ?: false }

    override suspend fun setSidebarCollapsed(collapsed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIDEBAR_COLLAPSED] = collapsed
        }
    }

    override val showExtensionsFlow: Flow<Boolean> = preference { it[PreferencesKeys.SHOW_EXTENSIONS] ?: false }

    override suspend fun setShowExtensions(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_EXTENSIONS] = show
        }
    }

    override val clipPanelFlow: Flow<SidePanelPrefs> = preference { preferences ->
        SidePanelPrefs(
            open = preferences[PreferencesKeys.CLIP_PANEL_OPEN] ?: false,
            widthDp = preferences[PreferencesKeys.CLIP_PANEL_WIDTH],
        )
    }

    override suspend fun setClipPanelOpen(open: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLIP_PANEL_OPEN] = open
        }
    }

    override suspend fun setClipPanelWidth(widthDp: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLIP_PANEL_WIDTH] = widthDp
        }
    }

    override val inspectorPanelFlow: Flow<SidePanelPrefs> = preference { preferences ->
        SidePanelPrefs(
            open = preferences[PreferencesKeys.INSPECTOR_PANEL_OPEN] ?: false,
            widthDp = preferences[PreferencesKeys.INSPECTOR_PANEL_WIDTH],
        )
    }

    override suspend fun setInspectorPanelOpen(open: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INSPECTOR_PANEL_OPEN] = open
        }
    }

    override suspend fun setInspectorPanelWidth(widthDp: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INSPECTOR_PANEL_WIDTH] = widthDp
        }
    }

    override val pikpakDomainFlow: Flow<String> = preference { it[PreferencesKeys.PIKPAK_DOMAIN].orEmpty() }

    override suspend fun setPikpakDomain(root: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PIKPAK_DOMAIN] = root
        }
    }

    override val snailModeFlow: Flow<SnailMode> = preference { preferences ->
        SnailMode(
            enabled = preferences[PreferencesKeys.SNAIL_ENABLED] ?: false,
            downloadKiBps = preferences[PreferencesKeys.SNAIL_DOWNLOAD_KIBPS] ?: SnailMode.DEFAULT_DOWNLOAD_KIBPS,
            uploadKiBps = preferences[PreferencesKeys.SNAIL_UPLOAD_KIBPS] ?: SnailMode.DEFAULT_UPLOAD_KIBPS,
        )
    }

    override suspend fun setSnailMode(mode: SnailMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SNAIL_ENABLED] = mode.enabled
            preferences[PreferencesKeys.SNAIL_DOWNLOAD_KIBPS] = mode.downloadKiBps
            preferences[PreferencesKeys.SNAIL_UPLOAD_KIBPS] = mode.uploadKiBps
        }
    }

    override val concurrentAccelerationFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.CONCURRENT_ACCELERATION] ?: true
    }

    override val concurrentConnectionsFlow: Flow<Int> = concurrentAccelerationFlow.map { enabled ->
        if (enabled) 8 else 1
    }

    override val downloadDirPathFlow: Flow<String> = preference { preferences ->
        preferences[PreferencesKeys.DOWNLOAD_DIR_PATH] ?: ""
    }

    override suspend fun setDownloadDirPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOWNLOAD_DIR_PATH] = path
        }
    }

    override suspend fun getDownloadDirPath(): String {
        val prefs = context.dataStore.data.first()
        return prefs[PreferencesKeys.DOWNLOAD_DIR_PATH] ?: ""
    }

    override suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CONCURRENT_ACCELERATION] = enabled
        }
    }

    override suspend fun loadDownloadTasks(): String =
        context.downloadsDataStore.data.first()[DOWNLOAD_TASKS].orEmpty()

    override suspend fun saveDownloadTasks(serialized: String) {
        context.downloadsDataStore.edit { preferences ->
            preferences[DOWNLOAD_TASKS] = serialized
        }
    }

    override suspend fun loadUploadTasks(): String =
        context.downloadsDataStore.data.first()[UPLOAD_TASKS].orEmpty()

    override suspend fun saveUploadTasks(serialized: String) {
        context.downloadsDataStore.edit { preferences ->
            preferences[UPLOAD_TASKS] = serialized
        }
    }

    override suspend fun loadUploadCredentials(taskId: String): String? =
        context.downloadsDataStore.data.first()[uploadCredentialsKey(taskId)]?.let(CredentialCipher::decrypt)

    // 密钥库不可用时加密抛出、不存明文，由调用方记下；这个会话重启后按凭据过期处理，只是重传
    override suspend fun saveUploadCredentials(taskId: String, serialized: String) {
        val sealed = CredentialCipher.encrypt(serialized)
        context.downloadsDataStore.edit { preferences ->
            preferences[uploadCredentialsKey(taskId)] = sealed
        }
    }

    override suspend fun clearUploadCredentials(taskId: String) {
        context.downloadsDataStore.edit { preferences ->
            preferences.remove(uploadCredentialsKey(taskId))
        }
    }

    override suspend fun loadOfflinePacks(): String =
        context.downloadsDataStore.data.first()[OFFLINE_PACKS].orEmpty()

    override suspend fun saveOfflinePacks(serialized: String) {
        context.downloadsDataStore.edit { preferences ->
            preferences[OFFLINE_PACKS] = serialized
        }
    }

    // 先对密文去重再解密：主偏好文件的任何一次写入都会让 data 重新发射，不该每次都过一遍密钥库
    override val archivePasswordsFlow: Flow<String> = preference { it[ARCHIVE_PASSWORDS] }.map { stored ->
        when {
            stored == null -> ""
            CredentialCipher.isEncrypted(stored) -> CredentialCipher.decrypt(stored).orEmpty()
            // 迁移因密钥库不可用没做成，仍是明文
            else -> stored
        }
    }

    // 密钥库不可用时不存，保留原来那份，与登录密码同一取舍：宁可少记一个，也不落明文
    override suspend fun saveArchivePasswords(serialized: String) {
        val sealed = runCatching { CredentialCipher.encrypt(serialized) }
            .onFailure { PikoLog.w(TAG, "解压密码加密失败，未保存", it) }
            .getOrNull() ?: return
        context.dataStore.edit { preferences ->
            preferences[ARCHIVE_PASSWORDS] = sealed
        }
    }

    override val recentMoveTargetsFlow: Flow<String> = preference { it[PreferencesKeys.RECENT_MOVE_TARGETS].orEmpty() }

    override suspend fun saveRecentMoveTargets(serialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECENT_MOVE_TARGETS] = serialized
        }
    }

    override val pinnedFoldersFlow: Flow<String> = preference { it[PreferencesKeys.PINNED_FOLDERS].orEmpty() }

    override suspend fun savePinnedFolders(serialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PINNED_FOLDERS] = serialized
        }
    }

    override val batchRenameFlow: Flow<String> = preference { it[PreferencesKeys.BATCH_RENAME].orEmpty() }

    override suspend fun saveBatchRename(serialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BATCH_RENAME] = serialized
        }
    }

    override val vaultArchiveOptionsFlow: Flow<String> = preference { it[PreferencesKeys.VAULT_ARCHIVE_OPTIONS].orEmpty() }

    override suspend fun saveVaultArchiveOptions(serialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VAULT_ARCHIVE_OPTIONS] = serialized
        }
    }

    override val renameRegexTextModeFlow: Flow<Boolean> = preference { it[PreferencesKeys.RENAME_REGEX_TEXT_MODE] ?: false }

    override suspend fun setRenameRegexTextMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RENAME_REGEX_TEXT_MODE] = enabled
        }
    }

    override val proxySettingFlow: Flow<ProxySetting> =
        preference { ProxySetting.decode(it[PreferencesKeys.PROXY_SETTING].orEmpty()) }

    override suspend fun saveProxySetting(setting: ProxySetting) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PROXY_SETTING] = setting.encode()
        }
    }

    override suspend fun getIgnoredUpdateVersion(): String? =
        context.dataStore.data.first()[PreferencesKeys.IGNORED_UPDATE_VERSION]

    override suspend fun setIgnoredUpdateVersion(version: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IGNORED_UPDATE_VERSION] = version
        }
    }
}
