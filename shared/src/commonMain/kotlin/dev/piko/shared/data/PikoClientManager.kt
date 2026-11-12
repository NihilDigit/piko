package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.AuthClientProfile
import io.github.nihildigit.pikpak.DeviceAuthorization
import io.github.nihildigit.pikpak.DeviceAuthorizationException
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.InteractiveLoginRequiredException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.generateDeviceId
import io.github.nihildigit.pikpak.getUserProfile
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 没有保存密码、而 SDK 需要重新交互式登录时抛出。refresh token 失效后无密码账号无法自动重登，
 * 这时只能让用户回登录页重新登录（密码或 Google 授权），不能拿空密码去试。
 */
class PasswordRequiredException(cause: Throwable? = null) : IllegalStateException(
    tr(
        "登录已过期，请重新登录",
        "Session expired. Please sign in again",
        "セッションの有効期限が切れました。再ログインしてください",
        "세션이 만료되었습니다. 다시 로그인해 주세요",
        "登入已過期，請重新登入",
    ),
    cause,
)

/**
 * 续期已保存账号时，实际完成认证的远端账号 `sub` 与目标槽位不一致。拒绝覆盖原槽位数据。
 */
class AccountMismatchException(
    val expectedLabel: String,
    val actualLabel: String,
) : IllegalStateException(
    tr(
        "当前登录的是「$actualLabel」，与待重新登录的账号「$expectedLabel」不一致；原账号数据未改动",
        "Signed in as \"$actualLabel\", which does not match \"$expectedLabel\". Existing account data was kept intact.",
        "ログインしたアカウント「$actualLabel」は対象アカウント「$expectedLabel」と一致しません。既存データは保持されました",
        "로그인한 계정(\"$actualLabel\")이 대상 계정(\"$expectedLabel\")과 일치하지 않습니다. 기존 계정 데이터는 유지되었습니다",
        "目前登入的是「$actualLabel」，與待重新登入的帳號「$expectedLabel」不一致；原帳號資料未改動",
    ),
)

/** 加密同步（解压密码与 MetaTube 配置）使用的密钥及其来源。 */
data class EncryptedSyncKey(
    val secret: String,
    /** 为真表示用户显式设置的独立同步口令（写入 `version = 2` 信封）；为假表示回退使用已保存的账号密码（`version = 1`）。 */
    val isPassphrase: Boolean,
) {
    /** 严禁在 `toString()` 中输出明文口令或密码。 */
    override fun toString(): String = "EncryptedSyncKey(isPassphrase=$isPassphrase)"
}

/** 会话失效回到登录页或从设置页点「重新登录」时的账号上下文，避免将内部存储键 `pikpak:<sub>` 填入用户名输入框。 */
data class ExpiredAuthContext(
    val storageKey: String,
    val displayName: String,
    val loginAccount: String,
    val authMethod: AccountAuthMethod,
    val sub: String,
)

internal enum class GoogleTxPhase {
    AWAITING_BROWSER,
    COMMITTING,
    COMPLETED,
    CANCELLED,
}

