package dev.piko.shared.state

import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveListingTest {

    private fun file(name: String, size: Long = 100_000_000) = FileStat(kind = FileKind.FILE, id = "f:$name", name = name, size = size.toString())

    private fun items(names: List<String>): List<DriveListItem> {
        val files = names.map { file(it) }
        return buildDriveItems(files, analyzeDriveFolder(files), hideFolded = true) { true }
    }

    @Test
    fun `a video without an extension is typed by its mime`() {
        val bare = FileStat(
            kind = FileKind.FILE, id = "bare", name = "[Grp] Show - 12 [WebRip 1080p HEVC-10bit AAC][END]",
            size = "600000000", mimeType = "video/x-matroska",
        )
        val readme = file("readme.txt")
        val structure = analyzeDriveFolder(listOf(bare, readme))
        assertTrue("bare" !in structure.secondaryIds, "服务端说是视频，不该因为没有扩展名就当成说明文件")
        assertEquals("Show 12", buildDriveItems(listOf(bare, readme), structure, hideFolded = true) { true }
            .filterIsInstance<DriveListItem.File>().single { it.file.id == "bare" }.view?.title)
    }

    @Test
    fun `unrelated videos are listed flat without a header each`() {
        val listed = items(listOf("Some Title Here.avi", "Another Clip Name.avi", "Third Thing.mp4"))
        assertTrue(listed.none { it is DriveListItem.WorkHeader || it is DriveListItem.SectionHeader }, listed.toString())
        assertEquals(3, listed.size)
    }

    @Test
    fun `a series keeps its header next to standalone files`() {
        val listed = items(listOf("[G] Show - 01 [1080p].mkv", "[G] Show - 02 [1080p].mkv", "[G] Show - 03 [1080p].mkv", "Lonely Clip.mp4"))
        assertTrue(listed.any { it is DriveListItem.SectionHeader && it.isWork && it.label == "Show" })
        val lonely = listed.filterIsInstance<DriveListItem.File>().single { it.file.name == "Lonely Clip.mp4" }
        assertEquals("Lonely Clip", lonely.view?.title)
    }

    @Test
    fun `copy markers are not episode numbers`() {
        val copies = items(listOf("image-2026.04.01-x86_64.iso", "image-2026.04.01-x86_64(1).iso", "image-2026.04.01-x86_64(2).iso"))
        assertTrue(copies.none { it is DriveListItem.SectionHeader || it is DriveListItem.WorkHeader })
        copies.filterIsInstance<DriveListItem.File>().forEach { assertNull(it.view, it.file.name) }

        val singles = items(listOf("3081 - abc_source(1).mp4", "3087 - def_source(1).mp4"))
        assertTrue(singles.none { it is DriveListItem.SectionHeader }, singles.toString())
    }

    @Test
    fun `only the same content collapses into versions`() {
        val listed = items(listOf("[G] Show - 01 [1080p].mkv", "[G] Show - 01 [720p].mkv", "[G] Show - 02 [1080p].mkv"))
        val rows = listed.filterIsInstance<DriveListItem.File>()
        assertEquals(2, rows.size, "01 的 720p 挂在 1080p 下面")
        assertTrue("2 版本" in rows.first { it.file.name.contains("01") }.view!!.tags)

        val different = items(listOf("3 (14).mp4", "4 (14).mp4", "5 (14).mp4"))
        assertEquals(3, different.filterIsInstance<DriveListItem.File>().size)
        val parts = items(listOf("ABC-PPV-1166282A.mp4", "ABC-PPV-1166282B.mp4"))
        assertEquals(2, parts.filterIsInstance<DriveListItem.File>().size)
    }

    @Test
    fun `small groups and the rest lie flat without headers`() {
        val listed = items(listOf("A Clip Name.mp4", "site.vip@01.mp4", "site.vip@02.mp4", "site.vip@03.mp4", "[G] Show - 01.mkv", "[G] Show - 02.mkv"))
        assertTrue(listed.none { it is DriveListItem.SectionHeader || it is DriveListItem.WorkHeader }, listed.toString())
        val show = listed.filterIsInstance<DriveListItem.File>().first { it.file.name.startsWith("[G] Show - 01") }
        assertEquals("Show 01", show.view?.title)
    }

    @Test
    fun `photos next to a series stay visible`() {
        val files = listOf("clip_1.mov", "clip_2.mov", "clip_3.mov", "beach.jpg", "hill.jpg", "lake.jpg", "sea.jpg").map { file(it) }
        val structure = analyzeDriveFolder(files)
        val others = structure.blocks.single { it.id == "unknown" }
        assertTrue(others.defaultExpanded, "照片是内容，不像字体说明那样默认收起")
    }

    @Test
    fun `import preview folders and files stay at the relative front across flat and structured drive items`() = runBlocking {
        val previewFolder = FileStat(kind = FileKind.FOLDER, id = ImportPreviewLocation("", "NewSeason").id, name = "Z-NewSeason")
        val existingFolder = FileStat(kind = FileKind.FOLDER, id = "folder-existing", name = "A-ExistingSeason")
        val previewFlat = FileStat(kind = FileKind.FILE, id = ImportPreviewEntryId.of(0, "GCID_FLAT", 100L, "z-preview-note.txt"), name = "z-preview-note.txt", size = "100")
        val previewSeriesEp5 = FileStat(kind = FileKind.FILE, id = ImportPreviewEntryId.of(1, "GCID_EP5", 1000L, "[Sub] Frieren - 05.mkv"), name = "[Sub] Frieren - 05.mkv", size = "1000")
        val existingFlat = FileStat(kind = FileKind.FILE, id = "file-existing-flat", name = "a-existing-readme.txt", size = "50")
        val existingSeries = (1..3).map { ep ->
            FileStat(kind = FileKind.FILE, id = "file-ep-$ep", name = "[Sub] Frieren - 0$ep.mkv", size = "1000")
        }
        val combined = listOf(previewFolder, existingFolder, previewFlat, previewSeriesEp5, existingFlat) + existingSeries
        val structure = DriveAnalysisCache.analyze(combined)
        val built = buildDriveItems(combined, structure, hideFolded = false) { true }
        val fileRows = built.filterIsInstance<DriveListItem.File>().map { it.file.id }

        assertEquals(listOf(previewFolder.id, existingFolder.id), fileRows.take(2))
        val afterFolders = fileRows.drop(2)
        assertEquals(previewFlat.id, afterFolders.first())
        assertTrue(afterFolders.indexOf(previewSeriesEp5.id) < afterFolders.indexOf("file-ep-1"))
        assertTrue(afterFolders.indexOf(previewSeriesEp5.id) < afterFolders.indexOf(existingFlat.id))
    }

    @Test
    fun `hideFiles keeps collapsed section headers while dropping emptied open sections and works`() {
        fun work(key: String) = DriveListItem.WorkHeader(key, key, emptyList())
        fun section(key: String, expanded: Boolean = true) = DriveListItem.SectionHeader(key, key, key, key, expanded)
        fun row(id: String) = DriveListItem.File(FileStat(id = id, name = id), null)

        val collapsedAndOpen = listOf(section("collapsed", expanded = false), section("open"), row("a.txt"), row("b.jpg"))
        assertEquals(listOf("collapsed", "open", "b.jpg"), hideFiles(collapsedAndOpen) { it.id.endsWith(".txt") }.map { it.key })

        val mixed = listOf(
            work("w1"), section("s1"), row("a.txt"),
            work("w2"), section("s2"), row("b.jpg"), section("s3"), row("c.txt"),
        )
        assertEquals(listOf("w2", "s2", "b.jpg"), hideFiles(mixed) { it.id.endsWith(".txt") }.map { it.key })
    }

    @Test
    fun `in-folder search matches card work title and episode display names`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(15_000) {
                val server = FakePikPakServer()
                val anime = server.addFolder("动画")
                listOf(1, 2, 3).forEach { server.addFile("[SubsPlease] Sousou no Frieren - 0$it (1080p) [ABCD1234].mkv", parentId = anime.id) }
                server.addFolder("其他")
                val prefs = MemoryPreferences()
                val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
                drive.load()
                awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 2 }
                val folder = drive.displayedFiles.single { it.name == "动画" }
                drive.onFolderVisible(folder)
                awaitUntil("认出文件夹里的作品") { drive.itemName(folder).title != null }
                val title = drive.itemName(folder).title!!
                drive.updateSearchQuery(title)
                assertEquals(listOf("动画"), drive.displayedFiles.map { it.name })

                val serverEp = FakePikPakServer()
                listOf(1, 2, 3).forEach { serverEp.addFile("[SubsPlease] Sousou no Frieren - 0$it (1080p) [ABCD1234].mkv") }
                val driveEp = DriveScreenState(PikoDriveRepository(serverEp.provider(), prefs), prefs, scope)
                driveEp.load()
                awaitUntil("列表按作品排好") { !driveEp.isLoading && driveEp.isDisplayStructureReady && driveEp.displayedFiles.size == 3 }
                driveEp.updateSearchQuery("Frieren 02")
                assertEquals(listOf("[SubsPlease] Sousou no Frieren - 02 (1080p) [ABCD1234].mkv"), driveEp.displayedFiles.map { it.name })
            }
        } finally {
            scope.cancel()
        }
    }
}
