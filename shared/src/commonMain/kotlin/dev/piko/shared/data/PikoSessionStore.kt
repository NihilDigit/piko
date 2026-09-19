package dev.piko.shared.data

import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore

/** Persistence required by the shared client manager. Platform shells provide it. */
interface PikoSessionStore : SessionStore {
    suspend fun loadLastAccount(): String?
    suspend fun saveLastAccount(account: String)
    suspend fun clearLastAccount()
    suspend fun loadCredentials(account: String): PikoCredentials?
    suspend fun saveCredentials(account: String, password: String)
    suspend fun clearCredentials(account: String)
}

data class PikoCredentials(val account: String, val password: String)

data class PikoUserSession(
    val account: String,
    val session: Session,
)
