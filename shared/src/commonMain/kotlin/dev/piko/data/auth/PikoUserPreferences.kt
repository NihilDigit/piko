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

/** 上次取回的配额。用于进页面时先出数字，避免等网络期间卡片整块缺席。 */
data class QuotaSnapshot(val usageBytes: Long, val limitBytes: Long)

/** 秒传与离线任务的保存目标。 */
data class InstantTarget(val folderId: String, val folderName: String)

interface PikoUserPreferences {
    suspend fun savePlaybackPosition(fileId: String, positionMs: Long)
    suspend fun getPlaybackPosition(fileId: String): Long
    suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String)
    suspend fun getLastFolder(): Triple<String, String, String>

    val spoilerBlurFlow: Flow<Boolean>
    suspend fun setSpoilerBlurEnabled(enabled: Boolean)
    val heuristicFilterFlow: Flow<Boolean>
    suspend fun setHeuristicFilterEnabled(enabled: Boolean)

    /** 网盘列表用网格还是列表。全局记住，不随进出目录或重启复位。 */
    val gridViewFlow: Flow<Boolean>
    suspend fun setGridViewEnabled(enabled: Boolean)
    val sessionFlow: Flow<UserSession>
    suspend fun saveSession(token: String, refreshToken: String = "", userId: String = "", username: String = "", avatarUrl: String = "")

    /**
     * 只更新昵称与头像。saveSession 会无条件重写 token，
     * 用它写资料会在刷新资料时把登录态覆盖掉。
     */
    suspend fun saveProfile(username: String, avatarUrl: String)

    val quotaSnapshotFlow: Flow<QuotaSnapshot?>
    suspend fun saveQuotaSnapshot(usageBytes: Long, limitBytes: Long)

    /**
     * 秒传与离线任务的保存目标。为空表示沿用默认的 My Packs。
     * 记住上次选的目标，免得每次存资源都要重新挑一遍目录。
     */
    val instantTargetFlow: Flow<InstantTarget?>
    suspend fun saveInstantTarget(folderId: String, folderName: String)
    suspend fun clearSession()
    val concurrentAccelerationFlow: Flow<Boolean>
    val concurrentConnectionsFlow: Flow<Int>
    val downloadDirPathFlow: Flow<String>
    suspend fun setDownloadDirPath(path: String)
    suspend fun getDownloadDirPath(): String
    suspend fun setConcurrentAccelerationEnabled(enabled: Boolean)
}
