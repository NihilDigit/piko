package dev.piko.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "piko_preferences")

data class UserSession(
    val token: String = "",
    val refreshToken: String = "",
    val userId: String = "",
    val username: String = "",
    val avatarUrl: String = "",
    val concurrentConnections: Int = 8,
) {
    val isLoggedIn: Boolean get() = token.isNotEmpty()
}

class SessionManager(private val context: Context) {

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
        val LAST_FOLDER_ID = stringPreferencesKey("last_folder_id")
        val LAST_FOLDER_NAME = stringPreferencesKey("last_folder_name")
        val LAST_FOLDER_STACK_SERIALIZED = stringPreferencesKey("last_folder_stack")
    }

    suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[longPreferencesKey("playback_pos_$fileId")] = positionMs
        }
    }

    suspend fun getPlaybackPosition(fileId: String): Long {
        val prefs = context.dataStore.data.first()
        return prefs[longPreferencesKey("playback_pos_$fileId")] ?: 0L
    }

    suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_FOLDER_ID] = folderId
            preferences[PreferencesKeys.LAST_FOLDER_NAME] = folderName
            preferences[PreferencesKeys.LAST_FOLDER_STACK_SERIALIZED] = stackSerialized
        }
    }

    suspend fun getLastFolder(): Triple<String, String, String> {
        val prefs = context.dataStore.data.first()
        return Triple(
            prefs[PreferencesKeys.LAST_FOLDER_ID] ?: "",
            prefs[PreferencesKeys.LAST_FOLDER_NAME] ?: "网盘",
            prefs[PreferencesKeys.LAST_FOLDER_STACK_SERIALIZED] ?: "",
        )
    }

    val spoilerBlurFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SPOILER_BLUR_ENABLED] ?: true // 默认开启 Spoiler 遮蔽
    }

    suspend fun setSpoilerBlurEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPOILER_BLUR_ENABLED] = enabled
        }
    }

    val heuristicFilterFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] ?: true // 默认开启启发式单视频内容筛选
    }

    suspend fun setHeuristicFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HEURISTIC_FILTER_ENABLED] = enabled
        }
    }

    val sessionFlow: Flow<UserSession> = context.dataStore.data.map { preferences ->
        UserSession(
            token = preferences[PreferencesKeys.TOKEN].orEmpty(),
            refreshToken = preferences[PreferencesKeys.REFRESH_TOKEN].orEmpty(),
            userId = preferences[PreferencesKeys.USER_ID].orEmpty(),
            username = preferences[PreferencesKeys.USERNAME].orEmpty(),
            avatarUrl = preferences[PreferencesKeys.AVATAR_URL].orEmpty(),
            concurrentConnections = preferences[PreferencesKeys.CONCURRENT_CONNECTIONS] ?: 8,
        )
    }

    suspend fun saveSession(
        token: String,
        refreshToken: String = "",
        userId: String = "",
        username: String = "",
        avatarUrl: String = "",
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

    val concurrentAccelerationFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CONCURRENT_ACCELERATION] ?: true
    }

    val concurrentConnectionsFlow: Flow<Int> = concurrentAccelerationFlow.map { enabled ->
        if (enabled) 8 else 1
    }

    val downloadDirPathFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DOWNLOAD_DIR_PATH] ?: ""
    }

    suspend fun setDownloadDirPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOWNLOAD_DIR_PATH] = path
        }
    }

    suspend fun getDownloadDirPath(): String {
        val prefs = context.dataStore.data.first()
        return prefs[PreferencesKeys.DOWNLOAD_DIR_PATH] ?: ""
    }

    suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CONCURRENT_ACCELERATION] = enabled
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.TOKEN)
            preferences.remove(PreferencesKeys.REFRESH_TOKEN)
            preferences.remove(PreferencesKeys.USER_ID)
            preferences.remove(PreferencesKeys.USERNAME)
            preferences.remove(PreferencesKeys.AVATAR_URL)
        }
    }
}
