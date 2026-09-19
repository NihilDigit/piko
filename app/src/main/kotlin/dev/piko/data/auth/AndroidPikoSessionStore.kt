package dev.piko.data.auth

import android.content.Context
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.PikoCredentials
import io.github.nihildigit.pikpak.Session

class AndroidPikoSessionStore(
    context: Context,
    private val sessionManager: SessionManager,
) : PikoSessionStore {
    private val delegate = DataStoreSessionStore(context)

    override suspend fun load(account: String): Session? = delegate.load(account)
    override suspend fun save(account: String, session: Session) {
        delegate.save(account, session)
        sessionManager.saveSession(
            token = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.sub,
            username = account,
        )
    }

    override suspend fun clear(account: String) {
        delegate.clear(account)
        sessionManager.clearSession()
    }
    override suspend fun loadLastAccount(): String? = delegate.loadLastAccount()
    override suspend fun saveLastAccount(account: String) = delegate.saveLastAccount(account)
    override suspend fun clearLastAccount() = delegate.clearLastAccount()
    override suspend fun loadCredentials(account: String): PikoCredentials? =
        delegate.loadCredentials(account)?.let { PikoCredentials(account, it) }
    override suspend fun saveCredentials(account: String, password: String) =
        delegate.saveCredentials(account, password)
    override suspend fun clearCredentials(account: String) = delegate.clearCredentials(account)
}
