package dev.piko.shared.state

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryCacheStore
import dev.piko.shared.smoke.MemoryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ClipFeedSessionTest {
    private val path = listOf(PikoDriveRepository.ROOT_BREADCRUMB)

    private fun runSession(block: suspend (FakePikPakServer, PikoDriveRepository, ClipFeedSession, MemoryCacheStore) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            runBlocking {
                withContext(dispatcher) {
                    withTimeout(10_000) {
                        val server = FakePikPakServer()
                        val provider = server.provider()
                        val drive = PikoDriveRepository(provider, MemoryPreferences())
                        val cache = MemoryCacheStore()
                        val session = ClipFeedSession(provider, drive, PikoMediaRepository(provider), cache, scope)
                        try { block(server, drive, session, cache) }
                        finally { session.close() }
                    }
                }
            }
        } finally {
            scope.cancel()
            dispatcher.close()
            executor.shutdown()
        }
    }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(1)
    }

    @Test fun oneVideoFillsThenContinuesPastTheFirstPage() = runSession { server, _, session, _ ->
        server.addVideo("single.mp4", durationSeconds = 600.0)
        session.open(path)
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        repeat(40) {
            awaitState { session.upcoming.isNotEmpty() }
            session.promote(session.upcoming.first())
            session.moveTo(session.clips.lastIndex)
        }
        assertEquals(40, session.clips.size)
        assertTrue(session.clips.zipWithNext().all { (a, b) -> abs(a.startMs - b.startMs) >= CLIP_LENGTH_MS })
        assertTrue(session.upcoming.size <= 25)
    }

    @Test fun promotionOfTheFirstPageRefillsEvenWithoutANewPageIndex() = runSession { server, _, session, _ ->
        server.addVideo("single.mp4")
        server.apiDelayMs = 10
        session.open(path)
        awaitState { session.upcoming.isNotEmpty() }
        val first = session.upcoming.first()
        session.promote(first)
        session.moveTo(0)
        awaitState { !session.isCollecting && session.upcoming.size == 25 }
        assertEquals(1, session.clips.size)
        assertFalse(session.isEmpty)
    }

    @Test fun asynchronousReadinessRetainsSameContentRangeOrder() = runSession { server, _, session, _ ->
        server.addVideo("single.mp4", durationSeconds = 60.0)
        session.open(path)
        awaitState { session.upcoming.size == 26 }
        val first = session.upcoming[0]
        val second = session.upcoming[1]
        val third = session.upcoming[2]
        val fourth = session.upcoming[3]
        assertEquals(second.startMs, fourth.startMs)
        assertNotEquals(second, fourth, "Same time ranges must own separate playback resources")
        session.promote(second)
        assertTrue(session.clips.isEmpty())
        session.promote(first)
        assertEquals(listOf(first, second), session.clips)
        session.promote(third)
        assertEquals(listOf(first, second, third), session.clips)
    }

    @Test fun aFailedPrepareRetriesWithANewObservableIdentity() = runSession { server, _, session, _ ->
        server.addVideo("single.mp4", durationSeconds = 60.0)
        session.open(path)
        awaitState { session.upcoming.size == 26 }
        val failed = session.upcoming.first()
        val later = session.upcoming[1]
        session.promote(later)
        session.drop(failed)
        val retry = session.upcoming.first()
        assertEquals(failed.startMs, retry.startMs)
        assertNotEquals(failed, retry)
        session.promote(failed)
        assertTrue(session.clips.isEmpty(), "Late callback from the failed occurrence must be ignored")
        session.promote(retry)
        assertEquals(listOf(retry, later), session.clips)
    }

    @Test fun publishedClipFormatRestoresWithIndependentOccurrenceIdentities() {
        val stored = """{"fileId":"video","name":"video.mp4","parentId":"","startMs":6000,"videoDurationMs":60000}"""
        val first = Json.decodeFromString(Clip.serializer(), stored)
        val second = Json.decodeFromString(Clip.serializer(), stored)
        assertEquals(first.startMs, second.startMs)
        assertNotEquals(first, second)
        assertFalse(Json.encodeToString(Clip.serializer(), first).contains("occurrence"))
    }

    @Test fun selectedVideosRemainFixedAndNeverTraverseSelectedFolders() = runSession { server, drive, session, _ ->
        val selectedNode = server.addVideo("chosen.mp4")
        server.addVideo("outside.mp4")
        val folder = server.addFolder("nested")
        server.addVideo("nested.mp4", folder.id)
        val listed = drive.listBrowsable("", PikoFileSortOrder.TIME_DESC).getOrThrow()
        val selection = listed.filter { it.id == selectedNode.id || it.id == folder.id }.toMutableList()
        val before = server.calls.count { it == "GET /drive/v1/files" }
        session.open(path, selection)
        selection.clear()
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        assertEquals(1, session.selectedVideoCount)
        assertEquals("已选 1 个视频", session.scopeLabel)
        assertTrue(session.upcoming.all { it.fileId == selectedNode.id })
        assertEquals(before, server.calls.count { it == "GET /drive/v1/files" })
    }

    @Test fun changingScopeDuringAProbeCannotAppendTheOldVideo() = runSession { server, drive, session, _ ->
        val first = server.addVideo("first.mp4")
        val second = server.addVideo("second.mp4")
        val files = drive.listBrowsable("", PikoFileSortOrder.TIME_DESC).getOrThrow()
        server.apiDelayMs = 50
        session.open(path, files.filter { it.id == first.id })
        delay(10)
        session.open(path, files.filter { it.id == second.id })
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        delay(75)
        assertTrue(session.upcoming.all { it.fileId == second.id })
    }

    @Test fun selectedScopeDoesNotOverwritePublishedFolderCache() = runSession { server, drive, session, _ ->
        val first = server.addVideo("first.mp4")
        val second = server.addVideo("second.mp4")
        session.open(path)
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        session.close()
        val files = drive.listBrowsable("", PikoFileSortOrder.TIME_DESC).getOrThrow()
        // close saves immediately; the next open joins that write before replacing the scope.
        session.open(path, files.filter { it.id == first.id })
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        session.close()
        session.open(path)
        awaitState { !session.isCollecting && session.upcoming.size == 26 }
        assertEquals(null, session.selectedVideoCount)
        assertTrue(session.upcoming.any { it.fileId == second.id }, "Folder cache must retain the full folder scope")
    }
}
