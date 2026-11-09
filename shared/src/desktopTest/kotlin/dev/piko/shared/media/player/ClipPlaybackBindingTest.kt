package dev.piko.shared.media.player

import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.proxy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*

class ClipPlaybackBindingTest {
    private class Backend : PlaybackBackend {
        override val positionMillis = 0L
        override val durationMillis = 60_000L
        override val bufferedPositionMillis = 0L
        override val isPlaying = false
        override val isBuffering = false
        override val speed = 1f
        override val supportsSpeed = true
        override val aspectRatio = PlayerAspectRatio.Fit
        override val videoAspect: Float? = null
        override val volume: Float? = null
        override val events = emptyFlow<PlaybackBackendEvent>()
        val idle = CompletableDeferred<Unit>()
        val stopStarted = CompletableDeferred<Unit>()
        var stops = 0
        var opens = 0
        var onOpen: suspend () -> Unit = {}
        override suspend fun open(target: PlaybackTarget, startMillis: Long, playWhenReady: Boolean, subtitles: List<ExternalSubtitle>) {
            opens++
            onOpen()
        }
        override suspend fun stopAndAwaitIdle() { stops++; stopStarted.complete(Unit); idle.await() }
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMillis: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setAspectRatio(mode: PlayerAspectRatio) = Unit
        override fun setVolume(volume: Float) = Unit
    }

    private class Resource {
        var closed = false
        val session = ProxySession(object : ProxyByteSource {
            override val size = 1L
            override suspend fun openReader(): ProxyReader = error("no reader expected")
            override fun close() { closed = true }
        })
        val clip = PreparedClip(ProxyStream("test", session) { session.close() }, null, false, 0, 0.0)
    }

    @Test fun retiredClipKeepsEndpointUntilNativeIdleAndSerializesReplacement() = runBlocking {
        val backend = Backend()
        val binding = ClipPlaybackBinding(backend, coroutineContext)
        val old = Resource()
        val next = Resource()
        try {
            binding.open(old.clip, PlaybackTarget.Url("old"), 0, false)
            old.clip.close()
            withTimeout(5000) { backend.stopStarted.await() }
            assertTrue(old.session.isWithdrawn)
            assertFalse(old.session.isClosed)
            assertFalse(old.closed)
            assertEquals(1, backend.stops)
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { binding.open(next.clip, PlaybackTarget.Url("next"), 0, false) }
            yield()
            assertEquals(1, backend.opens)
            backend.idle.complete(Unit)
            replacement.await()
            assertTrue(old.closed)
            assertEquals(2, backend.opens)
            old.clip.close()
            yield()
            assertEquals(1, backend.stops, "an old retirement cannot stop its replacement")
            next.clip.close()
            binding.retire().join()
            assertTrue(next.closed)
        } finally {
            backend.idle.complete(Unit)
            old.clip.close(); next.clip.close(); binding.retire().join()
        }
    }

    @Test fun retiringClipCancelsStalledOpenAndDrainsBeforeClosingSource() = runBlocking {
        val backend = Backend().apply { onOpen = { awaitCancellation() } }
        val binding = ClipPlaybackBinding(backend, coroutineContext)
        val resource = Resource()
        val loading = launch(start = CoroutineStart.UNDISPATCHED) { binding.open(resource.clip, PlaybackTarget.Url("clip"), 0, false) }
        yield()
        resource.clip.close()
        withTimeout(5000) { backend.stopStarted.await() }
        assertFalse(resource.closed)
        assertFalse(loading.isCompleted)
        backend.idle.complete(Unit)
        loading.join()
        binding.retire().join()
        assertTrue(resource.closed)
    }
}
