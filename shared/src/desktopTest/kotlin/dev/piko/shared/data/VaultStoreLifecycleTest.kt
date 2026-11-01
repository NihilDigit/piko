package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration

class VaultStoreLifecycleTest {
    @Test
    fun closeDrainsAcceptedTransactionAndBlocksSameKeyReload() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val cache = Cache()
        val uploaded = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val io = object : EmptyIo() {
            override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String {
                uploaded.complete(Unit)
                releaseUpload.await()
                return "manifest"
            }
        }
        try {
            val first = VaultStore(io, Duration.ZERO, cacheStore = cache, account = "A", scope = scope, saveDelayMillis = 0)
            first.awaitLoaded()
            val entry = VaultEntry("one", "one.mkv", 10, "HASH", addedAt = 1)
            val transaction = async { first.update("folder", VaultEdits.add(listOf(entry)), freshlyCreatedFolder = true).getOrThrow() }
            withTimeout(5_000) { uploaded.await() }
            val handoff = first.close()
            assertSame(handoff, first.close())
            assertFalse(handoff.isCompleted)
            assertFailsWith<IllegalStateException> { first.update("new", VaultEdits.remove(emptySet())) }
            assertFailsWith<IllegalStateException> { first.committed("folder") }
            assertFailsWith<IllegalStateException> { first.read("folder", emptyList()) }
            val readsBefore = cache.reads.get()
            val next = VaultStore(EmptyIo(), cacheStore = cache, account = "A", scope = scope,
                saveDelayMillis = 0, predecessor = handoff)
            yield()
            assertEquals(readsBefore, cache.reads.get(), "new account generation must not read before the handoff")
            releaseUpload.complete(Unit)
            withTimeout(5_000) { transaction.await(); handoff.await(); next.awaitLoaded() }
            assertEquals(listOf(entry), next.parsedFlow.value["manifest"])
            val pending = FileStat(kind = FileKind.FILE, id = "manifest", name = ".piko-vault-v1-00.json", phase = TaskPhase.PENDING)
            assertNotNull(next.winnerOf(listOf(pending)), "trusted pending manifests survive handoff")
            // This proves the committed folder baseline also survives, not just parsed entries.
            assertTrue(next.update("folder", VaultEdits.add(listOf(entry.copy(id = "two")))).isFailure)
            withTimeout(5_000) { next.close().await() }
            assertTrue(owner.children.none(), "closed stores must release their loader/writer child jobs")
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun failedCloseStillTerminatesAndDoesNotPoisonSuccessorLoading() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val cache = Cache().apply { fail = true }
        try {
            val old = VaultStore(EmptyIo(), cacheStore = cache, account = "A", scope = scope,
                initialParsed = mapOf("old" to emptyList()), saveDelayMillis = 0)
            val handoff = old.close()
            assertFailsWith<IllegalStateException> { withTimeout(5_000) { handoff.await() } }
            assertTrue(owner.children.none())
            cache.fail = false
            val next = VaultStore(EmptyIo(), cacheStore = cache, account = "A", scope = scope,
                predecessor = handoff, initialParsed = mapOf("new" to emptyList()), saveDelayMillis = 0)
            withTimeout(5_000) { next.awaitLoaded(); next.close().await() }
            assertNotNull(cache.values[VaultStore.cacheKeyOf("A")])
            assertTrue(owner.children.none())
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun repeatedAccountSwitchesReleaseOwnedChildren() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val predecessors = HashMap<String, Job>()
        try {
            repeat(20) { index ->
                val account = if (index % 2 == 0) "A" else "B"
                val store = VaultStore(EmptyIo(), cacheStore = Cache(), account = account, scope = scope,
                    predecessor = predecessors[account], saveDelayMillis = 0)
                store.awaitLoaded()
                predecessors[account] = store.close()
                withTimeout(5_000) { predecessors.getValue(account).join() }
                assertTrue(owner.children.none())
            }
        } finally { owner.cancelAndJoin() }
    }

    private open class EmptyIo : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> = emptyList()
        override suspend fun read(fileId: String): ByteArray = error("unexpected remote read")
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = error("unexpected upload")
        override suspend fun delete(ids: List<String>) = Unit
    }

    private class Cache : PikoCacheStore {
        override val jsonCodec = Json { ignoreUnknownKeys = true }.streamingCodec()
        val values = HashMap<String, String>()
        val reads = AtomicInteger()
        var fail = false
        override suspend fun read(key: String): String? { reads.incrementAndGet(); return values[key] }
        override suspend fun write(key: String, value: String) {
            check(!fail) { "disk full" }
            values[key] = value
        }
    }
}
