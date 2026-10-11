package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalStateSmokeTest {
    @Test
    fun `undo refuses to overwrite a subsequent rename`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("later.txt")
        val http = server.httpClient()
        val drive = PikoDriveRepository(server.provider(PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http)), MemoryPreferences())
        val journal = DriveChangeJournal(drive, scope)
        val change = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(file.id, "old.txt", "new.txt")), "rename")
        journal.record(change)
        assertTrue(journal.undo(change))
        awaitUntil("后续名称不被旧撤销覆盖") { journal.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.FAILED }
        assertEquals("later.txt", file.name)
        assertEquals(0, server.count("PATCH /drive/v1/files/${file.id}"))
    }

    @Test
    fun `retrying a partially undone name swap resumes after confirmed inverse steps`() = smoke { scope ->
        val server = FakePikPakServer()
        val a = server.addFile("b.txt")
        val b = server.addFile("a.txt")
        val attempts = AtomicInteger()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch && attempts.incrementAndGet() == 2)
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val drive = PikoDriveRepository(server.provider(PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http)), MemoryPreferences())
        val journal = DriveChangeJournal(drive, scope)
        val change = DriveChangeJournal.Change.Rename(listOf(
            DriveChangeJournal.Renamed(a.id, "a.txt", "temporary"), DriveChangeJournal.Renamed(b.id, "b.txt", "a.txt"),
            DriveChangeJournal.Renamed(a.id, "temporary", "b.txt")), "swap")
        journal.record(change)
        assertTrue(journal.undo(change))
        awaitUntil("反向步骤中断") { journal.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.FAILED }
        assertEquals("temporary", a.name)
        assertEquals(1, journal.state.value.undo[change.id]?.completedRenameSteps)
        assertTrue(journal.undo(change))
        awaitUntil("从已确认步骤后继续") { journal.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("a.txt", a.name)
        assertEquals("b.txt", b.name)
        assertEquals(4, attempts.get())
    }

    @Test
    fun `undo has a queryable result and concurrent duplicate clicks cannot execute it twice`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("new.txt")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val fail = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) {
                attempts.incrementAndGet()
                entered.send(Unit)
                release.receive()
                if (fail.get()) throw PikPakException(7, "permission_denied", httpStatus = 403)
            }
            execute(request)
        }
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        val drive = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val journal = DriveChangeJournal(drive, scope)
        val change = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(file.id, "old.txt", "new.txt")), "renamed")
        journal.record(change)
        assertTrue(journal.undo(change))
        assertFalse(journal.undo(change))
        withTimeout(5_000) { entered.receive() }
        assertEquals(DriveChangeJournal.UndoStatus.RUNNING, journal.state.value.undo[change.id]?.status)
        assertFalse(journal.undoLast(), "快捷键不能在反向操作尚未完成时越过它")
        release.send(Unit)
        awaitUntil("撤销失败可查询并允许重试") { journal.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.FAILED }
        assertTrue(change in journal.state.value.available)
        fail.set(false)
        assertTrue(journal.undo(change))
        withTimeout(5_000) { entered.receive() }
        release.send(Unit)
        awaitUntil("重试撤销成功") { journal.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("old.txt", file.name)
        assertEquals(2, attempts.get())
        assertFalse(journal.undo(change))
        assertTrue(journal.state.value.available.isEmpty())
    }

    @Test
    fun `clearing account state cancels an old undo without erasing new records`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("new.txt")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) {
                entered.send(Unit)
                withContext(NonCancellable) { release.receive() }
            }
            execute(request)
        }
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        val drive = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val journal = DriveChangeJournal(drive, CoroutineScope(scope.coroutineContext + parent))
        val old = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(file.id, "old.txt", "new.txt")), "old")
        journal.record(old)
        val before = parent.children.toSet()
        assertTrue(journal.undo(old))
        withTimeout(5_000) { entered.receive() }
        val execution = parent.children.single { it !in before }
        val generation = journal.state.value.generation
        journal.clear()
        val replacement = DriveChangeJournal.Change.Trash(listOf("new-account-file"), "replacement")
        journal.record(replacement)
        release.send(Unit)
        withTimeout(5_000) { execution.join() }
        assertTrue(journal.state.value.generation > generation)
        assertEquals(listOf<DriveChangeJournal.Change>(replacement), journal.state.value.available)
        assertTrue(journal.state.value.undo.isEmpty(), "旧撤销的晚到结果不能写进新账号")
        assertFalse(journal.undo(old))
        parent.cancel()
    }
}
