package dev.piko.shared.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotWriterTest {
    @Test
    fun cancelledBeforeFirstDispatchRejectsNewRequests() = runBlocking {
        for (cancelBeforeConstruction in listOf(true, false)) {
            val job = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + job)
            if (cancelBeforeConstruction) job.cancel()
            val writer = SnapshotWriter(scope, 1, SnapshotSource {
                VersionedSnapshot(PersistVersion(1, 1), Unit)
            }, debounceMillis = 0) { error("Cancelled writer must not persist") }
            job.cancel()
            job.join()
            assertFailsWith<IllegalStateException> { writer.markDirty(PersistVersion(1, 1)) }
            assertFailsWith<IllegalStateException> {
                withTimeout(1_000) { writer.flushThrough(PersistVersion(1, 1)) }
            }
        }
    }

    @Test
    fun changesDuringCommitAreWrittenByTheSameLoop() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var revision = 1L
            val firstCommit = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val committed = mutableListOf<Long>()
            val writer = SnapshotWriter(scope, 7, SnapshotSource {
                VersionedSnapshot(PersistVersion(7, revision), revision)
            }, debounceMillis = 0) { value ->
                committed += value
                if (value == 1L) { firstCommit.complete(Unit); releaseCommit.await() }
            }
            writer.markDirty(PersistVersion(7, 1))
            withTimeout(5_000) { firstCommit.await() }
            revision = 2
            writer.markDirty(PersistVersion(7, 2))
            releaseCommit.complete(Unit)
            withTimeout(5_000) { writer.flushThrough(PersistVersion(7, 2)) }
            assertEquals(listOf(1L, 2L), committed)
            assertFailsWith<IllegalArgumentException> { writer.markDirty(PersistVersion(8, 3)) }
        } finally { scope.cancel() }
    }

    @Test
    fun flushPropagatesFailureAndCanRetryBoundAccountSource() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var fail = true
            var capturedAccount = "A"
            val boundAccount = capturedAccount
            val commits = mutableListOf<String>()
            val writer = SnapshotWriter(scope, 1, SnapshotSource {
                VersionedSnapshot(PersistVersion(1, 1), boundAccount)
            }, debounceMillis = 0) { value ->
                if (fail) error("write failed")
                commits += value
            }
            capturedAccount = "B"
            assertEquals("B", capturedAccount)
            assertFailsWith<IllegalStateException> {
                withTimeout(5_000) { writer.flushThrough(PersistVersion(1, 1)) }
            }
            fail = false
            withTimeout(5_000) { writer.flushThrough(PersistVersion(1, 1)) }
            assertEquals(listOf("A"), commits)
        } finally { scope.cancel() }
    }

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
