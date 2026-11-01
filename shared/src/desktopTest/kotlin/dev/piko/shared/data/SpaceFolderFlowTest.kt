package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.FileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceFolderFlowTest {
    @Test
    fun completeTreeAutomaticallyReconcilesDirtyDescendant(): Unit = runBlocking {
        NetworkFixture().use { fixture ->
            val index = fixture.repository.driveTreeIndex
            index.putFolderListing("", listOf(FileStat(id = "folder", name = "folder", kind = FileKind.FOLDER)))
            index.putFolderListing("folder", emptyList())
            index.markComplete()
            fixture.repository.applyChange(DriveChange.ContentsChanged(setOf("folder")))
            val snapshot = withTimeout(10_000) {
                fixture.repository.observeSpaceFolder("", false).first { it.isTreeComplete && !it.isTreeBuilding }
            }
            assertEquals(100L, snapshot.physicalBytes)
            assertTrue(index.dirtyFolders.isEmpty())
            assertEquals(1, fixture.listCalls.get())
        }
    }

    @Test
    fun failedInitialSyncExposesErrorAndExplicitRetryRecovers(): Unit = runBlocking {
        NetworkFixture(failFirst = true).use { fixture ->
            val snapshots = MutableStateFlow<dev.piko.shared.space.SpaceFolderSnapshot?>(null)
            val collector = launch { fixture.repository.observeSpaceFolder("", false).collect { snapshots.value = it } }
            try {
                val failed = withTimeout(10_000) { snapshots.first { it?.syncError != null } }!!
                assertNotNull(failed.syncError)
                assertFalse(failed.isTreeBuilding)
                val calls = fixture.listCalls.get()
                delay(150)
                assertEquals(calls, fixture.listCalls.get(), "Failure must not trigger an automatic retry loop")
                fixture.repository.refreshSpaceFolder("")
                val recovered = withTimeout(10_000) { snapshots.first { it?.isTreeComplete == true && !it.isTreeBuilding && it.syncError == null } }!!
                assertEquals(100L, recovered.physicalBytes)
                assertTrue(fixture.listCalls.get() > calls)
            } finally { collector.cancel(); collector.join() }
        }
    }

    @Test
    fun replacedInitialSyncFollowsReplacementToCompletionAndFailure(): Unit = runBlocking {
        // Case 1: replacement rebuild succeeds -> exits building state cleanly.
        val firstEntered = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        NetworkFixture(onCall = { call ->
            if (call == 1) {
                firstEntered.complete(Unit)
                firstGate.await()
            }
        }).use { fixture ->
            val snapshots = MutableStateFlow<dev.piko.shared.space.SpaceFolderSnapshot?>(null)
            val collector = launch { fixture.repository.observeSpaceFolder("", false).collect { snapshots.value = it } }
            try {
                withTimeout(10_000) { firstEntered.await() }
                fixture.repository.requestTreeRebuild()
                firstGate.complete(Unit)
                val completed = withTimeout(10_000) {
                    snapshots.first { it?.isTreeComplete == true && !it.isTreeBuilding && it.syncError == null }
                }!!
                assertEquals(100L, completed.physicalBytes)
            } finally { collector.cancel(); collector.join() }
        }

        // Case 2: replacement rebuild fails -> exits building state and exposes retryable syncError.
        val secondEntered = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        NetworkFixture(onCall = { call ->
            if (call == 1) {
                secondEntered.complete(Unit)
                secondGate.await()
            } else if (call == 2) {
                error("Replacement sync failed")
            }
        }).use { fixture ->
            val snapshots = MutableStateFlow<dev.piko.shared.space.SpaceFolderSnapshot?>(null)
            val collector = launch { fixture.repository.observeSpaceFolder("", false).collect { snapshots.value = it } }
            try {
                withTimeout(10_000) { secondEntered.await() }
                fixture.repository.requestTreeRebuild()
                secondGate.complete(Unit)
                val failed = withTimeout(10_000) {
                    snapshots.first { it?.syncError != null && !it.isTreeBuilding }
                }!!
                assertNotNull(failed.syncError)
                assertFalse(failed.isTreeBuilding)
                fixture.repository.refreshSpaceFolder("")
                val recovered = withTimeout(10_000) {
                    snapshots.first { it?.isTreeComplete == true && !it.isTreeBuilding && it.syncError == null }
                }!!
                assertEquals(100L, recovered.physicalBytes)
            } finally { collector.cancel(); collector.join() }
        }
    }

    private class NetworkFixture(
        failFirst: Boolean = false,
        private val onCall: suspend (Int) -> Unit = {},
    ) : AutoCloseable {
        val listCalls = AtomicInteger()
        private val directory = Files.createTempDirectory("piko-space-network").toFile()
        private val headers = headersOf(HttpHeaders.ContentType, "application/json")
        private val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/v1/shield/captcha/init") -> """{"captcha_token":"C","expires_in":300,"url":""}"""
                request.url.encodedPath.endsWith("/v1/auth/signin") -> """{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}"""
                request.url.encodedPath.endsWith("/drive/v1/files") -> {
                    val call = listCalls.incrementAndGet()
                    onCall(call)
                    if (failFirst && call == 1) error("Injected network failure")
                    val parent = request.url.parameters["parent_id"].orEmpty()
                    """{"files":[{"id":"new","parent_id":"${if (parent == "*") "" else parent}","name":"new","kind":"drive#file","size":"100","phase":"PHASE_TYPE_COMPLETE"}],"next_page_token":""}"""
                }
                else -> "{}"
            }
            respond(body, headers = headers)
        }
        private val http = HttpClient(engine)
        private val client = PikPakClient("mock@example.com", "pw", sessionStore = InMemorySessionStore(), httpClient = http, cdnHttpClient = http)
        private val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(client)
        }
        val repository = PikoDriveRepository(provider, treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(directory))
        override fun close() {
            provider.currentClient.value = null
            repository.enterAccount(null)
            http.close()
            engine.close()
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun sqliteIncrementalMutationPublishesANewSpaceSnapshot(): Unit = runBlocking {
        val directory = Files.createTempDirectory("piko-space-flow").toFile()
        val provider = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(null)
        }
        val repository = PikoDriveRepository(provider, treeDatabaseFactory = DesktopPikoTreeDatabaseFactory(directory))
        try {
            repository.enterAccount("account")
            repository.driveTreeIndex.markComplete()
            val subscribed = CompletableDeferred<Unit>()
            val snapshots = async {
                repository.observeSpaceFolder("", false).onEach { subscribed.complete(Unit) }.take(2).toList()
            }
            withTimeout(5_000) { subscribed.await() }
            repository.driveTreeIndex.putFiles(listOf(FileStat(id = "new", name = "new", size = "100")))
            val result = withTimeout(5_000) { snapshots.await() }
            assertEquals(listOf(0L, 100L), result.map { it.physicalBytes })
            assertEquals("new", result.last().items.single().id)
            assertTrue(result.all { it.isTreeComplete })
        } finally {
            repository.enterAccount(null)
            assertTrue(directory.deleteRecursively())
        }
    }
}
