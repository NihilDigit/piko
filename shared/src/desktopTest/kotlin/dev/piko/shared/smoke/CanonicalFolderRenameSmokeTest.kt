package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.rename.BatchRenameMemory
import dev.piko.shared.rename.BatchRenameState
import dev.piko.shared.rename.CanonicalTreeScan
import dev.piko.shared.rename.RenameProblem
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 按番号规范命名一个文件夹：扫描整棵树交给批量重命名，新名照资源文件夹的规则，跨层的项带着所在目录；
 * 撞名的默认不勾，执行后整批记一条改动、一次撤销全部改回。
 */
class CanonicalFolderRenameSmokeTest {

    private class Fixture(val server: FakePikPakServer, val repository: PikoDriveRepository, val state: BatchRenameState, val scan: CanonicalTreeScan)

    private suspend fun open(scope: CoroutineScope, build: (FakePikPakServer) -> FakePikPakServer.Node): Fixture {
        val server = FakePikPakServer()
        val root = build(server)
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val scan = CanonicalTreeScan(server.provider(), repository, scope, PikoPathBreadcrumb(root.id, root.name))
        awaitUntil("扫完") { scan.phase == CanonicalTreeScan.Phase.DONE }
        fun listings() = server.calls.count { it.startsWith("GET") && it.endsWith("/drive/v1/files") }
        val before = listings()
        val state = BatchRenameState(repository, prefs, scope, scan.files, BatchRenameMemory(), tree = scan.tree)
        awaitUntil("同目录名称就绪") { !state.isCheckingSiblings }
        // 树里的目录扫描时已列过，只差树根所在的那一层
        assertEquals(before + 1, listings(), "批量重命名只该再列树根所在的目录")
        return Fixture(server, repository, state, scan)
    }

    @Test
    fun `tree items get canonical names with locations and one undo reverts the batch`() = smoke { scope ->
        lateinit var resource: FakePikPakServer.Node
        lateinit var part1: FakePikPakServer.Node
        lateinit var part2: FakePikPakServer.Node
        lateinit var subtitle: FakePikPakServer.Node
        lateinit var loose: FakePikPakServer.Node
        lateinit var clashing: FakePikPakServer.Node
        lateinit var grouping: FakePikPakServer.Node
        val fixture = open(scope) { server ->
            val library = server.addFolder("Library")
            resource = server.addFolder("[site.net] abc-123 某片名", library.id)
            part1 = server.addFile("abc00123hhb1.mp4", resource.id, hash = "P1")
            part2 = server.addFile("abc00123hhb2.mp4", resource.id, hash = "P2")
            subtitle = server.addFile("abc00123hhb1.chs.srt", resource.id)
            grouping = server.addFolder("收藏", library.id)
            loose = server.addFile("def00789 另一部.mp4", grouping.id, hash = "D")
            clashing = server.addFile("xyz00456.mp4", library.id, hash = "X")
            server.addFile("XYZ-456.mp4", library.id, hash = "OLD")
            library
        }
        val state = fixture.state
        assertTrue(state.isNamingTree)

        val changed = state.plan.rows.filter { it.isChanged }.associate { it.source.id to it.newName }
        assertEquals(
            mapOf(
                resource.id to "ABC-123 某片名",
                part1.id to "ABC-123-CD1.mp4",
                part2.id to "ABC-123-CD2.mp4",
                subtitle.id to "ABC-123-CD1.chs.srt",
                loose.id to "DEF-789 另一部.mp4",
            ),
            changed,
            "资源里的文件只写番号与分段，字幕跟随，用户的归类不改，撞名的不勾",
        )
        assertFalse(grouping.id in changed, "认不出番号的文件夹是用户的归类")
        assertEquals("Library/收藏", state.locationOf(loose.id))
        assertEquals("Library/[site.net] abc-123 某片名", state.locationOf(part1.id))
        assertEquals("Library", state.locationOf(clashing.id))

        // 撞名的默认不勾，照样能看出它本要改成什么；勾上就标出问题
        assertTrue(clashing.id in state.excludedIds)
        assertEquals("XYZ-456.mp4", state.proposedName(clashing.id))
        state.setIncluded(clashing.id, true)
        assertEquals(RenameProblem.TAKEN, state.plan.rows.single { it.source.id == clashing.id }.problem)
        state.setIncluded(clashing.id, false)

        // 取消勾选资源文件夹只让它自己保持原名，里面的文件仍按资源命名，不变成带片名的名字
        state.setIncluded(resource.id, false)
        assertEquals("ABC-123-CD1.mp4", state.plan.rows.single { it.source.id == part1.id }.newName)
        state.setIncluded(resource.id, true)

        state.setOverride(loose.id, "DEF-789 手改.mp4")
        assertTrue(state.canRename)
        state.rename()
        awaitUntil("改名完成") { state.phase == BatchRenameState.Phase.DONE }
        val server = fixture.server
        assertEquals("ABC-123 某片名", server.node(resource.id)?.name)
        assertEquals("ABC-123-CD2.mp4", server.node(part2.id)?.name)
        assertEquals("ABC-123-CD1.chs.srt", server.node(subtitle.id)?.name)
        assertEquals("DEF-789 手改.mp4", server.node(loose.id)?.name)
        assertEquals("xyz00456.mp4", server.node(clashing.id)?.name)

        // 整批是一条改动：撤销一次全部改回
        awaitUntil("记进改动日志") { fixture.repository.changes.latest.value != null }
        assertTrue(fixture.repository.changes.undoLast())
        awaitUntil("撤销完成") { server.node(loose.id)?.name == "def00789 另一部.mp4" && server.node(resource.id)?.name == "[site.net] abc-123 某片名" }
        assertEquals("abc00123hhb1.mp4", server.node(part1.id)?.name)
        assertEquals("abc00123hhb1.chs.srt", server.node(subtitle.id)?.name)
        assertFalse(fixture.repository.changes.undoLast(), "只有一条改动")
    }

    @Test
    fun `a folder that is itself a resource is renamed along with its files`() = smoke { scope ->
        lateinit var video: FakePikPakServer.Node
        lateinit var root: FakePikPakServer.Node
        val fixture = open(scope) { server ->
            root = server.addFolder("ssis00101 片名")
            video = server.addFile("ssis00101.mp4", root.id, hash = "S")
            root
        }
        val changed = fixture.state.plan.rows.filter { it.isChanged }.associate { it.source.id to it.newName }
        assertEquals(mapOf(root.id to "SSIS-101 片名", video.id to "SSIS-101.mp4"), changed)
        assertEquals(null, fixture.state.locationOf(root.id), "树根自己不写所在目录")
        assertEquals("ssis00101 片名", fixture.state.locationOf(video.id))
    }
}
