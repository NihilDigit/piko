package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FolderIndexTest {
    @Test
    fun unchangedUpdatesKeepThePublishedStateAndNodes() {
        val index = FolderIndex()
        val folder = FileStat(id = "child", name = "Child", kind = FileKind.FOLDER)
        assertTrue(index.listed("parent", listOf(folder)))
        val before = index.flow.value
        assertFalse(index.listed("parent", listOf(folder)))
        assertFalse(index.listed("parent", listOf(folder), complete = false))
        assertFalse(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.renamed("child", "Child", always = true))
        assertFalse(index.moved(listOf("child"), "parent"))
        assertFalse(index.removed(listOf("unknown"), emptySet()))
        assertFalse(index.seed(mapOf("child" to "Old"), mapOf("child" to "elsewhere")))
        assertSame(before, index.flow.value)
        assertSame(before.nodes, index.flow.value.nodes)
    }

    @Test
    fun authoritativeListingReplacesProvisionalNodesOnlyOnce() {
        val index = FolderIndex()
        assertTrue(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        val seeded = index.flow.value
        assertFalse(index.seed(mapOf("child" to "Child"), mapOf("child" to "parent")))
        assertSame(seeded, index.flow.value)
        assertTrue(index.learned("child", "Child", "parent", true, false))
        assertFalse(index.node("child")!!.provisional)
        assertFalse(index.learned("child", "Child", "parent", true, false))
    }

    @Test
    fun anEditCannotPublishThroughAnOlderInFlightProjection() {
        val index = FolderIndex()
        index.learned("folder", "before", "", isFolder = true, trashed = false)
        val transforming = CountDownLatch(1)
        val release = CountDownLatch(1)
        val editing = CountDownLatch(1)
        val edited = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val projection = executor.submit {
                index.project { state ->
                    if (state.nodes["folder"]?.name == "before") {
                        transforming.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    state
                }
            }
            assertTrue(transforming.await(5, TimeUnit.SECONDS))
            val edit = executor.submit {
                editing.countDown()
                index.renamed("folder", "after", always = false)
                edited.countDown()
            }
            assertTrue(editing.await(5, TimeUnit.SECONDS))
            assertFalse(edited.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            projection.get(5, TimeUnit.SECONDS)
            edit.get(5, TimeUnit.SECONDS)
            assertEquals("after", index.confirmedNode("folder")?.name)
            assertEquals("after", index.node("folder")?.name)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun legacyFolderStacksStillDecode() {
        assertEquals(
            listOf(PikoPathBreadcrumb("", "网盘"), PikoPathBreadcrumb("piko:starred", "星标")),
            LastFolderStack.decode("::网盘;piko:starred::星标"),
        )
    }

    @Test
    fun searchLocalFoldersMatchesNamePathAndFiltersInvalidDestinations() {
        val entries = listOf(
            FileStat(id = "movies", parentId = "", name = "影视", kind = FileKind.FOLDER),
            FileStat(id = "movies_2026", parentId = "movies", name = "2026", kind = FileKind.FOLDER),
            FileStat(id = "scifi", parentId = "movies_2026", name = "科幻合集", kind = FileKind.FOLDER),
            FileStat(id = "direct_2026", parentId = "", name = "2026归档", kind = FileKind.FOLDER),
            FileStat(id = "trashed_dir", parentId = "", name = "2026回收站", kind = FileKind.FOLDER, trashed = true),
            FileStat(id = "piko_internal", parentId = "", name = ".piko", kind = FileKind.FOLDER),
            FileStat(id = "piko_sub", parentId = "piko_internal", name = "2026配置", kind = FileKind.FOLDER),
            FileStat(id = "normal_file", parentId = "movies", name = "2026.mp4", kind = FileKind.FILE),
        ).associateBy { it.id }

        val folderIndex = FolderIndex().apply {
            learned("extra_folder", "2026待整理", "movies", isFolder = true, trashed = false)
            learned("archive_item", "2026照片.zip", "movies", isFolder = false, trashed = false)
            seed(mapOf("provisional_dir" to "2026临时"), mapOf("provisional_dir" to ""))
        }

        val hits = DriveTreeIndex.searchLocalFolders(
            query = "2026",
            entries = entries,
            indexNodes = folderIndex.flow.value.nodes,
            gone = folderIndex.flow.value.gone,
        )
        val hitIds = hits.map { it.folder.id }
        assertTrue("movies_2026" in hitIds)
        assertTrue("direct_2026" in hitIds)
        assertTrue("extra_folder" in hitIds)
        // 子目录通过父路径命中，且排在直接命中目录名的条目之后
        assertTrue("scifi" in hitIds)
        assertTrue(hitIds.indexOf("movies_2026") < hitIds.indexOf("scifi"))
        // 排除文件、压缩包、回收站、.piko 内部目录及 provisional 节点
        assertFalse("normal_file" in hitIds)
        assertFalse("archive_item" in hitIds)
        assertFalse("trashed_dir" in hitIds)
        assertFalse("piko_internal" in hitIds)
        assertFalse("piko_sub" in hitIds)
        assertFalse("provisional_dir" in hitIds)

        val scifiHit = hits.first { it.folder.id == "scifi" }
        assertEquals(
            listOf(
                PikoPathBreadcrumb("movies", "影视"),
                PikoPathBreadcrumb("movies_2026", "2026"),
                PikoPathBreadcrumb("scifi", "科幻合集"),
            ),
            scifiHit.path,
        )
    }

    @Test
    fun searchLocalFoldersSupportsPathQueryAndGoneFiltering() {
        val entries = listOf(
            FileStat(id = "work", parentId = "", name = "工作", kind = FileKind.FOLDER),
            FileStat(id = "docs", parentId = "work", name = "文档", kind = FileKind.FOLDER),
            FileStat(id = "deleted_parent", parentId = "", name = "旧目录", kind = FileKind.FOLDER),
            FileStat(id = "deleted_child", parentId = "deleted_parent", name = "文档", kind = FileKind.FOLDER),
        ).associateBy { it.id }

        val hits = DriveTreeIndex.searchLocalFolders(
            query = "工作/文档",
            entries = entries,
            indexNodes = emptyMap(),
            gone = setOf("deleted_parent"),
        )
        assertEquals(listOf("docs"), hits.map { it.folder.id })
    }
}
