package dev.piko.shared.search

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import dev.piko.shared.state.DriveScreenState
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CjkFuzzyMatchTest {

    @Test
    fun `simplified traditional and japanese shinjitai match interchangeably`() {
        // 简 / 繁 / 日新字体三向互通
        assertTrue(cjkMatches("桜空もも.mp4", "樱空"))
        assertTrue(cjkMatches("櫻空桃.mp4", "桜空"))
        assertTrue(cjkMatches("三上悠亜 [1080P].mkv", "三上悠亚"))
        assertTrue(cjkMatches("広瀬すず 写真集", "广濑"))
        assertTrue(cjkMatches("廣瀨鈴 写真集", "広瀬"))
        assertTrue(cjkMatches("浜辺美波.mp4", "滨边美波"))
        assertTrue(cjkMatches("濱邊美波.mp4", "浜辺美波"))
        assertTrue(cjkMatches("沢尻エリカ", "泽尻"))
        assertTrue(cjkMatches("澤尻英龍華", "沢尻英竜华"))
        assertTrue(cjkMatches("新垣結衣 - 恋.flac", "新垣结衣 戀"))
        assertTrue(cjkMatches("進撃の巨人 The Final Season", "进击"))
        assertTrue(cjkMatches("進擊的巨人", "進撃"))
        assertTrue(cjkMatches("黒木華_観月ありさ_覚悟.mkv", "黑木华 观月 觉悟"))
        assertTrue(cjkMatches("発車オーライ_黒髪_駅伝.mp4", "发车 黑发 驿传"))
    }

    @Test
    fun `kokuji iteration marks kana and fullwidth characters normalize cleanly`() {
        // 和制汉字在同一码位下保持匹配
        assertTrue(cjkMatches("凪のあすから_雫_畑_辻_峠_枠.mkv", "凪 雫 畑 辻 峠 枠"))
        // 叠字符 々 展开后与双字互通
        assertTrue(cjkMatches("佐々木希_菜々緒_奈々.mp4", "佐佐木希 菜菜绪 奈奈"))
        assertTrue(cjkMatches("佐佐木希.mp4", "佐々木"))
        // 平片假名、半角片假名、全角英数互通
        assertTrue(cjkMatches("枫カレン ＳＳＩＳ－１２３.mp4", "楓かれん ssis123"))
        assertTrue(cjkMatches("SSIS-123 桃乃木かな.mp4", "ssis 123 桃乃木カナ"))
    }

    @Test
    fun `cjk subsequence fuzzy match hits across particles without false positive ascii scattering`() {
        // 纯 CJK 词允许有界子序列匹配（跨过「的 / 之 / の」等助词）
        assertTrue(cjkMatches("進擊的巨人 01.mkv", "进击巨人"))
        assertTrue(cjkMatches("進撃の巨人 01.mkv", "进击巨人"))
        assertTrue(cjkMatches("葬送的芙莉蓮 02.mkv", "葬送芙莉莲"))
        assertTrue(cjkMatches("孤独摇滚！", "孤独的摇滚").not()) // 反向查询比目标多字时不误匹配
        assertTrue(cjkMatches("孤独的摇滚！", "孤独摇滚"))

        // 数字和拉丁字母不做跨词散落子序列匹配，避免误命中日期和哈希
        assertFalse(cjkMatches("2021-05-08_photo_01.jpg", "1080"))
        assertFalse(cjkMatches("Archive_Video_01.mp4", "avi"))
    }

    @Test
    fun `local tree search reuses cached tree and finds archived vault entries with cjk fuzzy match`() = smoke {
        val server = FakePikPakServer()
        val anime = server.addFolder("アニメーション")
        val movie = server.addFile("劇場版 進撃の巨人 完結編.mkv", anime.id, hash = "HASH01")
        val dramas = server.addFolder("日劇")
        val drive = PikoDriveRepository(server.provider(), MemoryPreferences())

        // 在日剧目录写入一条含日本新字体与叠字符的归档虚拟条目
        drive.vault.update(
            dramas.id,
            VaultEdits.add(
                listOf(
                    VaultEntry.create(
                        name = "佐々木希_桜空もも_SSIS-888.mp4",
                        size = 1024L,
                        gcid = "GCIDVAULT888",
                        source = "magnet:?xt=urn:btih:888",
                        addedAt = 1000L,
                    ),
                ),
            ),
        ).getOrThrow()

        // 首次全盘搜索：用简体中文 + 子序列搜出日文新字体真实文件，同时拉齐本地树与归档清单
        val firstHits = drive.searchRecursive("剧场 进击巨人").toList()
        assertEquals(listOf(movie.id), firstHits.map { it.file.id })
        assertEquals(listOf("アニメーション"), firstHits.single().breadcrumb)
        assertTrue(drive.driveTreeIndex.isComplete, "首次全盘搜索后本地文件树应当标记为完整")

        // 第二次全盘搜索：用简体「佐佐木 樱空 ssis888」直接从本地树命中归档条目
        val vaultHits = drive.searchRecursive("佐佐木 樱空 ssis888").toList()
        assertEquals(1, vaultHits.size)
        assertEquals("佐々木希_桜空もも_SSIS-888.mp4", vaultHits.single().file.name)
        assertEquals(listOf("日劇"), vaultHits.single().breadcrumb)

        // 改名后不重新全盘扫描也能立即按新名字搜到
        drive.rename(movie.id, "葬送的芙莉蓮 01.mkv", anime.id).getOrThrow()
        val renamedHits = drive.searchRecursive("葬送芙莉莲").toList()
        assertEquals(listOf(movie.id), renamedHits.map { it.file.id })
    }

    @Test
    fun `per folder sort remembers each folder order independently when enabled`() = smoke { scope ->
        val server = FakePikPakServer()
        val folderA = server.addFolder("FolderA")
        val folderB = server.addFolder("FolderB")
        val folderC = server.addFolder("FolderC")
        server.addFile("b.txt", folderA.id, content = ByteArray(10))
        server.addFile("a.txt", folderA.id, content = ByteArray(100))
        server.addFile("small.txt", folderB.id, content = ByteArray(10))
        server.addFile("large.txt", folderB.id, content = ByteArray(500))
        server.addFile("c1.txt", folderC.id, content = ByteArray(10))
        server.addFile("c2.txt", folderC.id, content = ByteArray(20))

        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repo, prefs, scope)

        state.setPerFolderSortEnabled(true)
        assertTrue(state.perFolderSort)

        // 进入 FolderA，设为按名称升序（a.txt 在前）
        state.openFolder(folderA.id, folderA.name)
        awaitUntil("FolderA 加载完成") { !state.isLoading && state.loadedFolderId == folderA.id }
        state.changeSortOrder(PikoFileSortOrder.NAME_ASC)
        awaitUntil("FolderA 按名称升序排好") { state.displayedFiles.map { it.name } == listOf("a.txt", "b.txt") }

        // 进入 FolderB，设为按大小升序（small.txt 在前）
        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderB.id, folderB.name)))
        awaitUntil("FolderB 加载完成") { !state.isLoading && state.loadedFolderId == folderB.id }
        state.changeSortOrder(PikoFileSortOrder.SIZE_ASC)
        awaitUntil("FolderB 按大小升序排好") { state.displayedFiles.map { it.name } == listOf("small.txt", "large.txt") }

        // 进入从未单独设置过排序的 FolderC，应回落到默认排序（TIME_DESC）
        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderC.id, folderC.name)))
        awaitUntil("FolderC 加载完成") { !state.isLoading && state.loadedFolderId == folderC.id }
        assertEquals(PikoFileSortOrder.TIME_DESC, state.sortOrder)

        // 切回 FolderA，自动恢复 NAME_ASC
        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderA.id, folderA.name)))
        awaitUntil("切回 FolderA 恢复 NAME_ASC") { state.loadedFolderId == folderA.id && state.sortOrder == PikoFileSortOrder.NAME_ASC }
        assertEquals(listOf("a.txt", "b.txt"), state.displayedFiles.map { it.name })

        // 切回 FolderB，自动恢复 SIZE_ASC
        state.navigation.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folderB.id, folderB.name)))
        awaitUntil("切回 FolderB 恢复 SIZE_ASC") { state.loadedFolderId == folderB.id && state.sortOrder == PikoFileSortOrder.SIZE_ASC }
        assertEquals(listOf("small.txt", "large.txt"), state.displayedFiles.map { it.name })
    }
}
