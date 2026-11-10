package dev.piko.shared.state

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ContentStats
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.SortContext
import dev.piko.shared.data.SubtreeStats
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameParsingAndFoldersFirstTest {
    private class Preferences : PikoUserPreferences by MemoryPreferences() {
        override val nameParsingFlow = MutableStateFlow(true)
        override suspend fun setNameParsingEnabled(enabled: Boolean) { nameParsingFlow.value = enabled }
        override val foldersFirstFlow = MutableStateFlow(true)
        override suspend fun setFoldersFirst(enabled: Boolean) { foldersFirstFlow.value = enabled }
    }

    @Test
    fun disablingParsingRemovesEveryParsedCardView() = smoke { scope ->
        val server = FakePikPakServer()
        server.addFile("[SubGroup] Show - 01 [1080p].mkv")
        server.addFile("[SubGroup] Show - 02 [1080p].mkv")
        server.addFile("[xxx.com]ABC-123 片名.mp4")
        val prefs = Preferences()
        val state = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        state.load()
        awaitUntil("解析卡片就绪") {
            !state.isLoading && state.isDisplayStructureReady &&
                state.displayItems.filterIsInstance<DriveListItem.File>().count { it.view != null } == 3
        }
        prefs.setNameParsingEnabled(false)
        awaitUntil("原始文件名卡片就绪") {
            !state.isNameParsing && state.displayItems.filterIsInstance<DriveListItem.File>().size == 3
        }
        state.displayItems.filterIsInstance<DriveListItem.File>().forEach { item ->
            assertNull(item.view)
            assertNull(driveItemName(item.file, item.view, state.folderView(item.file)).title)
        }
    }

    @Test
    fun mixedTimeOrderSurvivesProjectionRefreshAndPreferenceChanges() = smoke { scope ->
        val server = FakePikPakServer()
        val pack = server.addFolder("My Pack", params = mapOf("piko_original_created_at" to "20"))
        val folder = server.addFolder("Show", params = mapOf("piko_original_created_at" to "30"))
        val newest = server.addFile("Show - 01.mkv", content = ByteArray(40), params = mapOf("piko_original_created_at" to "40", "duration" to "40"))
        val oldest = server.addFile("Show - 02.mkv", content = ByteArray(10), params = mapOf("piko_original_created_at" to "10", "duration" to "10"))
        val prefs = Preferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repository, prefs, scope)
        fun ids() = state.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id }
        state.load()
        awaitUntil("文件夹置顶列表就绪") {
            !state.isLoading && state.isDisplayStructureReady && ids().take(2) == listOf(pack.id, folder.id)
        }
        val mixed = listOf(newest.id, folder.id, pack.id, oldest.id)
        state.updateFoldersFirst(false)
        assertEquals(mixed, ids(), "切换后立即本地混排，不能被作品分组重新提走文件夹")
        awaitUntil("偏好持久化") { !prefs.foldersFirstFlow.value }
        state.load(refresh = true)
        awaitUntil("刷新保持混排") { !state.isRefreshing && state.isDisplayStructureReady && ids() == mixed }
        repository.driveTreeIndex.putFiles(listOf(
            FileStat(id = "pack-child", parentId = pack.id, name = "part.mkv", size = "60", params = mapOf("duration" to "20")),
            FileStat(id = "folder-child", parentId = folder.id, name = "part.mkv", size = "20", params = mapOf("duration" to "60")),
        ))
        for (order in listOf(PikoFileSortOrder.SIZE_DESC, PikoFileSortOrder.DURATION_DESC)) {
            state.changeSortOrder(order)
            val expected = if (order == PikoFileSortOrder.SIZE_DESC) listOf(pack.id, newest.id, folder.id, oldest.id)
                else listOf(folder.id, newest.id, pack.id, oldest.id)
            awaitUntil("递归大小与时长混排投影 $order") { ids() == expected && state.sortOrder == order }
        }
        prefs.setFoldersFirst(true)
        awaitUntil("同步偏好恢复文件夹置顶") { ids().take(2) == listOf(pack.id, folder.id) }
    }

    @Test
    fun mixedSortUsesRecursivePhysicalAndVaultedSizeAndDuration() {
        val repository = PikoDriveRepository(FakePikPakServer().provider())
        val pack = FileStat(id = "pack", name = "My Pack", kind = FileKind.FOLDER, createdTime = "2025-01-02T00:00:00Z")
        val folder = pack.copy(id = "folder", name = "Folder 2", createdTime = "2025-01-03T00:00:00Z")
        val newest = FileStat(id = "new", name = "Video 10.mkv", size = "40", params = mapOf("duration" to "40"), createdTime = "2025-01-04T00:00:00Z")
        val oldest = newest.copy(id = "old", name = "Video 2.mkv", size = "10", params = mapOf("duration" to "10"), createdTime = "2025-01-01T00:00:00Z")
        val files = listOf(oldest, pack, newest, folder)
        val context = SortContext(0, mapOf(
            pack.id to SubtreeStats(physical = ContentStats(bytes = 15, durationMillis = 15_000), vaulted = ContentStats(bytes = 5, durationMillis = 5_000)),
            folder.id to SubtreeStats(physical = ContentStats(bytes = 20, durationMillis = 20_000), vaulted = ContentStats(bytes = 10, durationMillis = 10_000)),
        ), emptyMap(), emptyMap(), emptyMap())
        for (order in listOf(PikoFileSortOrder.TIME_DESC, PikoFileSortOrder.SIZE_DESC, PikoFileSortOrder.DURATION_DESC)) {
            assertEquals(listOf(pack, folder, newest, oldest), repository.sortFiles(files, order, "", context))
            assertEquals(listOf(newest, folder, pack, oldest), repository.sortFiles(files, order, "", context, foldersFirst = false))
        }
        for (order in listOf(PikoFileSortOrder.TIME_ASC, PikoFileSortOrder.SIZE_ASC, PikoFileSortOrder.DURATION_ASC)) {
            assertEquals(listOf(oldest, pack, folder, newest), repository.sortFiles(files, order, "", context, foldersFirst = false))
        }
        assertEquals(listOf(folder, pack, oldest, newest), repository.sortFiles(files, PikoFileSortOrder.NAME_ASC, "", context, foldersFirst = false))
        assertEquals(listOf(newest, oldest, pack, folder), repository.sortFiles(files, PikoFileSortOrder.NAME_DESC, "", context, foldersFirst = false))
        val unknownVideo = newest.copy(id = "unknown", params = emptyMap())
        val emptyFolder = folder.copy(id = "empty")
        val text = newest.copy(id = "text", name = "notes.txt", params = emptyMap())
        val tiers = repository.sortFiles(listOf(text, emptyFolder, unknownVideo, folder, newest), PikoFileSortOrder.DURATION_DESC, "", context, foldersFirst = false)
        assertEquals(listOf(newest, folder, unknownVideo), tiers.take(3))
        assertTrue(tiers.drop(3).containsAll(listOf(emptyFolder, text)))
    }

    @Test
    fun perFolderSortRemembersEachFolderOrderIndependentlyWhenEnabled() = smoke { scope ->
        val server = FakePikPakServer()
        val folderA = server.addFolder("FolderA")
        val folderB = server.addFolder("FolderB")
        val folderC = server.addFolder("FolderC")
        server.addFile("b.txt", folderA.id, content = ByteArray(10))
        server.addFile("a.txt", folderA.id, content = ByteArray(100))
        server.addFile("small.txt", folderB.id, content = ByteArray(10))
        server.addFile("large.txt", folderB.id, content = ByteArray(500))
        server.addFile("c1.txt", folderC.id, content = ByteArray(10))
        server.addFile("c2.txt", folderC.id, content = ByteArray(20))

        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repo, prefs, scope)

        state.setPerFolderSortEnabled(true)
        assertTrue(state.perFolderSort)

        state.openFolder(folderA.id, folderA.name)
        awaitUntil("FolderA 加载完成") { !state.isLoading && state.loadedFolderId == folderA.id }
        state.changeSortOrder(PikoFileSortOrder.NAME_ASC)
        awaitUntil("FolderA 按名称升序排好") { state.displayedFiles.map { it.name } == listOf("a.txt", "b.txt") }

        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderB.id, folderB.name)))
        awaitUntil("FolderB 加载完成") { !state.isLoading && state.loadedFolderId == folderB.id }
        state.changeSortOrder(PikoFileSortOrder.SIZE_ASC)
        awaitUntil("FolderB 按大小升序排好") { state.displayedFiles.map { it.name } == listOf("small.txt", "large.txt") }

        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderC.id, folderC.name)))
        awaitUntil("FolderC 加载完成") { !state.isLoading && state.loadedFolderId == folderC.id }
        assertEquals(PikoFileSortOrder.TIME_DESC, state.sortOrder)

        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderA.id, folderA.name)))
        awaitUntil("切回 FolderA 恢复 NAME_ASC") { state.loadedFolderId == folderA.id && state.sortOrder == PikoFileSortOrder.NAME_ASC }
        assertEquals(listOf("a.txt", "b.txt"), state.displayedFiles.map { it.name })

        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderB.id, folderB.name)))
        awaitUntil("切回 FolderB 恢复 SIZE_ASC") { state.loadedFolderId == folderB.id && state.sortOrder == PikoFileSortOrder.SIZE_ASC }
        assertEquals(listOf("small.txt", "large.txt"), state.displayedFiles.map { it.name })
    }
}