/** 一次进行中的 Google / 浏览器设备授权事务。 */
class GoogleLoginTransaction internal constructor(
    val txId: String,
    val authorization: DeviceAuthorization,
    val googleAuthUrl: String,
    val qrLoginUrl: String,
    val expectedAccount: String? = null,
    val expectedSub: String = "",
    internal val bootstrapClient: PikPakClient,
) {
    internal var phase: GoogleTxPhase = GoogleTxPhase.AWAITING_BROWSER
}

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
    transportMemoryBudgetBytes: Long = PikPakClient.DEFAULT_TRANSPORT_MEMORY_BUDGET_BYTES,
    /** Android 在创建界面前同步发布本地账号；网络鉴权仍在后台执行。测试与桌面端默认异步恢复。 */
    restoreLocallyImmediately: Boolean = false,
) : PikoClientProvider {
    private val budgetLock = PikoLock()
    private val googleTxLock = PikoLock()

    @Volatile
    private var activeGoogleTx: GoogleLoginTransaction? = null

    @Volatile
    private var currentTransportBudgetBytes: Long = transportMemoryBudgetBytes.coerceAtLeast(MIN_TRANSPORT_BUDGET_BYTES)

    /** 动态调整 SDK 传输层共享内存预算（如 Android 收到 onTrimMemory 或回到前台恢复）。 */
    fun setTransportMemoryBudgetBytes(bytes: Long) {
        val clamped = bytes.coerceAtLeast(MIN_TRANSPORT_BUDGET_BYTES)
        withPikoLock(budgetLock) {
            currentTransportBudgetBytes = clamped
            _currentClient.value?.setTransportMemoryBudgetBytes(clamped)
        }
    }

    private fun publishCurrentClient(client: PikPakClient): PikPakClient? = withPikoLock(budgetLock) {
        client.setTransportMemoryBudgetBytes(currentTransportBudgetBytes)
        val previous = _currentClient.value
        _currentClient.value = client
        previous
    }

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
     * 本次运行里已知会话被服务端拒绝、要重新登录的账号。设置里的账号列表据此标出，不必点了才发现。
     * 不存盘：下次启动时各自重新试过才知道。
     */
    private val _expiredAccounts = MutableStateFlow<Set<String>>(emptySet())
    val expiredAccounts: StateFlow<Set<String>> = _expiredAccounts.asStateFlow()

    /**
     * 因为这个账号失效才回到登录页（启动恢复或断网重连时被拒）时是它的存储键，登录页据此提示续期并按需预填登录名。
     */
    var expiredPrefill: String = ""
        private set

    private fun markExpired(account: String, returnsToLogin: Boolean = false) {
        _expiredAccounts.update { it + account }
        if (returnsToLogin) expiredPrefill = account
    }

    /** 将 [addingPrefill] 或 [expiredPrefill] 存储键解析为安全的界面预填上下文（绝不暴露 `pikpak:<sub>`）。 */
    fun resolvePrefillContext(storageKey: String): ExpiredAuthContext? {
        val key = storageKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val saved = _accounts.value.find(key) ?: SavedAccount(account = key)
        val safeLoginAccount = saved.loginAccount.ifBlank {
            if (saved.isInternalStorageKey) "" else saved.account
        }
        return ExpiredAuthContext(
            storageKey = saved.account,
            displayName = saved.displayName,
            loginAccount = safeLoginAccount,
            authMethod = saved.authMethod,
            sub = saved.effectiveSub,
        )
    }

    // 切换、登录与退出都在换 client，交错时后完成的会把先完成的换掉，而账号列表记的是先完成的那个
    private val switchLock = Mutex()

    private var reconnectJob: Job? = null

    init {
        if (restoreLocallyImmediately) {
            val client = runBlocking(Dispatchers.IO) { restoreLocalAccount() }
            if (client != null) scope.launch { validateRestoredClient(client) }
        } else {
            scope.launch { restore() }
        }
    }

    /** 用的是 SDK 自建的网络客户端。注入的是测试的 MockEngine 时为 false，这时测根域名的速度没有意义。 */
    val reachesRealNetwork: Boolean get() = httpClient == null

    /**
     * 用上次的会话恢复登录。
     *
     * 本地会话未过期时 SDK 的 login() 不发请求；过期了才去 refresh。refresh 在断网时抛的是
     * 传输层异常而不是 PikPakException，这种情况保留 client 先进主界面，后台等网络恢复再
     * 补一次 login()。只有服务端明确拒绝（PikPakException 或无密码需交互重登）才回登录页。
     */
    suspend fun restore() {
        try {
            val client = restoreLocalAccount(completeInitialization = false) ?: return
            validateRestoredClient(client)
        } finally {
            _isInitializing.value = false
        }
    }

    // 这里只读本地存储，不调用可能刷新令牌的 login，避免冷启动等待网络。
    private suspend fun restoreLocalAccount(completeInitialization: Boolean = true): PikPakClient? {
        try {
            val saved = sessionStore.loadAccounts()
            _accounts.value = saved
            val account = saved.current?.takeIf { it.isNotBlank() } ?: run {
                PikoLog.i(TAG, "Startup: no active saved account; showing sign-in (savedAccounts=${saved.accounts.size})")
                return null
            }
            val credentials = credentialsOf(account)
            val localSession = runSuspendCatching { sessionStore.load(account) }
                .logFailure(TAG, "Failed to load local session (acct=${logAccountSlot(account)})")
                .getOrNull()
            if (localSession != null) {
                val entry = saved.find(account)
                if (entry != null && (entry.sub.isBlank() && localSession.sub.isNotBlank() || entry.deviceId.isBlank() && !localSession.deviceId.isNullOrBlank())) {
                    _accounts.value = saved.upsert(
                        entry.copy(
                            sub = entry.sub.ifBlank { localSession.sub },
                            deviceId = entry.deviceId.ifBlank { localSession.deviceId.orEmpty() },
                        ),
                    )
                }
            }
            PikoLog.i(
                TAG,
                "Startup: restoring previous account (acct=${logAccountSlot(account)}, ${formatCredentialState(localSession, credentials?.password != null)}, savedAccounts=${saved.accounts.size})",
            )
            val client = clientFor(account, credentials?.password)
            publishCurrentClient(client)?.takeIf { it !== client }?.close()
            return client
        } finally {
            if (completeInitialization) _isInitializing.value = false
        }
    }

    private suspend fun validateRestoredClient(client: PikPakClient) {
        val failure = tryLogin(client, "restore session")
        switchLock.withLock {
            // 恢复期间可能已经切号或退出，旧鉴权结果不能影响新账号。
            if (_currentClient.value !== client) return
            when (failure) {
                null -> Unit
                is LoginFailure.Transient -> scheduleReconnect(client)
                is LoginFailure.Rejected -> {
                    _currentClient.value = null
                    client.close()
                    markExpired(client.account, returnsToLogin = true)
                    editAccounts { it.copy(current = null) }
                }
            }
        }
    }

    suspend fun login(
        account: String,
        expectedAccount: String? = null,
        passwordSupplier: suspend () -> String,
    ): Result<PikPakClient> = runSuspendCatching {
        cancelGoogleDeviceLogin()
        val trimmedInput = account.trim()
        require(trimmedInput.isNotEmpty()) {
            tr("请输入邮箱或手机号", "Please enter your email or phone number", "メールアドレスまたは電話番号を入力してください", "이메일 또는 전화번호를 입력해 주세요", "請輸入信箱或手機號碼")
        }
        val slot = logAccountSlot(trimmedInput)
        val password = passwordSupplier()
        val existingAccounts = _accounts.value
        val matchedSlot = existingAccounts.find(trimmedInput)
            ?: existingAccounts.accounts.firstOrNull { it.loginAccount.equals(trimmedInput, ignoreCase = true) }
        val expectedSaved = expectedAccount?.takeIf { it.isNotBlank() }?.let { existingAccounts.find(it) }
        val expectedSub = expectedSaved?.effectiveSub?.ifBlank {
            runSuspendCatching { sessionStore.load(expectedAccount)?.sub.orEmpty() }.getOrDefault("")
        }.orEmpty()
        val preferredDeviceId = (matchedSlot ?: expectedSaved)?.deviceId?.takeIf { it.isNotBlank() }
        val tempStore = InMemorySessionStore()
        val tempAccountKey = matchedSlot?.account ?: trimmedInput
        val bootstrap = PikPakClient(
            account = tempAccountKey,
            passwordSupplier = { password },
            sessionStore = tempStore,
            httpClient = httpClient,
            loginUsername = trimmedInput,
            explicitDeviceId = preferredDeviceId,
            connectionBudget = CONNECTION_BUDGET,
            accountConnectionBudget = ACCOUNT_CONNECTION_BUDGET,
            rateLimiter = RateLimiter(capacity = 64, refillPerSecond = 60.0),
            transportMemoryBudgetBytes = currentTransportBudgetBytes,
        )
        val started = TimeSource.Monotonic.markNow()
        PikoLog.i(TAG, "Password sign-in started (acct=$slot)")
        val bootstrapDeviceId: String
        val session: Session
        val profile = try {
            bootstrap.login()
            val fetchedProfile = runSuspendCatching { bootstrap.getUserProfile(Duration.ZERO) }.getOrNull()
            // 在 getUserProfile 之后再读取最新 Session，避免资料请求触发 401 刷新轮换令牌后保存旧快照
            session = bootstrap.currentSession
                ?: tempStore.load(tempAccountKey)
                ?: throw IllegalStateException("Session missing after successful password sign-in")
            bootstrapDeviceId = session.deviceId?.takeIf { it.isNotBlank() } ?: bootstrap.deviceId
            fetchedProfile
        } finally {
            bootstrap.close()
        }
        val verifiedSub = profile?.sub?.trim()?.takeIf { it.isNotEmpty() } ?: session.sub.trim()
        if (expectedSub.isNotEmpty() && verifiedSub.isNotEmpty() && verifiedSub != expectedSub) {
            val expectedLabel = expectedSaved?.displayName ?: expectedAccount.orEmpty()
            val actualLabel = profile?.email?.ifBlank { trimmedInput } ?: trimmedInput
            throw AccountMismatchException(expectedLabel = expectedLabel, actualLabel = actualLabel)
        }
        if (expectedAccount != null && expectedSub.isEmpty() && expectedSaved != null) {
            val expectedHandle = expectedSaved.publicAccountHandle ?: expectedAccount
            if (!trimmedInput.equals(expectedHandle, ignoreCase = true)) {
                throw AccountMismatchException(expectedLabel = expectedSaved.displayName, actualLabel = trimmedInput)
            }
        }
        cancelGoogleDeviceLogin()
        switchLock.withLock {
            val currentAccounts = _accounts.value
            val targetAccount = verifiedSub.takeIf { it.isNotEmpty() }?.let { currentAccounts.findBySub(it)?.account }
                ?: matchedSlot?.account
                ?: expectedAccount?.takeIf { key ->
                    val candidate = currentAccounts.find(key)
                    candidate != null && candidate.effectiveSub.isEmpty()
                }
                ?: trimmedInput
            val finalSession = session.copy(
                sub = verifiedSub.ifEmpty { session.sub },
                profileId = session.profileId ?: AuthClientProfile.AndroidDefault.id,
                deviceId = bootstrapDeviceId,
            )
            sessionStore.save(targetAccount, finalSession)
            sessionStore.saveCredentials(targetAccount, password)
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            editAccounts { saved ->
                val base = saved.find(targetAccount) ?: SavedAccount(account = targetAccount)
                val updated = base.copy(
                    sub = verifiedSub.ifEmpty { base.sub },
                    name = profile?.name?.ifEmpty { base.name } ?: base.name,
                    email = profile?.email?.ifEmpty { base.email } ?: base.email,
                    avatarUrl = profile?.avatarUrl ?: base.avatarUrl,
                    loginAccount = trimmedInput,
                    authMethod = AccountAuthMethod.Password,
                    deviceId = bootstrapDeviceId,
                    userProfile = profile ?: base.userProfile,
                    usedAt = now,
                )
                saved.upsert(updated).copy(current = targetAccount)
            }
            val client = clientFor(targetAccount, password)
            try {
                adopt(client)
            } catch (e: Throwable) {
                client.close()
                throw e
            }
            PikoLog.i(
                TAG,
                "Password sign-in succeeded (acct=${logAccountSlot(targetAccount)}, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
            )
            client
        }
    }.onFailure { err ->
        if (err !is CancellationException) {
            if (err is AccountMismatchException) {
                PikoLog.w(TAG, "Password sign-in rejected: account mismatch (acct=${logAccountSlot(account)})")
            } else {
                PikoLog.w(TAG, "Password sign-in failed (acct=${logAccountSlot(account)}, ${describeAuthError(err)})", err)
            }
        }
    }

    /**
     * 启动第一方 Android 设备授权流（RFC 8628）并生成跳转系统默认浏览器直达 Google 选号的地址。
     *
     * @param expectedAccount 若是在续期某个已失效账号，传入其存储键；完成授权后会核对 `sub` 防止登错账号串槽。
     */
    suspend fun startGoogleDeviceLogin(expectedAccount: String? = null): Result<GoogleLoginTransaction> =
        runSuspendCatching {
            val normalizedExpected = expectedAccount?.trim()?.takeIf { it.isNotEmpty() }
            val expectedSaved = normalizedExpected?.let { _accounts.value.find(it) }
            val expectedSub = expectedSaved?.effectiveSub?.ifBlank {
                runSuspendCatching { sessionStore.load(normalizedExpected)?.sub.orEmpty() }.getOrDefault("")
            }.orEmpty()
            val preferredDeviceId = expectedSaved?.deviceId?.takeIf { it.isNotBlank() } ?: generateDeviceId()
            val bootstrap = PikPakClient(
                account = normalizedExpected ?: "google-device-bootstrap",
                passwordSupplier = null,
                sessionStore = InMemorySessionStore(),
                httpClient = httpClient,
                loginUsername = "",
                explicitDeviceId = preferredDeviceId,
                authProfile = AuthClientProfile.AndroidDefault,
                connectionBudget = CONNECTION_BUDGET,
                accountConnectionBudget = ACCOUNT_CONNECTION_BUDGET,
                rateLimiter = RateLimiter(capacity = 64, refillPerSecond = 60.0),
                transportMemoryBudgetBytes = currentTransportBudgetBytes,
            )
            val authorization = try {
                bootstrap.startDeviceAuthorization(platformName = "Piko", deviceId = preferredDeviceId)
            } catch (e: Throwable) {
                bootstrap.close()
                throw e
            }
            val tx = GoogleLoginTransaction(
                txId = generateDeviceId(),
                authorization = authorization,
                googleAuthUrl = authorization.googleAuthUrl,
                qrLoginUrl = authorization.qrLoginUrl,
                expectedAccount = normalizedExpected,
                expectedSub = expectedSub,
                bootstrapClient = bootstrap,
            )
            val previous = withPikoLock(googleTxLock) {
                val old = activeGoogleTx
                if (old != null && old.phase == GoogleTxPhase.AWAITING_BROWSER) {
                    old.phase = GoogleTxPhase.CANCELLED
                }
                activeGoogleTx = tx
                old
            }
            previous?.bootstrapClient?.close()
            PikoLog.i(
                TAG,
                "Google device authorization started (tx=${tx.txId.take(8)}, reauth=${normalizedExpected != null}, expiresInSec=${authorization.expiresInSeconds})",
            )
            tx
        }.onFailure { err ->
            if (err !is CancellationException) {
                PikoLog.w(TAG, "Failed to start Google device authorization (${describeAuthError(err)})", err)
            }
        }

    /**
     * 轮询等待用户在系统浏览器完成 Google 登录与 `/drive/qr-login` 确认，随后校验 `/v1/user/me` 并按 `sub` 归并账号槽位。
     */
    suspend fun awaitGoogleDeviceLogin(tx: GoogleLoginTransaction): Result<PikPakClient> =
        runSuspendCatching {
            val started = TimeSource.Monotonic.markNow()
            try {
                val initialSession = tx.bootstrapClient.awaitDeviceAuthorization(tx.authorization, commit = true)
                if (!isActiveGoogleTx(tx.txId)) {
                    throw CancellationException("Google device authorization superseded or cancelled")
                }
                val profile = tx.bootstrapClient.getUserProfile(Duration.ZERO)
                // 在 getUserProfile 之后读取最新 Session，防止校验请求期间刷新轮换令牌导致持久化旧快照
                val session = tx.bootstrapClient.currentSession ?: initialSession
                val verifiedSub = profile.sub.trim()
                if (verifiedSub.isEmpty()) {
                    throw PikPakException(
                        errorCode = 0,
                        errorMessage = "missing_user_sub",
                        errorDescription = "User profile returned empty sub",
                    )
                }
                if (session.sub.isNotBlank() && session.sub != verifiedSub) {
                    throw PikPakException(
                        errorCode = 0,
                        errorMessage = "subject_mismatch",
                        errorDescription = "Token subject does not match /v1/user/me",
                    )
                }
                val actualLabel = profile.email.ifBlank { profile.name.ifBlank { "Google" } }
                if (tx.expectedAccount != null && tx.expectedSub.isEmpty()) {
                    val expectedLabel = _accounts.value.find(tx.expectedAccount)?.displayName ?: tx.expectedAccount
                    throw AccountMismatchException(expectedLabel = expectedLabel, actualLabel = actualLabel)
                }
                if (tx.expectedSub.isNotEmpty() && verifiedSub != tx.expectedSub) {
                    val expectedLabel = tx.expectedAccount?.let { _accounts.value.find(it)?.displayName }
                        ?: tx.expectedAccount.orEmpty()
                    throw AccountMismatchException(expectedLabel = expectedLabel, actualLabel = actualLabel)
                }
                if (tx.expectedAccount != null) {
                    val otherClaimed = _accounts.value.findBySub(verifiedSub)
                    if (otherClaimed != null && otherClaimed.account != tx.expectedAccount) {
                        val expectedLabel = _accounts.value.find(tx.expectedAccount)?.displayName ?: tx.expectedAccount
                        throw AccountMismatchException(expectedLabel = expectedLabel, actualLabel = actualLabel)
                    }
                }
                switchLock.withLock {
                    if (!claimGoogleTxCommit(tx.txId)) {
                        throw CancellationException("Google device authorization cancelled before commit")
                    }
                    withContext(NonCancellable) {
                        val currentAccounts = _accounts.value
                        // 仅当目标槽位的 sub 已确认一致时才复用 expectedAccount；旧槽位缺少 sub 时绝不盲目覆盖，改为独立 canonical 槽位
                        val targetAccount = currentAccounts.findBySub(verifiedSub)?.account
                            ?: tx.expectedAccount?.takeIf { key ->
                                tx.expectedSub.isNotEmpty() && tx.expectedSub == verifiedSub && currentAccounts.find(key) != null
                            }
                            ?: SavedAccount.storageKeyForSub(verifiedSub)
                        val existingPassword = credentialsOf(targetAccount)?.password
                        val finalDeviceId = session.deviceId?.takeIf { it.isNotBlank() } ?: tx.authorization.deviceId
                        val finalSession = session.copy(
                            sub = verifiedSub,
                            profileId = session.profileId ?: AuthClientProfile.AndroidDefault.id,
                            deviceId = finalDeviceId,
                        )
                        sessionStore.save(targetAccount, finalSession)
                        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        editAccounts { saved ->
                            val base = saved.find(targetAccount) ?: SavedAccount(account = targetAccount)
                            val updated = base.copy(
                                sub = verifiedSub,
                                name = profile.name.ifEmpty { base.name },
                                email = profile.email.ifEmpty { base.email },
                                avatarUrl = profile.avatarUrl ?: base.avatarUrl,
                                loginAccount = base.loginAccount.ifBlank {
                                    if (base.isInternalStorageKey) "" else base.account
                                },
                                authMethod = AccountAuthMethod.Google,
                                deviceId = finalDeviceId,
                                userProfile = profile,
                                usedAt = now,
                            )
                            saved.upsert(updated).copy(current = targetAccount)
                        }
                        val client = clientFor(targetAccount, existingPassword)
                        try {
                            adopt(client)
                            markGoogleTxCompleted(tx.txId)
                        } catch (e: Throwable) {
                            client.close()
                            throw e
                        }
                        PikoLog.i(
                            TAG,
                            "Google sign-in succeeded (acct=${logAccountSlot(targetAccount)}, preservedPassword=${existingPassword != null}, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
                        )
                        client
                    }
                }
            } finally {
                clearGoogleTxIfMatching(tx.txId)
                tx.bootstrapClient.close()
            }
        }.onFailure { err ->
            if (err !is CancellationException) {
                if (err is AccountMismatchException) {
                    PikoLog.w(TAG, "Google sign-in rejected: account mismatch (tx=${tx.txId.take(8)})")
                } else {
                    PikoLog.w(TAG, "Google sign-in failed (tx=${tx.txId.take(8)}, ${describeAuthError(err)})", err)
                }
            }
        }

    /** 取消正在等待浏览器确认的 Google 设备授权事务。若事务已进入原子提交阶段则返回 false。 */
    fun cancelGoogleDeviceLogin(txId: String? = null): Boolean {
        val cancelled = withPikoLock(googleTxLock) {
            val current = activeGoogleTx ?: return@withPikoLock null
            if (txId != null && current.txId != txId) return@withPikoLock null
            if (current.phase != GoogleTxPhase.AWAITING_BROWSER) return@withPikoLock null
            current.phase = GoogleTxPhase.CANCELLED
            activeGoogleTx = null
            current
        }
        if (cancelled != null) {
            cancelled.bootstrapClient.close()
            PikoLog.i(TAG, "Cancelled Google device authorization (tx=${cancelled.txId.take(8)})")
            return true
        }
        return false
    }

    private fun isActiveGoogleTx(txId: String): Boolean = withPikoLock(googleTxLock) {
        val current = activeGoogleTx
        current?.txId == txId && current.phase == GoogleTxPhase.AWAITING_BROWSER
    }

    private fun claimGoogleTxCommit(txId: String): Boolean = withPikoLock(googleTxLock) {
        val current = activeGoogleTx ?: return@withPikoLock false
        if (current.txId != txId || current.phase != GoogleTxPhase.AWAITING_BROWSER) return@withPikoLock false
        current.phase = GoogleTxPhase.COMMITTING
        true
    }

    private fun markGoogleTxCompleted(txId: String) {
        withPikoLock(googleTxLock) {
            val current = activeGoogleTx ?: return@withPikoLock
            if (current.txId == txId) {
                current.phase = GoogleTxPhase.COMPLETED
                activeGoogleTx = null
            }
        }
    }

    private fun clearGoogleTxIfMatching(txId: String) {
        withPikoLock(googleTxLock) {
            val current = activeGoogleTx ?: return@withPikoLock
            if (current.txId == txId && current.phase != GoogleTxPhase.COMMITTING) {
                activeGoogleTx = null
            }
        }
    }

    suspend fun loginWithToken(account: String, token: String, refreshToken: String = ""): Result<PikPakClient> =
        runSuspendCatching {
            cancelGoogleDeviceLogin()
            val slot = logAccountSlot(account)
            val started = TimeSource.Monotonic.markNow()
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
            PikoLog.i(TAG, "Token sign-in started (acct=$slot, hasRefreshToken=${refreshToken.isNotBlank()})")
            try {
                client.login()
                val profile = runSuspendCatching { client.getUserProfile(Duration.ZERO) }.getOrNull()
                switchLock.withLock {
                    editAccounts { saved ->
                        val base = saved.find(account) ?: SavedAccount(account = account)
                        saved.upsert(
                            base.copy(
                                sub = profile?.sub?.ifBlank { base.sub } ?: base.sub,
                                name = profile?.name?.ifEmpty { base.name } ?: base.name,
                                email = profile?.email?.ifEmpty { base.email } ?: base.email,
                                avatarUrl = profile?.avatarUrl ?: base.avatarUrl,
                                authMethod = AccountAuthMethod.Token,
                                userProfile = profile ?: base.userProfile,
                            ),
                        )
                    }
                    adopt(client)
                }
                PikoLog.i(TAG, "Token sign-in succeeded (acct=$slot, elapsedMs=${started.elapsedNow().inWholeMilliseconds})")
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }.onFailure { err ->
            if (err !is CancellationException) {
                PikoLog.w(TAG, "Token sign-in failed (acct=${logAccountSlot(account)}, ${describeAuthError(err)})", err)
            }
        }

    /**
     * 切到已保存的 [account]。本地会话还有效时不发请求，当场换好；断网时照样切过去，后台等网络恢复，与启动时一样。
     * 会话被服务端拒绝、又没有保存的密码时失败，仍停在原来的账号，由界面引导重新登录。
     */
    suspend fun switchTo(account: String): Result<Unit> = runSuspendCatching {
        cancelGoogleDeviceLogin()
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            val credentials = credentialsOf(account)
            PikoLog.i(TAG, "Switching account (acct=${logAccountSlot(account)}, ${credentialState(account, credentials?.password != null)})")
            val client = clientFor(account, credentials?.password)
            val failure = tryLogin(client, "switch account")
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
     * 打开登录页去加一个账号，当前账号不退出。[account] 非空时表示续期该已保存账号。
     */
    fun beginAddingAccount(account: String = "") {
        addingPrefill = account
        _addingAccount.value = true
    }

    /** 登录页打开时读一次，见 [beginAddingAccount]。 */
    var addingPrefill: String = ""
        private set

    fun cancelAddingAccount() {
        cancelGoogleDeviceLogin()
        addingPrefill = ""
        _addingAccount.value = false
    }

    /**
     * 退出当前账号：清掉它的会话、密码与同步口令，从列表里去掉，再切到最近用过的另一个已保存账号；切不过去的跳过，
     * 一个也没有时回登录页。
     */
    fun logout(): Job = logoutJob?.takeIf { it.isActive } ?: startLogout().also { logoutJob = it }

    private var logoutJob: Job? = null

    private fun startLogout(): Job = scope.launch {
        cancelGoogleDeviceLogin()
        switchLock.withLock {
            val leaving = _currentClient.value ?: return@withLock
            PikoLog.i(TAG, "Signing out (acct=${logAccountSlot(leaving.account)})")
            reconnectJob?.cancel()
            _currentClient.value = null
            leaving.close()
            forgetStored(leaving.account)
            val next = _accounts.value.accounts.sortedByDescending { it.usedAt }.firstNotNullOfOrNull { candidate ->
                val credentials = credentialsOf(candidate.account)
                val client = clientFor(candidate.account, credentials?.password)
                when (val failure = tryLogin(client, "switch account after sign-out")) {
                    is LoginFailure.Rejected -> null.also {
                        client.close()
                        markExpired(candidate.account)
                    }
                    else -> client to failure
                }
            }
            if (next == null) {
                PikoLog.i(TAG, "No usable saved account remaining after sign-out; returning to sign-in")
                return@withLock
            }
            val (client, failure) = next
            adopt(client)
            if (failure is LoginFailure.Transient) scheduleReconnect(client)
        }
    }

    /** 从列表里去掉一个不在用的账号，连同它的会话、密码与同步口令。在用的那个走 [logout]。 */
    fun forget(account: String): Job = scope.launch {
        switchLock.withLock {
            if (_currentClient.value?.account == account) return@withLock
            PikoLog.i(TAG, "Removing saved account (acct=${logAccountSlot(account)})")
            forgetStored(account)
        }
    }

    // 凭据删不掉（钥匙串锁着）也照样从列表里去掉：留在列表里等于没退出。残留的机密没有账号指向它，下次同名登录时覆盖
    private suspend fun forgetStored(account: String) {
        runSuspendCatching {
            sessionStore.clearCredentials(account)
            sessionStore.clearSyncSecret(account)
            sessionStore.clear(account)
        }.logFailure(TAG, "Failed to clear stored credentials (acct=${logAccountSlot(account)})")
        _expiredAccounts.update { it - account }
        if (expiredPrefill == account) expiredPrefill = ""
        if (addingPrefill == account) addingPrefill = ""
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
            runSuspendCatching { sessionStore.saveAccounts(next) }
                .logFailure(TAG, "Failed to persist saved accounts list (count=${next.accounts.size})")
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
        addingPrefill = ""
        _addingAccount.value = false
    }

    private fun replaceClient(client: PikPakClient) {
        reconnectJob?.cancel()
        val previous = publishCurrentClient(client)
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
                when (val failure = tryLogin(client, "reconnect attempt $attempt (waitedMs=$backoffMs)")) {
                    null -> return@launch
                    is LoginFailure.Transient -> backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    is LoginFailure.Rejected -> {
                        PikoLog.w(TAG, "Session rejected during reconnect (acct=${logAccountSlot(client.account)}); returning to sign-in")
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

    /** [account] 保存的登录密码，读不出时为 null。 */
    suspend fun savedPassword(account: String): String? = credentialsOf(account)?.password

    /**
     * 读取 [account] 用于加密同步的密钥：优先使用独立同步口令（`syncSecret`），未设置时回退到已保存的登录密码。
     *
     * 此方法不吞掉 [dev.piko.shared.auth.VaultUnavailableException]，避免系统钥匙串短暂锁定时误回退到旧密码或误判为无密钥。
     */
    suspend fun syncKeyFor(account: String): EncryptedSyncKey? {
        val passphrase = sessionStore.loadSyncSecret(account)?.takeIf { it.isNotEmpty() }
        if (passphrase != null) return EncryptedSyncKey(secret = passphrase, isPassphrase = true)
        val password = sessionStore.loadCredentials(account)?.password?.takeIf { it.isNotEmpty() }
        if (password != null) return EncryptedSyncKey(secret = password, isPassphrase = false)
        return null
    }

    /** [account] 是否已显式配置独立加密同步口令。 */
    suspend fun hasSyncPassphrase(account: String): Boolean =
        runSuspendCatching { !sessionStore.loadSyncSecret(account).isNullOrEmpty() }.getOrDefault(false)

    /** 读取 [account] 当前保存的独立同步口令原文（供口令校验失败时无损回滚）。 */
    suspend fun rawSyncPassphrase(account: String): String? =
        sessionStore.loadSyncSecret(account)

    /** 为 [account] 设置或更新独立加密同步口令；传入空串等价于清除独立口令。 */
    suspend fun setSyncPassphrase(account: String, passphrase: String) {
        if (passphrase.isEmpty()) {
            sessionStore.clearSyncSecret(account)
        } else {
            sessionStore.saveSyncSecret(account, passphrase)
        }
    }

    /** 清除 [account] 的独立加密同步口令（若该账号存有登录密码，后续回退使用登录密码）。 */
    suspend fun clearSyncPassphrase(account: String) {
        sessionStore.clearSyncSecret(account)
    }

    /** 压缩包密码与会话、密码同在账号的机密里，只经 ArchivePasswordVault 读写。 */
    val archivePasswordStore: ArchivePasswordStore get() = sessionStore

    private suspend fun credentialsOf(account: String): PikoCredentials? =
        runSuspendCatching { sessionStore.loadCredentials(account) }
            .onFailure { err ->
                if (err !is CancellationException) {
                    PikoLog.w(TAG, "Failed to load saved password (acct=${logAccountSlot(account)}); proceeding without password", err)
                }
            }
            .getOrNull()

    /** [step] 写进日志，说明这次登录因何而起：启动恢复、第几次重连。 */
    private suspend fun tryLogin(client: PikPakClient, step: String): LoginFailure? {
        val slot = logAccountSlot(client.account)
        val started = TimeSource.Monotonic.markNow()
        fun took() = "elapsedMs=${started.elapsedNow().inWholeMilliseconds}"
        return try {
            client.login()
            PikoLog.i(TAG, "Auth check [$step]: session valid (acct=$slot, ${took()})")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequiredException) {
            PikoLog.w(TAG, "Auth check [$step]: session expired without saved password (acct=$slot, ${took()})", e)
            LoginFailure.Rejected(e)
        } catch (e: InteractiveLoginRequiredException) {
            val wrapped = PasswordRequiredException(e)
            PikoLog.w(TAG, "Auth check [$step]: interactive re-login required (acct=$slot, ${took()})", e)
            LoginFailure.Rejected(wrapped)
        } catch (e: DeviceAuthorizationException) {
            val wrapped = PasswordRequiredException(e)
            PikoLog.w(TAG, "Auth check [$step]: device authorization rejected (acct=$slot, ${took()})", e)
            LoginFailure.Rejected(wrapped)
        } catch (e: PikPakException) {
            // SDK 已经把 429 与 5xx 重试过一轮，到这里仍失败说明服务端暂时不可用，不是会话失效
            val status = e.httpStatus
            if (status != null && (status == 429 || status >= 500)) {
                PikoLog.w(TAG, "Auth check [$step]: server temporarily unavailable; will retry (acct=$slot, ${describeAuthError(e)}, ${took()})", e)
                LoginFailure.Transient(e)
            } else {
                PikoLog.w(TAG, "Auth check [$step]: rejected by server (acct=$slot, ${describeAuthError(e)}, ${took()})", e)
                LoginFailure.Rejected(e)
            }
        } catch (e: Throwable) {
            PikoLog.w(TAG, "Auth check [$step]: transport failure; will retry (acct=$slot, ${describeAuthError(e)}, ${took()})", e)
            LoginFailure.Transient(e)
        }
    }

    /** 登录前本地凭据的样子，不含令牌本身：会话在不在、还剩多久，有没有刷新令牌与保存的密码。 */
    private suspend fun credentialState(account: String, hasPassword: Boolean): String {
        val session = runSuspendCatching { sessionStore.load(account) }
            .logFailure(TAG, "Failed to load local session (acct=${logAccountSlot(account)})")
            .getOrNull()
        return formatCredentialState(session, hasPassword)
    }

    private fun formatCredentialState(session: Session?, hasPassword: Boolean): String {
        val sessionText = if (session == null) {
            "session=none"
        } else {
            val leftMinutes = (session.expiresAt - currentTimeSeconds()) / 60
            val expiry = if (leftMinutes >= 0) "sessionRemainingMin=$leftMinutes" else "sessionExpiredMin=${-leftMinutes}"
            "$expiry, hasRefreshToken=${session.refreshToken.isNotBlank()}"
        }
        return "$sessionText, hasSavedPassword=$hasPassword"
    }

    private sealed interface LoginFailure {
        val error: Throwable

        class Transient(override val error: Throwable) : LoginFailure
        class Rejected(override val error: Throwable) : LoginFailure
    }

    // 预算与 SDK 默认值相同，显式写出是因为它们直接决定下载与播放的并发上限，
    // 改 SDK 版本时这里不该悄悄跟着变
    private fun clientFor(account: String, password: String? = null): PikPakClient {
        val saved = _accounts.value.find(account)
        val loginUsername = saved?.loginAccount?.ifBlank { null }
            ?: account.takeUnless { it.startsWith(SavedAccount.INTERNAL_ACCOUNT_PREFIX) }.orEmpty()
        val explicitDeviceId = saved?.deviceId?.takeIf { it.isNotBlank() }
        val supplier: (suspend () -> String)? = if (password == null || loginUsername.isBlank()) {
            null
        } else {
            { password }
        }
        return PikPakClient(
            account = account,
            passwordSupplier = supplier,
            sessionStore = sessionStore,
            httpClient = httpClient,
            loginUsername = loginUsername,
            explicitDeviceId = explicitDeviceId,
            connectionBudget = CONNECTION_BUDGET,
            accountConnectionBudget = ACCOUNT_CONNECTION_BUDGET,
            // 解除 OkHttp 单主机 5 并发枷锁后，实测 HTTP/2 下 80~115 req/s 均 0 次 429 与 0 次验证码（2026-10-30 ApiRateWallProbeTest）；
            // 取 64 突发 / 60 req/s 留足近一倍安全余量，批量重命名、引用保存与归档直接跑满多路并发
            rateLimiter = RateLimiter(capacity = 64, refillPerSecond = 60.0),
            transportMemoryBudgetBytes = currentTransportBudgetBytes,
        ).also { created ->
            saved?.toAccountSnapshot()?.let { snapshot ->
                created.seedAccountState(
                    quota = snapshot.quota,
                    transferQuota = snapshot.transferQuota,
                    userProfile = snapshot.userProfile,
                )
            }
            created.onInteractiveLoginRequired = {
                onRuntimeInteractiveLoginRequired(created)
            }
        }
    }

    private fun onRuntimeInteractiveLoginRequired(client: PikPakClient) {
        if (_currentClient.value !== client) return
        scope.launch {
            switchLock.withLock {
                if (_currentClient.value !== client) return@withLock
                PikoLog.w(
                    TAG,
                    "Active session requires interactive re-login (acct=${logAccountSlot(client.account)}); returning to sign-in",
                )
                reconnectJob?.cancel()
                _currentClient.value = null
                client.close()
                markExpired(client.account, returnsToLogin = true)
                editAccounts { it.copy(current = null) }
            }
        }
    }

    private fun currentTimeSeconds(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000L

    private companion object {
        const val TAG = "Auth"
        const val CONNECTION_BUDGET = 8
        const val ACCOUNT_CONNECTION_BUDGET = 16
        const val MIN_TRANSPORT_BUDGET_BYTES = 16L * 1024 * 1024
        const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 60_000L
    }
}

/**
 * 脱敏后的稳定短账号槽位标识（8 位十六进制 FNV-1a 摘要），用于关联多账号切换与落盘日志而不泄露邮箱或手机号。
 */
internal fun logAccountSlot(account: String?): String {
    val normalized = account?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "none"
    var hash = 0x811c9dc5u
    for (b in normalized.encodeToByteArray()) {
        hash = (hash xor b.toUByte().toUInt()) * 0x01000193u
    }
    return hash.toString(16).padStart(8, '0')
}

/** 服务端的错误码与名称比异常文案有用：同一句「登录失败」背后是验证码、限流还是密码错，只看得到这里。 */
private fun describeAuthError(error: Throwable): String = when (error) {
    is DeviceAuthorizationException -> buildString {
        append("deviceAuth=${error.reason} (${error.errorMessage})")
        error.httpStatus?.let { append(", http=$it") }
    }
    is PikPakException -> buildString {
        append("code=${error.errorCode} (${error.errorMessage})")
        error.httpStatus?.let { append(", http=$it") }
        if (error.isCaptchaRequired) append(", captchaRequired=true")
        error.errorDescription?.takeIf { it.isNotBlank() }?.let { append(", detail=$it") }
    }
    else -> error::class.simpleName ?: "unknown"
}
