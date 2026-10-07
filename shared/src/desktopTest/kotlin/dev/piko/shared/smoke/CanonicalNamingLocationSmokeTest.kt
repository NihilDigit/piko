package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.CanonicalListing
import dev.piko.shared.state.CanonicalNamingState
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.FolderTaskSession
import dev.piko.shared.rename.RenameProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 按番号规范命名一个文件夹：扫描出改名建议、在网盘页的位置里按作品列出，应用走改名与撤销的那一套，
 * 改成了的从建议里消失，撤销回来又出现；与目录里原有名字撞上的标出来、不能应用。
 */
class CanonicalNamingLocationSmokeTest {

    @Test
    fun `suggestions are applied, drop out, and come back on undo`() = smoke { scope ->
        val server = FakePikPakServer()
        val library = server.addFolder("Library")
        val resource = server.addFolder("[site.net] abc-123 某片名", library.id)
        val part1 = server.addFile("abc00123hhb1.mp4", resource.id, hash = "P1")
        val part2 = server.addFile("abc00123hhb2.mp4", resource.id, hash = "P2")
        val clashing = server.addFile("xyz00456.mp4", library.id, hash = "X")
        server.addFile("XYZ-456.mp4", library.id, hash = "OLD")
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val session = FolderTaskSession(
            newScope = { scope },
            newState = { sessionScope, root -> CanonicalNamingState(server.provider(), repository, null, sessionScope, root) },
        )
        val drive = DriveScreenState(repository, prefs, scope, { place ->
            session.state?.takeIf { place == DriveLibrary.CANONICAL_NAMES }?.let(::CanonicalListing)
        })

        session.open(PikoPathBreadcrumb(library.id, library.name))
        repository.updateFolderStack(listOf(DriveLibrary.CANONICAL_NAMES.crumb))
        val naming = session.state!!
        awaitUntil("扫完并列出建议") { naming.phase == CanonicalNamingState.Phase.DONE && drive.displayedFiles.size == 4 }

        val titles = drive.displayItems.filterIsInstance<DriveListItem.File>().associate { it.file.id to it.view?.title }
        assertEquals(
            mapOf(resource.id to "ABC-123 某片名", part1.id to "ABC-123-CD1.mp4", part2.id to "ABC-123-CD2.mp4", clashing.id to "XYZ-456.mp4"),
            titles,
        )
        assertEquals(RenameProblem.TAKEN, naming.suggestion(clashing.id)?.problem)
        assertEquals(setOf(resource.id, part1.id, part2.id), naming.applicableIds, "撞名的不能应用")
        assertTrue(!drive.isSelectionMode, "进来时不在多选里")

        // 全选再应用：撞名的那一项跳过
        drive.toggleSelectAll()
        naming.apply(drive.selectedFileIds.toSet())
        awaitUntil("改名完成") { server.node(part2.id)?.name == "ABC-123-CD2.mp4" && !naming.isApplying }
        assertEquals("ABC-123 某片名", server.node(resource.id)?.name)
        assertEquals("xyz00456.mp4", server.node(clashing.id)?.name)
        awaitUntil("改好的从建议里消失") { drive.displayedFiles.map { it.id } == listOf(clashing.id) }

        assertTrue(drive.undoLast())
        awaitUntil("撤销后建议回来") { drive.displayedFiles.size == 4 }
        assertEquals("abc00123hhb1.mp4", server.node(part1.id)?.name)
    }
}
