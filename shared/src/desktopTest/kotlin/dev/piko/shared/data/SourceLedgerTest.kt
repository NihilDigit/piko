package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.resourceListBody
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SourceLedgerSync
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.getFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLedgerTest {
    private val magnetA = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&tr=udp%3A%2F%2Ftracker.example%3A1337"
    private val magnetB = "magnet:?xt=urn:btih:" + "b".repeat(40)

    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        var failing = false
        override suspend fun read(account: String): String? {
            if (failing) error("网络不通")
            return text
        }
        override suspend fun write(account: String, text: String, stamp: Long) {
            if (failing) error("网络不通")
            this.text = text
        }
    }

    private fun ledger(scope: CoroutineScope, clock: () -> Long) =
        SourceLedger(null, scope, now = clock).apply { switchAccount("alice") }

    private fun file(id: String, name: String, hash: String, size: Long, url: String? = null) = FileStat(
        kind = FileKind.FILE,
        id = id,
        name = name,
        size = size.toString(),
        hash = hash,
        params = url?.let { mapOf("url" to it) }.orEmpty(),
    )

    @Test
    fun `account handoff drains an in flight write before returning to the same key`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val files = mutableMapOf<String, String>()
        var first = true
        val store = object : PikoCacheStore {
            override suspend fun read(key: String) = files[key]
            override suspend fun write(key: String, value: String) {
                if (first) {
                    first = false
                    started.complete(Unit)
                    release.await()
                }
                files[key] = value
            }
        }
        try {
            withTimeout(5_000) {
                val ledger = SourceLedger(store, scope, saveDelayMillis = 0)
                ledger.switchAccount("alice")
                ledger.record(magnetA, listOf(ResolvedFile("one", 1, "G1")))
                started.await()
                ledger.record(magnetA, listOf(ResolvedFile("two", 2, "G2")))
                ledger.switchAccount("bob")
                ledger.switchAccount("alice")
                release.complete(Unit)
                val restored = ledger.flow.first { it.size == 2 }
                assertEquals(setOf("G1:1", "G2:2"), restored.keys)
                assertEquals(restored, SourceLedger.decode(files.getValue("sources-alice.json")))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a file renamed or copied keeps its source, other sizes and server sources are left alone`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = ledger(scope) { 1_000 }
        ledger.record(magnetA, listOf(ResolvedFile(path = "Show/E01.mkv", size = 700, gcid = "ABCDEF")))

        assertEquals(magnetA, ledger.enrich(file("copy", "第一集.mkv", "abcdef", 700)).sourceUrl)
        assertNull(ledger.enrich(file("other", "E01.mkv", "ABCDEF", 701)).sourceUrl)
        val offline = "magnet:?xt=urn:btih:" + "c".repeat(40)
        assertEquals(offline, ledger.enrich(file("off", "E01.mkv", "ABCDEF", 700, url = offline)).sourceUrl)
        val folder = FileStat(kind = FileKind.FOLDER, id = "dir", name = "Show", hash = "ABCDEF", size = "700")
        assertNull(ledger.enrich(folder).sourceUrl)
        scope.cancel()
    }

    @Test
    fun `merging is order free and the later record wins`() {
        val a = mapOf(
            SourceLedger.keyOf("G1", 1) to SourceRecord(magnetA, "E01.mkv", 10),
            SourceLedger.keyOf("G2", 2) to SourceRecord(magnetA, "E02.mkv", 10),
        )
        val b = mapOf(
            SourceLedger.keyOf("G2", 2) to SourceRecord(magnetB, "x/E02.mkv", 20),
            SourceLedger.keyOf("G3", 3) to SourceRecord(magnetB, "E03.mkv", 20),
        )
        val c = mapOf(SourceLedger.keyOf("G1", 1) to SourceRecord(magnetB, "E01.mkv", 10))
        val ab = SourceLedger.union(SourceLedger.union(a, b), c)
        val ba = SourceLedger.union(c, SourceLedger.union(b, a))
        assertEquals(ab, ba)
        assertEquals(magnetB, ab.getValue(SourceLedger.keyOf("G2", 2)).source)
        assertEquals(3, ab.size)
        assertEquals(ab, SourceLedger.union(ab, ab))
    }

    @Test
    fun `the stored form lists each source once and reads back`() {
        val records = (1..30).associate { SourceLedger.keyOf("G$it", it.toLong()) to SourceRecord(magnetA, "Show/E$it.mkv", 5) } +
            (SourceLedger.keyOf("G99", 99) to SourceRecord(magnetB, "Other.mkv", 6))
        val text = SourceLedger.encode(records)
        assertEquals(1, Regex(Regex.escape(magnetA)).findAll(text).count())
        assertEquals(records, SourceLedger.decode(text))
        assertNull(SourceLedger.decode("{坏的"))
    }

    @Test
    fun `two devices end up with both ledgers and a failed upload keeps the local records`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        val clients = FakePikPakServer().provider()
        withTimeout(5_000) {
            val laptop = ledger(scope) { 100 }
            val desktop = ledger(scope) { 200 }
            laptop.record(magnetA, listOf(ResolvedFile(path = "E01.mkv", size = 1, gcid = "G1")))
            desktop.record(magnetB, listOf(ResolvedFile(path = "E02.mkv", size = 2, gcid = "G2")))

            remote.failing = true
            assertFalse(SourceLedgerSync(clients, remote, laptop, scope, flowOf(true)).syncNow())
            assertEquals(magnetA, laptop.flow.value.getValue(SourceLedger.keyOf("G1", 1)).source, "上传失败不丢本机的记录")
            remote.failing = false

            SourceLedgerSync(clients, remote, laptop, scope, flowOf(true)).syncNow()
            SourceLedgerSync(clients, remote, desktop, scope, flowOf(true)).syncNow()
            SourceLedgerSync(clients, remote, laptop, scope, flowOf(true)).syncNow()

            assertEquals(laptop.flow.value, desktop.flow.value)
            assertEquals(setOf(SourceLedger.keyOf("G1", 1), SourceLedger.keyOf("G2", 2)), laptop.flow.value.keys)
            assertEquals(laptop.flow.value, SourceLedger.decode(remote.text!!))
        }
        scope.cancel()
    }

    @Test
    fun `records made during a failing initial read or initial write are still pushed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = FakePikPakServer().provider()
            val ledgerA = PikoDriveRepository(provider, MemoryPreferences()).sourceLedger
            val enteredRead = Channel<Unit>(1)
            val releaseRead = Channel<Unit>(1)
            val writtenRead = Channel<String>(Channel.UNLIMITED)
            var reads = 0
            val remoteRead = object : RemoteSettingsStore {
                override suspend fun read(account: String): String? {
                    if (++reads == 1) { enteredRead.send(Unit); releaseRead.receive(); error("initial read failed") }
                    return null
                }
                override suspend fun write(account: String, text: String, stamp: Long) { writtenRead.send(text) }
            }
            SourceLedgerSync(provider, remoteRead, ledgerA, scope, flowOf(true)).start()
            withTimeout(5_000) { enteredRead.receive() }
            ledgerA.record("magnet:?xt=urn:btih:first", listOf(ResolvedFile("first.mkv", 1, "FIRST")))
            releaseRead.send(Unit)
            val afterFailedRead = assertNotNull(SourceLedger.decode(withTimeout(7_000) { writtenRead.receive() }))
            assertTrue(afterFailedRead.values.any { it.path == "first.mkv" })
            assertEquals(2, reads)

            val ledgerB = PikoDriveRepository(provider, MemoryPreferences()).sourceLedger
            ledgerB.record("magnet:?xt=urn:btih:first", listOf(ResolvedFile("first.mkv", 1, "FIRST")))
            val enteredWrite = Channel<Unit>(1)
            val releaseWrite = Channel<Unit>(1)
            val writtenWrite = Channel<String>(Channel.UNLIMITED)
            var remoteText: String? = null
            var writes = 0
            val remoteWrite = object : RemoteSettingsStore {
                override suspend fun read(account: String): String? = remoteText
                override suspend fun write(account: String, text: String, stamp: Long) {
                    if (++writes == 1) { enteredWrite.send(Unit); releaseWrite.receive() }
                    remoteText = text
                    writtenWrite.send(text)
                }
            }
            SourceLedgerSync(provider, remoteWrite, ledgerB, scope, flowOf(true)).start()
            withTimeout(5_000) { enteredWrite.receive() }
            ledgerB.record("magnet:?xt=urn:btih:second", listOf(ResolvedFile("second.mkv", 1, "SECOND")))
            releaseWrite.send(Unit)
            val first = assertNotNull(SourceLedger.decode(withTimeout(5_000) { writtenWrite.receive() }))
            val second = assertNotNull(SourceLedger.decode(withTimeout(7_000) { writtenWrite.receive() }))
            assertEquals(setOf("first.mkv"), first.values.map { it.path }.toSet())
            assertEquals(setOf("first.mkv", "second.mkv"), second.values.map { it.path }.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `source cache failure does not poison next account and dirty account can retry`(): Unit = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val files = mutableMapOf<String, String>()
        val writes = Channel<String>(Channel.UNLIMITED)
        var failingKey: String? = "sources-alice.json"
        var failures = 0
        val store = object : PikoCacheStore {
            override suspend fun read(key: String) = files[key]
            override suspend fun write(key: String, value: String) {
                if (key == failingKey) {
                    failures++
                    error("disk full")
                }
                files[key] = value
                writes.send(key)
            }
        }
        suspend fun awaitWrite(key: String) {
            while (writes.receive() != key) Unit
        }
        val a = mapOf("A:1" to SourceRecord("source-a", "a.mkv", 1))
        val b = mapOf("B:2" to SourceRecord("source-b", "b.mkv", 2))
        files["sources-bob.json"] = SourceLedger.encode(b)
        try {
            withTimeout(5_000) {
                val ledger = SourceLedger(store, scope, saveDelayMillis = 0)
                ledger.switchAccount("alice")
                ledger.merge(a)
                ledger.switchAccount("bob")
                ledger.flow.first { it == b }
                assertTrue(failures > 0)
                assertTrue(scope.coroutineContext[Job]!!.isActive)
                val fresh = mapOf("C:3" to SourceRecord("source-c", "c.mkv", 3))
                ledger.merge(fresh)
                awaitWrite("sources-bob.json")
                assertEquals(b + fresh, SourceLedger.decode(files.getValue("sources-bob.json")))

                failingKey = null
                ledger.switchAccount("alice")
                ledger.flow.first { it == a }
                awaitWrite("sources-alice.json")
                assertEquals(a, SourceLedger.decode(files.getValue("sources-alice.json")))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an instant-saved file carries its magnet in repository listings even when remote sync fails`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(15_000) {
                val magnet = "magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=E01"
                val server = FakePikPakServer()
                server.indexMagnet(magnet, resourceListBody("E01.mkv", listOf(Triple("E01.mkv", 4096L, "GCIDSRC"))))
                val provider = server.provider()
                val prefs = MemoryPreferences()
                val instantRepo = InstantMagnetRepository(provider)
                val driveRepo = PikoDriveRepository(provider, prefs)
                val attempts = AtomicInteger(0)
                val brokenRemote = object : RemoteSettingsStore {
                    override suspend fun read(account: String): String? {
                        attempts.incrementAndGet()
                        error("网盘不可写")
                    }
                    override suspend fun write(account: String, text: String, stamp: Long) = error("网盘不可写")
                }
                SourceLedgerSync(provider, brokenRemote, driveRepo.sourceLedger, scope, flowOf(true)).start()
                awaitUntil("初次账本同步已尝试") { attempts.get() >= 1 }
                val state = InstantSheetState(
                    instantRepo,
                    driveRepo,
                    prefs,
                    PreviewTempFolder(provider, driveRepo, instantRepo, scope),
                    OfflinePackTracker(instantRepo, driveRepo, prefs),
                    InstantSaveRecords(provider, null, scope),
                    scope,
                    magnet,
                )
                val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
                awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
                state.saveSelection()
                val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())
                val savedId = saved.createdIds.single()
                assertNull(server.client().getFile(savedId).sourceUrl)
                server.node(savedId)!!.name = "第一集.mkv"
                assertEquals(magnet, driveRepo.listBrowsable(saved.target.id, PikoFileSortOrder.NAME_ASC).getOrThrow().single { it.id == savedId }.sourceUrl)
                awaitUntil("账本同步试过两次") { attempts.get() >= 2 }
            }
        } finally {
            scope.cancel()
        }
    }
}
