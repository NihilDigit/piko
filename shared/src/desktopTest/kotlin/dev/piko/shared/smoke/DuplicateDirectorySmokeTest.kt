package dev.piko.shared.smoke

import androidx.compose.runtime.snapshots.Snapshot

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateDirectoryListing
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DuplicateDirectorySmokeTest {
    @Test
    fun `two report views filter the same scan without changing each other`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("动画")
        val hd = server.addFile("Title.S01E03.1080p.mkv", folder.id, content = ByteArray(80), hash = "HD")
        val copy = server.addFile("Title.S01E03.1080p.copy.mkv", folder.id, content = ByteArray(80), hash = "HD")
        val sd = server.addFile("Title.S01E03.720p.mkv", folder.id, content = ByteArray(40), hash = "SD")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val finder = DuplicateFinderState(server.provider(), repo, scope, PikoPathBreadcrumb(folder.id, folder.name))
        awaitUntil("扫描完成") { finder.phase == DuplicateFinderState.Phase.DONE }
        val first = dev.piko.shared.presentation.DuplicateReportViewModel()
        val second = dev.piko.shared.presentation.DuplicateReportViewModel()
        val firstListing = first.listing(finder, folder.id)
        val secondListing = second.listing(finder, folder.id)
        val report = finder.report

        assertEquals(setOf(hd.id, copy.id), firstListing.files.map { it.id }.toSet())
        second.selectKind(DuplicateKind.VERSIONS)
        assertEquals(DuplicateKind.IDENTICAL, first.kind)
        assertEquals(setOf(hd.id, copy.id), firstListing.files.map { it.id }.toSet())
        assertTrue(sd.id in secondListing.files.map { it.id })
        assertTrue(sd.id !in firstListing.files.map { it.id })
        assertTrue(finder.report === report)
    }

    private fun directorySmoke(block: suspend (CoroutineScope) -> Unit) = smoke { parent ->
        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        withContext(dispatcher) { block(CoroutineScope(parent.coroutineContext + dispatcher)) }
    }
    private suspend fun awaitUntil(description: String, condition: () -> Boolean) =
        dev.piko.shared.smoke.awaitUntil(description) {
            Snapshot.sendApplyNotifications()
            condition()
        }
    @Test
    fun `results preserve original folders and only expose matching files`() = directorySmoke { scope ->
        val server = FakePikPakServer()
        val root = server.addFolder("media")
        val originalFolder = server.addFolder("original", root.id)
        val backups = server.addFolder("backups", root.id)
        val nested = server.addFolder("season", backups.id)
        val unrelated = server.addFolder("unrelated", root.id)
        val original = server.addFile("Title.S01E03.1080p.mkv", originalFolder.id, content = ByteArray(96), hash = "EP03")
        val copy = server.addFile("Title.S01E03.1080p (1).mkv", nested.id, content = ByteArray(96), hash = "EP03")
        val ordinary = server.addFile("ordinary.mkv", nested.id, content = ByteArray(32), hash = "UNIQUE")
        server.addFile("other.txt", unrelated.id, content = ByteArray(10), hash = "TEXT")
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { child, start -> DuplicateFinderState(server.provider(), repo, child, start) },
        )
        val view = dev.piko.shared.presentation.DuplicateReportViewModel()
        val drive = DriveScreenState(repo, prefs, scope, { library ->
            session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let { finder ->
                val folder = repoWorkspace.folderStackFlow.value.last().id
                DuplicateDirectoryListing(finder, if (folder == library.id) finder.root.id else folder)
            }
        }, navigation = repoWorkspace)
        session.open(PikoPathBreadcrumb(root.id, root.name))
        repoWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("完成嵌套扫描") { session.state?.phase == DuplicateFinderState.Phase.DONE }
        assertEquals(setOf(originalFolder.id, backups.id), session.state!!.directoryFiles(root.id).map { it.id }.toSet())
        awaitUntil("筛选后的顶层目录") { drive.files.map { it.id }.toSet() == setOf(originalFolder.id, backups.id) }
        val listed = server.calls.count { "GET /drive/v1/files" == it }
        drive.toggleSelectAll()
        assertTrue(drive.selectedFileIds.isEmpty())
        drive.enterSelection(backups.id)
        assertFalse(drive.isSelectionMode)
        drive.moveToTrash(listOf(backups.id))
        assertFalse(backups.trashed)
        assertEquals(setOf(originalFolder.id, backups.id), drive.subfoldersOf(DriveLibrary.DUPLICATES.id).getOrThrow().map { it.id }.toSet())

        drive.openFolder(backups.id, backups.name)
        awaitUntil("保留中间目录") { drive.files.singleOrNull()?.id == nested.id }
        assertEquals(DriveLibrary.DUPLICATES, drive.libraryView)
        drive.openFolder(nested.id, nested.name)
        awaitUntil("叶目录只显示命中文件") { drive.files.singleOrNull()?.id == copy.id && drive.isDisplayStructureReady }
        assertTrue(drive.displayItems.none { it is DriveListItem.SectionHeader && it.label.contains("份相同") })
        assertEquals(listOf(copy.id), drive.displayedFiles.map { it.id })
        assertEquals(listed, server.calls.count { "GET /drive/v1/files" == it }, "进入查重目录不应重新请求普通目录内容")

        drive.moveToTrash(listOf(copy.id))
        awaitUntil("重复关系消失") { drive.files.isEmpty() && copy.trashed }
        assertFalse(original.trashed)
        assertFalse(ordinary.trashed)
        awaitUntil("删除记录") { repo.changes.latest.value != null }
        assertTrue(drive.undoLast())
        awaitUntil("撤销恢复筛选结果") { drive.files.singleOrNull()?.id == copy.id }
        drive.navigateUp()
        awaitUntil("返回上级仍是筛选目录") { drive.files.singleOrNull()?.id == nested.id }
        assertEquals(DriveLibrary.DUPLICATES, drive.libraryView)
    }

    @Test
    fun `version files remain independently selectable with normal name parsing`() = directorySmoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Title")
        val hd = server.addFile("Title.S01E03.1080p.mkv", folder.id, content = ByteArray(96), hash = "HD")
        val sd = server.addFile("Title.S01E03.720p.mkv", folder.id, content = ByteArray(40), hash = "SD")
        val unrelated = server.addFile("Title.S01E04.1080p.mkv", folder.id, content = ByteArray(80), hash = "EP04")
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { scope },
            newState = { child, start -> DuplicateFinderState(server.provider(), repo, child, start) },
        )
        val view = dev.piko.shared.presentation.DuplicateReportViewModel()
        val drive = DriveScreenState(repo, prefs, scope, { library ->
            session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let { view.listing(it, folder.id) }
        }, navigation = repoWorkspace)
        session.open(PikoPathBreadcrumb(folder.id, folder.name))
        repoWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("完成扫描") { session.state?.phase == DuplicateFinderState.Phase.DONE }
        view.selectKind(DuplicateKind.VERSIONS)
        awaitUntil("两个版本都可见") { drive.isDisplayStructureReady && drive.displayedFiles.map { it.id }.toSet() == setOf(hd.id, sd.id) }
        assertEquals(setOf(hd.id, sd.id), drive.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id }.toSet())
        assertTrue(drive.files.none { it.id == unrelated.id })
        drive.toggleSelectAll()
        assertEquals(setOf(hd.id, sd.id), drive.selectedFileIds.toSet())
    }
}
