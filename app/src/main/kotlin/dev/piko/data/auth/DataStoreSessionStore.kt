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

    private fun passwordKey(account: String) = stringPreferencesKey("pikpak_password_$account")

    suspend fun loadCredentials(account: String): String? {
        val stored = context.dataStore.data.first()[passwordKey(account)] ?: return null
        if (CredentialCipher.isEncrypted(stored)) return CredentialCipher.decrypt(stored)
        // 旧版本存的是明文。读到就地换成密文，不必等用户下次手动登录
        saveCredentials(account, stored)
        return stored
    }

    suspend fun saveCredentials(account: String, password: String) {
        // 密钥库不可用时宁可不存，也不退回明文；代价只是 refresh token 失效后要手动登录一次
        val sealed = runCatching { CredentialCipher.encrypt(password) }.getOrNull()
        context.dataStore.edit {
            if (sealed != null) it[passwordKey(account)] = sealed else it.remove(passwordKey(account))
        }
    }

    suspend fun clearCredentials(account: String) {
        context.dataStore.edit { it.remove(passwordKey(account)) }
    }
}
