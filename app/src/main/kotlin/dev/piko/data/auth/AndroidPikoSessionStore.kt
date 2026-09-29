package dev.piko.data.auth

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 登录态存在 DataStore（应用私有目录，已关闭备份）。会话与密码按账号分键，落盘前经 [CredentialCipher]
 * 用 AndroidKeyStore 里不可导出的密钥加密；账号列表只有账号名与资料，不加密。
 */
class AndroidPikoSessionStore(private val context: Context) : PikoSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sessionKey(account: String) = stringPreferencesKey("pikpak_session_$account")
    private fun passwordKey(account: String) = stringPreferencesKey("pikpak_password_$account")

    override suspend fun load(account: String): Session? {
        val stored = context.dataStore.data.first()[sessionKey(account)] ?: return null
        val raw = if (CredentialCipher.isEncrypted(stored)) {
            CredentialCipher.decrypt(stored) ?: return null
        } else {
            stored
        }
        val session = runCatching { json.decodeFromString(Session.serializer(), raw) }.getOrNull() ?: return null
        // 旧版本存的是明文，读到就地换成密文
        if (!CredentialCipher.isEncrypted(stored)) save(account, session)
        return session
    }

    override suspend fun save(account: String, session: Session) {
        val raw = json.encodeToString(Session.serializer(), session)
        // 密钥库不可用时仍存明文：会话每次刷新都要写，存不下就等于每次启动都要重新登录。
        // 密码不同，见 saveCredentials
        val sealed = runCatching { CredentialCipher.encrypt(raw) }
            .onFailure { PikoLog.w(TAG, "会话加密失败，以明文保存", it) }
            .getOrDefault(raw)
        context.dataStore.edit { it[sessionKey(account)] = sealed }
    }

    override suspend fun clear(account: String) {
        context.dataStore.edit { it.remove(sessionKey(account)) }
    }

    override suspend fun loadAccounts(): SavedAccounts {
        val prefs = context.dataStore.data.first()
        prefs[ACCOUNTS]?.let { text ->
            return runCatching { json.decodeFromString(SavedAccounts.serializer(), text) }.getOrDefault(SavedAccounts())
        }
        // 有多账号之前只记上次的账号，昵称与头像另存一份在偏好里（连同令牌的明文副本）。
        // 换成账号列表，那些键一并删掉
        val legacy = prefs[LEGACY_LAST_ACCOUNT]
        val migrated = if (legacy.isNullOrBlank()) {
            SavedAccounts()
        } else {
            val profile = SavedAccount(
                account = legacy,
                name = prefs[stringPreferencesKey("username")].orEmpty(),
                avatarUrl = prefs[stringPreferencesKey("avatar_url")].orEmpty(),
                email = prefs[stringPreferencesKey("email")].orEmpty(),
                usageBytes = prefs[longPreferencesKey("quota_usage_bytes")] ?: -1,
                limitBytes = prefs[longPreferencesKey("quota_limit_bytes")] ?: -1,
            )
            SavedAccounts(current = legacy, accounts = listOf(profile))
        }
        context.dataStore.edit { edit ->
            edit[ACCOUNTS] = json.encodeToString(SavedAccounts.serializer(), migrated)
            edit.dropLegacyMirror()
        }
        return migrated
    }

    override suspend fun saveAccounts(accounts: SavedAccounts) {
        context.dataStore.edit { it[ACCOUNTS] = json.encodeToString(SavedAccounts.serializer(), accounts) }
    }

    override suspend fun loadCredentials(account: String): PikoCredentials? {
        val stored = context.dataStore.data.first()[passwordKey(account)] ?: return null
        if (CredentialCipher.isEncrypted(stored)) {
            return CredentialCipher.decrypt(stored)?.let { PikoCredentials(account, it) }
        }
        // 旧版本存的是明文。读到就地换成密文，不必等用户下次手动登录
        saveCredentials(account, stored)
        return PikoCredentials(account, stored)
    }

    override suspend fun saveCredentials(account: String, password: String) {
        // 密钥库不可用时宁可不存，也不退回明文；代价只是 refresh token 失效后要手动登录一次
        val sealed = runCatching { CredentialCipher.encrypt(password) }.getOrNull()
        context.dataStore.edit {
            if (sealed != null) it[passwordKey(account)] = sealed else it.remove(passwordKey(account))
        }
    }

    override suspend fun clearCredentials(account: String) {
        context.dataStore.edit { it.remove(passwordKey(account)) }
    }

    private fun MutablePreferences.dropLegacyMirror() {
        remove(LEGACY_LAST_ACCOUNT)
        LEGACY_MIRROR_STRINGS.forEach { remove(stringPreferencesKey(it)) }
        remove(longPreferencesKey("quota_usage_bytes"))
        remove(longPreferencesKey("quota_limit_bytes"))
        remove(intPreferencesKey("concurrent_connections"))
    }

    private companion object {
        const val TAG = "Auth"
        val ACCOUNTS = stringPreferencesKey("piko_accounts")
        val LEGACY_LAST_ACCOUNT = stringPreferencesKey("piko_last_account")
        val LEGACY_MIRROR_STRINGS = listOf("auth_token", "refresh_token", "user_id", "username", "avatar_url", "email")
    }
}
