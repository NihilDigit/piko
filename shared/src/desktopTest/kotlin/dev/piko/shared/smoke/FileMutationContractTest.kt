package dev.piko.shared.smoke

import dev.piko.shared.data.*
import dev.piko.shared.presentation.*
import dev.piko.shared.state.*
import io.github.nihildigit.pikpak.*
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.*

class FileMutationContractTest {
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
}
