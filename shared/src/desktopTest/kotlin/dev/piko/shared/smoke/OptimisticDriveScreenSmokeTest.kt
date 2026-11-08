package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.state.DriveScreenState
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptimisticDriveScreenSmokeTest {
    private class HeldWrite(server: FakePikPakServer, private val reject: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = PikPakClient(
            account = "optimistic@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
            httpClient = server.httpClient().also { http ->
                http.plugin(HttpSend).intercept { request ->
                    if (request.url.encodedPath.startsWith("/drive/v1/files") &&
                        (request.method == HttpMethod.Patch || request.method == HttpMethod.Post || request.method == HttpMethod.Delete)) {
                        entered.complete(Unit)
                        release.await()
                        check(!reject) { "Rejected test mutation" }
                    }
                    execute(request)
                }
            },
        )
    }

    @Test
    fun vaultEditsProjectIntoTheRepositoryTreeAndRollBackRejectedUploads() = smoke { scope ->
        for (remove in listOf(false, true)) {
            val server = FakePikPakServer()
            server.addFile(".piko-vault-v1-0000000000000001.json", content = """
                {"entries":[{"id":"entry","name":"before.mkv","size":100,"gcid":"CONTENT","addedAt":1}]}
            """.trimIndent().encodeToByteArray())
            val held = HeldWrite(server, reject = true)
            val repo = PikoDriveRepository(server.provider(held.client), MemoryPreferences())
            val original = repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow().single { VaultEntry.isVaulted(it.id) }
            assertEquals("before.mkv", repo.driveTreeIndex.vaultEntries("")!!.single().name)
            val write = scope.async {
                repo.vault.update("", if (remove) VaultEdits.remove(setOf("entry")) else VaultEdits.rename("entry", "after.mkv"))
            }
            try {
                withTimeout(5_000) { held.entered.await() }
                assertTrue(repo.hasPendingOptimisticWrites)
                val expected = if (remove) emptyList() else listOf("after.mkv")
                assertEquals(expected, repo.driveTreeIndex.vaultEntries("")!!.map { it.name })
                assertEquals(expected, repo.driveTreeIndex.children("").files.filter { VaultEntry.isVaulted(it.id) }.map { it.name })
            } finally {
                held.release.complete(Unit)
            }
            assertTrue(write.await().isFailure)
            assertFalse(repo.hasPendingOptimisticWrites)
            assertEquals(listOf("before.mkv"), repo.driveTreeIndex.vaultEntries("")!!.map { it.name })
            assertEquals(original, repo.driveTreeIndex.children("").files.single { VaultEntry.isVaulted(it.id) })
        }
    }

    @Test
    fun renameAppearsWhileTheServerIsStillWaiting() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before.txt")
        val held = HeldWrite(server, reject = false)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(held.client), prefs)
        val page = DriveScreenState(repo, prefs, scope)
        page.load()
        awaitUntil("initial list") { !page.isLoading && page.files.size == 1 }
        page.rename(file.id, "after.txt")
        try {
            withTimeout(5_000) { held.entered.await() }
            awaitUntil("optimistic rename") { page.files.single().name == "after.txt" }
            assertEquals("before.txt", file.name)
            assertEquals(0, server.count("GET /drive/v1/files/${file.id}"))
        } finally {
            held.release.complete(Unit)
        }
        awaitUntil("committed rename") { file.name == "after.txt" && !repo.hasPendingOptimisticWrites }
        awaitUntil("visible committed rename") { page.files.single().name == "after.txt" }
    }

    @Test
    fun failedTrashRestoresTheOriginalRowWithoutDiscardingItsMetadata() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("keep.txt")
        val held = HeldWrite(server, reject = true)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(held.client), prefs)
        val page = DriveScreenState(repo, prefs, scope)
        page.load()
        awaitUntil("initial list") { !page.isLoading && page.files.size == 1 }
        val original = page.files.single()
        page.moveToTrash(listOf(file.id))
        try {
            withTimeout(5_000) { held.entered.await() }
            awaitUntil("optimistic removal") { page.files.isEmpty() }
            assertFalse(file.trashed)
            page.updateFoldersFirst(false)
        } finally {
            held.release.complete(Unit)
        }
        awaitUntil("rolled back removal") { !repo.hasPendingOptimisticWrites && page.files.size == 1 }
        assertEquals(original, page.files.single())
    }

    @Test
    fun failedUnstarReturnsTheRowToTheStarredLibrary() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("starred.txt").also { it.starred = true }
        val held = HeldWrite(server, reject = true)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(held.client), prefs)
        val page = DriveScreenState(repo, prefs, scope)
        page.restoreFolderStack(listOf(DriveLibrary.STARRED.crumb))
        page.load()
        awaitUntil("starred list") { !page.isLoading && page.files.size == 1 }
        page.setStarred(page.files.single(), false)
        try {
            withTimeout(5_000) { held.entered.await() }
            awaitUntil("optimistic unstar") { page.files.isEmpty() }
            assertTrue(file.starred)
        } finally {
            held.release.complete(Unit)
        }
        awaitUntil("rolled back unstar") { !repo.hasPendingOptimisticWrites && page.files.size == 1 }
        assertEquals(file.id, page.files.single().id)
        assertTrue(page.files.single().tags.any { it.name.equals("STAR", true) })
    }
}
