package dev.piko.shared.sync

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.MemorySessionStore
import dev.piko.shared.smoke.awaitUntil
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchivePasswordSyncTest {
    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        var lastStamp: Long = 0L
        val writeStamps = mutableListOf<Long>()
        val backups = mutableListOf<String>()
        override suspend fun read(account: String) = text
        override suspend fun latestStamp(account: String) = lastStamp
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
            this.lastStamp = maxOf(this.lastStamp, stamp)
            writeStamps += stamp
        }
        override suspend fun writeBackup(account: String, text: String, stamp: Long) {
            backups += text
        }
    }

    private class PerAccountRemote : RemoteSettingsStore {
        val files = mutableMapOf<String, String>()
        override suspend fun read(account: String) = files[account]
        override suspend fun write(account: String, text: String, stamp: Long) {
            files[account] = text
        }
    }

    private class Accounts(val a: PikPakClient, val b: PikPakClient) : PikoClientProvider {
        override val currentClient = MutableStateFlow<PikPakClient?>(a)
    }

    private class Device(val vault: ArchivePasswordVault, val sync: ArchivePasswordSync)

    private fun device(remote: RemoteSettingsStore, scope: CoroutineScope, accountPassword: () -> String): Device {
        val provider = FakePikPakServer().provider()
        val vault = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
        val sync = ArchivePasswordSync(provider, remote, vault, { accountPassword() }, JvmSyncCipher(), scope, flowOf(true))
        return Device(vault, sync)
    }

    private fun accounts(): Accounts {
        val server = FakePikPakServer()
        return Accounts(server.client(account = "a@piko.dev"), server.client(account = "b@piko.dev"))
    }

    @Test
    fun `a password forgotten on one device stays gone on the other`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        withTimeout(30_000) {
            val laptop = device(remote, scope) { "account-pw" }
            val desktop = device(remote, scope) { "account-pw" }
            laptop.vault.remember("old-secret")
            laptop.vault.remember("keep-me")
            desktop.vault.remember("from-desktop")
            laptop.sync.syncNow()
            desktop.sync.syncNow()
            assertEquals(setOf("old-secret", "keep-me", "from-desktop"), desktop.vault.passwords.first().toSet())

            laptop.vault.forget("old-secret")
            laptop.sync.syncNow()
            desktop.sync.syncNow()
            laptop.sync.syncNow()
            assertEquals(setOf("keep-me", "from-desktop"), desktop.vault.passwords.first().toSet())
            assertEquals(setOf("keep-me", "from-desktop"), laptop.vault.passwords.first().toSet())
            assertFalse("keep-me" in remote.text!!, "网盘上的文件里不能有明文")
        }
        scope.cancel()
    }

    @Test
    fun `key mismatch preserves the drive copy until explicit reset with backup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        withTimeout(30_000) {
            val laptop = device(remote, scope) { "before" }
            laptop.vault.remember("only-on-laptop")
            laptop.sync.syncNow()
            val originalRemote = remote.text!!

            val desktop = device(remote, scope) { "after" }
            desktop.vault.remember("only-on-desktop")
            desktop.sync.syncNow()
            // 密钥不匹配时严禁自动覆盖远端密文
            assertEquals(EncryptedSyncStatus.LockedKeyMismatch, desktop.sync.status.value)
            assertEquals(originalRemote, remote.text)
            assertTrue(remote.backups.isEmpty())

            // 用户显式触发重置时，先写入备份再以本机数据重新加密
            assertTrue(desktop.sync.resetRemoteWithCurrentKey())
            assertEquals(listOf(originalRemote), remote.backups)
            assertEquals(EncryptedSyncStatus.Unlocked, desktop.sync.status.value)

            val phone = device(remote, scope) { "after" }
            phone.sync.syncNow()
            assertEquals(listOf("only-on-desktop"), phone.vault.passwords.first())
        }
        scope.cancel()
    }

    @Test
    fun `google passwordless account locks until sync passphrase is set and writes v2 envelope`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        try {
            withTimeout(30_000) {
                var passphrase: String? = null
                val provider = FakePikPakServer().provider()
                val vaultA = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
                val syncA = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultA,
                    keyProvider = SyncKeyProvider {
                        passphrase?.let { dev.piko.shared.data.EncryptedSyncKey(it, isPassphrase = true) }
                    },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                )
                vaultA.remember("google-zip-pw")
                syncA.syncNow()
                assertEquals(EncryptedSyncStatus.LockedNoKey, syncA.status.value)
                assertEquals(null, remote.text)

                passphrase = "shared-passphrase-2026"
                syncA.syncNow()
                assertEquals(EncryptedSyncStatus.Unlocked, syncA.status.value)
                assertTrue("\"version\":2" in remote.text!!, "独立同步口令须写 v2 信封以阻止旧版本客户端用账号密码覆盖")

                val vaultB = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
                val syncB = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultB,
                    keyProvider = SyncKeyProvider { dev.piko.shared.data.EncryptedSyncKey("shared-passphrase-2026", isPassphrase = true) },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                )
                syncB.syncNow()
                assertEquals(listOf("google-zip-pw"), vaultB.passwords.first())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a password entered under one account stays with it through switches and sync`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(60_000) {
                val accounts = accounts()
                val remote = PerAccountRemote()
                val store = MemorySessionStore()
                val vault = ArchivePasswordVault(accounts, store, MemoryPreferences())
                val sync = ArchivePasswordSync(accounts, remote, vault, { "login-$it" }, JvmSyncCipher(), scope, flowOf(true))

                vault.remember("from-a")
                assertTrue(sync.syncNow())

                accounts.currentClient.value = accounts.b
                assertEquals(emptyList(), vault.passwords.first())
                vault.remember("from-b")
                assertTrue(sync.syncNow())
                assertFalse("from-a" in store.archivePasswords.getValue("b@piko.dev"))

                val otherDevice = ArchivePasswordVault(accounts, MemorySessionStore(), MemoryPreferences())
                ArchivePasswordSync(accounts, remote, otherDevice, { "login-$it" }, JvmSyncCipher(), scope, flowOf(true)).syncNow()
                assertEquals(listOf("from-b"), otherDevice.passwords.first())

                accounts.currentClient.value = accounts.a
                assertEquals(listOf("from-a"), vault.passwords.first())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the device-wide list from 1_1_0 goes to the first account to run, once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(60_000) {
                val accounts = accounts()
                val store = MemorySessionStore()
                val prefs = MemoryPreferences().apply { legacyArchivePasswords = """["newer","older"]""" }
                ArchivePasswordVault(accounts, store, prefs).start(scope)
                awaitUntil("并入头一个账号") { prefs.legacyArchivePasswords.isEmpty() }

                val restarted = ArchivePasswordVault(accounts, store, prefs)
                assertEquals(listOf("newer", "older"), restarted.passwords.first())
                accounts.currentClient.value = accounts.b
                assertEquals(emptyList(), restarted.passwords.first())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `v2 envelope is never downgraded to v1 and applyPassphrase rotates unlocked v1 losslessly`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        try {
            withTimeout(60_000) {
                val provider = FakePikPakServer().provider()
                val account = provider.currentClient.value!!.account
                var activeKey = dev.piko.shared.data.EncryptedSyncKey("old-account-pw", isPassphrase = false)
                val vaultA = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
                val syncA = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultA,
                    keyProvider = SyncKeyProvider { activeKey },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                    now = { 10_000L },
                )
                vaultA.remember("zip-from-v1")
                assertTrue(syncA.syncNow())
                assertTrue("\"version\":1" in remote.text!!)

                // 已解锁状态下设置新独立同步口令：用旧密码解密合并并无损重加密为 v2
                val prevKey = activeKey
                activeKey = dev.piko.shared.data.EncryptedSyncKey("rotated-passphrase", isPassphrase = true)
                assertEquals(
                    EncryptedSyncApplyOutcome.UNLOCKED,
                    syncA.checkPassphrase(account, "rotated-passphrase", prevKey),
                )
                assertEquals(
                    EncryptedSyncApplyOutcome.UNLOCKED,
                    syncA.applyPassphrase(account, "rotated-passphrase", prevKey),
                )
                assertTrue("\"version\":2" in remote.text!!)

                // 另一台仅凭相同字符串作为账号密码（isPassphrase=false）登录的设备写入新条目时，绝不能将 v2 降级回 v1
                val vaultB = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
                val syncB = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultB,
                    keyProvider = SyncKeyProvider { dev.piko.shared.data.EncryptedSyncKey("rotated-passphrase", isPassphrase = false) },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                    now = { 20_000L },
                )
                vaultB.remember("zip-added-on-b")
                assertTrue(syncB.syncNow())
                assertEquals(setOf("zip-from-v1", "zip-added-on-b"), vaultB.passwords.first().toSet())
                assertTrue("\"version\":2" in remote.text!!, "v2 信封绝不能被回退为 v1")

                // 无旧密钥的新设备输入错误口令：预检返回 KEY_MISMATCH 且不改动远端
                val beforeWrong = remote.text!!
                val vaultC = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
                val syncC = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultC,
                    keyProvider = SyncKeyProvider { null },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                )
                assertEquals(
                    EncryptedSyncApplyOutcome.KEY_MISMATCH,
                    syncC.checkPassphrase(account, "wrong-passphrase", previousKey = null),
                )
                assertEquals(beforeWrong, remote.text)

                // 时钟偏慢时显式重置仍以 latestStamp + 1 胜出，账号不匹配时拒绝重置
                remote.lastStamp = 999_999L
                val syncReset = ArchivePasswordSync(
                    clients = provider,
                    remote = remote,
                    vault = vaultA,
                    keyProvider = SyncKeyProvider { dev.piko.shared.data.EncryptedSyncKey("fresh-key", isPassphrase = true) },
                    cipher = JvmSyncCipher(),
                    scope = scope,
                    enabled = flowOf(true),
                    now = { 5_000L },
                )
                assertFalse(syncReset.resetRemoteWithCurrentKey("other@piko.dev"))
                assertTrue(syncReset.resetRemoteWithCurrentKey(account))
                assertEquals(1_000_000L, remote.writeStamps.last())
            }
        } finally {
            scope.cancel()
        }
    }
}
