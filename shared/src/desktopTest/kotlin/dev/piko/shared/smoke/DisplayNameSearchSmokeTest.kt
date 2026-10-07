package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveScreenState
import kotlin.test.Test
import kotlin.test.assertEquals

/** 目录内搜索认卡片上的名字：文件夹的作品名、集号前带作品名的写法，真实名称里没有这几个字也找得到。 */
class DisplayNameSearchSmokeTest {

    @Test
    fun `search finds a folder by the work title shown on its card`() = smoke { scope ->
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
        check(!folder.name.contains(title, ignoreCase = true)) { "作品名「$title」与文件夹名重合，测不出按显示名搜索" }

        drive.updateSearchQuery(title)
        assertEquals(listOf("动画"), drive.displayedFiles.map { it.name })
    }

    @Test
    fun `search finds an episode by work title and episode number`() = smoke { scope ->
        val server = FakePikPakServer()
        listOf(1, 2, 3).forEach { server.addFile("[SubsPlease] Sousou no Frieren - 0$it (1080p) [ABCD1234].mkv") }
        val prefs = MemoryPreferences()
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        drive.load()
        awaitUntil("列表按作品排好") { !drive.isLoading && drive.isDisplayStructureReady && drive.displayedFiles.size == 3 }

        drive.updateSearchQuery("Frieren 02")
        assertEquals(listOf("[SubsPlease] Sousou no Frieren - 02 (1080p) [ABCD1234].mkv"), drive.displayedFiles.map { it.name })
    }
}
