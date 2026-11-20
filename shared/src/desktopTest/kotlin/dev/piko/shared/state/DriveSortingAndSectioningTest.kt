package dev.piko.shared.state

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.shared.data.ContentStats
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.SortContext
import dev.piko.shared.data.SubtreeStats
import dev.piko.shared.data.treeEpochMillis
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveSortingAndSectioningTest {

    private class TestPreferences : PikoUserPreferences by MemoryPreferences() {
        override val nameParsingFlow = MutableStateFlow(true)
        override suspend fun setNameParsingEnabled(enabled: Boolean) { nameParsingFlow.value = enabled }
        override val foldersFirstFlow = MutableStateFlow(true)
        override suspend fun setFoldersFirst(enabled: Boolean) { foldersFirstFlow.value = enabled }
    }

    @Test
    fun externalSubtitlesStayInDedicatedSectionWhenFoldersFirstIsOffAcrossAllSortOrders() = smoke { scope ->
        val server = FakePikPakServer()
        val folderOld = server.addFolder("Archive", params = mapOf("piko_original_created_at" to "100"))
        val folderNew = server.addFolder("Recent", params = mapOf("piko_original_created_at" to "500"))
        val ep1 = server.addFile("[SubGroup] Frieren - 01 [1080p].mkv", content = ByteArray(200), params = mapOf("piko_original_created_at" to "200", "duration" to "1200"))
        val ep2 = server.addFile("[SubGroup] Frieren - 02 [1080p].mkv", content = ByteArray(400), params = mapOf("piko_original_created_at" to "600", "duration" to "1500"))
        val ep3 = server.addFile("[SubGroup] Frieren - 03 [1080p].mkv", content = ByteArray(300), params = mapOf("piko_original_created_at" to "300", "duration" to "900"))
        val sub1 = server.addFile("[SubGroup] Frieren - 01 [1080p].sc.ass", content = ByteArray(10), params = mapOf("piko_original_created_at" to "210"))
        val sub2 = server.addFile("[SubGroup] Frieren - 02 [1080p].tc.ass", content = ByteArray(12), params = mapOf("piko_original_created_at" to "610"))
        val sub3 = server.addFile("[SubGroup] Frieren - 03 [1080p].scjp.ass", content = ByteArray(11), params = mapOf("piko_original_created_at" to "310"))
        val subtitleIds = setOf(sub1.id, sub2.id, sub3.id)

        val prefs = TestPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repo, prefs, scope)
        state.load()
        awaitUntil("初始加载完成") { !state.isLoading && state.isDisplayStructureReady && state.files.size == 8 }

        for (foldersFirst in listOf(false, true)) {
            state.updateFoldersFirst(foldersFirst)
            for (order in PikoFileSortOrder.entries) {
                state.changeSortOrder(order)
                awaitUntil("排序与分区就绪 foldersFirst=$foldersFirst order=$order") {
                    state.foldersFirst == foldersFirst && state.sortOrder == order && state.isDisplayStructureReady
                }
                val collapsedItems = state.displayItems
                val subHeader = state.sectionHeaders.firstOrNull { it.value.blockId == "attached:subtitle" }
                assertTrue(subHeader != null, "foldersFirst=$foldersFirst, order=$order 必须保留独立外挂字幕分区")
                assertFalse(subHeader.value.expanded, "外挂字幕分区默认收起 (foldersFirst=$foldersFirst, order=$order)")
                assertEquals(collapsedItems[subHeader.index], subHeader.value, "sectionHeaders 索引必须与 displayItems 严格对齐")

                val visibleBeforeExpand = collapsedItems.filterIsInstance<DriveListItem.File>().map { it.file.id }
                assertTrue(visibleBeforeExpand.none { it in subtitleIds }, "外挂字幕不得混入主列表 (foldersFirst=$foldersFirst, order=$order)")
                assertTrue(visibleBeforeExpand.containsAll(listOf(folderOld.id, folderNew.id, ep1.id, ep2.id, ep3.id)))

                // 展开后字幕应全部出现在外挂字幕分区标题之后且无重复 key
                state.toggleSection("attached:subtitle")
                val expandedItems = state.displayItems
                val keys = expandedItems.map { it.key }
                assertEquals(keys.size, keys.distinct().size, "displayItems 不得出现重复 key")
                val expandedHeaderIndex = expandedItems.indexOfFirst { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" }
                assertTrue(expandedHeaderIndex >= 0)
                val rowsAfterSubHeader = expandedItems.drop(expandedHeaderIndex + 1)
                    .takeWhile { it is DriveListItem.File }
                    .map { (it as DriveListItem.File).file.id }
                assertEquals(subtitleIds, rowsAfterSubHeader.toSet(), "展开外挂字幕分区后应列出全部匹配字幕")
                // 还原为收起以便下一轮排序验证
                state.toggleSection("attached:subtitle")
            }
        }
    }

    @Test
    fun createdTimeOrderIsStrictlyMonotonicForBothFoldersFirstOnAndOffWithMixedWorksAndSubtitles() = smoke { scope ->
        val server = FakePikPakServer()
        // 刻意打乱文件名顺序与创建时间顺序，并混入文件夹、剧集、番号、独立视频与外挂字幕
        val folderOlder = server.addFolder("Z-Folder", params = mapOf("piko_original_created_at" to "200"))
        val folderNewer = server.addFolder("A-Folder", params = mapOf("piko_original_created_at" to "800"))
        val avNewest = server.addFile("SSIS-900.mp4", content = ByteArray(500), params = mapOf("piko_original_created_at" to "900"))
        val avOlder = server.addFile("ABP-100.mp4", content = ByteArray(500), params = mapOf("piko_original_created_at" to "150"))
        val avMid = server.addFile("MIDV-500.mp4", content = ByteArray(500), params = mapOf("piko_original_created_at" to "550"))
        val ep1 = server.addFile("[Grp] Show - 01 [1080p].mkv", content = ByteArray(300), params = mapOf("piko_original_created_at" to "100"))
        val ep2 = server.addFile("[Grp] Show - 02 [1080p].mkv", content = ByteArray(300), params = mapOf("piko_original_created_at" to "700"))
        val ep3 = server.addFile("[Grp] Show - 03 [1080p].mkv", content = ByteArray(300), params = mapOf("piko_original_created_at" to "400"))
        val standalone = server.addFile("Lonely Clip.mp4", content = ByteArray(300), params = mapOf("piko_original_created_at" to "300"))
        val subAvNew = server.addFile("SSIS-900.srt", content = ByteArray(5), params = mapOf("piko_original_created_at" to "905"))
        val subAvOld = server.addFile("ABP-100.srt", content = ByteArray(5), params = mapOf("piko_original_created_at" to "155"))
        val subEp = server.addFile("[Grp] Show - 02 [1080p].sc.ass", content = ByteArray(5), params = mapOf("piko_original_created_at" to "705"))

        val prefs = TestPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repo, prefs, scope)
        state.load()
        awaitUntil("默认按创建时间降序加载完成") { !state.isLoading && state.isDisplayStructureReady && state.files.size == 12 }

        fun primaryIds(): List<String> = state.displayItems
            .takeWhile { it !is DriveListItem.SectionHeader }
            .filterIsInstance<DriveListItem.File>()
            .map { it.file.id }

        fun expandedSubtitleIds(): List<String> {
            state.expandSection("attached:subtitle")
            val items = state.displayItems
            val idx = items.indexOfFirst { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" }
            return items.drop(idx + 1).takeWhile { it is DriveListItem.File }.map { (it as DriveListItem.File).file.id }
        }

        // 1. foldersFirst = true + TIME_DESC：文件夹按时间降序在前，非字幕主文件严格按时间降序在后，不被作品/番号分组打乱
        assertEquals(
            listOf(folderNewer.id, folderOlder.id, avNewest.id, ep2.id, avMid.id, ep3.id, standalone.id, avOlder.id, ep1.id),
            primaryIds(),
            "foldersFirst=true + TIME_DESC 必须严格按创建时间降序",
        )
        assertEquals(listOf(subAvNew.id, subEp.id, subAvOld.id), expandedSubtitleIds())

        // 2. foldersFirst = true + TIME_ASC：文件夹按时间升序在前，非字幕主文件严格按时间升序在后
        state.changeSortOrder(PikoFileSortOrder.TIME_ASC)
        awaitUntil("切换至 TIME_ASC") { state.sortOrder == PikoFileSortOrder.TIME_ASC }
        assertEquals(
            listOf(folderOlder.id, folderNewer.id, ep1.id, avOlder.id, standalone.id, ep3.id, avMid.id, ep2.id, avNewest.id),
            primaryIds(),
            "foldersFirst=true + TIME_ASC 必须严格按创建时间升序",
        )
        assertEquals(listOf(subAvOld.id, subEp.id, subAvNew.id), expandedSubtitleIds())

        // 3. foldersFirst = false + TIME_DESC：文件夹与主文件混排严格按时间降序，外挂字幕仍在独立分区
        state.updateFoldersFirst(false)
        state.changeSortOrder(PikoFileSortOrder.TIME_DESC)
        awaitUntil("切换至混排 TIME_DESC") { !state.foldersFirst && state.sortOrder == PikoFileSortOrder.TIME_DESC }
        assertEquals(
            listOf(avNewest.id, folderNewer.id, ep2.id, avMid.id, ep3.id, standalone.id, folderOlder.id, avOlder.id, ep1.id),
            primaryIds(),
            "foldersFirst=false + TIME_DESC 必须严格混排且按创建时间降序",
        )
        assertEquals(listOf(subAvNew.id, subEp.id, subAvOld.id), expandedSubtitleIds())

        // 4. foldersFirst = false + TIME_ASC：文件夹与主文件混排严格按时间升序
        state.changeSortOrder(PikoFileSortOrder.TIME_ASC)
        awaitUntil("切换至混排 TIME_ASC") { !state.foldersFirst && state.sortOrder == PikoFileSortOrder.TIME_ASC }
        assertEquals(
            listOf(ep1.id, avOlder.id, folderOlder.id, standalone.id, ep3.id, avMid.id, ep2.id, folderNewer.id, avNewest.id),
            primaryIds(),
            "foldersFirst=false + TIME_ASC 必须严格混排且按创建时间升序",
        )
        assertEquals(listOf(subAvOld.id, subEp.id, subAvNew.id), expandedSubtitleIds())
    }

    @Test
    fun mixedIso8601FormatsAndVaultedTimestampsSortStrictlyChronologically() {
        val repo = PikoDriveRepository(FakePikPakServer().provider())
        val projection = DriveListProjection(repo)

        val folderUtcNoFrac = FileStat(id = "f-utc-0", name = "Folder Z", kind = FileKind.FOLDER, createdTime = "2026-01-01T00:00:00Z")
        val folderPlus08Compact = FileStat(id = "f-08-compact", name = "Folder A", kind = FileKind.FOLDER, createdTime = "2026-01-01T08:00:01+0800")
        val videoSpaceFrac = FileStat(id = "v-space", name = "SSIS-200.mp4", kind = FileKind.FILE, size = "1000", createdTime = "2026-01-01 00:00:00.250Z")
        val videoOffsetFrac = FileStat(id = "v-offset", name = "ABP-100.mp4", kind = FileKind.FILE, size = "1000", createdTime = "2026-01-01T08:00:00.750+08:00")
        val videoVaultedOld = FileStat(
            id = "v-vaulted",
            name = "MIDV-300.mp4",
            kind = FileKind.FILE,
            size = "1000",
            createdTime = "2026-12-31T23:59:59Z",
            params = mapOf("piko_original_created_at" to (treeEpochMillis("2025-12-31T23:59:59Z")!!).toString()),
        )
        val sub = FileStat(id = "s-offset", name = "ABP-100.srt", kind = FileKind.FILE, size = "10", createdTime = "2026-01-01T08:00:00.900+08:00")

        val raw = listOf(videoVaultedOld, folderUtcNoFrac, sub, videoOffsetFrac, folderPlus08Compact, videoSpaceFrac)
        val context = SortContext(0, emptyMap(), emptyMap(), emptyMap(), emptyMap())

        // foldersFirst = true, TIME_DESC
        val sortedFirstDesc = repo.sortFiles(raw, PikoFileSortOrder.TIME_DESC, "parent", context, foldersFirst = true)
        val projectedFirstDesc = projection.items(sortedFirstDesc, analyzeDriveFolder(sortedFirstDesc), hideFolded = true, parseNames = true, foldersFirst = true, order = PikoFileSortOrder.TIME_DESC) { it.defaultExpanded }
        assertEquals(
            listOf(folderPlus08Compact.id, folderUtcNoFrac.id, videoOffsetFrac.id, videoSpaceFrac.id, videoVaultedOld.id),
            projectedFirstDesc.filterIsInstance<DriveListItem.File>().map { it.file.id },
        )
        assertTrue(projectedFirstDesc.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" && !it.expanded })

        // foldersFirst = false, TIME_ASC
        val sortedMixedAsc = repo.sortFiles(raw, PikoFileSortOrder.TIME_ASC, "parent", context, foldersFirst = false)
        val projectedMixedAsc = projection.items(sortedMixedAsc, analyzeDriveFolder(sortedMixedAsc), hideFolded = true, parseNames = true, foldersFirst = false, order = PikoFileSortOrder.TIME_ASC) { it.defaultExpanded }
        assertEquals(
            listOf(videoVaultedOld.id, folderUtcNoFrac.id, videoSpaceFrac.id, videoOffsetFrac.id, folderPlus08Compact.id),
            projectedMixedAsc.filterIsInstance<DriveListItem.File>().map { it.file.id },
        )
        assertTrue(projectedMixedAsc.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" && !it.expanded })
    }

    @Test
    fun sizeDurationAndNameDescRespectOrderWhileKeepingSubtitleSectionAndVersionCollapse() {
        val repo = PikoDriveRepository(FakePikPakServer().provider())
        val projection = DriveListProjection(repo)

        val folder = FileStat(id = "dir", name = "Dir", kind = FileKind.FOLDER, createdTime = "2026-01-02T00:00:00Z")
        val ep1Hd = FileStat(id = "ep1-hd", name = "[Grp] Anime - 01 [1080p].mkv", size = "300", params = mapOf("duration" to "20"), createdTime = "2026-01-01T00:00:00Z")
        val ep1Sd = FileStat(id = "ep1-sd", name = "[Grp] Anime - 01 [720p].mkv", size = "150", params = mapOf("duration" to "20"), createdTime = "2026-01-01T00:00:00Z")
        val ep2 = FileStat(id = "ep2", name = "[Grp] Anime - 02 [1080p].mkv", size = "500", params = mapOf("duration" to "60"), createdTime = "2026-01-03T00:00:00Z")
        val ep3 = FileStat(id = "ep3", name = "[Grp] Anime - 03 [1080p].mkv", size = "100", params = mapOf("duration" to "10"), createdTime = "2026-01-04T00:00:00Z")
        val sub = FileStat(id = "sub1", name = "[Grp] Anime - 01 [1080p].sc.ass", size = "5", createdTime = "2026-01-01T00:00:00Z")
        val files = listOf(folder, ep1Hd, ep1Sd, ep2, ep3, sub)
        val context = SortContext(
            revision = 0,
            subtreeStats = mapOf(folder.id to SubtreeStats(physical = ContentStats(bytes = 400, durationMillis = 40_000))),
            createdAtMillis = emptyMap(),
            modifiedAtMillis = emptyMap(),
            contentMetadata = emptyMap(),
        )

        // SIZE_DESC mixed (foldersFirst = false): ep2(500) > folder(400) > ep1Hd(300) > ep3(100); ep1Sd collapsed, sub in 外挂字幕
        val sizeMixed = repo.sortFiles(files, PikoFileSortOrder.SIZE_DESC, "p", context, foldersFirst = false)
        val sizeItems = projection.items(sizeMixed, analyzeDriveFolder(sizeMixed), hideFolded = true, parseNames = true, foldersFirst = false, order = PikoFileSortOrder.SIZE_DESC) { it.defaultExpanded }
        assertEquals(listOf(ep2.id, folder.id, ep1Hd.id, ep3.id), sizeItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
        assertTrue(sizeItems.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" })

        // DURATION_DESC foldersFirst = true: folder(40s), then ep2(60s) > ep1Hd(20s) > ep3(10s); sub still in 外挂字幕
        val durFirst = repo.sortFiles(files, PikoFileSortOrder.DURATION_DESC, "p", context, foldersFirst = true)
        val durItems = projection.items(durFirst, analyzeDriveFolder(durFirst), hideFolded = true, parseNames = true, foldersFirst = true, order = PikoFileSortOrder.DURATION_DESC) { it.defaultExpanded }
        assertEquals(listOf(folder.id, ep2.id, ep1Hd.id, ep3.id), durItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
        assertTrue(durItems.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" })

        // NAME_DESC foldersFirst = true: folder, then ep3 > ep2 > ep1Hd; sub still in 外挂字幕
        val nameDesc = repo.sortFiles(files, PikoFileSortOrder.NAME_DESC, "p", context, foldersFirst = true)
        val nameDescItems = projection.items(nameDesc, analyzeDriveFolder(nameDesc), hideFolded = true, parseNames = true, foldersFirst = true, order = PikoFileSortOrder.NAME_DESC) { it.defaultExpanded }
        assertEquals(listOf(folder.id, ep3.id, ep2.id, ep1Hd.id), nameDescItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
        assertTrue(nameDescItems.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" })
    }

    @Test
    fun nameAscPreservesWorkGroupingWhenFoldersFirstOrFolderlessAndMixesCleanlyOtherwise() {
        val repo = PikoDriveRepository(FakePikPakServer().provider())
        val projection = DriveListProjection(repo)

        val folder = FileStat(id = "f-mid", name = "[Grp] Frieren - 03 Folder", kind = FileKind.FOLDER)
        val eps = listOf(1, 2, 4).map { ep -> FileStat(id = "ep$ep", name = "[Grp] Frieren - 0$ep [1080p].mkv", size = "1000") }
        val sub = FileStat(id = "sub1", name = "[Grp] Frieren - 01 [1080p].ass", size = "10")

        // 无文件夹时，即便 foldersFirst = false，NAME_ASC 仍保留作品分区与外挂字幕分区
        val folderless = repo.sortFiles(eps + sub, PikoFileSortOrder.NAME_ASC, "p", foldersFirst = false)
        val folderlessItems = projection.items(folderless, analyzeDriveFolder(folderless), hideFolded = true, parseNames = true, foldersFirst = false, order = PikoFileSortOrder.NAME_ASC) { it.defaultExpanded }
        assertTrue(folderlessItems.any { it is DriveListItem.SectionHeader && it.isWork && it.label == "Frieren" })
        assertTrue(folderlessItems.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" && !it.expanded })

        // 含文件夹且 foldersFirst = false 时，NAME_ASC 严格混排文件夹与文件，同时外挂字幕仍独立分区
        val withFolder = repo.sortFiles(eps + folder + sub, PikoFileSortOrder.NAME_ASC, "p", foldersFirst = false)
        val mixedItems = projection.items(withFolder, analyzeDriveFolder(withFolder), hideFolded = true, parseNames = true, foldersFirst = false, order = PikoFileSortOrder.NAME_ASC) { it.defaultExpanded }
        assertEquals(listOf("ep1", "ep2", folder.id, "ep4"), mixedItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
        assertTrue(mixedItems.any { it is DriveListItem.SectionHeader && it.blockId == "attached:subtitle" && !it.expanded })
    }

    @Test
    fun orphanedSubtitleIsNeverHiddenIfHostIsMissingAndSearchStillWorks() = smoke { scope ->
        val server = FakePikPakServer()
        val video = server.addFile("Movie.mkv", content = ByteArray(100), params = mapOf("piko_original_created_at" to "200"))
        val sub = server.addFile("Movie.srt", content = ByteArray(10), params = mapOf("piko_original_created_at" to "100"))
        val structure = analyzeDriveFolder(listOf(
            FileStat(id = video.id, name = video.name, size = "100"),
            FileStat(id = sub.id, name = sub.name, size = "10"),
        ))
        // 宿主被乐观移除但旧结构尚未重算时，孤立字幕必须仍可见
        val orphanItems = buildOrderedDriveItems(listOf(FileStat(id = sub.id, name = sub.name, size = "10")), structure, hideFolded = true) { it.defaultExpanded }
        assertEquals(listOf(sub.id), orphanItems.filterIsInstance<DriveListItem.File>().map { it.file.id })

        // 端到端搜索与类型筛选不受外挂字幕分区影响
        val prefs = TestPreferences()
        val state = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        state.updateFoldersFirst(false)
        state.load()
        awaitUntil("加载完成") { !state.isLoading && state.isDisplayStructureReady && state.files.size == 2 }
        assertTrue(state.sectionHeaders.any { it.value.blockId == "attached:subtitle" })
        assertEquals(setOf(video.id, sub.id), state.displayedFiles.map { it.id }.toSet())

        state.updateSearchQuery("Movie")
        assertEquals(setOf(video.id, sub.id), state.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id }.toSet())
        state.updateSearchQuery("")
        state.updateTypeFilter(FileCategory.VIDEO)
        assertEquals(listOf(video.id), state.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
    }
}
