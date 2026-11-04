package dev.piko.shared.auth

import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccounts
import dev.piko.shared.media.cache.fileCacheName
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Session persistence for platforms with a [SecretVault], including iOS Keychain.
 *
 * Metadata and account secrets use separate, versioned keys. Account names are SHA-256 hashed in
 * keys and checked again inside the protected payload. A single instance must be shared by all
 * clients in one app process: its mutex serializes read/modify/write operations. No plaintext
 * files or in-memory optimistic writes are used. Corruption and unavailable storage propagate
 * without replacing the stored record with an empty account.
 */
class VaultSessionStore(private val vault: SecretVault) : PikoSessionStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun load(account: String): Session? = locked { readSecrets(account).session }
    override suspend fun save(account: String, session: Session) = update(account) { it.copy(session = session) }
    override suspend fun clear(account: String) = update(account) { it.copy(session = null) }

    override suspend fun loadCredentials(account: String): PikoCredentials? = locked {
        readSecrets(account).password?.let { PikoCredentials(account, it) }
    }
    override suspend fun saveCredentials(account: String, password: String) = update(account) { it.copy(password = password) }
    override suspend fun clearCredentials(account: String) = update(account) { it.copy(password = null) }
    override suspend fun loadArchivePasswords(account: String): String = locked { readSecrets(account).archivePasswords.orEmpty() }
    override suspend fun saveArchivePasswords(account: String, serialized: String) =
        update(account) { it.copy(archivePasswords = serialized.ifEmpty { null }) }

    override suspend fun loadAccounts(): SavedAccounts = locked {
        val bytes = vault.read(ACCOUNTS_KEY) ?: return@locked SavedAccounts()
        val record = decode { json.decodeFromString<AccountsRecord>(bytes.decodeToString(throwOnInvalidSequence = true)) }
        if (record.version != VERSION) corrupt()
        record.accounts
    }

    override suspend fun saveAccounts(accounts: SavedAccounts) = locked {
        vault.write(ACCOUNTS_KEY, json.encodeToString(AccountsRecord.serializer(), AccountsRecord(accounts = accounts)).encodeToByteArray())
    }

    private suspend fun update(account: String, change: (SecretsRecord) -> SecretsRecord) = locked {
        val current = readSecrets(account)
        val next = change(current)
        if (next != current) {
            if (next.isEmpty) vault.delete(key(account))
            else vault.write(key(account), json.encodeToString(SecretsRecord.serializer(), next).encodeToByteArray())
        }
    }

    private fun readSecrets(account: String): SecretsRecord {
        val bytes = vault.read(key(account)) ?: return SecretsRecord(account = account)
        val record = decode { json.decodeFromString<SecretsRecord>(bytes.decodeToString(throwOnInvalidSequence = true)) }
        if (record.version != VERSION || record.account != account) corrupt()
        return record
    }

    // Decoder diagnostics may contain input JSON, including tokens. Keep them out of exceptions.
    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        corrupt()
    }
    private fun corrupt(): Nothing = throw VaultUnavailableException("Stored credentials are corrupt or use an unsupported format")
    private fun key(account: String): String = "piko.session.v1." + fileCacheName(account)
    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    @Serializable
    private data class AccountsRecord(val version: Int = VERSION, val accounts: SavedAccounts)

    @Serializable
    private data class SecretsRecord(
        val version: Int = VERSION,
        val account: String,
        val session: Session? = null,
        val password: String? = null,
        val archivePasswords: String? = null,
    ) {
        val isEmpty: Boolean get() = session == null && password == null && archivePasswords == null
    }

    private companion object {
        const val VERSION = 1
        const val ACCOUNTS_KEY = "piko.accounts.v1"
    }
}
