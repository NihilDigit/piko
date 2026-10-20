package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DriveAnalysisCacheTest {
    @Test
    fun cancellingTheComputingCallerDoesNotCancelAnActiveWaiter() = runBlocking {
        withTimeout(5_000) {
            val files = listOf(FileStat(id = "cache-cancel-owner", name = "Show - 01.mkv"))
            val started = CompletableDeferred<Unit>()
            val expected = DriveStructure(emptyList(), emptyList(), emptySet(), emptyMap(), emptyMap())
            val owner = async {
                DriveAnalysisCache.analyze(files, Dispatchers.Unconfined) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                DriveAnalysisCache.analyze(files, Dispatchers.Unconfined) { expected }
            }
            owner.cancelAndJoin()
            assertSame(expected, waiter.await())
        }
    }

    @Test
    fun fingerprintIncludesTypeAndOrder() {
        fun key(kind: String, mime: String) = DriveAnalysisCache.fingerprint(
            listOf(FileStat(id = "cache-type", name = "Show - 01", kind = kind, mimeType = mime)),
        )
        assertNotEquals(key(FileKind.FILE, "video/mp4"), key(FileKind.FOLDER, "video/mp4"))
        assertNotEquals(key(FileKind.FILE, "video/mp4"), key(FileKind.FILE, "text/plain"))
        val files = listOf(FileStat(id = "one"), FileStat(id = "two"))
        assertNotEquals(DriveAnalysisCache.fingerprint(files), DriveAnalysisCache.fingerprint(files.reversed()))
    }

    @Test
    fun concurrentAnalysisAndVideoDiscoveryReuseTheSameStructure() = runBlocking {
        val files = listOf(FileStat(id = "cache-shared", name = "Show - 01.mkv"))
        val structures = List(8) { async { DriveAnalysisCache.analyze(files) } }.awaitAll()
        structures.forEach { assertSame(structures.first(), it) }
        val replacement = DriveStructure(emptyList(), emptyList(), setOf("cache-shared"), emptyMap(), emptyMap())
        DriveAnalysisCache.putStructure(DriveAnalysisCache.fingerprint(files), replacement)
        val batch = analyzeVideoFolder("cache-folder", files)
        kotlin.test.assertTrue(batch.candidates.isEmpty())
        assertSame(replacement, DriveAnalysisCache.analyze(files))
    }

    @Test
    fun folderViewHitsRefreshEvictionOrder() = runBlocking {
        val view = describeDriveFolder("cache", null)
        repeat(4_000) { DriveAnalysisCache.putFolderView("lru-$it", view) }
        assertSame(view, DriveAnalysisCache.folderView("lru-0"))
        DriveAnalysisCache.putFolderView("lru-new", view)
        assertSame(view, DriveAnalysisCache.folderView("lru-0"))
        assertNull(DriveAnalysisCache.folderView("lru-1"))
    }
}
