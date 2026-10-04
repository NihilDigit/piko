package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 查找重复的结果是网盘页里的一个位置：建议移走的预先勾上，删除与撤销都走网盘页的那一套，
 * 结果跟着撤销日志重新分组，不必重扫。
 */
class DuplicatesLocationSmokeTest {

    @Test
    fun `trashing from the duplicates location regroups and undo brings the copies back`() = smoke { scope ->
        val server = FakePikPakServer()
        val content = ByteArray(64) { 7 }
        val original = server.addFile("a.mkv", content = content, hash = "GCID")
        val copy = server.addFile("a (1).mkv", content = content, hash = "GCID")
        val nested = server.addFile("a.mkv", parentId = server.addFolder("sub").id, content = content, hash = "GCID")
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { sessionScope, root -> DuplicateFinderState(server.provider(), repository, sessionScope, root) },
        )
        val drive = DriveScreenState(repository, prefs, scope, session)

        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repository.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("扫完并列出") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.displayedFiles.size == 3 }

        // 名字里没有副本标记、存入最早的那份留着，另外两份预先勾上
        awaitUntil("建议移走的已勾上") { drive.isSelectionMode && drive.selectedFileIds.toSet() == setOf(copy.id, nested.id) }
        assertTrue(original.id !in drive.selectedFileIds)
        val keys = drive.displayItems.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "列表项的 key 不能重复")

        drive.moveToTrash(drive.selectedFileIds.toList())
        awaitUntil("移入回收站") { copy.trashed && nested.trashed }
        awaitUntil("只剩一份，这一组散了") { drive.displayItems.none { it is DriveListItem.File } }
        awaitUntil("移入回收站记下") { repository.changes.latest.value is DriveChangeJournal.Change.Trash }

        assertTrue(drive.undoLast())
        awaitUntil("撤销后三份都回来") { drive.displayedFiles.map { it.id }.toSet() == setOf(original.id, copy.id, nested.id) }
    }
}
