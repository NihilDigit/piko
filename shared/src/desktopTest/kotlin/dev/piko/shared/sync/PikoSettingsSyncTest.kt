package dev.piko.shared.sync

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.smoke.MemoryCacheStore
import dev.piko.shared.smoke.MemoryPreferences
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PikoSettingsSyncTest {
    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }

    private fun accountProvider(): PikoClientProvider = object : PikoClientProvider {
        override val currentClient = MutableStateFlow<PikPakClient?>(
            PikPakClient(account = "sync@piko.dev", password = "pw", sessionStore = InMemorySessionStore()),
        )
    }

    private class Device(
        remote: RemoteSettingsStore,
        clients: PikoClientProvider,
        scope: CoroutineScope,
        clock: () -> Long,
    ) {
        val theme = MutableStateFlow("LIGHT")
        val blur = MutableStateFlow("true")
        val sync = PikoSettingsSync(
            clients = clients,
            remote = remote,
            preferences = MemoryPreferences(),
            cacheStore = MemoryCacheStore(),
            scope = scope,
            enabled = MutableStateFlow(true),
            settings = listOf(
                SyncedSetting("theme", { theme }, { _, value -> theme.value = value }),
                SyncedSetting("blur", { blur }, { _, value -> blur.value = value }),
            ),
            now = clock,
        )
    }

    @Test
    fun `settings merge per key and a fresh device takes the synced values`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(5_000) {
                val clients = accountProvider()
                val remote = MemoryRemote()
                var time = 1_000L
                val clock = { time++ }
                val a = Device(remote, clients, scope, clock)

                a.theme.value = "DARK"
                assertTrue(a.sync.syncNow())

                val b = Device(remote, clients, scope, clock)
                assertTrue(b.sync.syncNow())
                assertEquals("DARK", b.theme.value)

                a.blur.value = "false"
                b.theme.value = "LIGHT"
                assertTrue(a.sync.syncNow())
                assertTrue(b.sync.syncNow())
                assertTrue(a.sync.syncNow())
                assertEquals("LIGHT" to "false", a.theme.value to a.blur.value)
                assertEquals("LIGHT" to "false", b.theme.value to b.blur.value)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `archive passwords synced by older versions are stripped from the remote while unknown keys survive`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(5_000) {
                val clients = accountProvider()
                val remote = MemoryRemote()
                remote.text = """{"version":1,"values":{
                    "theme":{"value":"DARK","updatedAt":5},
                    "spoilerBlur":{"value":"true","updatedAt":5},
                    "archivePasswords":{"value":"[\"hunter2\"]","updatedAt":5},
                    "futureSetting":{"value":"x","updatedAt":5}}}"""
                var time = 1_000L
                val device = Device(remote, clients, scope) { time++ }

                assertTrue(device.sync.syncNow())
                val pushed = remote.text!!
                assertTrue("archivePasswords" !in pushed && "hunter2" !in pushed && "spoilerBlur" !in pushed, pushed)
                assertTrue("futureSetting" in pushed, pushed)
                assertEquals("DARK", device.theme.value)
            }
        } finally {
            scope.cancel()
        }
    }
}
