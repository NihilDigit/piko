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

class FolderLocationSmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB

    private fun dev.piko.shared.presentation.DriveWorkspace.names() = folderStackFlow.value.map { it.name }

    @Test
    fun `renaming a folder renames it in every stored path`() = smoke { scope ->
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val show = server.addFolder("Frieren", anime.id)
        val pack = server.addArchive("extras.zip", mapOf("a.txt" to ByteArray(1)), parentId = show.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val animeCrumb = PikoPathBreadcrumb(anime.id, anime.name)
        val showCrumb = PikoPathBreadcrumb(show.id, show.name)
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repo.listAllFiles(show.id).getOrThrow()

        repoWorkspace.updateFolderStack(listOf(root, animeCrumb))
        repoWorkspace.updateFolderStack(listOf(root, animeCrumb, showCrumb, PikoPathBreadcrumb(ArchiveLocation(pack.node.id, pack.node.hash, "").id, pack.node.name)))
        val other = repoWorkspace.openTab(listOf(root, animeCrumb), activate = false)

        repo.rename(anime.id, "Anime").getOrThrow()
        repo.rename(pack.node.id, "特典.zip").getOrThrow()

        assertEquals(listOf("我的网盘", "Anime", "Frieren", "特典.zip"), repoWorkspace.names())
        assertEquals(listOf("我的网盘", "Anime"), repoWorkspace.historyFlow.value.back.last().map { it.name })
        assertEquals(listOf("我的网盘", "Anime"), repoWorkspace.tabsFlow.value.first { it.id == other }.stack.map { it.name })
        assertEquals("Anime", repoWorkspace.tabsFlow.value.first { it.id == other }.title)
        assertTrue(repoWorkspace.recentFoldersFlow.value.all { stack -> stack.none { it.name == "动画" } })
        assertTrue(repoWorkspace.recentFoldersFlow.value.any { stack -> stack.last().id == anime.id })
    }

    @Test
    fun `a folder moved elsewhere takes its new parents into the stack`() = smoke { scope ->
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val archive = server.addFolder("归档")
        val show = server.addFolder("Frieren", anime.id)
        val season = server.addFolder("S1", show.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repoWorkspace.updateFolderStack(
            listOf(root, PikoPathBreadcrumb(anime.id, anime.name), PikoPathBreadcrumb(show.id, show.name), PikoPathBreadcrumb(season.id, season.name)),
        )

        repo.move(listOf(show.id), archive.id).getOrThrow()

        assertEquals(listOf("", archive.id, show.id, season.id), repoWorkspace.folderStackFlow.value.map { it.id })
        assertEquals(listOf("我的网盘", "归档", "Frieren", "S1"), repoWorkspace.names())
    }

    @Test
    fun `trashing a folder takes other tabs out of it`() = smoke { scope ->
        val server = FakePikPakServer()
        val anime = server.addFolder("动画")
        val show = server.addFolder("Frieren", anime.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(anime.id).getOrThrow()
        repoWorkspace.updateFolderStack(listOf(root, PikoPathBreadcrumb(anime.id, anime.name), PikoPathBreadcrumb(show.id, show.name)))
        val inside = repoWorkspace.activeTabId.value
        repoWorkspace.openTab(listOf(root))

        repo.trash(listOf(anime.id)).getOrThrow()

        assertEquals(listOf(root), repoWorkspace.tabsFlow.value.first { it.id == inside }.stack)
        assertTrue(repoWorkspace.recentFoldersFlow.value.none { stack -> stack.any { it.id == anime.id } })
    }

    @Test
    fun `pinned folders show the name the drive has now`() = smoke { scope ->
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
        dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
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
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val drive = DriveScreenState(repo, prefs, scope, navigation = repoWorkspace)
        repoWorkspace.updateFolderStack(listOf(root, PikoPathBreadcrumb(anime.id, anime.name)))
        awaitUntil("列出动画") { !drive.isLoading && drive.files.map { it.name } == listOf("Frieren") }
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
