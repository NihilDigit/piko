package dev.piko.shared.data

import kotlinx.coroutines.*
import kotlin.test.*

class SnapshotWriterCloseTest {
    @Test
    fun closeWaitsForInflightCommitFlushesFinalRevisionAndReleasesWorker() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var revision = 1L
        val commits = ArrayList<Long>()
        val writer = SnapshotWriter(scope, 7, SnapshotSource {
            VersionedSnapshot(PersistVersion(7, revision), revision)
        }, debounceMillis = 0) { value ->
            commits += value
            if (value == 1L) { entered.complete(Unit); release.await() }
        }
        try {
            writer.markDirty(PersistVersion(7, 1))
            withTimeout(5_000) { entered.await() }
            revision = 2
            val closing = async(start = CoroutineStart.UNDISPATCHED) { writer.close(PersistVersion(7, 2)) }
            assertFalse(closing.isCompleted)
            assertFailsWith<IllegalStateException> { writer.markDirty(PersistVersion(7, 3)) }
            assertFailsWith<IllegalStateException> { writer.flushThrough(PersistVersion(7, 3)) }
            release.complete(Unit)
            withTimeout(5_000) { closing.await() }
            assertEquals(listOf(1L, 2L), commits)
            assertTrue(owner.children.none())
            writer.close(PersistVersion(7, 2))
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun failedFinalCommitPropagatesButWorkerAlwaysTerminates(): Unit = runBlocking {
        val owner = SupervisorJob()
        val writer = SnapshotWriter(CoroutineScope(coroutineContext + owner), 1, SnapshotSource {
            VersionedSnapshot(PersistVersion(1, 1), Unit)
        }, debounceMillis = 0) { error("disk full") }
        try {
            assertFailsWith<IllegalStateException> { withTimeout(5_000) { writer.close(PersistVersion(1, 1)) } }
            assertTrue(owner.children.none())
            assertFailsWith<IllegalStateException> { writer.markDirty(PersistVersion(1, 2)) }
        } finally { owner.cancelAndJoin() }
    }
}
