package dev.piko.data.auth

import kotlinx.coroutines.flow.Flow

data class UserSession(
    val token: String = "",
    val refreshToken: String = "",
    val userId: String = "",
    val username: String = "",
    val avatarUrl: String = "",
    /** 服务端返回时已打码（a***@example.com），API 取不到完整地址。 */
    val email: String = "",
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

    /**
     * 文件名解析总开关，默认开。关闭后网盘列表、磁力面板与选集都照原样列出文件名：不分区、不改标题、
     * 不挂标签；启发式折叠与配套字幕依赖解析，一并失效。
     */
    val nameParsingFlow: Flow<Boolean>
    suspend fun setNameParsingEnabled(enabled: Boolean)

    /** 添加链接时一并保存视频的外挂字幕。字幕在面板里挂在视频行下，不单独勾选，关闭后保存时跳过它们。 */
    val bundleSubtitlesFlow: Flow<Boolean>
    suspend fun setBundleSubtitlesEnabled(enabled: Boolean)

    /** 把播放进度上报到 PikPak 的播放历史，与官方客户端共用；没有本机记录时也从那里续播。 */
    val syncPlayHistoryFlow: Flow<Boolean>
    suspend fun setSyncPlayHistoryEnabled(enabled: Boolean)

    /** 深浅模式，存 ThemeMode 的名字。为 null 表示跟随系统。 */
    val themeModeFlow: Flow<String?>
    suspend fun setThemeMode(mode: String)

    /** 内置主题色，存 SeedTheme 的名字。为 null 表示系统取色。 */
    val themeSeedFlow: Flow<String?>
    suspend fun setThemeSeed(seed: String?)

    /** 网盘列表用网格还是列表。全局记住，不随进出目录或重启复位。 */
    val gridViewFlow: Flow<Boolean>
    suspend fun setGridViewEnabled(enabled: Boolean)
    val sessionFlow: Flow<UserSession>
    suspend fun saveSession(token: String, refreshToken: String = "", userId: String = "", username: String = "", avatarUrl: String = "")

    /**
     * 只更新昵称、头像与邮箱。saveSession 会无条件重写 token，
     * 用它写资料会在刷新资料时把登录态覆盖掉。
     */
    suspend fun saveProfile(username: String, avatarUrl: String, email: String)

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

    /** 本地下载任务表的 JSON。空串表示从未保存。 */
    suspend fun loadDownloadTasks(): String
    suspend fun saveDownloadTasks(serialized: String)

    /** 上传任务表的 JSON，见 PikoUploadCoordinator。含 12 小时有效的 OSS 凭据，与会话同等看待。空串表示从未保存。 */
    suspend fun loadUploadTasks(): String
    suspend fun saveUploadTasks(serialized: String)

    /** 整包离线任务的跟踪记录，JSON，见 OfflinePackTracker。空串表示从未保存。 */
    suspend fun loadOfflinePacks(): String
    suspend fun saveOfflinePacks(serialized: String)

    /** 解压成功过的压缩包密码，JSON，见 ArchivePasswordVault。空串表示从未保存。 */
    val archivePasswordsFlow: Flow<String>
    suspend fun saveArchivePasswords(serialized: String)

    /** 最近移动到过的目录路径，JSON，见 MoveHistory。空串表示从未保存。 */
    val recentMoveTargetsFlow: Flow<String>
    suspend fun saveRecentMoveTargets(serialized: String)

    /** 开屏提示里点了「忽略此版本」的版本号。只比相等，更新的版本出来照常提示。 */
    suspend fun getIgnoredUpdateVersion(): String?
    suspend fun setIgnoredUpdateVersion(version: String)
}
