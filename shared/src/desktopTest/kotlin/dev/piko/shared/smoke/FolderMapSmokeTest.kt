package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.ArchiveBrowser
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.FolderMapLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 目录图的一层：文件夹在前，文件随后，压缩包是能展开的节点，分卷只是文件，Piko-Temp 不列；压缩包往下是包里的内容。 */
class FolderMapSmokeTest {

    @Test
    fun `levels list folders and archives and archives open into their folders`() = smoke { scope ->
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        server.addFolder("Piko-Temp")
        server.addFile("notes.txt")
        server.addFile("big.part1.rar", hash = "VOLUME")
        server.addArchive("pack.zip", mapOf("season 1/ep01.mkv" to ByteArray(4), "extras/a.txt" to ByteArray(1), "readme.txt" to ByteArray(1)))
        server.addArchive("locked.7z", mapOf("x/y.txt" to ByteArray(1)), password = "pw")
        val prefs = MemoryPreferences()
        val browser = ArchiveBrowser(server.provider(), prefs, scratchFolder = { Result.success("") })
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope, archives = browser)

        val root = assertIs<FolderMapLevel.Loaded>(drive.folderMapLevel(""))
        assertEquals(listOf("动画", "big.part1.rar", "locked.7z", "notes.txt", "pack.zip"), root.nodes.map { it.crumb.name })
        assertEquals(listOf(true, false, true, false, true), root.nodes.map { it.expandable })
        assertEquals(listOf(false, false, true, false, true), root.nodes.map { it.isArchive })
        assertEquals(anime.id, root.nodes.first().crumb.id)

        val pack = root.nodes.single { it.crumb.name == "pack.zip" }
        val inside = assertIs<FolderMapLevel.Loaded>(drive.folderMapLevel(pack.crumb.id))
        assertEquals(listOf("extras", "season 1", "readme.txt"), inside.nodes.map { it.crumb.name })
        assertEquals(listOf(true, true, false), inside.nodes.map { it.expandable })
        val season = assertIs<FolderMapLevel.Loaded>(drive.folderMapLevel(inside.nodes[1].crumb.id))
        assertEquals(listOf("ep01.mkv"), season.nodes.map { it.crumb.name })
        // 地址栏的 › 在包里只列文件夹
        assertEquals(listOf("extras", "season 1"), drive.subfoldersOf(pack.crumb.id).getOrThrow().map { it.name })

        assertEquals(FolderMapLevel.NeedsPassword, drive.folderMapLevel(root.nodes.single { it.crumb.name == "locked.7z" }.crumb.id))
    }
}
