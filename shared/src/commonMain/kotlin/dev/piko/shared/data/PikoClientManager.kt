package dev.piko.shared.data

import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * 没有保存密码、而 SDK 需要重新用密码登录时抛出。refresh token 失效后 SDK 会自行回落到
 * 密码登录，这时只能让用户回登录页重新输入，不能拿空密码去试。
 */
class PasswordRequiredException : IllegalStateException("登录已失效，请重新登录")

/**
 * 登录态与已保存的账号。同一时刻只有一个账号在用（[currentClient]），其余登录过、没退出的记在 [accounts] 里，
 * 手动切换（[switchTo]）。切换即换一个 client，各仓库照旧只认 [currentClient]，按账号的东西在账号变了时各自换一份。
 *
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

    private val _accounts = MutableStateFlow(SavedAccounts())
    val accounts: StateFlow<SavedAccounts> = _accounts.asStateFlow()
    private val accountsLock = Mutex()

    /** 登录着一个账号、又打开登录页去加另一个。登录成功或取消时结束；这期间当前账号照常在后台工作。 */
    private val _addingAccount = MutableStateFlow(false)
    val addingAccount: StateFlow<Boolean> = _addingAccount.asStateFlow()

    /**
     * 本次运行里已知会话被服务端拒绝、要重新输入密码的账号。设置里的账号列表据此标出，不必点了才发现。
     * 不存盘：下次启动时各自重新试过才知道。
     */
    private val _expiredAccounts = MutableStateFlow<Set<String>>(emptySet())
    val expiredAccounts: StateFlow<Set<String>> = _expiredAccounts.asStateFlow()

    /**
     * 因为这个账号失效才回到登录页（启动恢复或断网重连时被拒）时是它，登录页预先填上、提示重新输入密码。
     * 登录页只是一张表单，不列已保存的账号：多账号的切换与补登都在设置里。
     */
    var expiredPrefill: String = ""
        private set

    private fun markExpired(account: String, returnsToLogin: Boolean = false) {
        _expiredAccounts.update { it + account }
        if (returnsToLogin) expiredPrefill = account
    }

    // 切换、登录与退出都在换 client，交错时后完成的会把先完成的换掉，而账号列表记的是先完成的那个
    private val switchLock = Mutex()

    private var reconnectJob: Job? = null

    init {
        scope.launch(Dispatchers.Default) { restore() }
    }

    /** 用的是 SDK 自建的网络客户端。注入的是测试的 MockEngine 时为 false，这时测根域名的速度没有意义。 */
    val reachesRealNetwork: Boolean get() = httpClient == null

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
            val saved = sessionStore.loadAccounts()
            _accounts.value = saved
            val account = saved.current?.takeIf { it.isNotBlank() } ?: run {
                PikoLog.i(TAG, "启动：没有上次的账号，进登录页，已保存 ${saved.accounts.size} 个账号")
                return
            }
            val credentials = credentialsOf(account)
            PikoLog.i(TAG, "启动：恢复上次的账号，${credentialState(account, credentials?.password != null)}，已保存 ${saved.accounts.size} 个账号")
            val client = clientFor(account, credentials?.password)
            when (val failure = tryLogin(client, "恢复会话")) {
                null -> _currentClient.value = client
                is LoginFailure.Transient -> {
                    PikoLog.i(TAG, "先以离线状态进主界面，后台等网络恢复")
                    _currentClient.value = client
                    scheduleReconnect(client)
                }
                // 刷新令牌失效时 SDK 已在改用密码登录之前把会话从存储里清掉，这里不必再清。
                // 账号留在列表里：登录页预先填上它，重新输入密码即可
                is LoginFailure.Rejected -> {
                    client.close()
                    markExpired(account, returnsToLogin = true)
                    editAccounts { it.copy(current = null) }
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
            PikoLog.i(TAG, "密码登录")
            try {
                client.login()
                PikoLog.i(TAG, "密码登录成功，用时 ${started.elapsedNow().inWholeMilliseconds} ms")
                sessionStore.saveCredentials(account, password)
                switchLock.withLock { adopt(client) }
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
            PikoLog.i(TAG, "令牌登录，${if (refreshToken.isBlank()) "无" else "有"}刷新令牌")
            try {
                client.login()
                PikoLog.i(TAG, "令牌登录成功")
                switchLock.withLock { adopt(client) }
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { PikoLog.w(TAG, "令牌登录失败，${describeAuthError(it)}", it) }

    /**
     * 切到已保存的 [account]。本地会话还有效时不发请求，当场换好；断网时照样切过去，后台等网络恢复，与启动时一样。
     * 会话被服务端拒绝、又没有保存的密码时失败，仍停在原来的账号，由界面引导重新登录。
     */
    suspend fun switchTo(account: String): Result<Unit> = runSuspendCatching {
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            val credentials = credentialsOf(account)
            PikoLog.i(TAG, "切换账号，${credentialState(account, credentials?.password != null)}")
            val client = clientFor(account, credentials?.password)
            val failure = tryLogin(client, "切换账号")
            if (failure is LoginFailure.Rejected) {
                client.close()
                markExpired(account)
                throw failure.error
            }
            adopt(client)
            if (failure is LoginFailure.Transient) scheduleReconnect(client)
        }
    }

    /**
     * 打开登录页去加一个账号，当前账号不退出。[account] 非空时登录页预先填上它：已保存的账号会话失效，要重新输入密码。
     */
    fun beginAddingAccount(account: String = "") {
        addingPrefill = account
        _addingAccount.value = true
    }

    /** 登录页打开时读一次，见 [beginAddingAccount]。 */
    var addingPrefill: String = ""
        private set

    fun cancelAddingAccount() {
        _addingAccount.value = false
    }

    /**
     * 退出当前账号：清掉它的会话与密码，从列表里去掉，再切到最近用过的另一个已保存账号；切不过去的跳过，
     * 一个也没有时回登录页。
     *
     * 在进程级的 [scope] 里做，调用方被取消也照样做完：退出按钮在对话框里，对话框一关，它的协程作用域就取消了，
     * 在那里清的话界面已显示退出、磁盘上的凭据却还在，下次启动又自动登录。返回的 Job 供需要等清完的调用方 join。
     * 正在退出时再调，返回同一个 Job，不另起一份，否则第二份会把刚切过去的账号也退掉。
     */
    fun logout(): Job = logoutJob?.takeIf { it.isActive } ?: startLogout().also { logoutJob = it }

    private var logoutJob: Job? = null

    private fun startLogout(): Job = scope.launch {
        switchLock.withLock {
            val leaving = _currentClient.value ?: return@withLock
            PikoLog.i(TAG, "退出登录")
            reconnectJob?.cancel()
            // 先摘下、关掉 client 再清：界面不再拿它发请求；关掉之后它也不会在刷新令牌时把会话写回，
            // 先清后关则可能被这样写回
            _currentClient.value = null
            leaving.close()
            forgetStored(leaving.account)
            val next = _accounts.value.accounts.sortedByDescending { it.usedAt }.firstNotNullOfOrNull { candidate ->
                val credentials = credentialsOf(candidate.account)
                val client = clientFor(candidate.account, credentials?.password)
                when (val failure = tryLogin(client, "退出后切换账号")) {
                    is LoginFailure.Rejected -> null.also {
                        client.close()
                        markExpired(candidate.account)
                    }
                    else -> client to failure
                }
            }
            if (next == null) {
                PikoLog.i(TAG, "没有可用的已保存账号，回登录页")
                return@withLock
            }
            val (client, failure) = next
            adopt(client)
            if (failure is LoginFailure.Transient) scheduleReconnect(client)
        }
    }

    /** 从列表里去掉一个不在用的账号，连同它的会话与密码。在用的那个走 [logout]。 */
    fun forget(account: String): Job = scope.launch {
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            PikoLog.i(TAG, "移除已保存的账号")
            forgetStored(account)
        }
    }

    // 凭据删不掉（钥匙串锁着）也照样从列表里去掉：留在列表里等于没退出。残留的机密没有账号指向它，下次同名登录时覆盖
    private suspend fun forgetStored(account: String) {
        runSuspendCatching {
            sessionStore.clearCredentials(account)
            sessionStore.clear(account)
        }.logFailure(TAG, "清除账号凭据失败")
        _expiredAccounts.update { it - account }
        if (expiredPrefill == account) expiredPrefill = ""
        editAccounts { it.without(account) }
    }

    /** 改 [account] 在列表里记着的资料或用量。不在列表里（已经退出）时不写。 */
    suspend fun updateAccount(account: String, edit: (SavedAccount) -> SavedAccount) {
        editAccounts { saved -> saved.find(account)?.let { saved.upsert(edit(it)) } ?: saved }
    }

    private suspend fun editAccounts(edit: (SavedAccounts) -> SavedAccounts) {
        accountsLock.withLock {
            val next = edit(_accounts.value)
            if (next == _accounts.value) return@withLock
            _accounts.value = next
            runSuspendCatching { sessionStore.saveAccounts(next) }.logFailure(TAG, "账号列表保存失败")
        }
    }

    /** 登录或切换成功的 [client] 成为当前账号，记进列表。调用方持有 [switchLock]。 */
    private suspend fun adopt(client: PikPakClient) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        editAccounts { saved ->
            val entry = saved.find(client.account) ?: SavedAccount(client.account)
            saved.upsert(entry.copy(usedAt = now)).copy(current = client.account)
        }
        replaceClient(client)
        _expiredAccounts.update { it - client.account }
        if (expiredPrefill == client.account) expiredPrefill = ""
        _addingAccount.value = false
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
                        if (_currentClient.compareAndSet(client, null)) {
                            client.close()
                            markExpired(client.account, returnsToLogin = true)
                            editAccounts { it.copy(current = null) }
                        }
                        return@launch
                    }
                }
                attempt++
            }
        }
    }

    /**
     * 保存的密码。平台的凭据存储暂时不可用（钥匙串锁着、用户拒绝解锁）时抛异常，这里当作没有密码：
     * 会话本身还有效就照常进去；会话也读不出时 SDK 的 login 同样失败，按网络错误处理，后台重连时再读。
     * 不能把这种失败当成已退出，否则钥匙串一时锁着就把人踢回登录页。
     */
    /** [account] 保存的登录密码，读不出时为 null。压缩包密码的同步拿它派生密钥，见 ArchivePasswordSync。 */
    suspend fun savedPassword(account: String): String? = credentialsOf(account)?.password

    private suspend fun credentialsOf(account: String): PikoCredentials? =
        runSuspendCatching { sessionStore.loadCredentials(account) }
            .onFailure { PikoLog.w(TAG, "读取保存的密码失败，本次按没有密码处理", it) }
            .getOrNull()

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
        val session = runSuspendCatching { sessionStore.load(account) }.logFailure(TAG, "读取本地会话失败").getOrNull()
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
            // 每个账号共用请求预算，批量工作和交互请求合计保持每秒 16 次；实测依据见 development/archive.md。
            rateLimiter = io.github.nihildigit.pikpak.RateLimiter(capacity = 16, refillPerSecond = 16.0),
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
