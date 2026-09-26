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
import kotlin.time.TimeSource

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
            val account = sessionStore.loadLastAccount()?.takeIf { it.isNotBlank() } ?: run {
                PikoLog.i(TAG, "启动：没有上次的账号，进登录页")
                return
            }
            val credentials = sessionStore.loadCredentials(account)
            PikoLog.i(TAG, "启动：恢复 ${maskAccount(account)}，${credentialState(account, credentials?.password != null)}")
            val client = clientFor(account, credentials?.password)
            when (val failure = tryLogin(client, "恢复会话")) {
                null -> _currentClient.value = client
                is LoginFailure.Transient -> {
                    PikoLog.i(TAG, "先以离线状态进主界面，后台等网络恢复")
                    _currentClient.value = client
                    scheduleReconnect(client)
                }
                is LoginFailure.Rejected -> {
                    client.close()
                    if (failure.error is PikPakException && failure.error.isRefreshTokenInvalid) {
                        PikoLog.i(TAG, "刷新令牌已失效，清除本地会话")
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
            val started = TimeSource.Monotonic.markNow()
            PikoLog.i(TAG, "密码登录：${maskAccount(account)}")
            try {
                client.login()
                PikoLog.i(TAG, "密码登录成功，用时 ${started.elapsedNow().inWholeMilliseconds} ms")
                sessionStore.saveLastAccount(account)
                sessionStore.saveCredentials(account, password)
                replaceClient(client)
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "密码登录失败，${describeAuthError(it)}", it) }

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
            PikoLog.i(TAG, "令牌登录：${maskAccount(account)}，${if (refreshToken.isBlank()) "无" else "有"}刷新令牌")
            try {
                client.login()
                PikoLog.i(TAG, "令牌登录成功")
                sessionStore.saveLastAccount(account)
                replaceClient(client)
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "令牌登录失败，${describeAuthError(it)}", it) }

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
            var attempt = 1
            while (_currentClient.value === client) {
                delay(backoffMs)
                when (val failure = tryLogin(client, "第 $attempt 次重连（等了 ${backoffMs / 1000} 秒）")) {
                    null -> return@launch
                    is LoginFailure.Transient -> backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    is LoginFailure.Rejected -> {
                        // 网络恢复后才发现会话已不可用。这时回登录页是唯一出路
                        PikoLog.w(TAG, "重连时会话被拒，回登录页")
                        if (_currentClient.compareAndSet(client, null)) client.close()
                        if (failure.error is PikPakException && failure.error.isRefreshTokenInvalid) {
                            sessionStore.clear(client.account)
                        }
                        return@launch
                    }
                }
                attempt++
            }
        }
    }

    /** [step] 写进日志，说明这次登录因何而起：启动恢复、第几次重连。 */
    private suspend fun tryLogin(client: PikPakClient, step: String): LoginFailure? {
        val started = TimeSource.Monotonic.markNow()
        fun took() = "用时 ${started.elapsedNow().inWholeMilliseconds} ms"
        return try {
            client.login()
            PikoLog.i(TAG, "$step：会话可用，${took()}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequiredException) {
            PikoLog.w(TAG, "$step：会话失效且没有保存的密码，回登录页，${took()}", e)
            LoginFailure.Rejected(e)
        } catch (e: PikPakException) {
            // SDK 已经把 429 与 5xx 重试过一轮，到这里仍失败说明服务端暂时不可用，不是会话失效
            val status = e.httpStatus
            if (status != null && (status == 429 || status >= 500)) {
                PikoLog.w(TAG, "$step：服务端暂时不可用，稍后重试，${describeAuthError(e)}，${took()}", e)
                LoginFailure.Transient(e)
            } else {
                PikoLog.w(TAG, "$step：服务端拒绝，${describeAuthError(e)}，${took()}", e)
                LoginFailure.Rejected(e)
            }
        } catch (e: Throwable) {
            PikoLog.w(TAG, "$step：网络出错，稍后重试，${describeAuthError(e)}，${took()}", e)
            LoginFailure.Transient(e)
        }
    }

    /** 登录前本地凭据的样子，不含令牌本身：会话在不在、还剩多久，有没有刷新令牌与保存的密码。 */
    private suspend fun credentialState(account: String, hasPassword: Boolean): String {
        val session = runSuspendCatching { sessionStore.load(account) }.getOrNull()
        val sessionText = if (session == null) {
            "无本地会话"
        } else {
            val leftMinutes = (session.expiresAt - currentTimeSeconds()) / 60
            val expiry = if (leftMinutes >= 0) "会话剩余 $leftMinutes 分钟" else "会话已过期 ${-leftMinutes} 分钟"
            expiry + if (session.refreshToken.isBlank()) "，无刷新令牌" else "，有刷新令牌"
        }
        return "$sessionText，${if (hasPassword) "有" else "无"}保存的密码"
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

/**
 * 日志里的账号只留首尾：导出的日志会发给别人，完整的邮箱或手机号用不上，
 * 同一台设备上切换过账号时又要分得清是哪个。
 */
private fun maskAccount(account: String): String {
    val name = account.substringBefore('@')
    val domain = account.substringAfter('@', "").let { if (it.isEmpty()) "" else "@$it" }
    val kept = if (name.length <= 3) name.take(1) else name.take(2) + "…" + name.takeLast(1)
    return kept + "***" + domain
}

/** 服务端的错误码与名称比异常文案有用：同一句「登录失败」背后是验证码、限流还是密码错，只看得到这里。 */
private fun describeAuthError(error: Throwable): String = when (error) {
    is PikPakException -> buildString {
        append("错误码 ${error.errorCode}（${error.errorMessage}）")
        error.httpStatus?.let { append("，HTTP $it") }
        if (error.isCaptchaRequired) append("，要求验证码")
        error.errorDescription?.takeIf { it.isNotBlank() }?.let { append("，$it") }
    }
    else -> error::class.simpleName ?: "未知异常"
}
