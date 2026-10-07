package dev.piko.shared.smoke

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SourceLedgerSync
import io.github.nihildigit.pikpak.getFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 防的是磁力秒传存下的文件没有来源：服务端不给它们填 params.url，Piko 记进来源账本，列出来时补上，
 * 改名、复制后照样补得上；账本上传网盘失败时保存照常成功。
 */
class SourceLedgerSmokeTest {
    private val magnet = "magnet:?xt=urn:btih:" + "d".repeat(40) + "&dn=E01"

    private class BrokenRemote : RemoteSettingsStore {
        val attempts = AtomicInteger(0)
        override suspend fun read(account: String): String? {
            attempts.incrementAndGet()
            error("网盘不可写")
        }
        override suspend fun write(account: String, text: String, stamp: Long) = error("网盘不可写")
    }

    @Test
    fun `an instant-saved file carries its magnet after rename and copy`() = smoke { scope ->
        val server = FakePikPakServer()
        server.indexMagnet(magnet, resourceListBody("E01.mkv", listOf(Triple("E01.mkv", 4096L, "GCIDSRC"))))
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val instantRepo = InstantMagnetRepository(provider)
        val driveRepo = PikoDriveRepository(provider, prefs)
        val remote = BrokenRemote()
        SourceLedgerSync(provider, remote, driveRepo.sourceLedger, scope, flowOf(true)).start()
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
        assertNull(server.client().getFile(savedId).sourceUrl, "服务端不给秒传的文件填来源")

        suspend fun listed(folderId: String) = driveRepo.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
        server.node(savedId)!!.name = "第一集.mkv"
        assertEquals(magnet, listed(saved.target.id).single { it.id == savedId }.sourceUrl)

        // 复制换了 ID，内容不变；gcid 大小写不同也是同一份。大小不同的不补
        val copies = server.addFolder("Copies")
        server.addFile("副本.mkv", copies.id, content = ByteArray(4096), hash = "gcidsrc")
        server.addFile("同哈希不同大小.mkv", copies.id, content = ByteArray(4095), hash = "GCIDSRC")
        val inCopies = listed(copies.id).associateBy { it.name }
        assertEquals(magnet, inCopies.getValue("副本.mkv").sourceUrl)
        assertNull(inCopies.getValue("同哈希不同大小.mkv").sourceUrl)

        // 开头一次、记下之后推一次，两次都失败了，保存与补全不受影响
        awaitUntil("账本同步试过两次") { remote.attempts.get() >= 2 }
    }
}
