package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OptimisticMutationSmokeTest {
    @Test fun `queued rename rolls back only its predecessor and rebases undo`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("original.txt")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        var first = true
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch && first) {
                first = false
                entered.send(Unit)
                release.receive()
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            }
            execute(request)
        }
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        val repo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val listing = repo.listAllFiles("").getOrThrow()
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        assertIs<FileCommandResult.Accepted>(commands.submit(FileWriteCommand.Rename(file.id, "first.txt")))
        withTimeout(5_000) { entered.receive() }
        assertEquals("first.txt", repo.projectOptimisticFiles(listing).single().name)
        assertIs<FileCommandResult.Accepted>(commands.submit(FileWriteCommand.Rename(file.id, "second.txt")))
        awaitUntil("second intent is projected before predecessor returns") {
            repo.projectOptimisticFiles(listing).single().name == "second.txt"
        }
        release.send(Unit)
        awaitUntil("both writes settled") { session.mutations.active.value.isEmpty() }
        assertEquals("second.txt", file.name)
        val rename = repo.changes.state.value.available.filterIsInstance<DriveChangeJournal.Change.Rename>().single()
        assertEquals("original.txt", rename.renames.single().oldName)
        assertEquals(0, server.count("GET /drive/v1/files/${file.id}"))
    }
}
