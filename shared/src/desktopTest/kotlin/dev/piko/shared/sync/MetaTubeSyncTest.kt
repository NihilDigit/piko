package dev.piko.shared.sync

import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        var text: String? = null
        var writes = 0
        var lastStamp = 0L
        val writeStamps = mutableListOf<Long>()
        val backups = mutableListOf<String>()
        override suspend fun read(account: String) = text
        override suspend fun latestStamp(account: String) = lastStamp
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
            this.lastStamp = maxOf(this.lastStamp, stamp)
            writeStamps += stamp
            writes++
        }
        override suspend fun writeBackup(account: String, text: String, stamp: Long) {
            backups += text
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
        val prefs = MemoryPreferences()
        val cache = Cache()
        val cipher = CountingCipher()
        var stamp = 1_000L
        val sync = MetaTubeSync(FakePikPakServer().provider(), remote, prefs, cache, { password() }, cipher, scope,
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
        assertEquals(url, b.prefs.metaTubeUrlFlow.value)
        assertEquals(token, b.prefs.metaTubeTokenFlow.value)
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
        assertEquals("https://updated.example", a.prefs.metaTubeUrlFlow.value)
        assertEquals("https://updated.example", b.prefs.metaTubeUrlFlow.value)
        assertEquals("updated-token", a.prefs.metaTubeTokenFlow.value)
        a.stamp = 4_000L
        a.prefs.setMetaTubeToken("")
        assertTrue(a.sync.syncNow())
        assertTrue(b.sync.syncNow())
        assertEquals("", b.prefs.metaTubeTokenFlow.value)
        assertEquals("https://updated.example", b.prefs.metaTubeUrlFlow.value)
        assertTrue(b.sync.syncNow())
        assertTrue(a.sync.syncNow())
        assertEquals("", a.prefs.metaTubeTokenFlow.value)
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
        assertEquals("https://retained.example", b.prefs.metaTubeUrlFlow.value)
        assertEquals("retained-token", b.prefs.metaTubeTokenFlow.value)
        assertEquals(2, remote.writes)
    }

    @Test
    fun losslessPassphraseRotationUpgradesV1ToV2AndNeverDowngradesBackToV1() = scenario { scope ->
        val remote = MemoryRemote()
        val provider = FakePikPakServer().provider()
        val account = provider.currentClient.value!!.account
        var activeKey = dev.piko.shared.data.EncryptedSyncKey("pw-v1", isPassphrase = false)
        val prefsA = MemoryPreferences()
        val cacheA = Cache()
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
        val prefsB = MemoryPreferences()
        val cacheB = Cache()
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
        assertEquals("https://metatube.example", prefsB.metaTubeUrlFlow.value)
        prefsB.setMetaTubeToken("token-v2")
        assertTrue(syncB.syncNow())
        assertTrue("\"version\":2" in assertNotNull(remote.text))

        remote.lastStamp = 500_000L
        assertFalse(syncA.resetRemoteWithCurrentKey("other@piko.dev"))
        assertTrue(syncA.resetRemoteWithCurrentKey(account))
        assertEquals(500_001L, remote.writeStamps.last())
    }
}
