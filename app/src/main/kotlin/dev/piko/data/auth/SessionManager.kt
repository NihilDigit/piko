package dev.piko.data.auth

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 文件损坏时整份重置而不是抛 CorruptionException：首读发生在 appScope 的恢复登录里，
// 那里抛出的异常没有人接，结果是每次冷启动都崩，只能清数据
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "piko_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 续播进度单独一个文件。Preferences DataStore 每次写入都整份重写文件，而进度是每个看过的
 * 视频一个键、只增不减，混在主文件里会让每一次写偏好、每一次冷启动读偏好都越来越慢。
 */
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "piko_playback",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { context -> listOf(LegacyPlaybackPositionMigration(context.dataStore)) },
)

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
        val TOKEN = stringPreferencesKey("auth_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val AVATAR_URL = stringPreferencesKey("avatar_url")
        val CONCURRENT_CONNECTIONS = intPreferencesKey("concurrent_connections")
        val CONCURRENT_ACCELERATION = booleanPreferencesKey("concurrent_acceleration")
        val DOWNLOAD_DIR_PATH = stringPreferencesKey("download_dir_path")
        val SPOILER_BLUR_ENABLED = booleanPreferencesKey("spoiler_blur_enabled")
        val HEURISTIC_FILTER_ENABLED = booleanPreferencesKey("heuristic_filter_enabled")
        val GRID_VIEW_ENABLED = booleanPreferencesKey("grid_view_enabled")
        val INSTANT_TARGET_ID = stringPreferencesKey("instant_target_id")
        val INSTANT_TARGET_NAME = stringPreferencesKey("instant_target_name")
        val QUOTA_USAGE_BYTES = longPreferencesKey("quota_usage_bytes")
        val QUOTA_LIMIT_BYTES = longPreferencesKey("quota_limit_bytes")
        val LAST_FOLDER_ID = stringPreferencesKey("last_folder_id")
        val LAST_FOLDER_NAME = stringPreferencesKey("last_folder_name")
        val LAST_FOLDER_STACK_SERIALIZED = stringPreferencesKey("last_folder_stack")
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

    override val heuristicFilterFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] ?: true // 默认开启启发式单视频内容筛选
    }

    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] = enabled
        }
    }

    override val gridViewFlow: Flow<Boolean> = preference { preferences ->
        preferences[PreferencesKeys.GRID_VIEW_ENABLED] ?: false // 默认列表视图
    }

    override suspend fun setGridViewEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GRID_VIEW_ENABLED] = enabled
        }
    }

    override val sessionFlow: Flow<UserSession> = preference { preferences ->
        UserSession(
            token = preferences[PreferencesKeys.TOKEN].orEmpty(),
            refreshToken = preferences[PreferencesKeys.REFRESH_TOKEN].orEmpty(),
            userId = preferences[PreferencesKeys.USER_ID].orEmpty(),
            username = preferences[PreferencesKeys.USERNAME].orEmpty(),
            avatarUrl = preferences[PreferencesKeys.AVATAR_URL].orEmpty(),
            concurrentConnections = preferences[PreferencesKeys.CONCURRENT_CONNECTIONS] ?: 8,
        )
    }

    override suspend fun saveSession(
        token: String,
        refreshToken: String,
        userId: String,
        username: String,
        avatarUrl: String,
    ) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TOKEN] = token
            if (refreshToken.isNotEmpty()) {
                preferences[PreferencesKeys.REFRESH_TOKEN] = refreshToken
            } else {
                preferences.remove(PreferencesKeys.REFRESH_TOKEN)
            }
            if (userId.isNotEmpty()) preferences[PreferencesKeys.USER_ID] = userId
            if (username.isNotEmpty()) preferences[PreferencesKeys.USERNAME] = username
            if (avatarUrl.isNotEmpty()) preferences[PreferencesKeys.AVATAR_URL] = avatarUrl
        }
    }

    override val quotaSnapshotFlow: Flow<QuotaSnapshot?> = preference { preferences ->
        val limit = preferences[PreferencesKeys.QUOTA_LIMIT_BYTES]
        val usage = preferences[PreferencesKeys.QUOTA_USAGE_BYTES]
        // 只有上限有值才算拿到过配额：零上限会让占比计算除零
        if (limit != null && usage != null && limit > 0) QuotaSnapshot(usage, limit) else null
    }

    override suspend fun saveQuotaSnapshot(usageBytes: Long, limitBytes: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUOTA_USAGE_BYTES] = usageBytes
            preferences[PreferencesKeys.QUOTA_LIMIT_BYTES] = limitBytes
        }
    }

    override val instantTargetFlow: Flow<InstantTarget?> = preference { preferences ->
        val name = preferences[PreferencesKeys.INSTANT_TARGET_NAME]
        // id 为根目录时是空串，所以用名字判断有没有配置过
        if (name.isNullOrEmpty()) {
            null
        } else {
            InstantTarget(preferences[PreferencesKeys.INSTANT_TARGET_ID].orEmpty(), name)
        }
    }

    override suspend fun saveInstantTarget(folderId: String, folderName: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INSTANT_TARGET_ID] = folderId
            preferences[PreferencesKeys.INSTANT_TARGET_NAME] = folderName
        }
    }

    override suspend fun saveProfile(username: String, avatarUrl: String) {
        context.dataStore.edit { preferences ->
            if (username.isNotEmpty()) preferences[PreferencesKeys.USERNAME] = username
            if (avatarUrl.isNotEmpty()) preferences[PreferencesKeys.AVATAR_URL] = avatarUrl
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

    override suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.TOKEN)
            preferences.remove(PreferencesKeys.REFRESH_TOKEN)
            preferences.remove(PreferencesKeys.USER_ID)
            preferences.remove(PreferencesKeys.USERNAME)
            preferences.remove(PreferencesKeys.AVATAR_URL)
        }
    }
}
