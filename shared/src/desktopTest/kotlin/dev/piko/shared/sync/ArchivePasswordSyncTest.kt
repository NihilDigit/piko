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
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
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
    fun `after the account password changes the drive copy is rewritten from this device`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        withTimeout(30_000) {
            val laptop = device(remote, scope) { "before" }
            laptop.vault.remember("only-on-laptop")
            laptop.sync.syncNow()

            val desktop = device(remote, scope) { "after" }
            desktop.vault.remember("only-on-desktop")
            desktop.sync.syncNow()
            val phone = device(remote, scope) { "after" }
            phone.sync.syncNow()
            assertEquals(listOf("only-on-desktop"), phone.vault.passwords.first())
        }
        scope.cancel()
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
}
