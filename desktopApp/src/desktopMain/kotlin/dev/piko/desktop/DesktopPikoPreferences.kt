package dev.piko.desktop

import dev.piko.data.auth.InstantTarget
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.data.auth.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PikoUserPreferences 的桌面实现：全部写穿到 ~/.piko/settings.properties。
 * 以前是纯内存实现，重启丢会话、丢播放进度、丢上次目录——与 Android（DataStore）
 * 的持久化语义对齐后补上。注意 tokens 明文落盘，与 Android DataStore 同级，
 * 后续如需 DPAPI 加密再升级存储后端，接口不用动。
 */
class DesktopPikoPreferences(private val settings: DesktopSettingsStore) : PikoUserPreferences {
    private val spoiler = MutableStateFlow(settings.get(KEY_SPOILER, "true").toBoolean())
    private val heuristic = MutableStateFlow(settings.get(KEY_HEURISTIC, "true").toBoolean())
    private val nameParsing = MutableStateFlow(settings.get(KEY_NAME_PARSING, "true").toBoolean())
    private val bundleSubtitles = MutableStateFlow(settings.get(KEY_BUNDLE_SUBTITLES, "true").toBoolean())
    private val syncPlayHistory = MutableStateFlow(settings.get(KEY_SYNC_PLAY_HISTORY, "true").toBoolean())
    private val themeMode = MutableStateFlow(settings.get(KEY_THEME_MODE).ifEmpty { null })
    private val themeSeed = MutableStateFlow(settings.get(KEY_THEME_SEED).ifEmpty { null })
    private val gridView = MutableStateFlow(settings.get(KEY_GRID_VIEW, "false").toBoolean())
    private val acceleration = MutableStateFlow(settings.get(KEY_ACCELERATION, "true").toBoolean())
    private val connections = MutableStateFlow(settings.get(KEY_CONNECTIONS, "8").toIntOrNull() ?: 8)
    private val session = MutableStateFlow(loadSession())
    private val quota = MutableStateFlow<QuotaSnapshot?>(null)
    private val instantTarget = MutableStateFlow<InstantTarget?>(null)
    private val archivePasswords = MutableStateFlow(settings.get(KEY_ARCHIVE_PASSWORDS))
    private val recentMoveTargets = MutableStateFlow(settings.get(KEY_RECENT_MOVE_TARGETS))
    private val downloadPath = MutableStateFlow(
        settings.get(KEY_DOWNLOAD_DIR, settings.downloadDirectory.absolutePath),
    )

