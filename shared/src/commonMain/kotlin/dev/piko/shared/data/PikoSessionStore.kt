package dev.piko.shared.data

import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.SessionStore
import kotlinx.serialization.Serializable

/**
 * 登录态的持久化，两端各有实现。会话（SDK 的 [SessionStore]）、密码与压缩包密码是机密，按账号分开存、落盘前加密；
 * 账号列表（[SavedAccounts]）只有账号名与资料，不含机密。
 */
interface PikoSessionStore : SessionStore, ArchivePasswordStore {
    suspend fun loadAccounts(): SavedAccounts
    suspend fun saveAccounts(accounts: SavedAccounts)
    suspend fun loadCredentials(account: String): PikoCredentials?
    suspend fun saveCredentials(account: String, password: String)
    suspend fun clearCredentials(account: String)
}

/**
 * 一个账号存过的压缩包密码，整份 JSON（格式见 ArchivePasswordVault），平台只原样存取。与会话、密码同属这个账号的机密，
 * 同样加密存放；退出登录不清它，再登回来仍在。空串表示没有。存储暂时不可用时抛出，不返回空串：
 * 调用方会把空表当成真实内容写回，覆盖掉存着的那份。
 */
interface ArchivePasswordStore {
    suspend fun loadArchivePasswords(account: String): String
    suspend fun saveArchivePasswords(account: String, serialized: String)
}

data class PikoCredentials(val account: String, val password: String)

/**
 * 本机登录过、没有退出的账号，与眼下用的那个。[current] 为 null 是停在登录页。
 * 顺序即切换菜单里的顺序，按首次登录的先后，切换不改它。
 */
@Serializable
data class SavedAccounts(
    val current: String? = null,
    val accounts: List<SavedAccount> = emptyList(),
) {
    fun find(account: String): SavedAccount? = accounts.firstOrNull { it.account == account }

    /** 加进 [account] 或更新它，保持原来的位置。 */
    fun upsert(account: SavedAccount): SavedAccounts {
        val at = accounts.indexOfFirst { it.account == account.account }
        return copy(accounts = if (at < 0) accounts + account else accounts.toMutableList().apply { set(at, account) })
    }

    fun without(account: String): SavedAccounts =
        copy(current = current.takeUnless { it == account }, accounts = accounts.filterNot { it.account == account })
}

/**
 * 一个已保存的账号。昵称、头像与邮箱登录时不带回，另取一次后记在这里，切换菜单与账号卡片不必等网络。
 * 空间用量同理，只作取到之前的占位。[usedAt] 是最近一次切到它的时刻，退出当前账号时切到最近用过的那个。
 */
@Serializable
data class SavedAccount(
    val account: String,
    val name: String = "",
    val avatarUrl: String = "",
    /** 服务端返回时已打码（a***@example.com），API 取不到完整地址。 */
    val email: String = "",
    val usageBytes: Long = -1,
    val limitBytes: Long = -1,
    val usedAt: Long = 0,
) {
    /** 界面上的名字：昵称，没有时是账号本身。 */
    val displayName: String get() = name.ifBlank { account }
}
