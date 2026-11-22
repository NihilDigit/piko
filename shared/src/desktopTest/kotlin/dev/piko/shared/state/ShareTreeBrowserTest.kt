package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ShareFolderPage
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareTreeBrowserTest {

    private data class RecordedRestore(
        val toParentId: String,
        val ancestorIds: List<String>,
        val fileIds: List<String>,
    )

    private class FakeShareSource(
        private val pageSize: Int = 100,
    ) : ShareTreeDataSource {
        private val childrenByParent = LinkedHashMap<String, MutableList<FileStat>>()
        val folderPageCalls = mutableListOf<Pair<String, String>>()
        val restoreCalls = mutableListOf<RecordedRestore>()
        val createdFolders = mutableListOf<Triple<String, String, String>>() // (parentId, name, createdId)
        private var nextCreatedId = 1

        /** Optional gate to pause a specific `(parentId, pageToken)` fetch for concurrency testing. */
        var pauseGate: Pair<String, String>? = null
        var pausedEntered: CompletableDeferred<Unit>? = null
        var pauseRelease: CompletableDeferred<Unit>? = null

        fun addFolder(id: String, name: String, parentId: String = ""): FileStat {
            val stat = FileStat(kind = FileKind.FOLDER, id = id, parentId = parentId, name = name)
            childrenByParent.getOrPut(parentId) { mutableListOf() }.add(stat)
            childrenByParent.putIfAbsent(id, mutableListOf())
            return stat
        }

        fun addFile(id: String, name: String, parentId: String = "", size: Long = 1024L, hash: String = "GCID-$id"): FileStat {
            val stat = FileStat(kind = FileKind.FILE, id = id, parentId = parentId, name = name, size = size.toString(), hash = hash)
            childrenByParent.getOrPut(parentId) { mutableListOf() }.add(stat)
            return stat
        }

        fun rootInfo(title: String = "Huge Share"): ShareInfo {
            val root = childrenByParent[""].orEmpty()
            val firstPage = root.take(pageSize)
            val next = if (root.size > pageSize) pageSize.toString() else ""
            return ShareInfo(
                shareStatus = ShareStatus.OK,
                passCodeToken = "TOKEN",
                title = title,
                fileNum = root.size.toString(),
                files = firstPage,
                nextPageToken = next,
            )
        }

        override suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> =
            Result.success(rootInfo())

        override suspend fun shareFolderPage(
            shareId: String,
            passCodeToken: String,
            parentId: String,
            pageToken: String,
        ): Result<ShareFolderPage> {
            folderPageCalls += parentId to pageToken
            if (pauseGate == (parentId to pageToken)) {
                pausedEntered?.complete(Unit)
                pauseRelease?.await()
            }
            val all = childrenByParent[parentId].orEmpty()
            val offset = pageToken.toIntOrNull() ?: 0
            val slice = all.drop(offset).take(pageSize)
            val next = if (offset + slice.size < all.size) (offset + slice.size).toString() else ""
            return Result.success(
                ShareFolderPage(
                    shareStatus = ShareStatus.OK,
                    files = slice,
                    nextPageToken = next,
                ),
            )
        }

        override suspend fun restoreFromShare(
            shareId: String,
            passCodeToken: String,
            fileIds: List<String>,
            toParentId: String,
            ancestorIds: List<String>,
        ): Result<Unit> {
            restoreCalls += RecordedRestore(toParentId = toParentId, ancestorIds = ancestorIds, fileIds = fileIds)
            return Result.success(Unit)
        }

        override suspend fun createFolder(parentId: String, name: String): Result<String> {
            val id = "created-${nextCreatedId++}"
            createdFolders += Triple(parentId, name, id)
            return Result.success(id)
        }
    }

    @Test
    fun `nested share with over 100 folders loads lazily and restores unexpanded subtrees without crawling`() = runBlocking {
        val source = FakeShareSource(pageSize = 50)
        for (i in 1..120) {
            val top = source.addFolder("top-$i", "Collection $i")
            val nested = source.addFolder("sub-$i", "Season 1", parentId = top.id)
            source.addFile("file-$i", "episode-$i.mkv", parentId = nested.id)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser(
                shareId = "VP18nKwD3Y-8azUkWrFGLGnmo2",
                info = source.rootInfo("Giant Archive"),
                dataSource = source,
                scope = scope,
                autoExpandSingleRoot = false,
            )

            assertTrue(browser.isLazyMode)
            assertTrue(browser.rootHasMore)
            assertEquals(50, browser.loadedNodeCount)
            assertEquals(0, source.folderPageCalls.size, "Opening a huge share must not eagerly crawl subfolders")
            assertTrue(browser.visibleRows().last() is ShareTreeRow.LoadMoreRow)

            browser.loadNextPage("top-1").getOrThrow()
            assertEquals(listOf("top-1" to ""), source.folderPageCalls)

            val restoredCount = browser.restoreSelected("dest-folder").getOrThrow()
            assertEquals(120, restoredCount)
            assertEquals(
                listOf("top-1" to "", "" to "50", "" to "100"),
                source.folderPageCalls,
                "Server-side restore must not crawl unexpanded subfolders",
            )
            val restoredIds = source.restoreCalls.flatMap { it.fileIds }
            assertEquals(120, restoredIds.size)
            assertTrue(source.restoreCalls.all { it.ancestorIds.isEmpty() && it.toParentId == "dest-folder" })
            assertEquals(2, source.restoreCalls.size, "120 IDs should be chunked into 100 + 20 batches")
            assertTrue(source.createdFolders.isEmpty(), "Whole-folder restore needs no extra folder creation")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `top-level pagination loads incrementally and preserves selection across pages`() = runBlocking {
        val source = FakeShareSource(pageSize = 40)
        for (i in 1..105) {
            source.addFile("f-$i", "video-$i.mkv")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser("share-paged", source.rootInfo(), source, scope, autoExpandSingleRoot = false)
            assertTrue(browser.isLazyMode, "Paginated root must enter lazy mode even with flat files")
            assertEquals(40, browser.loadedNodeCount)
            assertTrue(browser.rootHasMore)

            browser.loadNextPage("").getOrThrow()
            assertEquals(80, browser.loadedNodeCount)
            assertTrue(browser.rootHasMore)

            browser.loadNextPage("").getOrThrow()
            assertEquals(105, browser.loadedNodeCount)
            assertFalse(browser.rootHasMore)

            val target = browser.nodeById("f-105")!!
            browser.setNodeSelected(target, false)
            assertFalse(browser.isAllSelected)
            val batches = browser.computeRestoreBatches().getOrThrow()
            assertEquals(104, batches.sumOf { it.fileIds.size })
            assertTrue("f-105" !in batches.flatMap { it.fileIds })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `partial selection preserves nested subfolders via createFolder without flattening or collisions`() = runBlocking {
        val source = FakeShareSource(pageSize = 100)
        val show = source.addFolder("Show", "MyShow")
        val s1 = source.addFolder("S1", "Season 1", parentId = show.id)
        val s2 = source.addFolder("S2", "Season 2", parentId = show.id)
        val s3 = source.addFolder("S3", "Season 3", parentId = show.id)
        source.addFile("s1-ep1", "01.mkv", parentId = s1.id)
        source.addFile("s1-ad", "ad.txt", parentId = s1.id)
        source.addFile("s2-ep1", "01.mkv", parentId = s2.id)
        source.addFile("s2-ad", "ad.txt", parentId = s2.id)
        source.addFile("s3-ep1", "01.mkv", parentId = s3.id)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser("share-nested", source.rootInfo(), source, scope, autoExpandSingleRoot = false)
            browser.loadNextPage(show.id).getOrThrow()
            browser.loadNextPage(s1.id).getOrThrow()
            browser.loadNextPage(s2.id).getOrThrow()

            // Uncheck ads in S1 and S2 -> Show, S1, S2 are INDETERMINATE; S3 stays ON (unexpanded!).
            browser.setNodeSelected(browser.nodeById("s1-ad")!!, false)
            browser.setNodeSelected(browser.nodeById("s2-ad")!!, false)

            assertEquals(ShareNodeCheckState.INDETERMINATE, browser.checkStateOf(browser.nodeById("Show")!!))
            assertEquals(ShareNodeCheckState.INDETERMINATE, browser.checkStateOf(browser.nodeById("S1")!!))
            assertEquals(ShareNodeCheckState.INDETERMINATE, browser.checkStateOf(browser.nodeById("S2")!!))
            assertEquals(ShareNodeCheckState.ON, browser.checkStateOf(browser.nodeById("S3")!!))

            val restoredCount = browser.restoreSelected("dest-root").getOrThrow()
            assertEquals(3, restoredCount)

            // S3 must never be crawled!
            assertTrue(source.folderPageCalls.none { it.first == "S3" })

            // Verify folder materialization hierarchy: dest-root/MyShow -> Season 1 & Season 2.
            val showCreated = source.createdFolders.single { it.first == "dest-root" && it.second == "MyShow" }.third
            val s1Created = source.createdFolders.single { it.first == showCreated && it.second == "Season 1" }.third
            val s2Created = source.createdFolders.single { it.first == showCreated && it.second == "Season 2" }.third
            assertEquals(3, source.createdFolders.size)

            // Verify each restore lands in its exact materialized subfolder with exact ancestorIds!
            assertEquals(
                setOf(
                    RecordedRestore(toParentId = s1Created, ancestorIds = listOf("Show", "S1"), fileIds = listOf("s1-ep1")),
                    RecordedRestore(toParentId = s2Created, ancestorIds = listOf("Show", "S2"), fileIds = listOf("s2-ep1")),
                    RecordedRestore(toParentId = showCreated, ancestorIds = listOf("Show"), fileIds = listOf("S3")),
                ),
                source.restoreCalls.toSet(),
            )

            // Re-checking both ads collapses overrides cleanly back to All Selected.
            browser.setNodeSelected(browser.nodeById("s1-ad")!!, true)
            browser.setNodeSelected(browser.nodeById("s2-ad")!!, true)
            assertTrue(browser.isAllSelected)
            assertEquals(ShareNodeCheckState.ON, browser.checkStateOf(browser.nodeById("Show")!!))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent save while loadNextPage is in flight suspends cleanly on per-folder mutex without spin deadlock`() = runBlocking {
        // Runs entirely on runBlocking's single-threaded event loop (simulating Dispatchers.Main.immediate).
        val source = FakeShareSource(pageSize = 2)
        for (i in 1..5) {
            source.addFile("f-$i", "file-$i.mkv")
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.pauseGate = "" to "2"
        source.pausedEntered = entered
        source.pauseRelease = release

        val browser = ShareTreeBrowser("share-race", source.rootInfo(), source, this, autoExpandSingleRoot = false)
        assertTrue(browser.rootHasMore)

        // Start a UI loadMore("") that suspends on page 2.
        val uiLoad = launch { browser.loadNextPage("") }
        withTimeout(2_000) { entered.await() }

        // While page 2 is in flight, trigger restoreSelected (which calls loadAllPagesOfSingleFolder("")).
        // Previously this deadlocked a single-threaded dispatcher in a 100% CPU spin loop!
        val saveJob = async { browser.restoreSelected("dest") }
        yield()
        assertFalse(saveJob.isCompleted, "Save must suspend awaiting the in-flight page load")

        // Release page 2 -> both uiLoad and saveJob must complete cleanly with all 5 files restored.
        release.complete(Unit)
        withTimeout(2_000) {
            uiLoad.join()
            val count = saveJob.await().getOrThrow()
            assertEquals(5, count)
        }
        assertEquals(listOf("f-1", "f-2", "f-3", "f-4", "f-5"), source.restoreCalls.flatMap { it.fileIds })
    }

    @Test
    fun `vault streaming traversal handles over 120 folders in bounded chunks and skips unselected subtrees`() = runBlocking {
        val source = FakeShareSource(pageSize = 20)
        val skippedFolder = source.addFolder("skip-root", "Skipped")
        source.addFile("skip-file", "ignored.mkv", parentId = skippedFolder.id)

        for (i in 1..125) {
            val dir = source.addFolder("dir-$i", "Dir $i")
            source.addFile("f-$i-1", "a.mkv", parentId = dir.id, size = 100L)
            source.addFile("f-$i-2", "b.mkv", parentId = dir.id, size = 200L)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser("share-vault", source.rootInfo(), source, scope, autoExpandSingleRoot = false)
            browser.setNodeSelected(browser.nodeById("skip-root")!!, false)

            val batchSizes = mutableListOf<Int>()
            val collectedPaths = mutableListOf<String>()
            val totalSaved = browser.streamSelectedForVault { batch ->
                batchSizes += batch.size
                collectedPaths += batch.map { it.file.path }
                Result.success(batch.size)
            }.getOrThrow()

            assertEquals(250, totalSaved, "All 250 files across 125 folders must be collected without 64-folder truncation")
            assertEquals(250, collectedPaths.size)
            assertTrue(collectedPaths.none { it.startsWith("Skipped/") }, "Unselected subtree must be skipped")
            assertTrue(source.folderPageCalls.none { it.first == "skip-root" }, "Unselected folder must never be fetched")
            assertTrue(batchSizes.all { it <= 200 }, "Batches must remain bounded; got $batchSizes")
            assertTrue(batchSizes.size >= 2, "250 files should flush across at least 2 bounded batches")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `streamSelectedForVault keeps large single folder together, reports progress, and does not deadlock Persistence permit`() = runBlocking {
        val source = FakeShareSource(pageSize = 100)
        val bigFolder = source.addFolder("season-1", "Season 1")
        for (i in 1..450) {
            source.addFile("ep-$i", "ep-$i.mp4", parentId = bigFolder.id, size = 1024L)
        }
        val governor = dev.piko.shared.memory.PikoMemoryGovernor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser(
                shareId = "share-single-folder",
                info = source.rootInfo(),
                dataSource = source,
                scope = scope,
                memoryGovernor = governor,
                autoExpandSingleRoot = false,
            )
            val progressSnapshots = mutableListOf<ShareVaultProgress>()
            val batchSizes = mutableListOf<Int>()
            val totalSaved = kotlinx.coroutines.withTimeout(5_000) {
                browser.streamSelectedForVault(
                    onProgress = { progressSnapshots += it },
                ) { batch ->
                    // Simulate SnapshotWriter acquiring Persistence permit inside onBatch!
                    val persistPermit = governor.acquire(dev.piko.shared.memory.MemoryWorkKind.Persistence, 0L)
                    try {
                        batchSizes += batch.size
                    } finally {
                        persistPermit.release()
                    }
                    Result.success(batch.size)
                }.getOrThrow()
            }
            assertEquals(450, totalSaved)
            assertEquals(listOf(450), batchSizes, "All 450 items of a single folder must flush in one v1 batch")
            assertTrue(progressSnapshots.isNotEmpty())
            assertEquals(450, progressSnapshots.last().filesSaved)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `20000-item selection uses O(1) compact representation and masks shareId in logs`() = runBlocking {
        assertEquals("***", ShareTreeBrowser.maskShareId("abc123"))
        assertEquals("VP18…", ShareTreeBrowser.maskShareId("VP18nKwD3Y-8azUkWrFGLGnmo2"))

        val total = 20_000
        var selection = CompactIndexSelection.all(total)
        assertEquals(total, selection.size)
        assertTrue(selection.isAllSelected)
        assertEquals(0, selection.exceptions.size)

        for (i in 0 until 50) {
            selection = selection.withSingle(i * 100, false)
        }
        assertEquals(total - 50, selection.size)
        assertEquals(50, selection.exceptions.size)
        assertTrue(selection.defaultSelected)
        assertFalse(0 in selection)
        assertTrue(1 in selection)

        val bigFolder = (0 until 10_000).toHashSet()
        assertEquals(10_000 - 50, selection.countIn(bigFolder))

        selection = selection.withSelected((0 until 15_000).toList(), false)
        assertEquals(5_000, selection.size)
        assertFalse(selection.defaultSelected)
        assertEquals(5_000, selection.exceptions.size)
        assertFalse(14_999 in selection)
        assertTrue(15_000 in selection)

        val source = FakeShareSource(pageSize = 20_000)
        for (i in 0 until total) {
            source.addFile("item-$i", "file-$i.mkv")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = ShareTreeBrowser("share-20k", source.rootInfo(), source, scope, autoExpandSingleRoot = false)
            assertEquals(total, browser.loadedNodeCount)
            browser.setNodeSelected(browser.nodeById("item-42")!!, false)
            assertFalse(browser.isAllSelected)
            assertEquals(ShareNodeCheckState.OFF, browser.checkStateOf(browser.nodeById("item-42")!!))
            assertEquals(ShareNodeCheckState.ON, browser.checkStateOf(browser.nodeById("item-43")!!))
            browser.setAllSelected(false)
            assertFalse(browser.hasSelection)
            browser.setAllSelected(true)
            assertTrue(browser.isAllSelected)
        } finally {
            scope.cancel()
        }
    }
}
