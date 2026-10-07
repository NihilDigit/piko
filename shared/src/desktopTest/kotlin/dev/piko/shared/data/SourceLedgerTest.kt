package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SourceLedgerSync
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
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
import kotlin.test.assertNull

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
    fun `a file renamed or copied keeps its source, other sizes and server sources are left alone`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ledger = ledger(scope) { 1_000 }
        ledger.record(magnetA, listOf(ResolvedFile(path = "Show/E01.mkv", size = 700, gcid = "ABCDEF")))

        // 改名、复制换了名字与 ID，gcid 大小写不同也认
        assertEquals(magnetA, ledger.enrich(file("copy", "第一集.mkv", "abcdef", 700)).sourceUrl)
        assertNull(ledger.enrich(file("other", "E01.mkv", "ABCDEF", 701)).sourceUrl, "大小不同不是同一份内容")
        val offline = "magnet:?xt=urn:btih:" + "c".repeat(40)
        assertEquals(offline, ledger.enrich(file("off", "E01.mkv", "ABCDEF", 700, url = offline)).sourceUrl, "服务端给的来源不覆盖")
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
        // 时刻相同、来源不同：两边各自合并也要选中同一条
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
        assertEquals(1, Regex(Regex.escape(magnetA)).findAll(text).count(), "同一条磁力只存一次")
        assertEquals(records, SourceLedger.decode(text))
        assertNull(SourceLedger.decode("{坏的"))
    }

    @Test
    fun `two devices end up with both ledgers and a failed upload keeps the local records`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = MemoryRemote()
        // 同步只要一个当前账号，SDK 的请求走不到
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
}
