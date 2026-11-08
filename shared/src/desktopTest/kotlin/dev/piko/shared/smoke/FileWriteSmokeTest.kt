package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.FileWriteSession
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWriteSmokeTest {
    private fun drive(server: FakePikPakServer, http: HttpClient = server.httpClient()): PikoDriveRepository {
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        return PikoDriveRepository(server.provider(client), MemoryPreferences())
    }
    private fun FileCommands.accept(command: FileWriteCommand): Long = assertIs<FileCommandResult.Accepted>(submit(command)).workId

    @Test
    fun `closing the source page preserves execution and the result can be undone elsewhere`() = smoke { scope ->
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
        val page = dev.piko.shared.state.DriveScreenState(repo, MemoryPreferences(), pageScope, fileWriteSession = writes)
        page.load()
        awaitUntil("source page is loaded before rename") { page.files.any { it.id == file.id } }
        page.rename(file.id, "after.txt")
        withTimeout(5_000) { entered.receive() }
        pageScope.cancel()
        release.send(Unit)
        awaitUntil("原页面释放后仍有业务结果") { writes.mutations.active.value.isEmpty() }
        assertEquals("after.txt", file.name)
        assertIs<FileCommandResult.Rejected>(page.fileActions.submit(FileWriteCommand.Rename(file.id, "late.txt")))
        val commands = FileCommands(repo, writes)
        val inverse = repo.changes.state.value.available.single().id
        assertIs<FileCommandResult.Accepted>(commands.undo(inverse))
        withTimeout(5_000) { entered.receive() }
        release.send(Unit)
        awaitUntil("其他页面撤销已完成") { repo.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("before.txt", file.name)
    }

    @Test
    fun `copy follows the accepted remote task rather than declaring completion at acceptance`() = smoke { scope ->
        val server = FakePikPakServer().apply { copyPhase = TaskPhase.RUNNING }
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val repo = drive(server)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val command = FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(target.id, target.name))
        commands.accept(command)
        awaitUntil("已接受的远端复制任务") { session.works.value.single().copies.isNotEmpty() }
        assertTrue(session.works.value.single().isRunning)
        assertTrue(server.children(target.id).isEmpty())
        assertIs<FileCommandResult.Rejected>(commands.submit(command))
        server.tasksSnapshot().single().phase = TaskPhase.COMPLETE
        awaitUntil("复制真实完成") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertEquals(listOf("one.txt"), server.children(target.id).map { it.name })
        assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))
    }

    @Test
    fun `lost copy task identity stays unconfirmed and recheck cannot create a second copy`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith(":batchCopy")) throw IOException("copy response lost")
            call
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val command = FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(target.id, target.name))
        val id = commands.accept(command)
        awaitUntil("复制待确认") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.UNCONFIRMED, session.works.value.single().result!!.completion)
        assertIs<FileCommandResult.Rejected>(commands.submit(command))
        assertFalse(session.recheck(id), "没有远端标识时不提供无效查询，也不能重新提交")
        assertEquals(1, server.tasksSnapshot().size)
        assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))
    }

    @Test
    fun `rename preserves sibling metadata and stale status`() = smoke { _ ->
        val server = FakePikPakServer()
        val a = server.addFile("a")
        val b = server.addFile("b")
        val other = server.addFolder("other")
        val repo = drive(server)
        repo.listAllFiles("").getOrThrow()
        repo.listAllFiles(other.id).getOrThrow()
        repo.rename(a.id, "aa").getOrThrow()
        assertEquals("", repo.knownParentOf(b.id))
        assertEquals("aa", repo.knownFile(a.id)?.name)
        repo.rename(b.id, "bb").getOrThrow()
        assertNotNull(repo.cachedFiles(other.id, dev.piko.shared.data.PikoFileSortOrder.TIME_DESC))
        repo.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(setOf("")))
        repo.applyChange(dev.piko.shared.data.DriveChange.Renamed(a.id, "aaa", ""))
        assertEquals(null, repo.knownFile(a.id))
    }

    @Test
    fun `rename waves limit concurrency retry transient errors and keep journal order`() = smoke { _ ->
        val server = FakePikPakServer()
        val files = (1..24).map { server.addFile("$it.txt") }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method != HttpMethod.Patch) return@intercept execute(request)
            if (request.url.encodedPath.endsWith("/files/${files.first().id}") && attempts.incrementAndGet() <= 3) {
                throw io.github.nihildigit.pikpak.PikPakException(1, "busy", httpStatus = 503)
            }
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try { kotlinx.coroutines.delay(25); execute(request) } finally { active.decrementAndGet() }
        }
        val repo = drive(server, http)
        val sources = files.map { dev.piko.shared.rename.RenameSource(it.id, it.parentId, it.name, false) }
        val plan = dev.piko.shared.rename.planRenames(sources, files.map { "new-${it.name}" }, emptyMap())
        val run = dev.piko.shared.rename.RenameRun(repo, "test")
        run.execute(plan)
        assertEquals(4, attempts.get())
        assertTrue(maximum.get() in 2..16)
        assertEquals(24, run.processed)
        assertTrue(run.failures.isEmpty())
        assertEquals(files.map { it.id }, run.renamed.map { it.id })
        files.forEach { assertEquals(0, server.count("GET /drive/v1/files/${it.id}")) }
    }

    @Test
    fun `failed rename blocks dependent occupancies but independent files finish`() = smoke { _ ->
        val server = FakePikPakServer()
        val files = listOf("a", "b", "c", "d").map { server.addFile(it) }
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/files/${files[2].id}")) {
                throw io.github.nihildigit.pikpak.PikPakException(7, "permission_denied", httpStatus = 403)
            }
            execute(request)
        }
        val repo = drive(server, http)
        val sources = files.map { dev.piko.shared.rename.RenameSource(it.id, it.parentId, it.name, false) }
        val plan = dev.piko.shared.rename.planRenames(sources, listOf("b", "c", "z", "done"), emptyMap())
        val run = dev.piko.shared.rename.RenameRun(repo, "test")
        run.execute(plan)
        assertEquals(3, run.failures.size)
        assertEquals(4, run.processed)
        assertEquals(listOf("a", "b", "c", "done"), files.map { it.name })
        files.take(2).forEach { assertEquals(0, server.count("PATCH /drive/v1/files/${it.id}")) }
    }
}
