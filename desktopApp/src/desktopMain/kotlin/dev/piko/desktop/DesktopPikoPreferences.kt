package dev.piko.desktop

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopPikoPreferences(settings: DesktopSettingsStore) : PikoUserPreferences {
    private val playback = mutableMapOf<String, Long>()
    private val spoiler = MutableStateFlow(true)
    private val heuristic = MutableStateFlow(true)
    private val acceleration = MutableStateFlow(true)
    private val session = MutableStateFlow(UserSession())
    private val downloadPath = MutableStateFlow(settings.downloadDirectory.absolutePath)

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) { playback[fileId] = positionMs }
    override suspend fun getPlaybackPosition(fileId: String): Long = playback[fileId] ?: 0L
    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) = Unit
    override suspend fun getLastFolder(): Triple<String, String, String> = Triple("", "网盘", "")
    override val spoilerBlurFlow: Flow<Boolean> = spoiler.asStateFlow()
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) { spoiler.value = enabled }
    override val heuristicFilterFlow: Flow<Boolean> = heuristic.asStateFlow()
    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) { heuristic.value = enabled }
    override val sessionFlow: Flow<UserSession> = session.asStateFlow()
    override suspend fun saveSession(token: String, refreshToken: String, userId: String, username: String, avatarUrl: String) {
        session.value = UserSession(token, refreshToken, userId, username, avatarUrl)
    }
    override suspend fun clearSession() { session.value = UserSession() }
    override val concurrentAccelerationFlow: Flow<Boolean> = acceleration.asStateFlow()
    override val concurrentConnectionsFlow: Flow<Int> = MutableStateFlow(8).asStateFlow()
    override val downloadDirPathFlow: Flow<String> = downloadPath.asStateFlow()
    override suspend fun setDownloadDirPath(path: String) { downloadPath.value = path }
    override suspend fun getDownloadDirPath(): String = downloadPath.value
    override suspend fun setConcurrentAccelerationEnabled(enabled: Boolean) { acceleration.value = enabled }
}
