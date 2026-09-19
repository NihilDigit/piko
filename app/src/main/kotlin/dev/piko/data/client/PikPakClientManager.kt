package dev.piko.data.client

import android.content.Context
import dev.piko.data.auth.DataStoreSessionStore
import dev.piko.data.auth.SessionManager
import dev.piko.util.runSuspendCatching
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manager class orchestrating PikPak client instances and session lifecycle.
 *
 * Documentation References:
 * - Kotlin Structured Concurrency & Cancellation: kotlin-docs-mirror/pages/docs/coroutines-cancellation.md
 *   "Ensure CancellationException is rethrown to allow proper coroutine teardown."
 * - Android DataStore Session Persistence: android-docs-mirror/pages/develop/ui/compose/state.md
 */
class PikPakClientManager(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
) {
    private val sessionStore = DataStoreSessionStore(context)
    private val _currentClient = MutableStateFlow<PikPakClient?>(null)
    val currentClient: StateFlow<PikPakClient?> = _currentClient.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val session = sessionManager.sessionFlow.first()
            if (session.isLoggedIn && session.username.isNotEmpty()) {
                val client = PikPakClient(
                    account = session.username,
                    password = "",
                    sessionStore = sessionStore,
                    connectionBudget = 8,
                    accountConnectionBudget = 16,
                )
                try {
                    // SDK handles the refresh-token flow. Persist the returned
                    // session because a successful refresh rotates its tokens.
                    val restoredSession = client.login()
                    persistSession(session.username, restoredSession)
                    _currentClient.value = client
                } catch (e: PikPakException) {
                    if (e.isRefreshTokenInvalid) {
                        sessionStore.clear(session.username)
                        sessionManager.clearSession()
                    }
                    client.close()
                    _currentClient.value = null
                } catch (e: CancellationException) {
                    client.close()
                    throw e
                } catch (e: Exception) {
                    // Network and server failures must not destroy a valid
                    // refresh token; the next process start can retry silently.
                    client.close()
                    _currentClient.value = null
                }
            }
            _isInitializing.value = false
        }
    }

    suspend fun login(account: String, passwordSupplier: suspend () -> String): Result<PikPakClient> {
        return runSuspendCatching {
            val client = PikPakClient(
                account = account,
                passwordSupplier = passwordSupplier,
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )
            // 调用 login/prewarm 登录并建立凭据
            client.login()
            persistSession(account, client.currentSession ?: error("Login returned no session"))
            _currentClient.value = client
            client
        }
    }

    suspend fun loginWithToken(account: String, token: String, refreshToken: String = ""): Result<PikPakClient> {
        return runSuspendCatching {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val expiresAt = currentTimeSeconds + 3600 * 24 * 30 // 30天
            sessionStore.save(
                account,
                Session(
                    accessToken = token,
                    refreshToken = refreshToken,
                    sub = account,
                    expiresAt = expiresAt,
                ),
            )

            val client = PikPakClient(
                account = account,
                password = "",
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )

            // 从 sessionStore 激活会话
            client.login()

            persistSession(account, client.currentSession ?: error("Token login returned no session"))
            _currentClient.value = client
            client
        }
    }

    suspend fun logout() {
        val current = _currentClient.value
        if (current != null) {
            sessionStore.clear(current.account)
            _currentClient.value = null
        }
        sessionManager.clearSession()
    }

    private suspend fun persistSession(account: String, session: Session) {
        sessionManager.saveSession(
            token = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.sub,
            username = account,
        )
    }
}
