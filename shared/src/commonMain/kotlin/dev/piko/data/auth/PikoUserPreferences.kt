package dev.piko.data.auth

import kotlinx.coroutines.flow.Flow

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

interface PikoUserPreferences {
    suspend fun savePlaybackPosition(fileId: String, positionMs: Long)
    suspend fun getPlaybackPosition(fileId: String): Long
    suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String)
    suspend fun getLastFolder(): Triple<String, String, String>

    val spoilerBlurFlow: Flow<Boolean>
    suspend fun setSpoilerBlurEnabled(enabled: Boolean)
    val heuristicFilterFlow: Flow<Boolean>
    suspend fun setHeuristicFilterEnabled(enabled: Boolean)
    val sessionFlow: Flow<UserSession>
    suspend fun saveSession(token: String, refreshToken: String = "", userId: String = "", username: String = "", avatarUrl: String = "")
    suspend fun clearSession()
    val concurrentAccelerationFlow: Flow<Boolean>
    val concurrentConnectionsFlow: Flow<Int>
    val downloadDirPathFlow: Flow<String>
    suspend fun setDownloadDirPath(path: String)
    suspend fun getDownloadDirPath(): String
    suspend fun setConcurrentAccelerationEnabled(enabled: Boolean)
}
