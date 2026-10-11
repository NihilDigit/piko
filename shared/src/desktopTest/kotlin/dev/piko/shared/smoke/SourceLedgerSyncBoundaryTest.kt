package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.SourceLedger
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SourceLedgerSync
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class SourceLedgerSyncBoundaryTest {
    @Test
    fun `records made during a failing initial read are still pushed`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val ledger = PikoDriveRepository(provider, MemoryPreferences()).sourceLedger
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val written = Channel<String>(Channel.UNLIMITED)
        var reads = 0
        val remote = object : RemoteSettingsStore {
            override suspend fun read(account: String): String? {
                if (++reads == 1) { entered.send(Unit); release.receive(); error("initial read failed") }
                return null
            }
            override suspend fun write(account: String, text: String, stamp: Long) { written.send(text) }
        }
        SourceLedgerSync(provider, remote, ledger, scope, flowOf(true)).start()
        withTimeout(5_000) { entered.receive() }
        ledger.record("magnet:?xt=urn:btih:first", listOf(ResolvedFile("first.mkv", 1, "FIRST")))
        release.send(Unit)
        val records = assertNotNull(SourceLedger.decode(withTimeout(7_000) { written.receive() }))
        assertTrue(records.values.any { it.path == "first.mkv" })
        assertEquals(2, reads)
    }

    @Test
    fun `records made during the initial write are not mistaken for its synchronized snapshot`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val ledger = PikoDriveRepository(provider, MemoryPreferences()).sourceLedger
        ledger.record("magnet:?xt=urn:btih:first", listOf(ResolvedFile("first.mkv", 1, "FIRST")))
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val written = Channel<String>(Channel.UNLIMITED)
        var remoteText: String? = null
        var writes = 0
        val remote = object : RemoteSettingsStore {
            override suspend fun read(account: String): String? = remoteText
            override suspend fun write(account: String, text: String, stamp: Long) {
                if (++writes == 1) { entered.send(Unit); release.receive() }
                remoteText = text
                written.send(text)
            }
        }
        SourceLedgerSync(provider, remote, ledger, scope, flowOf(true)).start()
        withTimeout(5_000) { entered.receive() }
        ledger.record("magnet:?xt=urn:btih:second", listOf(ResolvedFile("second.mkv", 1, "SECOND")))
        release.send(Unit)
        val first = assertNotNull(SourceLedger.decode(withTimeout(5_000) { written.receive() }))
        val second = assertNotNull(SourceLedger.decode(withTimeout(7_000) { written.receive() }))
        assertEquals(setOf("first.mkv"), first.values.map { it.path }.toSet())
        assertEquals(setOf("first.mkv", "second.mkv"), second.values.map { it.path }.toSet())
    }
}
