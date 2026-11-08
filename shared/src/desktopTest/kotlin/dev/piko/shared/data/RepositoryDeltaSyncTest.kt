package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryDeltaSyncTest {
    @Test fun failedSecondPageKeepsTheOriginalCursorAndRetryReplaysEveryPage() = smoke {
        val directory = Files.createTempDirectory("piko-repository-delta").toFile()
        val server = FakePikPakServer()
        val existing = server.addFile("before.mp4")
        val fail = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.parameters["with_audit"] == "true") {
                val lower = request.url.parameters["filters"]?.let {
                    Json.parseToJsonElement(it).jsonObject["modified_time"]?.jsonObject?.get("gt")?.jsonPrimitive?.content
                }.orEmpty()
                val token = request.url.parameters["page_token"].orEmpty()
                requests += lower to token
                if (fail.get() && token.isNotEmpty()) throw PikPakException(7, "permission_denied", httpStatus = 403)
            }
            execute(request)
        }
        val client = PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http)
        val repository = PikoDriveRepository(server.provider(client), treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(directory))
        try {
            repository.warmDriveTree().getOrThrow()
            val initialCursor = assertNotNull(repository.driveTreeIndex.syncCursor)
            existing.name = "after.mp4"
            val added = List(501) { server.addFile("added-$it.mp4") }
            requests.clear()
            fail.set(true)
            assertTrue(repository.probeRemoteChanges(force = true).isFailure)
            assertEquals(initialCursor, repository.driveTreeIndex.syncCursor)
            assertEquals("before.mp4", repository.driveTreeIndex.file(existing.id)?.name)
            added.forEach { assertNull(repository.driveTreeIndex.file(it.id)) }
            assertEquals(listOf(initialCursor to "", initialCursor to "500"), requests.toList())
            requests.clear()
            fail.set(false)
            assertEquals(PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH,
                repository.probeRemoteChanges(force = true).getOrThrow())
            assertEquals(listOf(initialCursor to "", initialCursor to "500"), requests.toList())
            assertEquals("after.mp4", repository.driveTreeIndex.file(existing.id)?.name)
            added.forEach { assertNotNull(repository.driveTreeIndex.file(it.id)) }
            assertTrue(repository.driveTreeIndex.syncCursor != initialCursor)
            requests.clear()
            assertEquals(PikoDriveRepository.CanaryProbeResult.UNCHANGED,
                repository.probeRemoteChanges(force = true).getOrThrow())
            assertEquals(1, requests.size)
            assertEquals(repository.driveTreeIndex.syncCursor, requests.single().first)
            val previousCursor = repository.driveTreeIndex.syncCursor
            requests.clear()
            server.syncVersionOutdated = true
            existing.name = "rebuilt.mp4"
            assertEquals(PikoDriveRepository.CanaryProbeResult.FULL_REBUILD,
                repository.probeRemoteChanges(force = true).getOrThrow())
            assertEquals(previousCursor, requests.first().first)
            assertTrue(requests.drop(1).all { it.first.isEmpty() }, "An invalidated cursor requires a fresh baseline")
            assertEquals("rebuilt.mp4", repository.driveTreeIndex.file(existing.id)?.name)
            added.forEach { assertNotNull(repository.driveTreeIndex.file(it.id)) }
        } finally {
            repository.enterAccount(null)
            http.close()
            directory.deleteRecursively()
        }
    }
}
