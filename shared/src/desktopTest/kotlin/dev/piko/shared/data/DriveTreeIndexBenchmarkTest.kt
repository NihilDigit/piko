package dev.piko.shared.data

import dev.piko.shared.search.normalizeCjk
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveTreeIndexBenchmarkTest {
    private fun fixture(block: (DesktopPikoTreeDatabaseFactory) -> Unit) {
        val dir = Files.createTempDirectory("piko-tree-bench").toFile()
        try {
            block(DesktopPikoTreeDatabaseFactory(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun folder(id: String, parent: String = "") =
        FileStat(id = id, parentId = parent, name = "Folder_$id", kind = FileKind.FOLDER)

    private fun file(id: String, parent: String, sizeBytes: Long, hash: String = "HASH_$id", name: String = "影片_$id.mp4") =
        FileStat(
            id = id,
            parentId = parent,
            name = name,
            kind = FileKind.FILE,
            size = sizeBytes.toString(),
            hash = hash,
            mimeType = "video/mp4",
            createdTime = "2025-06-01T12:00:00Z",
            modifiedTime = "2025-06-02T12:00:00Z",
            params = mapOf("duration" to "120", "width" to "1920", "height" to "1080"),
        )

    @Test
    fun topSpacePreviewFilesByParentAvoidsWindowFunctionsForSqlite322() = fixture { factory ->
        val raw = factory.open("sqlite322-audit")
        val executedSql = ArrayList<String>()
        val recording = object : PikoTreeDatabase by raw {
            override fun execute(sql: String, parameters: List<Any?>) {
                executedSql += sql
                raw.execute(sql, parameters)
            }

            override fun query(sql: String, parameters: List<Any?>): PikoTreeCursor {
                executedSql += sql
                return raw.query(sql, parameters)
            }

            override fun scalarString(sql: String, parameters: List<Any?>): String? {
                executedSql += sql
                return raw.scalarString(sql, parameters)
            }
        }
        val store = SqliteTreeStore(recording, "sqlite322-audit")
        try {
            val gen = store.beginCandidate()
            store.appendCandidate(
                gen,
                listOf(folder("p1"), folder("p2")) +
                    (1..10).map { i -> file("f1_$i", "p1", i * 1000L) } +
                    (1..5).map { i -> file("f2_$i", "p2", i * 2000L) },
            )
            store.commitCandidate(gen, usage = 100_000L, syncedAt = 1000L, syncCursor = "c1")

            val top = store.topSpacePreviewFilesByParent(listOf("p1", "p2", "missing"), includeVaulted = false, limitPerParent = 3)
            assertEquals(listOf("f1_10", "f1_9", "f1_8"), top["p1"]?.map { it.id })
            assertEquals(listOf("f2_5", "f2_4", "f2_3"), top["p2"]?.map { it.id })
            assertNull(top["missing"])

            val forbidden = listOf("ROW_NUMBER", "OVER (", "RETURNING", "ON CONFLICT")
            for (sql in executedSql) {
                val upper = sql.uppercase()
                for (token in forbidden) {
                    assertFalse(
                        upper.contains(token),
                        "SQLite 3.22 (Android 10) incompatible SQL token '$token' found in: $sql",
                    )
                }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun interruptedCandidateCheckpointSurvivesReopenAndResumesCleanly() = fixture { factory ->
        var index = DriveTreeIndex(database = factory.open("resume-acct"), ownerAccount = "resume-acct")
        try {
            val gen = index.beginCandidate()
            val page1 = listOf(folder("d1"), file("f1", "d1", 1024L), file("f2", "d1", 2048L))
            index.appendCandidate(
                gen,
                page1,
                DriveTreeIndex.CandidateResumeCheckpoint(
                    generation = gen,
                    pageToken = "page-token-2",
                    firstSyncTime = "2026-11-17T10:00:00Z",
                    foldersRead = 1,
                    filesRead = 2,
                ),
            )
            assertFalse(index.isComplete)
            // Simulate process exit / kill before scan completes (close without commit or abort).
            index.close()

            index = DriveTreeIndex(database = factory.open("resume-acct"), ownerAccount = "resume-acct")
            assertFalse(index.isComplete)
            val resumed = assertNotNull(index.resumableCandidate(), "Resumable candidate checkpoint must survive cold reopen")
            assertEquals(gen, resumed.generation)
            assertEquals("page-token-2", resumed.pageToken)
            assertEquals("2026-11-17T10:00:00Z", resumed.firstSyncTime)
            assertEquals(1, resumed.foldersRead)
            assertEquals(2, resumed.filesRead)

            // Append remaining page and commit.
            index.appendCandidate(resumed.generation, listOf(folder("d2"), file("f3", "d2", 4096L)))
            index.commitCandidate(resumed.generation, usageBytes = 7168L, syncedAtMillis = 2000L, syncCursor = resumed.firstSyncTime)

            assertTrue(index.isComplete)
            assertNull(index.resumableCandidate())
            assertEquals("2026-11-17T10:00:00Z", index.syncCursor)
            assertEquals(3, index.folderUsage("")?.fileCount)
            assertEquals(7168L, index.folderUsage("")?.bytes)
            assertNotNull(index.file("f1"))
            assertNotNull(index.file("f3"))
        } finally {
            index.close()
        }
    }

    @Test
    fun resumedCheckpointRejectionOnlyTriggersOnFirstPageDefiniteTokenRejection() {
        val outdated = io.github.nihildigit.pikpak.FileSyncVersionOutdatedException()
        val badToken400 = io.github.nihildigit.pikpak.PikPakException(errorCode = 3, errorMessage = "invalid_argument", httpStatus = 400)
        val rateLimit429 = io.github.nihildigit.pikpak.PikPakException(errorCode = 429, errorMessage = "too_many_requests", httpStatus = 429)
        val timeout408 = io.github.nihildigit.pikpak.PikPakException(errorCode = 408, errorMessage = "timeout", httpStatus = 408)
        val server502 = io.github.nihildigit.pikpak.PikPakException(errorCode = 502, errorMessage = "bad_gateway", httpStatus = 502)
        val networkErr = IllegalStateException("socket reset")

        // First resumed page: only FileSyncVersionOutdatedException or definite 4xx (excluding 408/429) resets checkpoint.
        assertTrue(isResumedCheckpointRejected(outdated, resumedPagesFetched = 0))
        assertTrue(isResumedCheckpointRejected(badToken400, resumedPagesFetched = 0))
        assertFalse(isResumedCheckpointRejected(rateLimit429, resumedPagesFetched = 0))
        assertFalse(isResumedCheckpointRejected(timeout408, resumedPagesFetched = 0))
        assertFalse(isResumedCheckpointRejected(server502, resumedPagesFetched = 0))
        assertFalse(isResumedCheckpointRejected(networkErr, resumedPagesFetched = 0))

        // Mid-scan failure after >= 1 resumed page succeeded MUST preserve checkpoint even on 4xx / 5xx / outdated.
        assertFalse(isResumedCheckpointRejected(outdated, resumedPagesFetched = 1))
        assertFalse(isResumedCheckpointRejected(badToken400, resumedPagesFetched = 40))
        assertFalse(isResumedCheckpointRejected(server502, resumedPagesFetched = 40))
        assertFalse(isResumedCheckpointRejected(rateLimit429, resumedPagesFetched = 40))
    }

    /** Run on demand with -Dpiko.test.bench=true (50k-node profiling benchmark). */
    @Test
    fun profileFiftyThousandNodeTreeIndexPipeline() = fixture { factory ->
        org.junit.Assume.assumeTrue("Benchmark disabled by default; pass -Dpiko.test.bench=true", java.lang.Boolean.getBoolean("piko.test.bench"))
        val folderCount = 200
        val fileCount = 50_000
        val folders = List(folderCount) { i -> folder("d$i", parent = if (i < 20) "" else "d${i % 20}") }
        val files = List(fileCount) { i ->
            file(
                id = "f$i",
                parent = "d${i % folderCount}",
                sizeBytes = (i.toLong() + 1L) * 4096L,
                hash = "GCID_${i % 25_000}",
                name = if (i % 2 == 0) "櫻花アニメ_EP${i}_1080p.mp4" else "Movie_Sample_${i}_HEVC.mkv",
            )
        }

        // Measure pure compute phases independently from SQLite I/O.
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = false }
        val normStart = System.nanoTime()
        var normSink = 0
        for (f in files) normSink += normalizeCjk(f.name).length
        val normMs = (System.nanoTime() - normStart) / 1_000_000L

        val jsonStart = System.nanoTime()
        var jsonSink = 0
        for (f in files) {
            jsonSink += json.encodeToString(DriveTreeIndex.StoredTreeEntry.serializer(), DriveTreeIndex.StoredTreeEntry.of(f)).length
        }
        val jsonMs = (System.nanoTime() - jsonStart) / 1_000_000L
        assertTrue(normSink > 0 && jsonSink > 0)

        var index = DriveTreeIndex(database = factory.open("bench-50k"), ownerAccount = "bench-50k")
        try {
            // 1. Cold full-drive candidate build (500-item pages matching PikPak API pageSize=500).
            val coldStart = System.nanoTime()
            val gen1 = index.beginCandidate()
            index.appendCandidate(gen1, folders)
            for (offset in 0 until fileCount step 500) {
                index.appendCandidate(gen1, files.subList(offset, minOf(offset + 500, fileCount)))
            }
            val coldAppendMs = (System.nanoTime() - coldStart) / 1_000_000L

            val coldCommitStart = System.nanoTime()
            index.commitCandidate(gen1, usageBytes = 1_000_000_000L, syncedAtMillis = 10_000L, syncCursor = "t1")
            val coldCommitMs = (System.nanoTime() - coldCommitStart) / 1_000_000L

            assertTrue(index.isComplete)
            assertEquals(fileCount, index.folderUsage("")?.fileCount)

            // 2. Shadow rebuild (tree_content already populated — exercises batch tree_content prefetch!).
            val shadowStart = System.nanoTime()
            val gen2 = index.beginCandidate()
            index.appendCandidate(gen2, folders)
            for (offset in 0 until fileCount step 500) {
                index.appendCandidate(gen2, files.subList(offset, minOf(offset + 500, fileCount)))
            }
            val shadowAppendMs = (System.nanoTime() - shadowStart) / 1_000_000L

            val shadowCommitStart = System.nanoTime()
            index.commitCandidate(gen2, usageBytes = 1_000_000_000L, syncedAtMillis = 20_000L, syncCursor = "t2")
            val shadowCommitMs = (System.nanoTime() - shadowCommitStart) / 1_000_000L

            // 3. Local CJK search query timing.
            val searchStart = System.nanoTime()
            val hits = index.searchLocal("樱花 49998", "", FolderIndex())
            val searchMs = (System.nanoTime() - searchStart) / 1_000_000L
            assertEquals(listOf("f49998"), hits.map { it.file.id })

            // 4. SpaceMap top-K preview query timing (24 folders x 24 files).
            val spaceStart = System.nanoTime()
            val space = spaceFolderSnapshot(index, "", includeVaulted = true, isBuilding = false)
            val spaceMs = (System.nanoTime() - spaceStart) / 1_000_000L
            assertEquals(20, space.items.size)
            assertEquals(fileCount.toLong(), space.fileCount)

            // 5. Cold reopen timing.
            index.close()
            val reopenStart = System.nanoTime()
            index = DriveTreeIndex(database = factory.open("bench-50k"), ownerAccount = "bench-50k")
            val reopenMs = (System.nanoTime() - reopenStart) / 1_000_000L
            assertTrue(index.isComplete)
            assertEquals(fileCount, index.folderUsage("")?.fileCount)

            println(
                "DriveTreeIndexBenchmark(50k): normCjkMs=$normMs jsonEncodeMs=$jsonMs " +
                    "coldAppendMs=$coldAppendMs coldCommitMs=$coldCommitMs " +
                    "shadowAppendMs=$shadowAppendMs shadowCommitMs=$shadowCommitMs " +
                    "searchMs=$searchMs spaceMs=$spaceMs reopenMs=$reopenMs",
            )
        } finally {
            index.close()
        }
    }
}
