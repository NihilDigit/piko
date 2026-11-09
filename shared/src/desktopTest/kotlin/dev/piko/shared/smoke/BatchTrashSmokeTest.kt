package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateListing
import dev.piko.shared.state.DuplicateSession
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchTrashSmokeTest {
    private class Fixture(val server: FakePikPakServer, val nodes: List<FakePikPakServer.Node>, val repo: PikoDriveRepository, val drive: DriveScreenState)

    private suspend fun fixture(scope: CoroutineScope, copies: Int, beforeTrash: suspend () -> Unit): Fixture {
        val server = FakePikPakServer()
        val nodes = (0..copies).map { server.addFile("copy-$it.mkv", content = byteArrayOf(7), hash = "SAME") }
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.toString().contains(":batchTrash")) beforeTrash()
            execute(request)
        }
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        val provider = server.provider(client)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        val session = DuplicateSession(newScope = { scope }, newState = { child, root -> DuplicateFinderState(provider, repo, child, root) })
        val drive = DriveScreenState(repo, prefs, scope, { library -> session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let(::DuplicateListing) }, navigation = repoWorkspace)
        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        repoWorkspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("scan") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.displayedFiles.size == copies + 1 }
        drive.selectOnly(session.state!!.suggestedIds)
        assertEquals(copies, drive.selectedFileIds.size)
        return Fixture(server, nodes, repo, drive)
    }

    @Test
    fun thousandFilesSubmitTwoConcurrentBatchesAndOneUndo() = smoke { scope ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val f = fixture(scope, 1000) { started.send(Unit); permits.receive() }
        val ids = f.drive.selectedFileIds.toList()
        f.drive.moveToTrash(ids)
        kotlinx.coroutines.withTimeout(5000) { repeat(2) { started.receive() } }
        awaitUntil("all selected files disappear before either response") { f.drive.displayedFiles.none { it.id in ids } }
        assertTrue(f.nodes.none { it.trashed })
        repeat(2) { permits.send(Unit) }
        awaitUntil("both batches committed") { (f.repo.changes.latest.value as? DriveChangeJournal.Change.Trash)?.ids?.size == 1000 }
        assertEquals(2, f.server.count("POST /drive/v1/files:batchTrash"))
        assertEquals(1, f.repo.changes.state.value.available.size)
        assertFalse(f.drive.isSelectionMode)
        assertTrue(f.drive.undoLast())
        awaitUntil("single undo restores the selection") { f.nodes.none { it.trashed } && f.drive.displayedFiles.size == 1001 }
    }

    @Test
    fun failedBatchRollsBackOnlyItsOwnProjection() = smoke { scope ->
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val f = fixture(scope, 1000) {
            if (calls.incrementAndGet() == 2) throw io.github.nihildigit.pikpak.PikPakException(7, "permission_denied", httpStatus = 403)
        }
        f.drive.moveToTrash(f.drive.selectedFileIds.toList())
        awaitUntil("successful subset has an inverse") { (f.repo.changes.latest.value as? DriveChangeJournal.Change.Trash)?.ids?.size == 500 }
        awaitUntil("rejected subset is visible again") { f.drive.displayedFiles.size == 501 }
        assertEquals(500, f.nodes.count { it.trashed })
        assertTrue(f.drive.undoLast())
        awaitUntil("undo affects only committed files") { f.nodes.none { it.trashed } && f.drive.displayedFiles.size == 1001 }
    }
}
