package dev.piko.shared.smoke

import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStarRecord
import dev.piko.shared.data.VaultStars
import dev.piko.shared.data.isVaulted
import dev.piko.shared.state.DriveAnalysisCache
import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.buildDriveItems
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class VaultStarsAndPreviewOrderTest {

    @Test
    fun `import preview folders and files stay at the relative front across flat and structured drive items`() = runBlocking {
        val previewFolder = FileStat(
            kind = FileKind.FOLDER,
            id = ImportPreviewLocation("", "NewSeason").id,
            name = "Z-NewSeason",
        )
        val existingFolder = FileStat(
            kind = FileKind.FOLDER,
            id = "folder-existing",
            name = "A-ExistingSeason",
        )
        val previewFlat = FileStat(
            kind = FileKind.FILE,
            id = ImportPreviewEntryId.of(0, "GCID_FLAT", 100L, "z-preview-note.txt"),
            name = "z-preview-note.txt",
            size = "100",
        )
        val previewSeriesEp5 = FileStat(
            kind = FileKind.FILE,
            id = ImportPreviewEntryId.of(1, "GCID_EP5", 1000L, "[Sub] Frieren - 05.mkv"),
            name = "[Sub] Frieren - 05.mkv",
            size = "1000",
        )
        val existingFlat = FileStat(
            kind = FileKind.FILE,
            id = "file-existing-flat",
            name = "a-existing-readme.txt",
            size = "50",
        )
        val existingSeries = (1..3).map { ep ->
            FileStat(
                kind = FileKind.FILE,
                id = "file-ep-$ep",
                name = "[Sub] Frieren - 0$ep.mkv",
                size = "1000",
            )
        }
        val combined = listOf(previewFolder, existingFolder, previewFlat, previewSeriesEp5, existingFlat) + existingSeries
        val structure = DriveAnalysisCache.analyze(combined)
        val items = buildDriveItems(combined, structure, hideFolded = false) { true }
        val fileRows = items.filterIsInstance<DriveListItem.File>().map { it.file.id }

        // 文件夹区：待添加文件夹排在已有文件夹之前
        assertEquals(listOf(previewFolder.id, existingFolder.id), fileRows.take(2))
        // 文件区：待添加平铺文件及含待添加集数的作品块排在已有平铺文件之前，且同作品块内待添加的第 05 集排在 01~03 集最前
        val afterFolders = fileRows.drop(2)
        assertEquals(previewFlat.id, afterFolders.first())
        assertTrue(afterFolders.indexOf(previewSeriesEp5.id) < afterFolders.indexOf("file-ep-1"))
        assertTrue(afterFolders.indexOf(previewSeriesEp5.id) < afterFolders.indexOf(existingFlat.id))
    }

    @Test
    fun `archiving a starred real file preserves its star in vault stars and restoring transfers it back to real file`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Favorites")
        val starredReal = server.addFile("episode.mkv", parentId = folder.id, content = ByteArray(128) { 7 }, hash = "GCID_STAR_1")
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences(), MemoryCacheStore())
        repo.setStarred(listOf(starredReal.id), starred = true).getOrThrow()
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == starredReal.id })

        // 归档包含星标文件的目录（彻底删除原文件以验证非回收站还原路径同样迁回星标）
        val vaultSession = FolderVaultSession(repo, scope)
        vaultSession.archive(PikoPathBreadcrumb(folder.id, folder.name), includeUnsourced = true, moveToTrash = false)
        vaultSession.outcomes.first()

        // 归档后：目录列表中的虚拟条目保留 STAR 标签，星标库直接列出它，且 locateFolder 能零全盘扫描定位到所在目录
        val browsed = repo.listBrowsable(folder.id, PikoFileSortOrder.TIME_DESC).getOrThrow()
        val vaultedStat = browsed.single { it.isVaulted }
        assertTrue(vaultedStat.tags.any { it.name.equals("STAR", ignoreCase = true) }, "归档后必须保留原文件的星标")
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id }, "星标库必须包含已归档的星标条目")
        assertEquals(folder.id, repo.locateFolder(vaultedStat.id).getOrThrow().last().id)

        // 支持直接对归档条目取消星标再重新加星标
        repo.setStarred(listOf(vaultedStat.id), starred = false).getOrThrow()
        assertFalse(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id })
        repo.setStarred(listOf(vaultedStat.id), starred = true, mapOf(vaultedStat.id to vaultedStat)).getOrThrow()
        assertTrue(repo.starredFiles().getOrThrow().any { it.id == vaultedStat.id })

        // 取回为真实文件后：新真实文件自动获得服务端星标，虚拟星标自动迁出不留重影
        vaultSession.restore(listOf(vaultedStat))
        vaultSession.outcomes.first()

        val starredAfterRestore = repo.starredFiles().getOrThrow()
        assertFalse(starredAfterRestore.any { VaultEntry.isVaulted(it.id) }, "取回后星标库中不应残留虚拟条目")
        val restoredReal = starredAfterRestore.single { !it.isFolder && it.name == "episode.mkv" }
        assertTrue(restoredReal.tags.any { it.name.equals("STAR", ignoreCase = true) }, "取回后的真实文件必须自动补回服务端星标")
    }

    @Test
    fun `vault stars lww merge respects unstar tombstones and is strictly commutative on equal timestamps`() {
        val starOnDeviceA = mapOf(
            "e1" to VaultStarRecord("e1", "f1", "a.mkv", 10L, "G1", starred = true, updatedAt = 1_000L),
            "e2" to VaultStarRecord("e2", "f1", "renamed.mkv", 20L, "G2", starred = true, updatedAt = 1_000L, restoredFileId = "real-2"),
        )
        val unstarOnDeviceB = mapOf(
            "e1" to VaultStarRecord("e1", "f1", "a.mkv", 10L, "G1", starred = false, updatedAt = 2_000L),
            "e2" to VaultStarRecord("e2", "f1", "old.mkv", 20L, "G2", starred = true, updatedAt = 1_000L, restoredFileId = null),
        )
        val ab = VaultStars.union(starOnDeviceA, unstarOnDeviceB, nowMs = 3_000L)
        val ba = VaultStars.union(unstarOnDeviceB, starOnDeviceA, nowMs = 3_000L)
        assertEquals(ab, ba, "等时戳合并必须满足交换律，防止同步顺序导致名称或取回状态反复回滚")
        assertFalse(ab.getValue("e1").starred, "较晚取消星标的墓碑必须胜出，防止旧星标死灰复燃")
        assertTrue(ab.getValue("e2").starred)
        assertEquals("real-2", ab.getValue("e2").restoredFileId)
    }
}
