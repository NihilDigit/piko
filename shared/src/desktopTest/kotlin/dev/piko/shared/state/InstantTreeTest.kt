package dev.piko.shared.state

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.NamingFixtures
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.resourceListBody
import dev.piko.shared.smoke.smoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 磁链面板的结构与勾选。夹具是真实种子的文件树，见 desktopTest/resources/naming/。
 */
class InstantTreeTest {

    private fun InstantTree.group(title: String): InstantGroup =
        allGroups().single { it.title == title }

    private fun InstantTree.allGroups(): List<InstantGroup> = buildList {
        fun visit(node: InstantNode) {
            if (node is InstantGroup) {
                add(node)
                node.children.forEach(::visit)
            }
        }
        roots.forEach(::visit)
    }

    /**
     * 走完整的面板状态：假服务端返回命运石之门的文件树，解析在后台线程完成。
     * 防的是默认勾选漏掉正片字幕或勾上 PV、菜单、字体包，以及勾选视频时字幕没有跟着走。
     */
    @Test
    fun `steins gate selects main episodes with their subtitles and subtitles follow the video`() = smoke { scope ->
        val files = NamingFixtures.load("steins-gate-dbd")
        val server = FakePikPakServer()
        val magnet = "magnet:?xt=urn:btih:" + "b".repeat(40)
        server.indexMagnet(magnet, resourceListBody("[DBD-Raws][命运石之门]", files.mapIndexed { i, f -> Triple(f.path, f.size, "G$i") }))
        val provider = server.provider()
        val instantRepo = InstantMagnetRepository(provider)
        val prefs = MemoryPreferences()
        val driveRepo = PikoDriveRepository(provider, prefs)
        val state = InstantSheetState(
            instantRepo, driveRepo, prefs, PreviewTempFolder(driveRepo, instantRepo, scope),
            OfflinePackTracker(instantRepo, driveRepo, prefs), scope, magnet,
        )
        awaitUntil("解析与整理完成") { state.resolution != null }
        val tree = state.tree!!

        fun nameOf(index: Int) = state.items[index].file.path
        val selected = state.selectedIndices.map(::nameOf)
        assertEquals("Steins;Gate", state.folderName)
        assertTrue(selected.any { it == "[DBD-Raws][Steins;Gate][01][1080P][BDRip][HEVC-10bit][FLAC].scjp.ass" }, "正片的字幕随正片勾选")
        assertTrue(selected.none { it.startsWith("PV/") || it.startsWith("menu/") || it.startsWith("NCOP&NCED/") || it.startsWith("Fonts/") })
        assertEquals(29, state.selectedEntryCount, "Steins;Gate 正片 25 行加 Soumei 4 行，字幕不算行")

        val fonts = tree.group("其他文件")
        assertFalse(state.isGroupExpanded(fonts))
        assertEquals(listOf("Fonts.rar"), fonts.rows.map { it.label })

        val episode01 = tree.rows.single { nameOf(it.index) == "[DBD-Raws][Steins;Gate][01][1080P][BDRip][HEVC-10bit][FLAC].mkv" }
        assertEquals("01", episode01.label)
        assertEquals(2, episode01.subtitleCount)
        state.setItemSelected(episode01.index, false)
        assertTrue(episode01.indices.none { it in state.selectedIndices }, "取消视频时字幕一起取消")
        state.setItemSelected(episode01.index, true)
        assertTrue(episode01.indices.all { it in state.selectedIndices }, "勾回视频时字幕一起勾回")

        // 分区的三态：勾上 PV 分区只动这一个分区，另一部作品的 PV 不受影响
        val sections = tree.group("Steins;Gate").children.filterIsInstance<InstantGroup>()
        val pv = sections.single { it.title == "PV/CM" }
        assertFalse(state.isGroupExpanded(pv), "PV 默认收起")
        assertTrue(state.isGroupExpanded(sections.single { it.title == "正片" }))
        state.setGroupSelected(pv, true)
        assertEquals(NameGroupSummary(selected = 2, total = 2, bytes = pv.indices.sumOf { state.items[it].file.size }, hasUnindexed = false), state.summaryOf(pv))
        val moviePv = tree.group("Steins;Gate Fuka Ryouiki no Deja vu").children.filterIsInstance<InstantGroup>().single { it.title == "PV/CM" }
        assertEquals(0, state.summaryOf(moviePv).selected)
    }

    /**
     * 子目录里的原声 CD 与扫图不能像旧的前缀折叠那样平铺进正片：CD 归作品的「其他」分区，扫图与日志进「其他文件」，都不勾。
     */
    @Test
    fun `heike keeps cds and scans out of the episode list`() {
        val files = NamingFixtures.load("heike-vcb")
        val tree = buildInstantTree(files, "[Nekomoe kissaten&VCB-Studio] Heike Monogatari [Ma10p_1080p]")
        assertEquals("Heike Monogatari", tree.folderName)

        val work = tree.group("Heike Monogatari")
        assertEquals(listOf("Nekomoe kissaten&VCB-Studio", "1080p", "HEVC", "10bit", "FLAC"), work.tags)
        val main = work.children.filterIsInstance<InstantGroup>().single { it.title == "正片" }
        assertEquals((1..11).map { it.toString().padStart(2, '0') }, main.rows.map { it.label })
        assertTrue(main.rows.all { row -> row.indices.all { it in tree.defaultSelection } })

        val cds = work.children.filterIsInstance<InstantGroup>().single { it.title == "其他" }
        assertTrue(cds.rows.all { "/CDs/" in files[it.index].path })
        assertFalse(cds.defaultExpanded)

        val secondary = tree.group("其他文件")
        val scans = secondary.children.filterIsInstance<InstantGroup>().single { it.title == "扫图" }
        assertTrue(scans.rows.all { "/Scans/" in files[it.index].path })

        val selectedPaths = tree.defaultSelection.map { files[it].path }
        assertTrue(selectedPaths.none { "/CDs/" in it || "/Scans/" in it || "/SPs/" in it })
    }

    @Test
    fun `unrecognized files go last under other with their raw names`() {
        val files = listOf(
            MediaFileInput("[Group] Show - 01 [1080p].mkv", 700L shl 20),
            MediaFileInput("[Group] Show - 02 [1080p].mkv", 700L shl 20),
            MediaFileInput("0000-0049.mov", 300L shl 20),
        )
        val tree = buildInstantTree(files, "Show")
        val last = tree.roots.last() as InstantGroup
        assertEquals("其他", last.title)
        assertEquals(listOf("0000-0049.mov"), last.rows.map { it.label })
        assertTrue(2 in tree.defaultSelection, "认不出的视频宁可多选，不漏掉主体")
    }

    @Test
    fun `a pack of several codes keeps the torrent name for its folder`() {
        val files = listOf(
            MediaFileInput("ABC-123/ABC-123.mp4", 4L shl 30),
            MediaFileInput("XYZ-456/XYZ-456.mp4", 5L shl 30),
        )
        val tree = buildInstantTree(files, "精选合集")
        assertEquals("精选合集", tree.folderName)
        assertEquals(listOf("ABC-123", "XYZ-456"), tree.rows.map { it.label })
    }
}