    private fun loadSession(): UserSession =
        UserSession(
            token = settings.get(KEY_SESSION_TOKEN),
            refreshToken = settings.get(KEY_SESSION_REFRESH),
            userId = settings.get(KEY_SESSION_USER_ID),
            username = settings.get(KEY_SESSION_USERNAME),
            avatarUrl = settings.get(KEY_SESSION_AVATAR),
            concurrentConnections = connections.value,
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

    override val gridViewFlow: Flow<Boolean> = gridView.asStateFlow()
    override suspend fun setGridViewEnabled(enabled: Boolean) {
        settings.set(KEY_GRID_VIEW, enabled.toString())
        gridView.value = enabled
    }

    override val sessionFlow: Flow<UserSession> = session.asStateFlow()
    override suspend fun saveSession(token: String, refreshToken: String, userId: String, username: String, avatarUrl: String) {
        settings.set(KEY_SESSION_TOKEN, token)
        settings.set(KEY_SESSION_REFRESH, refreshToken)
        settings.set(KEY_SESSION_USER_ID, userId)
        settings.set(KEY_SESSION_USERNAME, username)
        settings.set(KEY_SESSION_AVATAR, avatarUrl)
        // 邮箱只在内存里，重写会话时带过去，否则令牌刷新后要等下次取资料才重新出现
        session.value = loadSession().copy(email = session.value.email)
    }

    override suspend fun saveProfile(username: String, avatarUrl: String, email: String) {
        session.value = session.value.copy(
            username = username.ifEmpty { session.value.username },
            avatarUrl = avatarUrl.ifEmpty { session.value.avatarUrl },
            email = email.ifEmpty { session.value.email },
        )
    }

    override suspend fun clearSession() {
        settings.set(KEY_SESSION_TOKEN, "")
        settings.set(KEY_SESSION_REFRESH, "")
        settings.set(KEY_SESSION_USER_ID, "")
        settings.set(KEY_SESSION_USERNAME, "")
        settings.set(KEY_SESSION_AVATAR, "")
        session.value = loadSession()
    }

    // 配额与秒传目标是纯会话态，与上游 Android 实现一致放内存，不落盘。
    override val quotaSnapshotFlow: Flow<QuotaSnapshot?> = quota.asStateFlow()
    override suspend fun saveQuotaSnapshot(usageBytes: Long, limitBytes: Long) {
        quota.value = QuotaSnapshot(usageBytes, limitBytes)
    }

    override val instantTargetFlow: Flow<InstantTarget?> = instantTarget.asStateFlow()
    override suspend fun saveInstantTarget(folderId: String, folderName: String) {
        instantTarget.value = InstantTarget(folderId, folderName)
    }

    override val concurrentAccelerationFlow: Flow<Boolean> = acceleration.asStateFlow()
    override val concurrentConnectionsFlow: Flow<Int> = connections.asStateFlow()
    override val downloadDirPathFlow: Flow<String> = downloadPath.asStateFlow()

    override suspend fun setDownloadDirPath(path: String) {
        settings.downloadDirectory = java.io.File(path)
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

    override suspend fun loadOfflinePacks(): String = settings.get(KEY_OFFLINE_PACKS)

    override suspend fun saveOfflinePacks(serialized: String) {
        settings.set(KEY_OFFLINE_PACKS, serialized)
    }

    override val archivePasswordsFlow: Flow<String> = archivePasswords.asStateFlow()
    override suspend fun saveArchivePasswords(serialized: String) {
        settings.set(KEY_ARCHIVE_PASSWORDS, serialized)
        archivePasswords.value = serialized
    }

    override val recentMoveTargetsFlow: Flow<String> = recentMoveTargets.asStateFlow()
    override suspend fun saveRecentMoveTargets(serialized: String) {
        settings.set(KEY_RECENT_MOVE_TARGETS, serialized)
        recentMoveTargets.value = serialized
    }

    override suspend fun getIgnoredUpdateVersion(): String? = settings.get(KEY_IGNORED_UPDATE).ifEmpty { null }

    override suspend fun setIgnoredUpdateVersion(version: String) {
        settings.set(KEY_IGNORED_UPDATE, version)
    }

    private companion object {
        const val MAX_PLAYBACK_ENTRIES = 500
        const val KEY_DOWNLOAD_TASKS = "download.tasks"
        const val KEY_OFFLINE_PACKS = "download.offlinePacks"
        const val KEY_UPLOAD_TASKS = "upload.tasks"
        const val KEY_ARCHIVE_PASSWORDS = "drive.archivePasswords"
        const val KEY_RECENT_MOVE_TARGETS = "drive.recentMoveTargets"
        const val KEY_IGNORED_UPDATE = "update.ignoredVersion"
        const val KEY_SPOILER = "ui.spoilerBlur"
        const val KEY_HEURISTIC = "ui.heuristicFilter"
        const val KEY_BUNDLE_SUBTITLES = "ui.bundleSubtitles"
        const val KEY_SYNC_PLAY_HISTORY = "player.syncPlayHistory"
        const val KEY_NAME_PARSING = "ui.nameParsing"
        const val KEY_GRID_VIEW = "ui.gridView"
        // 沿用 Fluent 版设置页的键，旧值是小写的 system、light、dark，解析时不分大小写
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_THEME_SEED = "ui.themeSeed"
        const val KEY_ACCELERATION = "download.concurrentAcceleration"
        const val KEY_CONNECTIONS = "download.concurrentConnections"
        const val KEY_DOWNLOAD_DIR = "download.directory"
        const val KEY_SESSION_TOKEN = "session.token"
        const val KEY_SESSION_REFRESH = "session.refreshToken"
        const val KEY_SESSION_USER_ID = "session.userId"
        const val KEY_SESSION_USERNAME = "session.username"
        const val KEY_SESSION_AVATAR = "session.avatarUrl"
        const val KEY_LAST_FOLDER_ID = "drive.lastFolderId"
        const val KEY_LAST_FOLDER_NAME = "drive.lastFolderName"
        const val KEY_LAST_FOLDER_STACK = "drive.lastFolderStack"
        const val KEY_PLAYBACK_PREFIX = "playback."
    }
}
