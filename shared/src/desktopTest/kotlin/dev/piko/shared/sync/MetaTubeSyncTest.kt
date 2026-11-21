package dev.piko.shared.sync

import dev.piko.shared.auth.SecretVault
import dev.piko.shared.data.AccountScopedPreferences
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.preferences.PreferenceSettings
import dev.piko.shared.preferences.StoredPikoPreferences
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaTubeSyncTest {
    private class MemoryRemote : RemoteSettingsStore {
        private val byAccount = mutableMapOf<String, String>()
        private val stampsByAccount = mutableMapOf<String, Long>()
        var writes = 0
        val writesByAccount = mutableMapOf<String, Int>()
        val writeStamps = mutableListOf<Long>()
        val backups = mutableListOf<String>()

        var text: String?
            get() = byAccount.values.lastOrNull()
            set(value) {
                if (value == null) byAccount.clear() else byAccount[DEFAULT_BUCKET] = value
            }

        var lastStamp: Long
            get() = stampsByAccount.values.maxOrNull() ?: 0L
            set(value) {
                stampsByAccount[DEFAULT_BUCKET] = value
            }

        fun textFor(account: String): String? = byAccount[account] ?: byAccount[DEFAULT_BUCKET]

        override suspend fun read(account: String): String? = byAccount[account] ?: byAccount[DEFAULT_BUCKET]
        override suspend fun latestStamp(account: String): Long =
            maxOf(stampsByAccount[account] ?: 0L, stampsByAccount[DEFAULT_BUCKET] ?: 0L)
        override suspend fun write(account: String, text: String, stamp: Long) {
            byAccount.remove(DEFAULT_BUCKET)
            byAccount[account] = text
            stampsByAccount[account] = maxOf(stampsByAccount[account] ?: 0L, stamp)
            writeStamps += stamp
            writes++
            writesByAccount[account] = (writesByAccount[account] ?: 0) + 1
        }
        override suspend fun writeBackup(account: String, text: String, stamp: Long) {
            backups += text
        }

        private companion object {
            const val DEFAULT_BUCKET = "__default__"
        }
    }

    private class Cache : PikoCacheStore {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
    }

    private class CountingCipher(private val delegate: SyncCipher = JvmSyncCipher()) : SyncCipher by delegate {
        var derives = 0
        override fun deriveKey(secret: String, salt: ByteArray, iterations: Int): ByteArray {
            derives++
            return delegate.deriveKey(secret, salt, iterations)
        }
    }

    private class Device(remote: MemoryRemote, scope: CoroutineScope, password: () -> String = { "account-pw" }) {
        val provider = FakePikPakServer().provider()
        val backingPrefs = MemoryPreferences()
        val cache = Cache()
        val prefs = AccountScopedPreferences(backingPrefs, provider, cache)
        val cipher = CountingCipher()
        var stamp = 1_000L
        val sync = MetaTubeSync(provider, remote, prefs, cache, { password() }, cipher, scope,
            flowOf(true), now = { stamp })
    }

    private fun scenario(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { withTimeout(30_000) { block(scope) } } finally { scope.cancel() }
    }

    @Test
    fun emptyConfigurationDoesNotDeriveKeyOrWriteAnything() = scenario { scope ->
        val remote = MemoryRemote()
        val device = Device(remote, scope)
        assertTrue(device.sync.syncNow())
        assertNull(remote.text)
        assertEquals(0, remote.writes)
        assertEquals(0, device.cipher.derives)
        assertTrue(device.cache.values.isEmpty())
    }

    @Test
    fun pushAndPullKeepBothRemoteAndLocalLedgerFreeOfPlaintext() = scenario { scope ->
        val remote = MemoryRemote()
        val a = Device(remote, scope)
        val url = "https://private-scraper.example:8443/secret-path"
        val token = "private-token-long-enough-to-find"
        a.prefs.setMetaTubeUrl(url)
        a.prefs.setMetaTubeToken(token)
        assertTrue(a.sync.syncNow())
        val b = Device(remote, scope)
        // 从未同步过的本机非空旧值也不能覆盖远端配置。
        b.prefs.setMetaTubeUrl("https://stale.example")
        assertTrue(b.sync.syncNow())
        assertEquals(url, b.prefs.metaTubeUrlFlow.first())
        assertEquals(token, b.prefs.metaTubeTokenFlow.first())
        assertEquals(1, remote.writes)
        val remoteText = assertNotNull(remote.text)
        assertFalse(url in remoteText)
        assertFalse(token in remoteText)
        for (device in listOf(a, b)) {
            assertTrue(device.cache.values.keys.all { it.startsWith("metatube-sync-") })
            val ledger = device.cache.values.values.single()
            assertFalse(url in ledger)
            assertFalse(token in ledger)
            assertFalse("secret-path" in ledger)
            assertTrue("sha256" in ledger)
            assertTrue(Regex("\"sha256\":\"[0-9a-f]{64}\"").containsMatchIn(ledger))
        }
        assertTrue(a.sync.syncNow())
        assertEquals(1, a.cipher.derives, "未变更密码与 salt 时应复用派生密钥")
    }

    @Test
    fun fieldsMergeIndependentlyAndAnExplicitlyClearedTokenStaysEmpty() = scenario { scope ->
        val remote = MemoryRemote()
        val a = Device(remote, scope)
        val b = Device(remote, scope)
        a.prefs.setMetaTubeUrl("https://initial.example")
        a.prefs.setMetaTubeToken("initial-token")
        assertTrue(a.sync.syncNow())
        assertTrue(b.sync.syncNow())
        // 两台设备各改一项，B 的本机旧 URL 不应盖掉 A 的新 URL。
        a.stamp = 2_000L
        a.prefs.setMetaTubeUrl("https://updated.example")
        b.prefs.setMetaTubeToken("updated-token")
        assertTrue(a.sync.syncNow())
        b.stamp = 3_000L
        assertTrue(b.sync.syncNow())
        assertTrue(a.sync.syncNow())
        assertEquals("https://updated.example", a.prefs.metaTubeUrlFlow.first())
        assertEquals("https://updated.example", b.prefs.metaTubeUrlFlow.first())
        assertEquals("updated-token", a.prefs.metaTubeTokenFlow.first())
        a.stamp = 4_000L
        a.prefs.setMetaTubeToken("")
        assertTrue(a.sync.syncNow())
        assertTrue(b.sync.syncNow())
        assertEquals("", b.prefs.metaTubeTokenFlow.first())
        assertEquals("https://updated.example", b.prefs.metaTubeUrlFlow.first())
        assertTrue(b.sync.syncNow())
        assertTrue(a.sync.syncNow())
        assertEquals("", a.prefs.metaTubeTokenFlow.first())
    }

    @Test
    fun keyMismatchPreservesRemoteUntilExplicitResetWithBackup() = scenario { scope ->
        val remote = MemoryRemote()
        var password = "before"
        val a = Device(remote, scope) { password }
        a.prefs.setMetaTubeUrl("https://retained.example")
        a.prefs.setMetaTubeToken("retained-token")
        assertTrue(a.sync.syncNow())
        val oldCiphertext = assertNotNull(remote.text)

        password = "after"
        assertTrue(a.sync.syncNow())
        assertEquals(EncryptedSyncStatus.LockedKeyMismatch, a.sync.status.value)
        assertEquals(oldCiphertext, remote.text, "密钥变化时不得自动覆盖云端密文")
        assertEquals(1, remote.writes)
        assertTrue(remote.backups.isEmpty())

        assertTrue(a.sync.resetRemoteWithCurrentKey())
        assertEquals(listOf(oldCiphertext), remote.backups)
        assertFalse(remote.text == oldCiphertext)
        assertEquals(EncryptedSyncStatus.Unlocked, a.sync.status.value)

        val b = Device(remote, scope) { "after" }
        assertTrue(b.sync.syncNow())
        assertEquals("https://retained.example", b.prefs.metaTubeUrlFlow.first())
        assertEquals("retained-token", b.prefs.metaTubeTokenFlow.first())
        assertEquals(2, remote.writes)
    }

    @Test
    fun losslessPassphraseRotationUpgradesV1ToV2AndNeverDowngradesBackToV1() = scenario { scope ->
        val remote = MemoryRemote()
        val provider = FakePikPakServer().provider()
        val account = provider.currentClient.value!!.account
        var activeKey = dev.piko.shared.data.EncryptedSyncKey("pw-v1", isPassphrase = false)
        val cacheA = Cache()
        val prefsA = AccountScopedPreferences(MemoryPreferences(), provider, cacheA)
        val syncA = MetaTubeSync(
            clients = provider,
            remote = remote,
            preferences = prefsA,
            cacheStore = cacheA,
            keyProvider = SyncKeyProvider { activeKey },
            cipher = JvmSyncCipher(),
            scope = scope,
            enabled = flowOf(true),
            now = { 10_000L },
        )
        prefsA.setMetaTubeUrl("https://metatube.example")
        prefsA.setMetaTubeToken("token-v1")
        assertTrue(syncA.syncNow())
        assertTrue("\"version\":1" in assertNotNull(remote.text))

        val prevKey = activeKey
        activeKey = dev.piko.shared.data.EncryptedSyncKey("rotated-pass", isPassphrase = true)
        assertEquals(EncryptedSyncApplyOutcome.UNLOCKED, syncA.checkPassphrase(account, "rotated-pass", prevKey))
        assertEquals(EncryptedSyncApplyOutcome.UNLOCKED, syncA.applyPassphrase(account, "rotated-pass", prevKey))
        assertTrue("\"version\":2" in assertNotNull(remote.text))

        // 另一台以相同字符串作为账号密码的设备更新 token 时保持 v2，不降级回 v1
        val cacheB = Cache()
        val prefsB = AccountScopedPreferences(MemoryPreferences(), provider, cacheB)
        val syncB = MetaTubeSync(
            clients = provider,
            remote = remote,
            preferences = prefsB,
            cacheStore = cacheB,
            keyProvider = SyncKeyProvider { dev.piko.shared.data.EncryptedSyncKey("rotated-pass", isPassphrase = false) },
            cipher = JvmSyncCipher(),
            scope = scope,
            enabled = flowOf(true),
            now = { 20_000L },
        )
        assertTrue(syncB.syncNow())
        assertEquals("https://metatube.example", prefsB.metaTubeUrlFlow.first())
        prefsB.setMetaTubeToken("token-v2")
        assertTrue(syncB.syncNow())
        assertTrue("\"version\":2" in assertNotNull(remote.text))

        remote.lastStamp = 500_000L
        assertFalse(syncA.resetRemoteWithCurrentKey("other@piko.dev"))
        assertTrue(syncA.resetRemoteWithCurrentKey(account))
        assertEquals(500_001L, remote.writeStamps.last())
    }

    @Test
    fun switchingAccountsIsolatesTokensNeverClobbersRemoteAndMigratesLegacyByDigest() = scenario { scope ->
        val serverA = FakePikPakServer("A")
        val serverB = FakePikPakServer("B")
        val clientA = serverA.client(account = "alice@piko.dev")
        val clientB = serverB.client(account = "bob@piko.dev")
        val clients = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(clientA)
        }
        val settingsMap = mutableMapOf<String, String>()
        val vaultMap = mutableMapOf<String, ByteArray>()
        val storedPrefs = StoredPikoPreferences(
            settings = object : PreferenceSettings {
                override fun get(key: String, default: String): String = settingsMap[key] ?: default
                override fun set(key: String, value: String) { settingsMap[key] = value }
                override fun remove(key: String) { settingsMap.remove(key) }
                override fun keysWithPrefix(prefix: String): List<String> = settingsMap.keys.filter { it.startsWith(prefix) }.sorted()
                override val downloadDirectoryPath: String = "/tmp"
                override fun setDownloadDirectory(path: String) = Unit
            },
            openSecrets = {
                object : SecretVault {
                    override fun read(key: String): ByteArray? = vaultMap[key]?.copyOf()
                    override fun write(key: String, data: ByteArray) { vaultMap[key] = data.copyOf() }
                    override fun delete(key: String) { vaultMap.remove(key) }
                }
            },
        )
        val cache = Cache()
        val prefs = AccountScopedPreferences(
            delegate = storedPrefs,
            clients = clients,
            cacheStore = cache,
            knownAccounts = { listOf("alice@piko.dev", "bob@piko.dev") },
        )
        val remote = MemoryRemote()
        var clock = 1_000L
        val sync = MetaTubeSync(
            clients = clients,
            remote = remote,
            preferences = prefs,
            cacheStore = cache,
            passwordOf = { acct -> "pw-$acct" },
            cipher = JvmSyncCipher(),
            scope = scope,
            enabled = flowOf(true),
            now = { clock },
        )

        // 1. Account A configures MetaTube and syncs.
        prefs.setMetaTubeUrl("https://alice-mt.example")
        prefs.setMetaTubeToken("alice-secret-token")
        assertTrue(sync.syncNow())
        assertEquals(1, remote.writesByAccount["alice@piko.dev"])

        // 2. Switch to Account B: Account A's URL and token must NEVER leak to Account B!
        clock = 2_000L
        clients.currentClient.value = clientB
        assertEquals("", prefs.metaTubeUrlFlow.first())
        assertEquals("", prefs.metaTubeTokenFlow.first())
        assertTrue(sync.syncNow())
        assertNull(remote.textFor("bob@piko.dev"))

        prefs.setMetaTubeUrl("https://bob-mt.example")
        prefs.setMetaTubeToken("bob-secret-token")
        clock = 3_000L
        assertTrue(sync.syncNow())
        assertEquals(1, remote.writesByAccount["bob@piko.dev"])

        // 3. Switch back to Account A (A -> B -> A): Account B's values must NEVER clobber Account A!
        clock = 4_000L
        clients.currentClient.value = clientA
        assertEquals("https://alice-mt.example", prefs.metaTubeUrlFlow.first())
        assertEquals("alice-secret-token", prefs.metaTubeTokenFlow.first())
        assertTrue(sync.syncNow())
        assertEquals(1, remote.writesByAccount["alice@piko.dev"], "A -> B -> A switch must not overwrite Account A remote")
        assertEquals("https://alice-mt.example", prefs.metaTubeUrlFlow.first())
        assertEquals("alice-secret-token", prefs.metaTubeTokenFlow.first())

        // 4. Logged out state emits empty strings and never leaks secrets into cacheStore.
        clients.currentClient.value = null
        assertEquals("", prefs.metaTubeUrlFlow.first())
        assertEquals("", prefs.metaTubeTokenFlow.first())
        assertTrue(cache.values.values.none { "alice-secret-token" in it || "bob-secret-token" in it })
        assertTrue(settingsMap.values.none { "alice-secret-token" in it || "bob-secret-token" in it })

        // 5. Smart legacy migration: when v1.5.0 legacy slot matches Alice's sync ledger sha256,
        //    launching first on Bob gives Bob "" and assigns legacy token strictly to Alice's vault slot.
        val upgradeSettings = mutableMapOf("scrape.metaTubeUrl" to "https://legacy-alice.example")
        val upgradeVault = mutableMapOf("metatube-token" to "legacy-alice-token".encodeToByteArray())
        val upgradeBacking = StoredPikoPreferences(
            settings = object : PreferenceSettings {
                override fun get(key: String, default: String): String = upgradeSettings[key] ?: default
                override fun set(key: String, value: String) { upgradeSettings[key] = value }
                override fun remove(key: String) { upgradeSettings.remove(key) }
                override fun keysWithPrefix(prefix: String): List<String> = upgradeSettings.keys.filter { it.startsWith(prefix) }.sorted()
                override val downloadDirectoryPath: String = "/tmp"
                override fun setDownloadDirectory(path: String) = Unit
            },
            openSecrets = {
                object : SecretVault {
                    override fun read(key: String): ByteArray? = upgradeVault[key]?.copyOf()
                    override fun write(key: String, data: ByteArray) { upgradeVault[key] = data.copyOf() }
                    override fun delete(key: String) { upgradeVault.remove(key) }
                }
            },
        )
        val upgradeCache = Cache().apply {
            val urlSha = PortableDigest.sha256("https://legacy-alice.example".encodeToByteArray()).toHexString()
            val tokSha = PortableDigest.sha256("legacy-alice-token".encodeToByteArray()).toHexString()
            values["metatube-sync-alice@piko.dev.json"] =
                """{"url":{"sha256":"$urlSha","updatedAt":10},"token":{"sha256":"$tokSha","updatedAt":10}}"""
        }
        clients.currentClient.value = clientB
        val upgradePrefs = AccountScopedPreferences(
            delegate = upgradeBacking,
            clients = clients,
            cacheStore = upgradeCache,
            knownAccounts = { listOf("alice@piko.dev", "bob@piko.dev") },
        )
        assertEquals("", upgradePrefs.metaTubeUrlFlow.first(), "Bob must not inherit Alice's legacy URL")
        assertEquals("", upgradePrefs.metaTubeTokenFlow.first(), "Bob must not inherit Alice's legacy token")
        clients.currentClient.value = clientA
        assertEquals("https://legacy-alice.example", upgradePrefs.metaTubeUrlFlow.first())
        assertEquals("legacy-alice-token", upgradePrefs.metaTubeTokenFlow.first())
        assertTrue(upgradeCache.values.values.none { "legacy-alice-token" in it })
    }
}
