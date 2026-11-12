package dev.piko.shared.state

import dev.piko.shared.media.CLIP_SCAN_READ_AHEAD_BYTES
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PreparedClip
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.transcodeName
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.TransferDemand
import io.github.nihildigit.pikpak.getFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class ClipPreparationTest {
    @Test
    fun downloadDetailsDoNotHideAvailableClipTranscodes() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(1024), transcode = ByteArray(188 * 100), transcodeName = "720P")
        val client = cloud.provider.currentClient.value!!
        val repository = PikoMediaRepository(cloud.provider)
        try {
            assertTrue(client.getFile("f1", usage = io.github.nihildigit.pikpak.FILE_USAGE_FETCH).medias.isEmpty())
            assertTrue(repository.clipProbe("f1").hasTranscode)
            repository.preparePlayback("f1", "720P").getOrThrow().use { playback ->
                assertEquals("720P", playback.info.transcodeName)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun readAheadIntentSurvivesPreparationAndPauseButNotNeighbourHold() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val ready = CompletableDeferred<Unit>()
        val session = dev.piko.shared.media.proxy.ProxySession(
            object : dev.piko.shared.media.proxy.ProxyByteSource {
                override val size = 64L
                override suspend fun openReader(): dev.piko.shared.media.proxy.ProxyReader = error("no reads")
                override fun close() = Unit
            },
        )
        val proxy = dev.piko.shared.media.proxy.ProxyStream("test", session) { session.close() }
        var clipDemand: TransferDemand? = null
        val streams = ClipStreams(PikoMediaRepository(cloud.provider), {}, {}, coroutineContext) { _, _, owner ->
            clipDemand = owner
            ready.await()
            Result.success(PreparedClip(proxy, null, false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val clip = Clip("current", "movie.mkv", "", 0, 60_000)
        try {
            streams.bringToFront(setOf(clip))
            val preparing = streams.get(clip, forPlayer = true)
            streams.boost(clip, 10, 2L * 1024 * 1024)
            awaitUntil("准备阶段持有关键帧扫描预读上限") {
                clipDemand?.intent?.bufferBudget?.maxReadAheadBytes == CLIP_SCAN_READ_AHEAD_BYTES
            }
            val demand = assertNotNull(clipDemand)
            ready.complete(Unit)
            preparing.await()
            assertEquals(2L * 1024 * 1024, demand.intent.bufferBudget.maxReadAheadBytes)

            streams.setSuspended(true)
            assertEquals(ServiceIntent.PAUSED, demand.intent.service)
            assertEquals(0L, demand.intent.bufferBudget.maxReadAheadBytes)

            streams.setSuspended(false)
            assertEquals(ServiceIntent.WAITING, demand.intent.service)
            assertEquals(2L * 1024 * 1024, demand.intent.bufferBudget.maxReadAheadBytes)

            streams.updateFocusState(clip, FocusPlaybackState.Paused)
            assertEquals(ServiceIntent.PAUSED, demand.intent.service)
            assertEquals(0L, demand.intent.bufferBudget.maxReadAheadBytes)

            streams.updateFocusState(clip, FocusPlaybackState.Playing)
            assertEquals(ServiceIntent.PLAYING, demand.intent.service)
            assertEquals(2L * 1024 * 1024, demand.intent.bufferBudget.maxReadAheadBytes)

            streams.holdAllBut(clip.copy(fileId = "another"))
            streams.bringToFront(setOf(clip))
            assertEquals(0L, demand.intent.bufferBudget.maxReadAheadBytes)
        } finally {
            ready.complete(Unit)
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }

    @Test
    fun neighbourChangesPreserveUrgentPhaseAndSuspensionRestoresIt() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val owners = mutableMapOf<Clip, TransferDemand>()
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
            streams.updateFocusState(current, FocusPlaybackState.Waiting)
            streams.bringToFront(setOf(current, neighbour))
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)
            assertEquals(ServiceIntent.PREFETCH, owners.getValue(neighbour).intent.service)
            assertEquals(PikPakStreamReader.WARM_PRIORITY, owners.getValue(neighbour).priority)
            assertEquals(0L, owners.getValue(neighbour).intent.bufferBudget.maxReadAheadBytes)

            streams.bringToFront(setOf(current))
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)
            assertEquals(PikPakStreamReader.WARM_PRIORITY, owners.getValue(neighbour).priority)

            streams.setSuspended(true)
            assertEquals(ServiceIntent.PAUSED, owners.getValue(current).intent.service)
            assertEquals(PikPakStreamReader.BACKGROUND_READ_AHEAD_PRIORITY, owners.getValue(current).priority)

            streams.setSuspended(false)
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, owners.getValue(current).priority)

            streams.updateFocusState(current, FocusPlaybackState.Playing)
            assertEquals(PikPakStreamReader.STREAMING_PRIORITY, owners.getValue(current).priority)

            streams.bringToFront(setOf(neighbour))
            assertEquals(PikPakStreamReader.WARM_PRIORITY, owners.getValue(current).priority)
        } finally {
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }

    @Test
    fun reusedClipRemapsPlayerGenerationsAndRejectsDetachedPlayerFeedback() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val source = object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size = 64L
            override suspend fun openReader() = object : dev.piko.shared.media.proxy.ProxyReader {
                override var position = 0L
                override suspend fun seekTo(position: Long) {
                    this.position = position
                }
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    position += length
                    return length
                }
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        val session = dev.piko.shared.media.proxy.ProxySession(source)
        val proxy = dev.piko.shared.media.proxy.ProxyStream("test", session) { session.close() }
        lateinit var clipDemand: TransferDemand
        val streams = ClipStreams(PikoMediaRepository(cloud.provider), {}, {}, coroutineContext) { _, _, owner ->
            clipDemand = owner
            Result.success(PreparedClip(proxy, "", false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val clip = Clip("f", "movie.mkv", "", 0, 60_000)
        val firstPlayer = Any()
        val nextPlayer = Any()
        val clock = TestTimeSource()
        val sample = PlaybackBufferSnapshot(9, Duration.parse("2s"), clock.markNow())
        try {
            streams.get(clip).await()
            streams.bindPlayback(clip, firstPlayer)
            session.stream(0, 1) { _, _, _ -> }
            streams.reportPlaybackBuffer(clip, firstPlayer, sample)
            val firstAccepted = assertNotNull(clipDemand.intent.playbackSample)
            val oldGeneration = firstAccepted.generation
            assertEquals(Duration.parse("2s"), firstAccepted.bufferedDuration)
            assertEquals(1000L, firstAccepted.mediaBytesPerSecond)

            streams.bindPlayback(clip, nextPlayer)
            assertNull(assertNotNull(clipDemand.intent.playbackSample).bufferedDuration)
            assertTrue(clipDemand.intent.epoch > oldGeneration)

            streams.reportPlaybackBuffer(clip, firstPlayer, sample.copy(generation = 100))
            assertNull(assertNotNull(clipDemand.intent.playbackSample).bufferedDuration)

            clock += Duration.parse("1s")
            streams.reportPlaybackBuffer(clip, nextPlayer, sample.copy(generation = 1))
            val accepted = assertNotNull(clipDemand.intent.playbackSample)
            assertTrue(accepted.generation > oldGeneration)
            assertEquals(Duration.parse("1s"), accepted.sampledAt.elapsedNow())

            streams.reportPlaybackBuffer(clip, nextPlayer, sample.copy(generation = 0))
            assertSame(accepted, clipDemand.intent.playbackSample)
        } finally {
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }

    @Test
    fun coldStartAndWaitingPagePromoteAwaitingCandidateInPlaceAcrossPrepareAndPrefetch() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        val gate0 = CompletableDeferred<Unit>()
        val gate1 = CompletableDeferred<Unit>()
        val owners = mutableMapOf<Clip, TransferDemand>()
        val readyClips = mutableListOf<Clip>()
        val streams = ClipStreams(
            PikoMediaRepository(cloud.provider),
            onReady = { readyClips += it },
            onDead = { error("unexpected dead clip: $it") },
            coroutineContext = coroutineContext,
        ) { clip, _, owner ->
            owners[clip] = owner
            if (clip.fileId == "c0") gate0.await() else gate1.await()
            Result.success(PreparedClip(null, "", false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val c0 = Clip("c0", "ep0.mp4", "", 0, 60_000)
        val c1 = Clip("c1", "ep1.mp4", "", 0, 60_000)
        try {
            // Cold-start before first page is published: awaitingClip gets WAITING + scan budget, c1 stays PREFETCH
            streams.setAwaitingClip(c0)
            streams.ripen(listOf(c0, c1))
            awaitUntil("两个候补均已登记需求") { owners.size == 2 }
            val demand0 = owners.getValue(c0)
            val demand1 = owners.getValue(c1)
            assertEquals(ServiceIntent.WAITING, demand0.intent.service)
            assertEquals(CLIP_SCAN_READ_AHEAD_BYTES, demand0.intent.bufferBudget.maxReadAheadBytes)
            assertEquals(ServiceIntent.PREFETCH, demand1.intent.service)
            assertEquals(0L, demand1.intent.bufferBudget.maxReadAheadBytes)

            // Once c0 finishes ripening while user is on waiting page (focusClip == null), c1 promotes in-place on the SAME TransferDemand
            gate0.complete(Unit)
            awaitUntil("首段就绪后下一候补原地晋升为 WAITING") {
                c0 in readyClips && demand1.intent.service == ServiceIntent.WAITING
            }
            assertSame(demand1, owners.getValue(c1))
            assertEquals(PikPakStreamReader.BLOCKING_PRIORITY, demand1.priority)
            assertEquals(CLIP_SCAN_READ_AHEAD_BYTES, demand1.intent.bufferBudget.maxReadAheadBytes)

            gate1.complete(Unit)
            awaitUntil("次段完成预取") { c1 in readyClips }
        } finally {
            gate0.complete(Unit)
            gate1.complete(Unit)
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }

    @Test
    fun queuedCandidateIsPromotedWithoutReplacingItsDeferredOrRestartingPreparation() = smoke { _ ->
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
            a.await()
            b.await()
            c.await()
            d.await()
            assertEquals(5, started.size)
        } finally {
            release.complete(Unit)
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }

    @Test
    fun receivedBytesTrackPreparationAndStayMonotonicAcrossRetirement() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(64))
        var firstBytes = 0L
        var secondBytes = 0L
        val firstSource = object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size = 64L
            override val receivedBytes: Long get() = firstBytes
            override suspend fun openReader(): dev.piko.shared.media.proxy.ProxyReader = error("no reads")
            override fun close() = Unit
        }
        val secondSource = object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size = 64L
            override val receivedBytes: Long get() = secondBytes
            override suspend fun openReader(): dev.piko.shared.media.proxy.ProxyReader = error("no reads")
            override fun close() = Unit
        }
        val gate = CompletableDeferred<Unit>()
        val streams = ClipStreams(
            PikoMediaRepository(cloud.provider),
            {},
            {},
            coroutineContext,
        ) { clip, _, owner, onByteSource ->
            val src = if (clip.fileId == "c1") firstSource else secondSource
            onByteSource?.invoke(src)
            if (clip.fileId == "c1") gate.await()
            val session = dev.piko.shared.media.proxy.ProxySession(src)
            val proxy = dev.piko.shared.media.proxy.ProxyStream("test-${clip.fileId}", session) { session.close() }
            Result.success(PreparedClip(proxy, null, false, 0, 1.0).also { it.bindDemand(owner) })
        }
        val c1 = Clip("c1", "a.mp4", "", 0, 60_000)
        val c2 = Clip("c2", "b.mp4", "", 0, 60_000)
        try {
            val d1 = streams.get(c1, forPlayer = true)
            awaitUntil("c1 已登记底层字节源") { firstBytes = 4096L; streams.receivedBytes(c1) == 4096L }
            assertEquals(4096L, streams.totalReceivedBytes())
            gate.complete(Unit)
            d1.await()
            firstBytes = 12_288L
            assertEquals(12_288L, streams.receivedBytes(c1))

            streams.get(c2).await()
            secondBytes = 8_192L
            assertEquals(20_480L, streams.totalReceivedBytes())

            // 淘汰 c1 后总接收字节数不倒退，避免等待页网速跳零
            streams.sync(setOf(c2))
            assertEquals(20_480L, streams.totalReceivedBytes())
        } finally {
            gate.complete(Unit)
            streams.closeAll()
            cloud.provider.currentClient.value?.close()
        }
    }
}
