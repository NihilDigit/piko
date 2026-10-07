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

/** 压缩包密码按账号存：一个账号输的密码，换到另一个账号既试不到、也同步不进它的网盘。 */
class ArchivePasswordAccountsTest {
    private class Accounts(val a: PikPakClient, val b: PikPakClient) : PikoClientProvider {
        override val currentClient = MutableStateFlow<PikPakClient?>(a)
    }

    private class PerAccountRemote : RemoteSettingsStore {
        val files = mutableMapOf<String, String>()
        override suspend fun read(account: String) = files[account]
        override suspend fun write(account: String, text: String, stamp: Long) {
            files[account] = text
        }
    }

    private fun accounts(): Accounts {
        val server = FakePikPakServer()
        return Accounts(server.client(account = "a@piko.dev"), server.client(account = "b@piko.dev"))
    }

    private fun withScope(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(60_000) { block(scope) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a password entered under one account stays with it through switches and sync`() = withScope { scope ->
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

        // B 的另一台设备只拉得到 B 的；网盘上的文件是密文，只能这样看里面有什么
        val otherDevice = ArchivePasswordVault(accounts, MemorySessionStore(), MemoryPreferences())
        ArchivePasswordSync(accounts, remote, otherDevice, { "login-$it" }, JvmSyncCipher(), scope, flowOf(true)).syncNow()
        assertEquals(listOf("from-b"), otherDevice.passwords.first())

        accounts.currentClient.value = accounts.a
        assertEquals(listOf("from-a"), vault.passwords.first())
    }

    @Test
    fun `the device-wide list from 1_1_0 goes to the first account to run, once`() = withScope { scope ->
        val accounts = accounts()
        val store = MemorySessionStore()
        val prefs = MemoryPreferences().apply { legacyArchivePasswords = """["newer","older"]""" }
        ArchivePasswordVault(accounts, store, prefs).start(scope)
        awaitUntil("并入头一个账号") { prefs.legacyArchivePasswords.isEmpty() }

        // 重启后从存储里读回，顺序仍是 1.1.0 里的先后
        val restarted = ArchivePasswordVault(accounts, store, prefs)
        assertEquals(listOf("newer", "older"), restarted.passwords.first())
        accounts.currentClient.value = accounts.b
        assertEquals(emptyList(), restarted.passwords.first())
    }
}
