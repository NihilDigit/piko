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
 * 查找重复的结果是网盘页里的一个位置：建议移走的一键选中，删除与撤销都走网盘页的那一套，
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

        // 进来时不在多选里，单击照常打开；名字里没有副本标记、存入最早的那份留着，选中的是另外两份
        assertTrue(!drive.isSelectionMode)
        drive.selectSuggestedDuplicates()
        assertEquals(setOf(copy.id, nested.id), drive.selectedFileIds.toSet())
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

    /**
     * 选中的是文件，界面交来的是行：行的 key 带着组，不是文件 ID。把它当文件 ID 存下，「已选 2 项」却一个勾也不亮，
     * 删除时交给服务端的也不是文件。同一个文件占两行时，选中它两行都勾上；Shift 连选按行数，从点过的那一行起。
     */
    @Test
    fun `selection on the duplicates location works on files while gestures name rows`() = smoke { scope ->
        val server = FakePikPakServer()
        val episode = ByteArray(96) { 3 }
        val anime = server.addFolder("anime")
        // 留下的那份既在完全相同的组里，又代表版本组的一行：一个文件两行
        val kept = server.addFile("[Group] Title - 03 [1080p].mkv", parentId = anime.id, content = episode, hash = "EP03")
        server.addFile("[Group] Title - 04 [1080p].mkv", parentId = anime.id, content = ByteArray(80) { 4 }, hash = "EP04")
        val copy = server.addFile("[Group] Title - 03 [1080p] (1).mkv", parentId = server.addFolder("backup").id, content = episode, hash = "EP03")
        val scene = server.addFile("Title.S01E03.720p.mkv", parentId = server.addFolder("tv").id, content = ByteArray(40) { 5 }, hash = "SCENE")
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { sessionScope, root -> DuplicateFinderState(server.provider(), repository, sessionScope, root) },
        )
        val drive = DriveScreenState(repository, prefs, scope, session)

        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repository.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("扫完并分成两组") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.sectionHeaders.size == 2 }
        val (identical, versions) = rowsBySection(drive.displayItems)
        assertEquals(listOf(kept.id, copy.id), identical.map { it.file.id })
        // 版本组按大小从大到小，留下的那份在前
        assertEquals(listOf(kept.id, scene.id), versions.map { it.file.id })
        fun checked() = drive.displayItems.filterIsInstance<DriveListItem.File>().filter { it.file.id in drive.selectedFileIds }.map { it.key }

        // 截图里的情形：框住一组的两张卡片，两份都选上、两张都勾上
        drive.selectBoxed(emptySet(), identical.map { it.key })
        assertEquals(setOf(kept.id, copy.id), drive.selectedFileIds.toSet())
        assertEquals(identical.map { it.key } + versions.first().key, checked())

        // 只框住它的一行，选中的仍是这一个文件，另一组里的那一行一起勾上
        drive.selectBoxed(emptySet(), listOf(versions.first().key))
        assertEquals(listOf(kept.id), drive.selectedFileIds.toList())
        assertEquals(listOf(identical.first().key, versions.first().key), checked())

        // Shift 连选按眼前的行：从副本那一行到版本组的最后一行，中间版本组里留下的那份也在内
        drive.exitSelection()
        drive.toggleSelected(identical[1].key)
        drive.selectRange(versions[1].key)
        assertEquals(setOf(copy.id, kept.id, scene.id), drive.selectedFileIds.toSet())

        // 收起的组与网盘页收起的分区一样，全选照样算在内
        drive.toggleSection(drive.sectionHeaders.first().value.blockId)
        awaitUntil("第一组收起") { rowsBySection(drive.displayItems).first().isEmpty() }
        assertEquals(setOf(kept.id, copy.id, scene.id), drive.displayedFiles.map { it.id }.toSet())

        // 移入回收站交出去的是文件 ID
        drive.exitSelection()
        drive.selectBoxed(emptySet(), listOf(versions[1].key))
        drive.moveToTrash(drive.selectedFileIds.toList())
        awaitUntil("移入回收站") { scene.trashed }
    }

    private fun rowsBySection(items: List<DriveListItem>): List<List<DriveListItem.File>> {
        val sections = mutableListOf<MutableList<DriveListItem.File>>()
        items.forEach { item ->
            when (item) {
                is DriveListItem.SectionHeader -> sections += mutableListOf<DriveListItem.File>()
                is DriveListItem.File -> sections.last() += item
                is DriveListItem.WorkHeader -> Unit
            }
        }
        return sections
    }
}
