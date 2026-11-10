package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateListing
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.SessionListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicatesLocationSmokeTest {

    @Test
    fun `switching result category replaces visible files and selection scope`() = smoke { scope ->
        val server = FakePikPakServer()
        val kept = server.addFile("Title.S01E03.1080p.mkv", content = ByteArray(96), hash = "EP03")
        val copy = server.addFile("Title.S01E03.1080p (1).mkv", content = ByteArray(96), hash = "EP03")
        val version = server.addFile("Title.S01E03.720p.mkv", content = ByteArray(40), hash = "OTHER")
        val prefs = MemoryPreferences()
        val clients = server.provider()
        val repository = PikoDriveRepository(clients, prefs)
        val repositoryWorkspace = dev.piko.shared.presentation.DriveWorkspace(repository, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { child, root -> DuplicateFinderState(clients, repository, child, root) },
        )
        val view = dev.piko.shared.presentation.DuplicateReportViewModel()
        val drive = DriveScreenState(repository, prefs, scope, { library ->
            session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let { DuplicateListing(it, view.kind) }
        }, navigation = repositoryWorkspace)
        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repositoryWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("相同文件") { drive.displayedFiles.map { it.id }.toSet() == setOf(kept.id, copy.id) }
        drive.toggleSelectAll()
        assertEquals(setOf(kept.id, copy.id), drive.selectedFileIds.toSet())

        drive.exitSelection()
        view.selectKind(DuplicateKind.VERSIONS)
        awaitUntil("不同版本") { drive.displayedFiles.map { it.id }.toSet() == setOf(kept.id, version.id) }
        assertTrue(!drive.isSelectionMode)
        drive.toggleSelectAll()
        assertEquals(setOf(kept.id, version.id), drive.selectedFileIds.toSet())

        drive.exitSelection()
        view.selectKind(DuplicateKind.IDENTICAL)
        awaitUntil("切回相同文件") { drive.displayedFiles.map { it.id }.toSet() == setOf(kept.id, copy.id) }
        assertEquals(1, drive.sectionHeaders.size)
    }

    @Test
    fun `trashing from the duplicates location regroups and undo brings the copies back`() = smoke { scope ->
        val server = FakePikPakServer()
        val content = ByteArray(64) { 7 }
        val original = server.addFile("a.mkv", content = content, hash = "GCID")
        val copy = server.addFile("a (1).mkv", content = content, hash = "GCID")
        val nested = server.addFile("a.mkv", parentId = server.addFolder("sub").id, content = content, hash = "GCID")
        val prefs = MemoryPreferences()
        val clients = server.provider()
        val repository = PikoDriveRepository(clients, prefs)
        val repositoryWorkspace = dev.piko.shared.presentation.DriveWorkspace(repository, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { sessionScope, root -> DuplicateFinderState(clients, repository, sessionScope, root) },
        )
        val drive = DriveScreenState(repository, prefs, scope, listingOf(session), navigation = repositoryWorkspace)

        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repositoryWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("扫完并列出") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.displayedFiles.size == 3 }

        assertTrue(!drive.isSelectionMode)
        drive.selectOnly(session.state!!.suggestedIds)
        assertEquals(setOf(copy.id, nested.id), drive.selectedFileIds.toSet())
        assertTrue(original.id !in drive.selectedFileIds)
        val keys = drive.displayItems.map { it.key }
        assertEquals(keys.size, keys.toSet().size)

        drive.moveToTrash(drive.selectedFileIds.toList())
        awaitUntil("移入回收站") { copy.trashed && nested.trashed }
        awaitUntil("分组更新") { drive.displayItems.none { it is DriveListItem.File } }
        awaitUntil("撤销记录写入") { repository.changes.latest.value is DriveChangeJournal.Change.Trash }

        assertTrue(drive.undoLast())
        awaitUntil("撤销恢复") { drive.displayedFiles.map { it.id }.toSet() == setOf(original.id, copy.id, nested.id) }
    }

    @Test
    fun `selection on the duplicates location works on files while gestures name rows`() = smoke { scope ->
        val server = FakePikPakServer()
        val episode = ByteArray(96) { 3 }
        val anime = server.addFolder("anime")
        val kept = server.addFile("[Group] Title - 03 [1080p].mkv", parentId = anime.id, content = episode, hash = "EP03")
        server.addFile("[Group] Title - 04 [1080p].mkv", parentId = anime.id, content = ByteArray(80) { 4 }, hash = "EP04")
        val copy = server.addFile("[Group] Title - 03 [1080p] (1).mkv", parentId = server.addFolder("backup").id, content = episode, hash = "EP03")
        val scene = server.addFile("Title.S01E03.720p.mkv", parentId = server.addFolder("tv").id, content = ByteArray(40) { 5 }, hash = "SCENE")
        val prefs = MemoryPreferences()
        val clients = server.provider()
        val repository = PikoDriveRepository(clients, prefs)
        val repositoryWorkspace = dev.piko.shared.presentation.DriveWorkspace(repository, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { sessionScope, root -> DuplicateFinderState(clients, repository, sessionScope, root) },
        )
        val drive = DriveScreenState(repository, prefs, scope, listingOf(session), navigation = repositoryWorkspace)

        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repositoryWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("扫完并分成两组") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.sectionHeaders.size == 2 }
        val (identical, versions) = rowsBySection(drive.displayItems)
        assertEquals(listOf(kept.id, copy.id), identical.map { it.file.id })
        assertEquals(listOf(kept.id, scene.id), versions.map { it.file.id })
        fun checked() = drive.displayItems.filterIsInstance<DriveListItem.File>().filter { it.file.id in drive.selectedFileIds }.map { it.key }

        drive.selectBoxed(emptySet(), identical.map { it.key })
        assertEquals(setOf(kept.id, copy.id), drive.selectedFileIds.toSet())
        assertEquals(identical.map { it.key } + versions.first().key, checked())

        drive.selectBoxed(emptySet(), listOf(versions.first().key))
        assertEquals(listOf(kept.id), drive.selectedFileIds.toList())
        assertEquals(listOf(identical.first().key, versions.first().key), checked())

        drive.exitSelection()
        drive.toggleSelected(identical[1].key)
        drive.selectRange(versions[1].key)
        assertEquals(setOf(copy.id, kept.id, scene.id), drive.selectedFileIds.toSet())

        drive.toggleSection(drive.sectionHeaders.first().value.blockId)
        awaitUntil("第一组收起") { rowsBySection(drive.displayItems).first().isEmpty() }
        assertEquals(setOf(kept.id, copy.id, scene.id), drive.displayedFiles.map { it.id }.toSet())

        drive.exitSelection()
        drive.selectBoxed(emptySet(), listOf(versions[1].key))
        drive.moveToTrash(drive.selectedFileIds.toList())
        awaitUntil("移入回收站") { scene.trashed }
    }

    @Test
    fun `two report views filter the same scan without changing each other`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("动画")
        val hd = server.addFile("Title.S01E03.1080p.mkv", folder.id, content = ByteArray(80), hash = "HD")
        val copy = server.addFile("Title.S01E03.1080p.copy.mkv", folder.id, content = ByteArray(80), hash = "HD")
        val sd = server.addFile("Title.S01E03.720p.mkv", folder.id, content = ByteArray(40), hash = "SD")
        val clients = server.provider()
        val repo = PikoDriveRepository(clients, MemoryPreferences())
        val finder = DuplicateFinderState(clients, repo, scope, PikoPathBreadcrumb(folder.id, folder.name))
        awaitUntil("扫描完成") { finder.phase == DuplicateFinderState.Phase.DONE }
        val first = dev.piko.shared.presentation.DuplicateReportViewModel()
        val second = dev.piko.shared.presentation.DuplicateReportViewModel()
        val firstListing = first.listing(finder)
        val secondListing = second.listing(finder)
        val report = finder.report

        assertEquals(setOf(hd.id, copy.id), firstListing.files.map { it.id }.toSet())
        second.selectKind(DuplicateKind.VERSIONS)
        assertEquals(DuplicateKind.IDENTICAL, first.kind)
        assertEquals(setOf(hd.id, copy.id), firstListing.files.map { it.id }.toSet())
        assertTrue(sd.id in secondListing.files.map { it.id })
        assertTrue(sd.id !in firstListing.files.map { it.id })
        assertTrue(finder.report === report)
    }

    private fun listingOf(session: DuplicateSession): (DriveLibrary) -> SessionListing? = { library ->
        session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let(::DuplicateListing)
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
