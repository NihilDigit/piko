package dev.piko.shared.auth

import dev.piko.shared.PikoHome
import dev.piko.shared.data.PikoCredentials
import dev.piko.shared.data.PikoSessionStore
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.Session
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 桌面端与 CLI 共用的登录态存储，都在 [root]（默认 ~/.piko）下：
 * - `accounts.json`：账号列表与当前账号，不含机密，明文。
 * - 每个账号的会话、密码与压缩包密码合成一份 JSON，交给系统保管处（Windows 的 DPAPI、macOS 的钥匙串）；
 *   钥匙串写不进时落在 `accounts/<key>.plain`，见 [layeredSecretVault]。
 *
 * 读过的机密缓存在内存里：SDK 每次刷新令牌都要读改写一遍，钥匙串中途锁上也不影响已登录的账号。
 * 同一进程内的读改写经一把锁串行；CLI 与桌面端同时开着时的竞争不处理。
 *
 * 系统保管处暂时用不了且兜底里也没有时，读取抛 [VaultUnavailableException]，而不是返回 null：
 * 返回 null 等于告诉调用方已退出登录。
 */
class DesktopSessionStore(
    private val root: Path = defaultPikoRoot(),
    primaryVault: (secretsDirectory: Path) -> SecretVault? = ::platformSecretVault,
) : PikoSessionStore {
    // 推迟到第一次存取、在 IO 线程上建：Linux 上要先经 D-Bus 试探 Secret Service 在不在，不该压在启动的主线程上
    private val vault by lazy {
        root.resolve("accounts").let { directory -> layeredSecretVault(directory, primaryVault(directory)) }
    }
    private val accountsFile = root.resolve("accounts.json")
    private val mutex = Mutex()
    private val cache = HashMap<String, AccountSecrets>()
    private var migrated = false

    /** 旧格式迁移没做完时由旧文件得出的账号列表，只在本进程内顶替 accounts.json，下次启动重做迁移。 */
    private var legacyAccounts: SavedAccounts? = null

    override suspend fun load(account: String): Session? = secrets(account).session

    override suspend fun save(account: String, session: Session) = update(account) { it.copy(session = session) }

    override suspend fun clear(account: String) = update(account) { it.copy(session = null) }

    override suspend fun loadCredentials(account: String): PikoCredentials? =
        secrets(account).password?.let { PikoCredentials(account, it) }

    override suspend fun saveCredentials(account: String, password: String) = update(account) { it.copy(password = password) }

    override suspend fun clearCredentials(account: String) = update(account) { it.copy(password = null) }

    override suspend fun loadArchivePasswords(account: String): String = secrets(account).archivePasswords.orEmpty()

    override suspend fun saveArchivePasswords(account: String, serialized: String) =
        update(account) { it.copy(archivePasswords = serialized.ifEmpty { null }) }

    override suspend fun loadAccounts(): SavedAccounts = locked { readAccountsFile() ?: legacyAccounts ?: SavedAccounts() }

    override suspend fun saveAccounts(accounts: SavedAccounts) = locked {
        writeAccountsFile(accounts)
        legacyAccounts = null
    }

    /**
     * 机密眼下是否由系统加密存放。[account] 为 null 时问的是本机有没有系统保管处；
     * 给了账号则看它的那份：系统保管处写入失败时它落在明文兜底里，此时为假。
     */
    suspend fun encryptedAtRest(account: String? = null): Boolean = locked {
        when {
            !vault.hasPrimary -> false
            account == null -> true
            else -> !vault.inFallback(vaultKey(account))
        }
    }

    private suspend fun secrets(account: String): AccountSecrets = locked {
        val key = vaultKey(account)
        cache[key] ?: readSecrets(key).also { cache[key] = it }
    }

    private suspend fun update(account: String, change: (AccountSecrets) -> AccountSecrets) = locked {
        val key = vaultKey(account)
        val current = cache[key] ?: readSecrets(key)
        val next = change(current)
        if (next != current) writeSecrets(key, next)
        cache[key] = next
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!migrated) {
                // 失败也不在本进程内重试：旧文件原样留着，下次启动再来
                runCatching { migrateLegacy() }.onFailure { PikoLog.w(TAG, "旧格式的登录信息迁移失败，保留旧文件", it) }
                migrated = true
            }
            block()
        }
    }

    private fun readSecrets(key: String): AccountSecrets {
        val data = vault.read(key) ?: return AccountSecrets()
        return runCatching { json.decodeFromString(AccountSecrets.serializer(), data.decodeToString()) }
            .onFailure { PikoLog.w(TAG, "凭据内容无法解析，当作没有", it) }
            .getOrDefault(AccountSecrets())
    }

    private fun writeSecrets(key: String, secrets: AccountSecrets) {
        if (secrets.isEmpty) vault.delete(key)
        else vault.write(key, json.encodeToString(AccountSecrets.serializer(), secrets).encodeToByteArray())
    }

    private fun readAccountsFile(): SavedAccounts? {
        if (!Files.isRegularFile(accountsFile)) return null
        // 解析不了当作不存在：旧文件还在的话迁移会重写它，比退回登录页好
        return runCatching { json.decodeFromString(SavedAccounts.serializer(), Files.readString(accountsFile)) }
            .onFailure { PikoLog.w(TAG, "账号列表无法解析", it) }
            .getOrNull()
    }

    private fun writeAccountsFile(accounts: SavedAccounts) {
        Files.createDirectories(root)
        val staging = Files.createTempFile(root, "accounts", ".tmp")
        try {
            Files.writeString(staging, json.encodeToString(SavedAccounts.serializer(), accounts))
            Files.move(staging, accountsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    /**
     * v0.10.0 起的旧格式：一个账号，会话、账号名与密码明文分存三个文件。
     * 读出后先放进内存，后面哪一步失败这次启动都照样能登录；写进新存储并读回核对一致，
     * 再写账号列表，最后才删旧文件。
     */
    private fun migrateLegacy() {
        val accountFile = root.resolve("pikpak-account.txt")
        val sessionFile = root.resolve("pikpak-session.json")
        val passwordFile = root.resolve("pikpak-password.txt")
        val legacyFiles = listOf(accountFile, sessionFile, passwordFile, root.resolve("pikpak-session.json.tmp"))
        fun deleteLegacyFiles() = legacyFiles.forEach(Files::deleteIfExists)

        val account = accountFile.readTextOrNull()?.trim()?.ifEmpty { null }
        if (account == null) {
            // 没有账号名的会话与密码，旧版本自己也不会用（load 要求账号相符），留着只是明文躺在磁盘上
            if (legacyFiles.any(Files::exists)) deleteLegacyFiles()
            return
        }
        val listed = readAccountsFile()
        if (listed != null && listed.find(account) == null) {
            // 上次迁移已写了账号列表，之后这个账号又退出了：旧文件里的是已作废的登录态
            deleteLegacyFiles()
            return
        }

        val key = vaultKey(account)
        val legacy = AccountSecrets(
            session = sessionFile.readTextOrNull()?.let { text ->
                runCatching { json.decodeFromString(Session.serializer(), text) }
                    .onFailure { PikoLog.w(TAG, "旧会话文件无法解析，只迁移密码") }
                    .getOrNull()
            },
            password = passwordFile.readTextOrNull(),
        )
        val accounts = listed ?: if (legacy.isEmpty) SavedAccounts() else SavedAccounts(current = account, accounts = listOf(SavedAccount(account)))
        cache[key] = legacy
        if (listed == null) legacyAccounts = accounts

        val stored = readSecrets(key)
        if (stored.isEmpty) {
            writeSecrets(key, legacy)
            check(readSecrets(key) == legacy) { "迁移后读回的凭据与旧文件不一致" }
        } else {
            // 新存储里已有：上次迁移写进去了但没删成旧文件，之后可能又刷新过令牌，以新存储为准
            cache[key] = stored
        }
        if (listed == null) {
            writeAccountsFile(accounts)
            legacyAccounts = null
        }
        deleteLegacyFiles()
        PikoLog.i(TAG, "旧格式的登录信息已迁移")
    }

    private companion object {
        const val TAG = "credentials"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** 退出登录只清 [session] 与 [password]，[archivePasswords] 留着，这一份随之不为空、不删。 */
@Serializable
internal data class AccountSecrets(
    val session: Session? = null,
    val password: String? = null,
    val archivePasswords: String? = null,
) {
    val isEmpty: Boolean get() = session == null && password == null && archivePasswords == null
}

/** 账号名可能是邮箱或手机号，不直接进文件名与钥匙串属性，取其摘要。 */
internal fun vaultKey(account: String): String =
    MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

fun defaultPikoRoot(): Path = PikoHome.root

/** 本平台的系统保管处。Windows 上这是经 PowerShell 的 DPAPI，桌面端换成 FFM 直调的那一份。 */
fun platformSecretVault(directory: Path): SecretVault? {
    val os = System.getProperty("os.name")
    return when {
        os.startsWith("Mac") -> MacKeychainVault(service = "dev.piko.desktop" + PikoHome.secretNamespace)
        os.startsWith("Windows") -> PowerShellDpapiVault(directory)
        else -> null
    }
}

private fun Path.readTextOrNull(): String? = if (Files.isRegularFile(this)) Files.readString(this) else null
