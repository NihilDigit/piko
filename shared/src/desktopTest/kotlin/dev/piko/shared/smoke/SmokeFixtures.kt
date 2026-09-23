package dev.piko.shared.smoke

import dev.piko.data.auth.InstantTarget
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.auth.QuotaSnapshot
import dev.piko.data.auth.UserSession
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
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
class MemoryPreferences(instantTarget: InstantTarget? = null) : PikoUserPreferences {
    private val instantTargetState = MutableStateFlow(instantTarget)
    private val positions = ConcurrentHashMap<String, Long>()

    override suspend fun savePlaybackPosition(fileId: String, positionMs: Long) {
        positions[fileId] = positionMs
    }
    override suspend fun getPlaybackPosition(fileId: String): Long = positions[fileId] ?: 0L
    override suspend fun saveLastFolder(folderId: String, folderName: String, stackSerialized: String) = Unit
    override suspend fun getLastFolder(): Triple<String, String, String> = Triple("", "网盘", "")
    override val spoilerBlurFlow: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setSpoilerBlurEnabled(enabled: Boolean) = Unit
    override val heuristicFilterFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setHeuristicFilterEnabled(enabled: Boolean) = Unit
    override val bundleSubtitlesFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setBundleSubtitlesEnabled(enabled: Boolean) = Unit
    override val gridViewFlow: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setGridViewEnabled(enabled: Boolean) = Unit
    override val sessionFlow: Flow<UserSession> = MutableStateFlow(UserSession())
    override suspend fun saveSession(token: String, refreshToken: String, userId: String, username: String, avatarUrl: String) = Unit
    override suspend fun saveProfile(username: String, avatarUrl: String, email: String) = Unit
    override val quotaSnapshotFlow: Flow<QuotaSnapshot?> = MutableStateFlow(null)
    override suspend fun saveQuotaSnapshot(usageBytes: Long, limitBytes: Long) = Unit
    override val instantTargetFlow: Flow<InstantTarget?> = instantTargetState
    override suspend fun saveInstantTarget(folderId: String, folderName: String) {
        instantTargetState.value = InstantTarget(folderId, folderName)
    }
    override suspend fun clearSession() = Unit
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
}

/** 内存会话存储，行为与两端的实现一致：会话、上次账号、密码三份各自独立。 */
class MemorySessionStore : PikoSessionStore {
    val sessions = ConcurrentHashMap<String, Session>()
    val passwords = ConcurrentHashMap<String, String>()
    @Volatile var lastAccount: String? = null

    override suspend fun load(account: String): Session? = sessions[account]
    override suspend fun save(account: String, session: Session) {
        sessions[account] = session
    }
    override suspend fun clear(account: String) {
        sessions.remove(account)
    }
    override suspend fun loadLastAccount(): String? = lastAccount
    override suspend fun saveLastAccount(account: String) {
        lastAccount = account
    }
    override suspend fun clearLastAccount() {
        lastAccount = null
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
