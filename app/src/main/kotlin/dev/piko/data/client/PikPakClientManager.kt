package dev.piko.data.client

import android.content.Context
import dev.piko.data.auth.DataStoreSessionStore
import dev.piko.data.auth.SessionManager
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                    // 冷启动关键：从 sessionStore 恢复并激活内存中的 session
                    client.login()
                    _currentClient.value = client
                } catch (e: Exception) {
                    // 凭据彻底失效时清空登录态回到登录页
                    sessionManager.clearSession()
                    _currentClient.value = null
                }
            }
            _isInitializing.value = false
        }
    }

    suspend fun login(account: String, passwordSupplier: suspend () -> String): Result<PikPakClient> {
        return runCatching {
            val client = PikPakClient(
                account = account,
                passwordSupplier = passwordSupplier,
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )
            // 调用 login/prewarm 登录并建立凭据
            client.login()
            val accessToken = client.currentSession?.accessToken ?: "authenticated"
            val refreshToken = client.currentSession?.refreshToken.orEmpty()
            sessionManager.saveSession(
                token = accessToken,
                refreshToken = refreshToken,
                username = account,
                userId = account,
            )
            _currentClient.value = client
            client
        }
    }

    suspend fun loginWithToken(account: String, token: String, refreshToken: String = ""): Result<PikPakClient> {
        return runCatching {
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val expiresAt = currentTimeSeconds + 3600 * 24 * 30 // 30天
            sessionStore.save(
                account,
                Session(
                    accessToken = token,
                    refreshToken = refreshToken.ifEmpty { token },
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

            sessionManager.saveSession(
                token = token,
                refreshToken = refreshToken,
                username = account,
                userId = account,
            )
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
}
