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
    fun `thousand copies update each batch and undo together`() = smoke { scope ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val f = fixture(scope, 1000) { started.send(Unit); permits.receive() }
        val ids = f.drive.selectedFileIds.toList()
        f.drive.moveToTrash(ids)
        assertEquals(0, assertNotNull(f.drive.trashProgress).completed)
        assertEquals(1000, f.drive.trashProgress!!.total)
        f.drive.moveToTrash(ids)
        started.receive()
        permits.send(Unit)
        started.receive()
        awaitUntil("first batch reflected") { f.drive.displayedFiles.size == 901 }
        assertEquals(100, f.nodes.count { it.trashed })
        assertEquals(100, f.drive.trashProgress!!.completed)
        assertEquals(0, f.drive.selectedFileIds.size)
        assertNull(f.repo.changes.latest.value, "one undo record only after the operation")
        repeat(9) { permits.send(Unit) }
        awaitUntil("all batches") { f.drive.trashProgress == null && f.drive.displayedFiles.isEmpty() && f.repo.changes.latest.value != null }
        assertEquals(10, f.server.count("POST /drive/v1/files:batchTrash"))
        assertEquals(1000, (f.repo.changes.latest.value as DriveChangeJournal.Change.Trash).ids.size)
        assertFalse(f.drive.isSelectionMode)
        assertTrue(f.drive.undoLast())
        awaitUntil("undo all batches") { f.nodes.none { it.trashed } && f.drive.displayedFiles.size == 1001 }
        awaitUntil("no second undo record") { f.repo.changes.latest.value == null }
    }

    @Test
    fun `failure keeps remaining selection and retry excludes completed files`() = smoke { scope ->
        var calls = 0
        var fail = true
        val f = fixture(scope, 250) {
            calls++
            if (fail && calls == 2) error("simulated batch rejection")
        }
        f.drive.moveToTrash(f.drive.selectedFileIds.toList())
        awaitUntil("partial failure") { f.drive.trashFailure != null && f.drive.trashProgress == null && f.repo.changes.latest.value != null && f.drive.displayedFiles.size == 151 }
        assertEquals(100, f.nodes.count { it.trashed })
        assertEquals(100, f.drive.trashFailure!!.completed)
        assertEquals(150, f.drive.trashFailure!!.remainingIds.size)
        assertTrue(f.drive.selectedFileIds.isEmpty())
        assertEquals(100, (f.repo.changes.latest.value as DriveChangeJournal.Change.Trash).ids.size)
        fail = false
        f.drive.retryTrash()
        awaitUntil("retry completes") { f.drive.trashProgress == null && f.nodes.count { it.trashed } == 250 && (f.repo.changes.latest.value as? DriveChangeJournal.Change.Trash)?.ids?.size == 250 }
        assertNull(f.drive.trashFailure)
        assertEquals(4, calls)
        assertEquals(3, f.server.count("POST /drive/v1/files:batchTrash"))
        assertTrue(f.drive.undoLast())
        awaitUntil("undo whole cleanup") { f.nodes.none { it.trashed } && f.drive.displayedFiles.size == 251 }
        awaitUntil("one undo including retry") { f.repo.changes.latest.value == null }
    }
}
