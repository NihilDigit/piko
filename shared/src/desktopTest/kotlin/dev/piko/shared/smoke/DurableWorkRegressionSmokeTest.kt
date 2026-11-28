package dev.piko.shared.smoke

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.DesktopPikoTreeDatabaseFactory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.work.WorkJobKind
import dev.piko.shared.work.WorkJobStatus
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.RetryPolicy
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.getTask
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DurableWorkRegressionSmokeTest {
    private fun makeClient(http: HttpClient, account: String = "durable@piko.dev") = PikPakClient(
        account = account,
        password = "pw",
        sessionStore = InMemorySessionStore(),
        httpClient = http,
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
        retryPolicy = RetryPolicy.None,
    )

    @Test
    fun `durable copy survives more than five transient poll errors and resumes across session restart without resubmitting`() = smoke { scope ->
        val workDir = Files.createTempDirectory("piko-durable-copy").toFile()
        try {
            val server = FakePikPakServer()
            server.copyPhase = TaskPhase.RUNNING
            val http = server.httpClient()
            val copyPosts = AtomicInteger(0)
            val failTaskPolls = AtomicInteger(7)
            http.plugin(HttpSend).intercept { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/files:batchCopy")) {
                    copyPosts.incrementAndGet()
                }
                if (request.method == HttpMethod.Get && request.url.encodedPath.contains("/drive/v1/tasks/") && failTaskPolls.getAndDecrement() > 0) {
                    throw IOException("transient poll network drop")
                }
                execute(request)
            }
            val srcFolder = server.addFolder("src")
            val dstFolder = server.addFolder("dst")
            val fileA = server.addFile("ep01.mp4", srcFolder.id, content = ByteArray(16), hash = "GCID_EP01")

            val provider1 = server.provider(makeClient(http))
            val repo1 = PikoDriveRepository(
                provider1,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope1 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val session1 = FileWriteSession(repo1, scope1, pollMillis = 5L)

            val submittedId = assertNotNull(
                session1.submit(FileWriteCommand.Copy(listOf(fileA.id), PikoPathBreadcrumb(dstFolder.id, dstFolder.name))),
            )
            awaitUntil("copy submitted and persisted to work journal") {
                copyPosts.get() == 1 &&
                    repo1.workJournal?.loadInProgressCopyJobs("durable@piko.dev")?.singleOrNull()?.copies?.isNotEmpty() == true
            }
            awaitUntil("all 7 transient poll failures absorbed while remaining IN_PROGRESS") {
                failTaskPolls.get() <= 0 && session1.works.value.single { it.id == submittedId }.isRunning
            }

            // Simulate process crash of session1 before remote task completes
            scope1.cancel()
            repo1.durableStore?.close()

            // Restart a brand new repository + FileWriteSession against the same durable SQLite directory
            val provider2 = server.provider(makeClient(http))
            val repo2 = PikoDriveRepository(
                provider2,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope2 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val session2 = FileWriteSession(repo2, scope2, pollMillis = 5L)

            awaitUntil("restored copy work appears in new session") {
                session2.works.value.size == 1 && session2.works.value.single().isRunning
            }
            assertEquals(1, copyPosts.get(), "Restart recovery must resume polling existing taskId without re-POSTing batchCopy")

            server.tasksSnapshot().forEach { it.phase = TaskPhase.COMPLETE }
            val finalResult = assertNotNull(session2.awaitResult(submittedId))
            assertEquals(FileWriteCompletion.SUCCEEDED, finalResult.completion)
            assertEquals(1, copyPosts.get())
            assertTrue(repo2.workJournal!!.loadInProgressCopyJobs("durable@piko.dev").isEmpty())

            scope2.cancel()
            repo2.durableStore?.close()
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `durable archive extraction never stores plaintext password and resumes remote task across restart`() = smoke { scope ->
        val workDir = Files.createTempDirectory("piko-durable-extract").toFile()
        try {
            val server = FakePikPakServer()
            val secret = "SuperSecretArchivePw!2026"
            val archive = server.addArchive("pack.zip", mapOf("inner/a.txt" to byteArrayOf(1, 2, 3)), password = secret)
            val blockProgress = AtomicBoolean(true)
            val http = server.httpClient()
            http.plugin(HttpSend).intercept { request ->
                if (blockProgress.get() && request.url.encodedPath.endsWith("/decompress/v1/progress")) {
                    throw IOException("network offline during decompress poll")
                }
                execute(request)
            }

            val provider1 = server.provider(makeClient(http))
            val prefs = MemoryPreferences()
            val repo1 = PikoDriveRepository(
                provider1,
                prefs,
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope1 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val pwVault1 = ArchivePasswordVault(provider1, MemorySessionStore(), prefs)
            val extract1 = ArchiveExtractSession(provider1, repo1, pwVault1, scope1, queryRetryDelay = 5.milliseconds)

            val fileStat = repo1.listAllFiles("").getOrThrow().single { it.id == archive.node.id }
            extract1.extractEntries(fileStat, listOf("inner/a.txt"), password = secret)

            awaitUntil("decompress submitted and remoteTaskId persisted") {
                server.decompressCalls.get() == 1 &&
                    repo1.workJournal?.loadInProgressExtractJobs("durable@piko.dev")?.singleOrNull()?.second?.remoteTaskId != null
            }
            val rawRows = repo1.workJournal!!.listJobs("durable@piko.dev", WorkJobKind.EXTRACT, WorkJobStatus.IN_PROGRESS)
            assertEquals(1, rawRows.size)
            assertFalse(rawRows.single().payload.contains(secret), "Plaintext archive password must never be persisted to work.db")

            // Kill session 1 mid-extraction
            scope1.cancel()
            repo1.durableStore?.close()

            // Start session 2 and unblock progress polling
            val provider2 = server.provider(makeClient(http))
            val repo2 = PikoDriveRepository(
                provider2,
                prefs,
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope2 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val pwVault2 = ArchivePasswordVault(provider2, MemorySessionStore(), prefs)
            val extract2 = ArchiveExtractSession(provider2, repo2, pwVault2, scope2, queryRetryDelay = 5.milliseconds)

            awaitUntil("restored extraction job loaded") { extract2.jobs.size == 1 }
            blockProgress.set(false)
            awaitUntil("restored extraction completes") {
                extract2.jobs.isEmpty() && extract2.completedJobs.size == 1
            }
            assertEquals(1, server.decompressCalls.get(), "Restart recovery must follow existing remoteTaskId without re-submitting decompress")
            val finishedStatus = assertIs<ArchiveJobStatus.Finished>(extract2.completedJobs.single().status)
            assertEquals(ArchiveCompletion.SUCCEEDED, finishedStatus.outcome)

            scope2.cancel()
            repo2.durableStore?.close()
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `instant save claims existing folder on 400 file_duplicated_name and deduplicates existing files on retry`() = smoke { _ ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        val root = server.addFolder("Dramas")
        val preExistingSub = server.addFolder("Season 1", root.id)
        val preExistingEp1 = server.addFile("S01E01.mkv", preExistingSub.id, content = ByteArray(500), hash = "GCID_S01E01")

        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                val bodyObj = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                val kind = bodyObj["kind"]?.jsonPrimitive?.content.orEmpty()
                val reqName = bodyObj["name"]?.jsonPrimitive?.content.orEmpty()
                val parentId = bodyObj["parent_id"]?.jsonPrimitive?.content.orEmpty()
                if (kind == "drive#folder" && parentId == root.id && reqName == "Season 1") {
                    throw PikPakException(3, "file_duplicated_name", httpStatus = 400, rawBody = """{"error":"file_duplicated_name","error_code":3}""")
                }
            }
            execute(request)
        }

        val provider = server.provider(makeClient(http))
        val instantRepo = InstantMagnetRepository(provider)
        val items = listOf(
            InstantFileItem(ResolvedFile("Season 1/S01E01.mkv", 500L, "GCID_S01E01"), true),
            InstantFileItem(ResolvedFile("Season 1/S01E02.mkv", 600L, "GCID_S01E02"), true),
        )

        val savedIds = instantRepo.instantSave(
            items = items,
            targetParentId = root.id,
            keepStructure = true,
            checkExistingInTarget = true,
        ).getOrThrow()

        assertEquals(2, savedIds.size)
        assertEquals(preExistingEp1.id, savedIds[0], "Existing S01E01.mkv with matching (name, size, gcid) must be claimed without duplicate instantCreate")
        assertEquals(1, server.instantCreates.get(), "Only missing S01E02.mkv should invoke instantCreate")
    }

    @Test
    fun `copy interrupted after server accepts request but before receipt is persisted reconciles without duplicate batchCopy`() = smoke { scope ->
        val workDir = Files.createTempDirectory("piko-durable-copy-pre-receipt").toFile()
        try {
            val server = FakePikPakServer()
            server.copyPhase = TaskPhase.COMPLETE
            val http = server.httpClient()
            val copyPosts = AtomicInteger(0)
            val dropCopyResponseOnce = AtomicBoolean(true)
            val crashedMidPost = kotlinx.coroutines.CompletableDeferred<Unit>()
            http.plugin(HttpSend).intercept { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/files:batchCopy")) {
                    copyPosts.incrementAndGet()
                    val call = execute(request)
                    if (dropCopyResponseOnce.compareAndSet(true, false)) {
                        crashedMidPost.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                    return@intercept call
                }
                execute(request)
            }
            val srcFolder = server.addFolder("src")
            val dstFolder = server.addFolder("dst")
            val fileA = server.addFile("movie.mkv", srcFolder.id, content = ByteArray(32), hash = "GCID_MOVIE")

            val provider1 = server.provider(makeClient(http))
            val repo1 = PikoDriveRepository(
                provider1,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope1 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val session1 = FileWriteSession(repo1, scope1, pollMillis = 5L)

            val submittedId = assertNotNull(
                session1.submit(FileWriteCommand.Copy(listOf(fileA.id), PikoPathBreadcrumb(dstFolder.id, dstFolder.name))),
            )
            crashedMidPost.await()
            // Simulate server background worker finishing the accepted copy task while client process is dead
            val serverWorkerClient = makeClient(server.httpClient())
            server.tasksSnapshot().forEach { serverTask ->
                serverWorkerClient.getTask(serverTask.id)
            }
            // Simulate abrupt process death right after server executed batchCopy and before receipt persisted
            scope1.cancel()
            repo1.durableStore?.close()

            val provider2 = server.provider(makeClient(http))
            val repo2 = PikoDriveRepository(
                provider2,
                MemoryPreferences(),
                durableDatabaseFactory = DesktopPikoTreeDatabaseFactory(workDir),
            )
            val scope2 = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val session2 = FileWriteSession(repo2, scope2, pollMillis = 5L)

            awaitUntil("recovered copy work finishes by reconciling existing destination file") {
                session2.works.value.firstOrNull { it.id == submittedId }?.result != null
            }
            val finalResult = assertNotNull(session2.works.value.single { it.id == submittedId }.result)
            assertEquals(FileWriteCompletion.SUCCEEDED, finalResult.completion)
            assertEquals(1, copyPosts.get(), "Must reconcile already-copied destination file without sending a second batchCopy")
            assertEquals(1, repo2.listAllFiles(dstFolder.id).getOrThrow().count { !it.trashed && it.name == "movie.mkv" })

            scope2.cancel()
            repo2.durableStore?.close()
        } finally {
            workDir.deleteRecursively()
        }
    }
}
