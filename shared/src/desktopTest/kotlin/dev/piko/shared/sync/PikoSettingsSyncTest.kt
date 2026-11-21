package dev.piko.shared.sync

import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PinnedFolders
import dev.piko.shared.smoke.MemoryCacheStore
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `eink mode and legacy EINK themeSeed are never pushed or pulled across devices`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(5_000) {
                assertTrue(SyncedSettings.none { it.key.equals("einkMode", ignoreCase = true) })

                val clients = accountProvider()
                val remote = MemoryRemote()
                var time = 1_000L
                val clock = { time++ }

                // Device A enables E-Ink locally and has a legacy EINK seed value.
                val prefsA = MemoryPreferences().apply {
                    setEinkMode(true)
                    setThemeSeed("EINK")
                }
                val syncA = PikoSettingsSync(
                    clients = clients,
                    remote = remote,
                    preferences = prefsA,
                    cacheStore = MemoryCacheStore(),
                    scope = scope,
                    enabled = MutableStateFlow(true),
                    settings = SyncedSettings,
                    now = clock,
                )
                assertTrue(syncA.syncNow())
                val pushedFromA = remote.text!!
                assertTrue("EINK" !in pushedFromA, pushedFromA)
                assertTrue("einkMode" !in pushedFromA, pushedFromA)

                // Simulate an older client having pushed themeSeed=EINK remotely.
                remote.text = """{"version":1,"values":{"themeSeed":{"value":"EINK","updatedAt":999999}}}"""
                val prefsB = MemoryPreferences().apply {
                    setEinkMode(false)
                    setThemeSeed("RURI")
                }
                val syncB = PikoSettingsSync(
                    clients = clients,
                    remote = remote,
                    preferences = prefsB,
                    cacheStore = MemoryCacheStore(),
                    scope = scope,
                    enabled = MutableStateFlow(true),
                    settings = SyncedSettings,
                    now = clock,
                )
                assertTrue(syncB.syncNow())
                assertEquals("RURI", prefsB.themeSeedFlow.value)
                assertEquals(false, prefsB.einkModeFlow.value)
                assertTrue("EINK" !in remote.text!!, remote.text!!)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `switching A to B to A does not clobber Account A global settings with Account B pulled values`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(10_000) {
                val clientA = PikPakClient(account = "a@piko.dev", password = "pw", sessionStore = InMemorySessionStore())
                val clientB = PikPakClient(account = "b@piko.dev", password = "pw", sessionStore = InMemorySessionStore())
                val clients = object : PikoClientProvider {
                    override val currentClient = MutableStateFlow<PikPakClient?>(clientA)
                }
                val docs = mutableMapOf<String, String>(
                    "a@piko.dev" to """{"version":1,"values":{
                        "themeSeed":{"value":"RURI","updatedAt":100},
                        "pureBlack":{"value":"true","updatedAt":100},
                        "pinnedFolders":{"value":"[{\"id\":\"fa\",\"name\":\"FolderA\"}]","updatedAt":100}
                    }}""",
                    "b@piko.dev" to """{"version":1,"values":{
                        "themeSeed":{"value":"SAKURA","updatedAt":50},
                        "pureBlack":{"value":"false","updatedAt":50},
                        "pinnedFolders":{"value":"[{\"id\":\"fb\",\"name\":\"FolderB\"}]","updatedAt":50}
                    }}""",
                )
                val writes = mutableMapOf<String, Int>()
                val remote = object : RemoteSettingsStore {
                    override suspend fun read(account: String): String? = docs[account]
                    override suspend fun write(account: String, text: String, stamp: Long) {
                        docs[account] = text
                        writes[account] = (writes[account] ?: 0) + 1
                    }
                }
                val backing = MemoryPreferences()
                val cache = MemoryCacheStore()
                val prefs = AccountScopedPreferences(backing, clients, cache)
                var time = 200L
                val sync1 = PikoSettingsSync(
                    clients = clients,
                    remote = remote,
                    preferences = prefs,
                    cacheStore = cache,
                    scope = scope,
                    enabled = MutableStateFlow(true),
                    settings = SyncedSettings,
                    now = { time++ },
                )

                // 1. Account A syncs and pulls RURI + pureBlack=true + FolderA.
                assertTrue(sync1.syncNow())
                assertEquals("RURI", backing.themeSeedFlow.value)
                assertEquals(true, backing.pureBlackFlow.value)
                assertTrue("FolderA" in prefs.pinnedFoldersFlow.first())

                // 2. Switch to Account B: pulls SAKURA + pureBlack=false + FolderB into shared device prefs.
                clients.currentClient.value = clientB
                assertTrue(sync1.syncNow())
                assertEquals("SAKURA", backing.themeSeedFlow.value)
                assertEquals(false, backing.pureBlackFlow.value)
                assertTrue("FolderB" in prefs.pinnedFoldersFlow.first())

                // 3. Reopen PikoSettingsSync (simulating restart) and switch back to Account A without user edits:
                //    Account B's SAKURA/false must NOT be stamped as a manual edit overwriting Account A!
                clients.currentClient.value = clientA
                val sync2 = PikoSettingsSync(
                    clients = clients,
                    remote = remote,
                    preferences = prefs,
                    cacheStore = cache,
                    scope = scope,
                    enabled = MutableStateFlow(true),
                    settings = SyncedSettings,
                    now = { time++ },
                )
                val writesABefore = writes["a@piko.dev"] ?: 0
                assertTrue(sync2.syncNow())
                assertEquals("RURI", backing.themeSeedFlow.value, "Account A's themeSeed should be restored")
                assertEquals(true, backing.pureBlackFlow.value, "Account A's pureBlack should be restored")
                assertTrue("FolderA" in prefs.pinnedFoldersFlow.first())
                assertTrue("SAKURA" !in docs["a@piko.dev"]!!, "Account B's themeSeed must never clobber Account A cloud")
                assertEquals(writesABefore, writes["a@piko.dev"] ?: 0)

                // 4. Genuine manual user edit while on Account A still stamps now() and pushes to cloud.
                prefs.setThemeSeed("MATCHA")
                assertTrue(sync2.syncNow())
                assertTrue("MATCHA" in docs["a@piko.dev"]!!)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `midSyncSignOutDoesNotWipeCloudPinnedFoldersAndPinnedFoldersCapturesAccountUpFront`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(10_000) {
                val clientA = PikPakClient(account = "a@piko.dev", password = "pw", sessionStore = InMemorySessionStore())
                val clientB = PikPakClient(account = "b@piko.dev", password = "pw", sessionStore = InMemorySessionStore())
                val clients = object : PikoClientProvider {
                    override val currentClient = MutableStateFlow<PikPakClient?>(clientA)
                }
                var remoteDoc = """{"version":1,"values":{"pinnedFolders":{"value":"[{\"id\":\"cloud-pin\",\"name\":\"CloudPin\"}]","updatedAt":100}}}"""
                var signOutOnRead = true
                val remote = object : RemoteSettingsStore {
                    override suspend fun read(account: String): String {
                        if (signOutOnRead) {
                            clients.currentClient.value = null
                        }
                        return remoteDoc
                    }
                    override suspend fun write(account: String, text: String, stamp: Long) {
                        remoteDoc = text
                    }
                }
                val cache = MemoryCacheStore()
                val prefs = AccountScopedPreferences(MemoryPreferences(), clients, cache)
                var time = 500L
                val sync = PikoSettingsSync(
                    clients = clients,
                    remote = remote,
                    preferences = prefs,
                    cacheStore = cache,
                    scope = scope,
                    enabled = MutableStateFlow(true),
                    settings = SyncedSettings,
                    now = { time++ },
                )

                // Sign-out mid-sync must abort cleanly without saving a poisoned ledger.
                assertFalse(sync.syncNow())

                // Next login to Account A must still pull CloudPin rather than wiping it with "".
                signOutOnRead = false
                clients.currentClient.value = clientA
                assertTrue(sync.syncNow())
                assertTrue("CloudPin" in prefs.pinnedFoldersFlowFor("a@piko.dev").first())
                assertTrue("CloudPin" in remoteDoc)

                // PinnedFolders.edit captures account synchronously before launching async work.
                val pinned = PinnedFolders(prefs, scope)
                pinned.pin(PikoPathBreadcrumb("pin-a2", "PinA2"))
                clients.currentClient.value = clientB
                awaitUntil("Account A pin persisted despite immediate switch to B") {
                    kotlinx.coroutines.runBlocking { "pin-a2" in prefs.pinnedFoldersFlowFor("a@piko.dev").first() }
                }
                assertFalse("pin-a2" in prefs.pinnedFoldersFlowFor("b@piko.dev").first())
            }
        } finally {
            scope.cancel()
        }
    }
}
