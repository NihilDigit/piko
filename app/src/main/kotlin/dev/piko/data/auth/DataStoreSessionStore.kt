package dev.piko.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json

/**
 * 将 PikPak 会话通过 DataStore 序列化并持久化到本地安全沙盒中。
 * 进程重启后完全保留 Token 与 RefreshToken，自动触发无感续期。
 */
class DataStoreSessionStore(private val context: Context) : SessionStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sessionKey(account: String) = stringPreferencesKey("pikpak_session_$account")

    override suspend fun load(account: String): Session? {
        val prefs = context.dataStore.data.first()
        val raw = prefs[sessionKey(account)] ?: return null
        return runCatching {
            json.decodeFromString(Session.serializer(), raw)
        }.getOrNull()
    }

    override suspend fun save(account: String, session: Session) {
        val raw = json.encodeToString(Session.serializer(), session)
        context.dataStore.edit { prefs ->
            prefs[sessionKey(account)] = raw
        }
    }

    override suspend fun clear(account: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(sessionKey(account))
        }
    }

    suspend fun loadLastAccount(): String? =
        context.dataStore.data.first()[stringPreferencesKey("piko_last_account")]

    suspend fun saveLastAccount(account: String) {
        context.dataStore.edit { it[stringPreferencesKey("piko_last_account")] = account }
    }

    suspend fun clearLastAccount() {
        context.dataStore.edit { it.remove(stringPreferencesKey("piko_last_account")) }
    }

    suspend fun loadCredentials(account: String): String? =
        context.dataStore.data.first()[stringPreferencesKey("pikpak_password_$account")]

    suspend fun saveCredentials(account: String, password: String) {
        context.dataStore.edit { it[stringPreferencesKey("pikpak_password_$account")] = password }
    }

    suspend fun clearCredentials(account: String) {
        context.dataStore.edit { it.remove(stringPreferencesKey("pikpak_password_$account")) }
    }
}
