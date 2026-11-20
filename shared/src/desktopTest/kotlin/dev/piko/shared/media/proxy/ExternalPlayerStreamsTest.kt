package dev.piko.shared.media.proxy

import dev.piko.shared.memory.MemoryPolicy
import dev.piko.shared.memory.PikoMemoryGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalPlayerStreamsTest {
    private class FakeExternalHandle(
        override val fileId: String,
        override val displayName: String,
    ) : ExternalPlayerHandle {
        override val label: String = "file $fileId ($displayName)"
        var playingEpochs = mutableListOf<Long>()
        var pauseCount = 0
        var closed = false
        private var activityCallback: (() -> Unit)? = null

        override fun activatePlaying(epoch: Long) {
            playingEpochs += epoch
        }

        override fun pauseSpeculation() {
            pauseCount++
        }

        override fun setOnReadActivity(callback: (() -> Unit)?) {
            activityCallback = callback
        }

        fun simulateHttpRead() {
            activityCallback?.invoke()
        }

        override fun close() {
            closed = true
            activityCallback = null
        }
    }

    @Test
    fun sameFileReopenReplacesOldStreamAndAvoidsCompetingPreemption() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = ExternalPlayerStreamRegistry(scope, idleTimeoutMillis = 5_000L, capacity = 4)
            val first = FakeExternalHandle("f1", "movie.mp4")
            val second = FakeExternalHandle("f1", "movie.mp4")

            registry.retain(first)
            assertEquals(listOf(1L), first.playingEpochs)
            assertTrue(registry.active.value)
            assertEquals("movie.mp4", registry.activeFileName.value)
            assertFalse(first.closed)

            // Retrying same fileId must close the previous handle instead of keeping two competing streams.
            registry.retain(second)
            assertTrue(first.closed, "Old stream for the same fileId must be closed immediately on retry")
            assertEquals(listOf(2L), second.playingEpochs)
            assertFalse(second.closed)
            assertTrue(registry.active.value)

            registry.closeAll()
            assertTrue(second.closed)
            assertFalse(registry.active.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun secondFilePausesFirstSpeculationAndHttpReadReactivatesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = ExternalPlayerStreamRegistry(scope, idleTimeoutMillis = 5_000L, capacity = 4)
            val a = FakeExternalHandle("fileA", "a.mp4")
            val b = FakeExternalHandle("fileB", "b.mp4")

            registry.retain(a)
            assertEquals(1, a.playingEpochs.size)
            assertEquals(0, a.pauseCount)

            // Opening a second file pauses first stream's speculation without closing its URL.
            registry.retain(b)
            assertFalse(a.closed)
            assertEquals(1, a.pauseCount)
            assertEquals(1, b.playingEpochs.size)
            assertEquals("b.mp4", registry.activeFileName.value)

            // If external player reads from stream A again, A reactivates PLAYING and B pauses.
            a.simulateHttpRead()
            assertEquals(2, a.playingEpochs.size)
            assertEquals(1, b.pauseCount)
            assertEquals("a.mp4", registry.activeFileName.value)

            registry.closeAll()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backgroundPlaybackProtectsMemoryPolicyUntilIdleTimeout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val governor = PikoMemoryGovernor(scope)
            val registry = ExternalPlayerStreamRegistry(
                scope = scope,
                idleTimeoutMillis = 120L,
                capacity = 4,
                onActiveChanged = governor::setBackgroundPlaybackActive,
            )
            val handle = FakeExternalHandle("f1", "big_1080p.mp4")

            registry.retain(handle)
            // App goes to background right after launching external player: must NOT choke to Background/Constrained.
            governor.onVisibilityChanged(false)
            assertEquals(MemoryPolicy.Normal, governor.policy.value)

            // After idle timeout with zero HTTP reads, speculation pauses and memory policy drops to Background.
            delay(250L)
            assertFalse(registry.active.value)
            assertNull(registry.activeFileName.value)
            assertTrue(handle.pauseCount >= 1)
            assertFalse(handle.closed, "Idle timeout pauses speculation but keeps loopback URL alive for resume")
            assertEquals(MemoryPolicy.Background, governor.policy.value)

            // Subsequent HTTP read wakes stream and re-protects memory policy.
            handle.simulateHttpRead()
            assertTrue(registry.active.value)
            assertEquals(MemoryPolicy.Normal, governor.policy.value)

            registry.closeAll()
            assertEquals(MemoryPolicy.Background, governor.policy.value)
        } finally {
            scope.cancel()
        }
    }
}
