package dev.piko.shared.data

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

class PikoClientManager(
    private val sessionStore: PikoSessionStore,
    private val scope: CoroutineScope,
) : PikoClientProvider {
    private val _currentClient = MutableStateFlow<PikPakClient?>(null)
    override val currentClient: StateFlow<PikPakClient?> = _currentClient.asStateFlow()

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) { restore() }
    }

    suspend fun restore() {
        try {
            val account = sessionStore.loadLastAccount()
            if (!account.isNullOrBlank()) {
                val credentials = sessionStore.loadCredentials(account)
                val client = clientFor(account, credentials?.password)
                try {
                    client.login()
                    _currentClient.value = client
                } catch (e: PikPakException) {
                    if (e.isRefreshTokenInvalid && credentials != null) {
                        client.close()
                        val passwordClient = clientFor(account, credentials.password)
                        try {
                            passwordClient.login()
                            _currentClient.value = passwordClient
                        } catch (retry: Throwable) {
                            passwordClient.close()
                            sessionStore.clear(account)
                        }
                    } else if (e.isRefreshTokenInvalid) {
                        sessionStore.clear(account)
                    }
                    client.close()
                } catch (e: CancellationException) {
                    client.close()
                    throw e
                } catch (_: Exception) {
                    client.close()
                }
            }
        } finally {
            _isInitializing.value = false
        }
    }

    suspend fun login(account: String, passwordSupplier: suspend () -> String): Result<PikPakClient> =
        runSuspendCatching {
            val password = passwordSupplier()
            val client = PikPakClient(
                account = account,
                passwordSupplier = { password },
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )
            try {
                client.login()
                sessionStore.saveLastAccount(account)
                sessionStore.saveCredentials(account, password)
                _currentClient.value = client
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }

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
                _currentClient.value = client
                client
            } catch (e: Throwable) {
                client.close()
                throw e
            }
        }

    suspend fun logout() {
        val current = _currentClient.value
        if (current != null) {
            sessionStore.clear(current.account)
            current.close()
        }
        _currentClient.value = null
        sessionStore.clearLastAccount()
    }

    private fun clientFor(account: String, password: String? = null): PikPakClient {
        return if (password == null) {
            PikPakClient(
                account = account,
                password = "",
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )
        } else {
            PikPakClient(
                account = account,
                passwordSupplier = { password },
                sessionStore = sessionStore,
                connectionBudget = 8,
                accountConnectionBudget = 16,
            )
        }
    }

    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun currentTimeSeconds(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000L
}
