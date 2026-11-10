package dev.piko.shared.smoke

import dev.piko.shared.data.*
import dev.piko.shared.presentation.*
import dev.piko.shared.rename.RenameRun
import dev.piko.shared.rename.RenameSource
import dev.piko.shared.rename.planRenames
import dev.piko.shared.state.*
import io.github.nihildigit.pikpak.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class FileMutationContractTest {
    private fun drive(server: FakePikPakServer, http: HttpClient = server.httpClient()): PikoDriveRepository {
        val client = PikPakClient(
            account = "smoke@piko.dev",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        return PikoDriveRepository(server.provider(client), MemoryPreferences())
    }

    private fun FileCommands.accept(command: FileWriteCommand): Long =
        assertIs<FileCommandResult.Accepted>(submit(command)).workId

    private class TrashFixture(
        val server: FakePikPakServer,
        val nodes: List<FakePikPakServer.Node>,
        val repo: PikoDriveRepository,
        val drive: DriveScreenState,
    )

    private suspend fun trashFixture(scope: CoroutineScope, copies: Int, beforeTrash: suspend () -> Unit): TrashFixture {
        val server = FakePikPakServer()
        val nodes = (0..copies).map { server.addFile("copy-$it.mkv", content = byteArrayOf(7), hash = "SAME") }
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.toString().contains(":batchTrash")) beforeTrash()
            execute(request)
        }
        val client = PikPakClient(
            account = "smoke@piko.dev",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = http,
        )
        val provider = server.provider(client)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val session = DuplicateSession(newScope = { scope }, newState = { child, root -> DuplicateFinderState(provider, repo, child, root) })
        val drive = DriveScreenState(repo, prefs, scope, { library -> session.state?.takeIf { library == DriveLibrary.DUPLICATES }?.let(::DuplicateListing) }, navigation = workspace)
        session.open(PikoDriveRepository.ROOT_BREADCRUMB)
        workspace.updateFolderStack(listOf(DriveLibrary.DUPLICATES.crumb))
        awaitUntil("scan") { session.state?.phase == DuplicateFinderState.Phase.DONE && drive.displayedFiles.size == copies + 1 }
        drive.selectOnly(session.state!!.suggestedIds)
        assertEquals(copies, drive.selectedFileIds.size)
        return TrashFixture(server, nodes, repo, drive)
    }

    @Test fun renameWithoutCachedBaselineStillRecordsUndo() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before")
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        repository.enterAccount(provider.currentClient.value!!.account)
        assertNull(repository.knownConfirmedFile(file.id))
        val writes = FileWriteSession(repository, scope)
        val id = writes.submit(FileWriteCommand.Rename(file.id, "after"))!!
        val result = assertNotNull(writes.awaitResult(id))
        assertEquals(FileWriteCompletion.SUCCEEDED, result.completion)
        assertEquals(1, result.undoChangeIds.size)
        val inverse = assertIs<DriveChangeJournal.Change.Rename>(repository.changes.state.value.available.single())
        assertEquals("before", inverse.renames.single().oldName)
        assertTrue(repository.changes.undoLast())
        awaitUntil("uncached rename is undone") { file.name == "before" }
    }

    @Test fun largeMoveSubmitsNativeBatchesWithoutPollingAndUndoesOnce() = smoke { scope ->
        val server = FakePikPakServer()
        val files = List(1200) { server.addFile("file-$it") }
        val target = server.addFolder("target")
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences())
        repository.listAllFiles("").getOrThrow()
        val writes = FileWriteSession(repository, scope)
        val id = assertIs<FileCommandResult.Accepted>(FileCommands(repository, writes).submit(
            FileWriteCommand.Move(files.map { it.id }, PikoPathBreadcrumb(target.id, target.name)))).workId
        val result = assertNotNull(writes.awaitResult(id))
        assertEquals(FileWriteCompletion.SUCCEEDED, result.completion)
        assertTrue(writes.works.value.isEmpty())
        assertTrue(files.all { it.parentId == target.id })
        assertEquals(3, server.count("POST /drive/v1/files:batchMove"))
        assertEquals(0, server.count("GET /drive/v1/tasks/"))
        assertEquals(1, result.undoChangeIds.size)
        assertTrue(repository.changes.undoLast())
        awaitUntil("all moved files restored") { files.all { it.parentId.isEmpty() } }
    }

    @Test fun lostRenameResponseIsCheckedWithoutReplaying() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before")
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.method == HttpMethod.Patch) throw IOException("response lost")
            call
        }
        val client = PikPakClient("mutation@piko.dev", "pw", sessionStore = InMemorySessionStore(), retryPolicy = RetryPolicy.None, httpClient = http)
        val repository = PikoDriveRepository(server.provider(client), MemoryPreferences())
        repository.listAllFiles("").getOrThrow()
        val writes = FileWriteSession(repository, scope)
        val id = writes.submit(FileWriteCommand.Rename(file.id, "after"))!!
        assertEquals(FileWriteCompletion.SUCCEEDED, writes.awaitResult(id)?.completion)
        assertEquals("after", file.name)
        assertEquals(1, server.count("PATCH /drive/v1/files/${file.id}"))
        assertTrue(writes.works.value.isEmpty())
    }

    @Test fun switchingAccountsDoesNotRedirectAnAcceptedMutation() = smoke { scope ->
        val first = FakePikPakServer("first")
        val second = FakePikPakServer("second")
        val a = first.addFile("a")
        val b = second.addFile("b")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = first.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith(a.id) && request.method == HttpMethod.Patch) {
                entered.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val old = PikPakClient("old@piko.dev", "pw", sessionStore = InMemorySessionStore(), retryPolicy = RetryPolicy.None, httpClient = http)
        val current = MutableStateFlow<PikPakClient?>(old)
        val repository = PikoDriveRepository(object : PikoClientProvider { override val currentClient = current }, MemoryPreferences())
        repository.enterAccount(old.account)
        val writes = FileWriteSession(repository, scope)
        writes.submit(FileWriteCommand.Rename(a.id, "old-change"))
        withTimeout(5000) { entered.receive() }
        val next = second.client(account = "next@piko.dev")
        current.value = next
        repository.enterAccount(next.account)
        val id = writes.submit(FileWriteCommand.Rename(b.id, "new-change"))!!
        writes.clear(old.account)
        release.trySend(Unit)
        assertEquals(FileWriteCompletion.SUCCEEDED, writes.awaitResult(id)?.completion)
        assertEquals("new-change", b.name)
        assertEquals(0, second.count("PATCH /drive/v1/files/${a.id}"))
    }

    @Test fun thousandFilesSubmitTwoConcurrentTrashBatchesAndFailedBatchRollsBackIndependently() = smoke { scope ->
        val started = Channel<Unit>(Channel.UNLIMITED)
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val f1 = trashFixture(scope, 1000) { started.send(Unit); permits.receive() }
        val ids = f1.drive.selectedFileIds.toList()
        f1.drive.moveToTrash(ids)
        withTimeout(5000) { repeat(2) { started.receive() } }
        awaitUntil("all selected files disappear before either response") { f1.drive.displayedFiles.none { it.id in ids } }
        assertTrue(f1.nodes.none { it.trashed })
        repeat(2) { permits.send(Unit) }
        awaitUntil("both batches committed") { (f1.repo.changes.latest.value as? DriveChangeJournal.Change.Trash)?.ids?.size == 1000 }
        assertEquals(2, f1.server.count("POST /drive/v1/files:batchTrash"))
        assertEquals(1, f1.repo.changes.state.value.available.size)
        assertFalse(f1.drive.isSelectionMode)
        assertTrue(f1.drive.undoLast())
        awaitUntil("single undo restores the selection") { f1.nodes.none { it.trashed } && f1.drive.displayedFiles.size == 1001 }

        val calls = AtomicInteger()
        val f2 = trashFixture(scope, 1000) {
            if (calls.incrementAndGet() == 2) throw PikPakException(7, "permission_denied", httpStatus = 403)
        }
        f2.drive.moveToTrash(f2.drive.selectedFileIds.toList())
        awaitUntil("successful subset has an inverse") { (f2.repo.changes.latest.value as? DriveChangeJournal.Change.Trash)?.ids?.size == 500 }
        awaitUntil("rejected subset is visible again") { f2.drive.displayedFiles.size == 501 }
        assertEquals(500, f2.nodes.count { it.trashed })
        assertTrue(f2.drive.undoLast())
        awaitUntil("undo affects only committed files") { f2.nodes.none { it.trashed } && f2.drive.displayedFiles.size == 1001 }
    }

    @Test fun closingSourcePagePreservesExecutionAndAllowsCrossPageUndo() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before.txt")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = drive(server, http)
        val writes = FileWriteSession(repo, scope, 10)
        val pageScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val page = DriveScreenState(repo, MemoryPreferences(), pageScope, fileWriteSession = writes)
        page.load()
        awaitUntil("source page loaded") { page.files.any { it.id == file.id } }
        page.rename(file.id, "after.txt")
        withTimeout(5_000) { entered.receive() }
        pageScope.cancel()
        release.send(Unit)
        awaitUntil("mutation finishes after page disposal") { writes.mutations.active.value.isEmpty() }
        assertEquals("after.txt", file.name)
        assertIs<FileCommandResult.Rejected>(page.fileActions.submit(FileWriteCommand.Rename(file.id, "late.txt")))
        val commands = FileCommands(repo, writes)
        val inverse = repo.changes.state.value.available.single().id
        assertIs<FileCommandResult.Accepted>(commands.undo(inverse))
        withTimeout(5_000) { entered.receive() }
        release.send(Unit)
        awaitUntil("undo completes") { repo.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("before.txt", file.name)
    }

    @Test fun copyFollowsRemoteTaskAndLostIdentityStaysUnconfirmedWithoutReplay() = smoke { scope ->
        val server = FakePikPakServer().apply { copyPhase = TaskPhase.RUNNING }
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val repo = drive(server)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val command = FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(target.id, target.name))
        commands.accept(command)
        awaitUntil("remote copy task accepted") { session.works.value.single().copies.isNotEmpty() }
        assertTrue(session.works.value.single().isRunning)
        assertTrue(server.children(target.id).isEmpty())
        assertIs<FileCommandResult.Rejected>(commands.submit(command))
        server.tasksSnapshot().single().phase = TaskPhase.COMPLETE
        awaitUntil("copy completes") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertEquals(listOf("one.txt"), server.children(target.id).map { it.name })
        assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))

        val serverLost = FakePikPakServer()
        val fileLost = serverLost.addFile("one.txt")
        val targetLost = serverLost.addFolder("target")
        val httpLost = serverLost.httpClient()
        httpLost.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith(":batchCopy")) throw IOException("copy response lost")
            call
        }
        val repoLost = drive(serverLost, httpLost)
        val sessionLost = FileWriteSession(repoLost, scope, 10)
        val commandsLost = FileCommands(repoLost, sessionLost)
        val commandLost = FileWriteCommand.Copy(listOf(fileLost.id), PikoPathBreadcrumb(targetLost.id, targetLost.name))
        val idLost = commandsLost.accept(commandLost)
        awaitUntil("copy unconfirmed") { sessionLost.works.value.single().result != null }
        assertEquals(FileWriteCompletion.UNCONFIRMED, sessionLost.works.value.single().result!!.completion)
        assertIs<FileCommandResult.Rejected>(commandsLost.submit(commandLost))
        assertFalse(sessionLost.recheck(idLost))
        assertEquals(1, serverLost.tasksSnapshot().size)
        assertEquals(1, serverLost.count("POST /drive/v1/files:batchCopy"))
    }

    @Test fun renameWavesRetryTransientErrorsAndBlockDependentOccupanciesOnFailure() = smoke { _ ->
        val server = FakePikPakServer()
        val files = (1..24).map { server.addFile("$it.txt") }
        val attempts = AtomicInteger()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method != HttpMethod.Patch) return@intercept execute(request)
            if (request.url.encodedPath.endsWith("/files/${files.first().id}") && attempts.incrementAndGet() <= 3) {
                throw PikPakException(1, "busy", httpStatus = 503)
            }
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try { delay(10); execute(request) } finally { active.decrementAndGet() }
        }
        val repo = drive(server, http)
        val sources = files.map { RenameSource(it.id, it.parentId, it.name, false) }
        val run = RenameRun(repo, "test")
        run.execute(planRenames(sources, files.map { "new-${it.name}" }, emptyMap()))
        assertEquals(4, attempts.get())
        assertTrue(maximum.get() in 1..16)
        assertEquals(24, run.processed)
        assertTrue(run.failures.isEmpty())

        val serverDep = FakePikPakServer()
        val depFiles = listOf("a", "b", "c", "d").map { serverDep.addFile(it) }
        val httpDep = serverDep.httpClient()
        httpDep.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/files/${depFiles[2].id}")) {
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            }
            execute(request)
        }
        val repoDep = drive(serverDep, httpDep)
        val runDep = RenameRun(repoDep, "test")
        runDep.execute(planRenames(depFiles.map { RenameSource(it.id, it.parentId, it.name, false) }, listOf("b", "c", "z", "done"), emptyMap()))
        assertEquals(3, runDep.failures.size)
        assertEquals(listOf("a", "b", "c", "done"), depFiles.map { it.name })
    }
}
