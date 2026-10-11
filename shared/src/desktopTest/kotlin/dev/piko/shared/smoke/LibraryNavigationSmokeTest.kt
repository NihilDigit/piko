package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.presentation.LibraryNavigation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.TrashSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import io.ktor.client.plugins.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryNavigationSmokeTest {
    @Test
    fun `library browsing preserves duplicate path guard history and scroll`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("收藏")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val original = listOf(DriveLibrary.DUPLICATES.crumb, PikoPathBreadcrumb(folder.id, folder.name))
        repoWorkspace.updateFolderStack(original)
        repoWorkspace.saveScrollAnchor(folder.id, ScrollAnchor(27, 12))
        val history = repoWorkspace.historyFlow.value
        var guardCalls = 0
        repoWorkspace.leaveGuard = dev.piko.shared.presentation.DriveWorkspace.LeaveGuard { _, _ -> guardCalls++; false }
        val starred = LibraryNavigation(repo, DriveLibrary.STARRED, scope)
        val trash = LibraryNavigation(repo, DriveLibrary.TRASH, scope)
        starred.pushFolder(folder.id, folder.name)
        starred.saveScrollAnchor(folder.id, ScrollAnchor(3, 4))
        assertEquals(original, repoWorkspace.folderStackFlow.value)
        assertEquals(history, repoWorkspace.historyFlow.value)
        assertEquals(0, guardCalls)
        assertEquals(ScrollAnchor(27, 12), repoWorkspace.scrollAnchor(folder.id))
        assertEquals(ScrollAnchor(3, 4), starred.scrollAnchor(folder.id))
        assertEquals(listOf(DriveLibrary.TRASH.crumb), trash.folderStackFlow.value)
        assertTrue(starred.goBack())
        assertEquals(listOf(DriveLibrary.STARRED.crumb), starred.folderStackFlow.value)
        assertTrue(starred.goForward())
        assertEquals(folder.id, starred.folderStackFlow.value.last().id)
        starred.close()
        trash.close()
    }

    @Test
    fun `cleanup survives the originating page and another library can restore it`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = (1..250).map { server.addFile("file-$it.mkv", content = byteArrayOf(1)) }
        val started = Channel<Unit>(Channel.UNLIMITED)
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val http = server.httpClient()
        http.plugin(io.ktor.client.plugins.HttpSend).intercept { request ->
            if (request.url.toString().contains(":batchTrash")) { started.send(Unit); permits.receive() }
            execute(request)
        }
        val client = io.github.nihildigit.pikpak.PikPakClient(
            account = "smoke@piko.dev", password = "pw",
            sessionStore = io.github.nihildigit.pikpak.InMemorySessionStore(),
            rateLimiter = io.github.nihildigit.pikpak.RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val driveRepo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val cleanup = TrashSession(driveRepo, scope)
        val pageScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val page = DriveScreenState(driveRepo, MemoryPreferences(), pageScope, trashSession = cleanup)
        page.moveToTrash(files.map { it.id })
        started.receive()
        pageScope.cancel()
        val library = LibraryNavigation(driveRepo, DriveLibrary.TRASH, scope)
        assertEquals(0, cleanup.progress!!.completed)
        repeat(3) { permits.send(Unit) }
        awaitUntil("background cleanup") { cleanup.progress == null && files.all { it.trashed } }
        assertEquals(250, cleanup.completedCount)
        cleanup.undo()
        awaitUntil("whole cleanup restored") { files.none { it.trashed } && cleanup.completedCount == 0 }
        assertEquals(listOf(DriveLibrary.TRASH.crumb), library.folderStackFlow.value)
        library.close()
    }
}
