package dev.piko.shared.state

import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.transcodeName
import dev.piko.shared.media.testing.FakePikPakCloud
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.getFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.piko.shared.smoke.smoke
import dev.piko.shared.smoke.awaitUntil
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ClipPreparationTest {
    @Test fun downloadDetailsDoNotHideAvailableClipTranscodes() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(1024), transcode = ByteArray(188 * 100), transcodeName = "720P")
        val client = cloud.provider.currentClient.value!!
        val repository = PikoMediaRepository(cloud.provider)
        try {
            assertTrue(client.getFile("f1", usage = io.github.nihildigit.pikpak.FILE_USAGE_FETCH).medias.isEmpty())
            assertTrue(repository.clipProbe("f1").hasTranscode)
            repository.preparePlayback("f1", "720P").getOrThrow().use { playback ->
                assertEquals("720P", playback.info.transcodeName)
            }
        } finally { client.close() }
    }

    @Test fun readAheadIntentSurvivesPreparationAndPauseButNotNeighbourHold() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val ready = CompletableDeferred<Unit>()
        val session = dev.piko.shared.media.proxy.ProxySession(object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size = 64L
            override suspend fun openReader(): dev.piko.shared.media.proxy.ProxyReader = error("no reads")
            override fun close() = Unit
        })
        val proxy = dev.piko.shared.media.proxy.ProxyStream("test", session) { session.close() }
        val streams = ClipStreams(PikoMediaRepository(cloud.provider), {}, {}, coroutineContext) { _, _, owner ->
            ready.await()
            Result.success(PreparedClip(proxy, null, false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val clip = Clip("current", "movie.mkv", "", 0, 60_000)
        try {
            streams.bringToFront(setOf(clip))
            val preparing = streams.get(clip, forPlayer = true)
            streams.boost(clip, 10, 2L * 1024 * 1024)
            ready.complete(Unit)
            preparing.await()
            assertEquals(2L * 1024 * 1024, session.readAheadLimit)
            streams.setSuspended(true)
            assertEquals(0L, session.readAheadLimit)
            streams.setSuspended(false)
            assertEquals(2L * 1024 * 1024, session.readAheadLimit)
            streams.setUrgent(clip, false, playing = false)
            assertEquals(0L, session.readAheadLimit)
            streams.setUrgent(clip, false, playing = true)
            assertEquals(2L * 1024 * 1024, session.readAheadLimit)
            streams.holdAllBut(clip.copy(fileId = "another"))
            streams.bringToFront(setOf(clip))
            assertEquals(0L, session.readAheadLimit)
        } finally { ready.complete(Unit); streams.closeAll(); cloud.provider.currentClient.value?.close() }
    }

    @Test fun neighbourChangesPreserveUrgentPhaseAndSuspensionRestoresIt() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val owners = mutableMapOf<Clip, io.github.nihildigit.pikpak.TransferDemand>()
        val streams = ClipStreams(PikoMediaRepository(cloud.provider), {}, {}, coroutineContext) { clip, _, owner ->
            owners[clip] = owner
            Result.success(PreparedClip(null, "", false, 0, 0.0))
        }
        val current = Clip("current", "movie.mkv", "", 0, 60_000)
        val neighbour = Clip("next", "movie.mkv", "", 0, 60_000)
        try {
            streams.get(current, forPlayer = true).await()
            streams.get(neighbour).await()
            streams.bringToFront(setOf(current))
            streams.setUrgent(current, true)
            streams.bringToFront(setOf(current, neighbour))
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)
            streams.bringToFront(setOf(current))
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)
            streams.setSuspended(true)
            assertEquals(PikPakStreamReader.WARM_PRIORITY, owners.getValue(current).priority)
            streams.setSuspended(false)
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)
            streams.setUrgent(current, false)
            assertEquals(PikPakStreamReader.STREAMING_PRIORITY, owners.getValue(current).priority)
            streams.bringToFront(setOf(neighbour))
            assertEquals(PikPakStreamReader.WARM_PRIORITY, owners.getValue(current).priority)
        } finally { streams.closeAll(); cloud.provider.currentClient.value?.close() }
    }

    @Test fun reusedClipRemapsPlayerGenerationsAndRejectsDetachedPlayerFeedback() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val snapshots = mutableListOf<io.github.nihildigit.pikpak.PlaybackBufferSnapshot>()
        val source = object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size = 64L
            override suspend fun openReader() = object : dev.piko.shared.media.proxy.ProxyReader {
                override var position = 0L
                override suspend fun seekTo(position: Long) { this.position = position }
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { position += length; return length }
                override fun reportPlaybackBuffer(snapshot: io.github.nihildigit.pikpak.PlaybackBufferSnapshot) { snapshots.add(snapshot) }
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        val session = dev.piko.shared.media.proxy.ProxySession(source)
        val proxy = dev.piko.shared.media.proxy.ProxyStream("test", session) { session.close() }
        val streams = ClipStreams(PikoMediaRepository(cloud.provider), {}, {}, coroutineContext) { _, _, owner ->
            Result.success(PreparedClip(proxy, "", false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val clip = Clip("f", "movie.mkv", "", 0, 60_000)
        val firstPlayer = Any()
        val nextPlayer = Any()
        val clock = kotlin.time.TestTimeSource()
        val sample = io.github.nihildigit.pikpak.PlaybackBufferSnapshot(9, kotlin.time.Duration.parse("2s"), clock.markNow())
        try {
            streams.get(clip).await()
            streams.bindPlayback(clip, firstPlayer)
            session.stream(0, 1) { _, _, _ -> }
            streams.reportPlaybackBuffer(clip, firstPlayer, sample)
            val oldGeneration = snapshots.last().generation
            streams.bindPlayback(clip, nextPlayer)
            assertNull(snapshots.last().bufferedDuration)
            streams.reportPlaybackBuffer(clip, firstPlayer, sample.copy(generation = 100))
            assertNull(snapshots.last().bufferedDuration)
            clock += kotlin.time.Duration.parse("1s")
            streams.reportPlaybackBuffer(clip, nextPlayer, sample.copy(generation = 1))
            assertTrue(snapshots.last().generation > oldGeneration)
            assertEquals(kotlin.time.Duration.parse("1s"), snapshots.last().sampledAt.elapsedNow())
            val accepted = snapshots.last()
            streams.reportPlaybackBuffer(clip, nextPlayer, sample.copy(generation = 0))
            assertSame(accepted, snapshots.last())
        } finally { streams.closeAll(); cloud.provider.currentClient.value?.close() }
    }

    @Test fun queuedCandidateIsPromotedWithoutReplacingItsDeferredOrRestartingPreparation() = smoke { scope ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val repository = PikoMediaRepository(cloud.provider)
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val priorities = mutableListOf<Int>()
        val streams = ClipStreams(repository, {}, { error("unexpected dead clip") }, coroutineContext) { clip, _, owner ->
            started.add(clip.fileId)
            priorities.add(owner.priority)
            if (clip.fileId != "current") release.await()
            Result.success(PreparedClip(null, "", false, 0, 0.0))
        }
        fun clip(id: String) = Clip(id, "movie.mkv", "", 0, 600_000)
        val a = streams.get(clip("a"))
        val b = streams.get(clip("b"))
        val c = streams.get(clip("c"))
        val d = streams.get(clip("d"))
        val current = clip("current")
        val candidate = streams.get(current)
        try {
            awaitUntil("四个候补已进入准备") { started.size == 4 }
            assertEquals(listOf("a", "b", "c", "d"), started)
            assertSame(candidate, streams.get(current, forPlayer = true))
            awaitUntil("当前片段获得保留准备入口") { candidate.isCompleted }
            assertTrue(candidate.isCompleted)
            assertNotNull(candidate.await())
            assertEquals(listOf("a", "b", "c", "d", "current"), started)
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, priorities.last())
            release.complete(Unit)
            a.await(); b.await(); c.await(); d.await()
            assertEquals(5, started.size)
        } finally { release.complete(Unit); streams.closeAll(); cloud.provider.currentClient.value?.close() }
    }
}
