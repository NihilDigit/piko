package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网盘页的后退与前进：记的是去过的位置，与「上一级」无关；恢复上次的位置不算一步。
 * 快捷栏的「最近」按文件夹去重，根目录不记。
 */
class FolderHistorySmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB
    private val anime = PikoPathBreadcrumb("A", "动画")
    private val show = PikoPathBreadcrumb("S", "Frieren")
    private val docs = PikoPathBreadcrumb("D", "文档")

    @Test
    fun `back returns to where a jump started and forward undoes it`() = smoke {
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        repo.restoreFolderStack(listOf(root, docs))
        assertFalse(repo.historyFlow.value.canGoBack, "恢复上次的位置不该留下一步后退")

        // 从别处「在网盘中显示」跳到很深的目录
        repo.updateFolderStack(listOf(root, anime, show))
        // 上一级走的是层级
        repo.popFolder()
        assertEquals(listOf(root, anime), repo.folderStackFlow.value)

        // 后退两步回到跳之前在看的地方，而不是层级上的根
        assertTrue(repo.goBack())
        assertEquals(listOf(root, anime, show), repo.folderStackFlow.value)
        assertTrue(repo.goBack())
        assertEquals(listOf(root, docs), repo.folderStackFlow.value)
        assertFalse(repo.goBack())

        assertTrue(repo.goForward())
        assertEquals(listOf(root, anime, show), repo.folderStackFlow.value)

        // 走了新的一步，前进作废
        repo.pushFolder("E", "SPs")
        assertFalse(repo.historyFlow.value.canGoForward)
        assertTrue(repo.historyFlow.value.canGoBack)
    }

    @Test
    fun `recent folders keep one entry per folder, newest first, without the root`() = smoke {
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        repo.updateFolderStack(listOf(root, anime, show))
        repo.updateFolderStack(listOf(root, docs))
        repo.popFolder()
        repo.goBack()
        assertEquals(listOf(listOf(root, docs), listOf(root, anime, show)), repo.recentFoldersFlow.value)
    }

    @Test
    fun `each tab keeps its own place and history`() = smoke {
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        repo.updateFolderStack(listOf(root, anime))
        val first = repo.activeTabId.value

        // 新标签停在文档里，历史是它自己的
        val second = repo.openTab(listOf(root, docs))
        assertEquals(listOf(root, docs), repo.folderStackFlow.value)
        assertFalse(repo.historyFlow.value.canGoBack)
        repo.pushFolder("E", "SPs")

        // 切回第一个：位置与后退都是原来的
        repo.switchTab(first)
        assertEquals(listOf(root, anime), repo.folderStackFlow.value)
        assertTrue(repo.goBack())
        assertEquals(listOf(root), repo.folderStackFlow.value)

        // 标签列表里第二个带着它离开时的位置
        assertEquals(listOf(root, docs, PikoPathBreadcrumb("E", "SPs")), repo.tabsFlow.value.first { it.id == second }.stack)

        // 关掉活动标签，落到旁边那个
        repo.closeTab(first)
        assertEquals(second, repo.activeTabId.value)
        assertEquals(listOf(root, docs, PikoPathBreadcrumb("E", "SPs")), repo.folderStackFlow.value)
        repo.closeTab(second)
        assertEquals(second, repo.activeTabId.value, "最后一个标签不关")
    }

    @Test
    fun `open tabs come back after a restart`() = smoke {
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        val before = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        before.updateFolderStack(listOf(root, anime))
        before.openTab(listOf(root, docs))
        // 存盘有一秒的延迟，等它落下
        kotlinx.coroutines.delay(1_500)

        val after = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        assertTrue(after.restoreTabs())
        assertEquals(listOf(listOf(root, anime), listOf(root, docs)), after.tabsFlow.value.map { it.stack })
        assertEquals(listOf(root, docs), after.folderStackFlow.value, "活动的是后开的那个")
        assertFalse(after.restoreTabs(), "已经不在初始状态，不再恢复")
    }
}
