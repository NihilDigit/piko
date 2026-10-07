package dev.piko.shared.smoke

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.DriveScreenState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网盘里改了名、移走、删掉文件夹之后，各处存着的路径（栈、别的标签、浏览历史、最近去过、快速访问）跟着变，
 * 目录图与地址栏 › 读到的是改动之后的列表。
 */
class FolderLocationSmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB

    private fun PikoDriveRepository.names() = folderStackFlow.value.map { it.name }

    @Test
    fun `renaming a folder renames it in every stored path`() = smoke {
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val show = server.addFolder("Frieren", anime.id)
        val pack = server.addArchive("extras.zip", mapOf("a.txt" to ByteArray(1)), parentId = show.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val animeCrumb = PikoPathBreadcrumb(anime.id, anime.name)
        val showCrumb = PikoPathBreadcrumb(show.id, show.name)
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repo.listAllFiles(show.id).getOrThrow()

        // 一个标签停在压缩包里，另一个停在动画里，历史里还有一步
        repo.updateFolderStack(listOf(root, animeCrumb))
        repo.updateFolderStack(listOf(root, animeCrumb, showCrumb, PikoPathBreadcrumb(ArchiveLocation(pack.node.id, pack.node.hash, "").id, pack.node.name)))
        val other = repo.openTab(listOf(root, animeCrumb), activate = false)

        repo.rename(anime.id, "Anime").getOrThrow()
        repo.rename(pack.node.id, "特典.zip").getOrThrow()

        assertEquals(listOf("网盘", "Anime", "Frieren", "特典.zip"), repo.names())
        assertEquals(listOf("网盘", "Anime"), repo.historyFlow.value.back.last().map { it.name })
        assertEquals(listOf("网盘", "Anime"), repo.tabsFlow.value.first { it.id == other }.stack.map { it.name })
        assertEquals("Anime", repo.tabsFlow.value.first { it.id == other }.title)
        assertTrue(repo.recentFoldersFlow.value.all { stack -> stack.none { it.name == "动画" } }, "最近去过里不该留着旧名")
        assertTrue(repo.recentFoldersFlow.value.any { stack -> stack.last().id == anime.id }, "改名不是删除，最近去过里照留")
    }

    @Test
    fun `a folder moved elsewhere takes its new parents into the stack`() = smoke {
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val archive = server.addFolder("归档")
        val show = server.addFolder("Frieren", anime.id)
        val season = server.addFolder("S1", show.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repo.updateFolderStack(
            listOf(root, PikoPathBreadcrumb(anime.id, anime.name), PikoPathBreadcrumb(show.id, show.name), PikoPathBreadcrumb(season.id, season.name)),
        )

        repo.move(listOf(show.id), archive.id).getOrThrow()

        assertEquals(listOf("", archive.id, show.id, season.id), repo.folderStackFlow.value.map { it.id })
        assertEquals(listOf("网盘", "归档", "Frieren", "S1"), repo.names())
    }

    @Test
    fun `trashing a folder takes other tabs out of it`() = smoke {
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val show = server.addFolder("Frieren", anime.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repo.updateFolderStack(listOf(root, PikoPathBreadcrumb(anime.id, anime.name), PikoPathBreadcrumb(show.id, show.name)))
        val inside = repo.activeTabId.value
        repo.openTab(listOf(root))

        repo.trash(listOf(anime.id)).getOrThrow()

        assertEquals(listOf(root), repo.tabsFlow.value.first { it.id == inside }.stack)
        assertTrue(repo.recentFoldersFlow.value.none { stack -> stack.any { it.id == anime.id } }, "删掉的文件夹不再列在最近去过里")
    }

    @Test
    fun `pinned folders show the name the drive has now`() = smoke {
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val pins = MutableStateFlow("""[{"id":"${anime.id}","name":"动画"}]""")
        val prefs = object : PikoUserPreferences by MemoryPreferences() {
            override val pinnedFoldersFlow = pins
            override suspend fun savePinnedFolders(serialized: String) {
                pins.value = serialized
            }
        }
        val repo = PikoDriveRepository(server.provider(), prefs)
        // 在别的设备上改的名：只有列到根目录时才知道
        server.node(anime.id)!!.name = "Anime"
        repo.listAllFiles("").getOrThrow()
        assertEquals(listOf("Anime"), repo.pinnedFoldersFlow.first().map { it.name })
    }

    @Test
    fun `changes reach the open folder, the folder map and the chevron menu`() = smoke { scope ->
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        server.addFolder("Frieren", anime.id)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repo, prefs, scope)
        repo.updateFolderStack(listOf(root, PikoPathBreadcrumb(anime.id, anime.name)))
        awaitUntil("列出动画") { !drive.isLoading && drive.files.map { it.name } == listOf("Frieren") }
        // 目录图与 › 先读到改动之前的一份，它在路径上，缓存一直留着
        assertEquals(listOf("Frieren"), repo.folderMapLevel(anime.id).getOrThrow().map { it.name })

        val changes = scope.async(start = CoroutineStart.UNDISPATCHED) { repo.folderChanges.take(1).toList() }
        val created = repo.createFolder(anime.id, "SPs").getOrThrow()

        val change = changes.await().single()
        assertTrue(change is DriveChange.Created && change.id == created && change.affects(anime.id) && !change.affects(""))
        awaitUntil("网盘页自己重列") { drive.files.any { it.id == created } }
        assertEquals(listOf("Frieren", "SPs"), repo.folderMapLevel(anime.id).getOrThrow().map { it.name })
        assertEquals(listOf("Frieren", "SPs"), repo.subfolders(anime.id).getOrThrow().map { it.name })
    }

    @Test
    fun `a library has no subfolders to ask the server for`() = smoke {
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        assertEquals(emptyList(), repo.subfolders(DriveLibrary.STARRED.id).getOrThrow())
        assertTrue(server.calls.none { it.startsWith("GET") && it.endsWith("/drive/v1/files") }, "库的 ID 不该拿去列目录")
    }

}
