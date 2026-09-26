package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 没有保存密码、而 SDK 需要重新用密码登录时抛出。refresh token 失效后 SDK 会自行回落到
 * 密码登录，这时只能让用户回登录页重新输入，不能拿空密码去试。
 */
class PasswordRequiredException : IllegalStateException("登录已失效，请重新登录")

/**
 * @param httpClient 交给 SDK 的 API 客户端。缺省由 SDK 自建；冒烟测试在这里换成 Ktor 的
 *   MockEngine，走的仍是 SDK 真实的鉴权、重试与解析路径。
 */
class PikoClientManager(
    private val sessionStore: PikoSessionStore,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient? = null,
) : PikoClientProvider {
    private val _currentClient = MutableStateFlow<PikPakClient?>(null)
    override val currentClient: StateFlow<PikPakClient?> = _currentClient.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private var reconnectJob: Job? = null

    init {
        scope.launch(Dispatchers.Default) { restore() }
    }

    /**
     * 用上次的会话恢复登录。
     *
     * 本地会话未过期时 SDK 的 login() 不发请求；过期了才去 refresh。refresh 在断网时抛的是
     * 传输层异常而不是 PikPakException，这种情况保留 client 先进主界面，后台等网络恢复再
     * 补一次 login()。旧实现在这里把 client 关掉，离线启动就被踢回登录页，连已下载的文件
     * 都看不到。只有服务端明确拒绝（PikPakException 或需要密码）才回登录页。
     */
    suspend fun restore() {
        try {
            val account = sessionStore.loadLastAccount()?.takeIf { it.isNotBlank() } ?: return
            val credentials = sessionStore.loadCredentials(account)
            val client = clientFor(account, credentials?.password)
            when (val failure = tryLogin(client)) {
                null -> _currentClient.value = client
                is LoginFailure.Transient -> {
                    _currentClient.value = client
                    scheduleReconnect(client)
                }
                is LoginFailure.Rejected -> {
                    client.close()
                    if (failure.error is PikPakException && failure.error.isRefreshTokenInvalid) {
                        sessionStore.clear(account)
                    }
                }
            }
        } finally {
            _isInitializing.value = false
        }
    }

    suspend fun login(account: String, passwordSupplier: suspend () -> String): Result<PikPakClient> =
        runSuspendCatching {
            val password = passwordSupplier()
            val client = clientFor(account, password)
            try {
                client.login()
                sessionStore.saveLastAccount(account)
                sessionStore.saveCredentials(account, password)
                replaceClient(client)
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "密码登录失败", it) }

    suspend fun loginWithToken(account: String, token: String, refreshToken: String = ""): Result<PikPakClient> =
        runSuspendCatching {
            val now = currentTimeSeconds()
            sessionStore.save(
                account,
                Session(
                    accessToken = token,
                    refreshToken = refreshToken,
                    sub = account,
                    expiresAt = now + 30L * 24L * 60L * 60L,
                ),
            )
            val client = clientFor(account)
            try {
                client.login()
                sessionStore.saveLastAccount(account)
                replaceClient(client)
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "令牌登录失败", it) }

    suspend fun logout() {
        PikoLog.i(TAG, "退出登录")
        reconnectJob?.cancel()
        val current = _currentClient.value
        _currentClient.value = null
        if (current != null) {
            // 先清密码再清会话：Desktop 的存储以 last account 判断归属，清会话会连它一起删掉，
            // 之后再清密码就找不到主人了。旧实现从不清密码，退出登录后明文密码仍留在磁盘上。
            sessionStore.clearCredentials(current.account)
            sessionStore.clear(current.account)
            current.close()
        }
        sessionStore.clearLastAccount()
    }

    private fun replaceClient(client: PikPakClient) {
        reconnectJob?.cancel()
        val previous = _currentClient.value
        _currentClient.value = client
        if (previous != null && previous !== client) previous.close()
    }

    /** 断网启动后按退避重试，直到登录成功、被服务端拒绝或 client 被替换。 */
    private fun scheduleReconnect(client: PikPakClient) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.Default) {
            var backoffMs = RECONNECT_INITIAL_DELAY_MS
            while (_currentClient.value === client) {
                delay(backoffMs)
                when (val failure = tryLogin(client)) {
                    null -> return@launch
                    is LoginFailure.Transient -> backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    is LoginFailure.Rejected -> {
                        // 网络恢复后才发现会话已不可用。这时回登录页是唯一出路
                        if (_currentClient.compareAndSet(client, null)) client.close()
                        if (failure.error is PikPakException && failure.error.isRefreshTokenInvalid) {
                            sessionStore.clear(client.account)
                        }
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun tryLogin(client: PikPakClient): LoginFailure? = try {
        client.login()
        PikoLog.i(TAG, "会话可用")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: PasswordRequiredException) {
        PikoLog.w(TAG, "会话失效且没有保存的密码，回登录页", e)
        LoginFailure.Rejected(e)
    } catch (e: PikPakException) {
        // SDK 已经把 429 与 5xx 重试过一轮，到这里仍失败说明服务端暂时不可用，不是会话失效
        val status = e.httpStatus
        if (status != null && (status == 429 || status >= 500)) {
            PikoLog.w(TAG, "服务端暂时不可用（HTTP $status），稍后重试", e)
            LoginFailure.Transient(e)
        } else {
            PikoLog.w(TAG, "服务端拒绝登录（HTTP $status）", e)
            LoginFailure.Rejected(e)
        }
    } catch (e: Throwable) {
        PikoLog.w(TAG, "登录时网络出错，稍后重试", e)
        LoginFailure.Transient(e)
    }

    private sealed interface LoginFailure {
        val error: Throwable

        class Transient(override val error: Throwable) : LoginFailure
        class Rejected(override val error: Throwable) : LoginFailure
    }

    // 预算与 SDK 默认值相同，显式写出是因为它们直接决定下载与播放的并发上限，
    // 改 SDK 版本时这里不该悄悄跟着变
    private fun clientFor(account: String, password: String? = null): PikPakClient {
        val supplier: suspend () -> String = if (password == null) {
            { throw PasswordRequiredException() }
        } else {
            { password }
        }
        return PikPakClient(
            account = account,
            passwordSupplier = supplier,
            sessionStore = sessionStore,
            httpClient = httpClient,
            connectionBudget = CONNECTION_BUDGET,
            accountConnectionBudget = ACCOUNT_CONNECTION_BUDGET,
        )
    }

    private fun currentTimeSeconds(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000L

    private companion object {
        const val TAG = "Auth"
        const val CONNECTION_BUDGET = 8
        const val ACCOUNT_CONNECTION_BUDGET = 16
        const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 60_000L
    }
}
