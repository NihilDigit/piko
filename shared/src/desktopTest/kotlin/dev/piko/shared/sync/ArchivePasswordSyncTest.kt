package dev.piko.shared.sync

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.MemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArchivePasswordSyncTest {
    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }

    private class Device(val vault: ArchivePasswordVault, val sync: ArchivePasswordSync)

    private fun device(remote: RemoteSettingsStore, scope: CoroutineScope, accountPassword: () -> String): Device {
        // 同步只要一个当前账号，SDK 的请求走不到
        val provider = FakePikPakServer().provider()
        val vault = ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences())
        val sync = ArchivePasswordSync(provider, remote, vault, { accountPassword() }, JvmSyncCipher(), scope, flowOf(true))
        return Device(vault, sync)
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

            // 桌面上还留着 old-secret 的有效记录，比笔记本上的删除旧
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
            // 用旧密码加密的那份解不开，丢掉，以桌面的记录重写；之后用新密码的设备都读得出来
            val phone = device(remote, scope) { "after" }
            phone.sync.syncNow()
            assertEquals(listOf("only-on-desktop"), phone.vault.passwords.first())
        }
        scope.cancel()
    }
}
