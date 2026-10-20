package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网盘页的后退与前进：记的是去过的位置，与「上一级」无关；恢复上次的位置不算一步。
 * 快捷栏的「最近」按文件夹去重，根目录不记。
 */
class FolderHistorySmokeTest {
    @Test
    fun `released caches without identities and invalid identities get distinct context ids`() = smoke { scope ->
        for (metadata in listOf("", ",\"ids\":[7,7]")) {
            val cache = MemoryCacheStore()
            cache.write("drive-tabs-smoke@piko.dev.json", """{"tabs":[[{"id":"","name":"网盘"},{"id":"A","name":"动画"}],[{"id":"","name":"网盘"},{"id":"D","name":"文档"}]],"active":1$metadata}""")
            val repository = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
            val workspace = dev.piko.shared.presentation.DriveWorkspace(repository, cache, scope)
            try {
                assertTrue(workspace.restoreLocation())
                assertEquals(listOf("A", "D"), workspace.tabsFlow.value.map { it.stack.last().id })
                assertEquals("D", workspace.folderStackFlow.value.last().id)
                assertEquals(2, workspace.tabsFlow.value.map { it.id }.distinct().size)
            } finally { workspace.close() }
        }
    }

    private val root = PikoDriveRepository.ROOT_BREADCRUMB
    private val anime = PikoPathBreadcrumb("A", "动画")
    private val show = PikoPathBreadcrumb("S", "Frieren")
    private val docs = PikoPathBreadcrumb("D", "文档")

    @Test
    fun `back returns to where a jump started and forward undoes it`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        repoWorkspace.restoreFolderStack(listOf(root, docs))
        assertFalse(repoWorkspace.historyFlow.value.canGoBack, "恢复上次的位置不该留下一步后退")

        // 从别处「在网盘中显示」跳到很深的目录
        repoWorkspace.updateFolderStack(listOf(root, anime, show))
        // 上一级走的是层级
        repoWorkspace.popFolder()
        assertEquals(listOf(root, anime), repoWorkspace.folderStackFlow.value)

        // 后退两步回到跳之前在看的地方，而不是层级上的根
        assertTrue(repoWorkspace.goBack())
        assertEquals(listOf(root, anime, show), repoWorkspace.folderStackFlow.value)
        assertTrue(repoWorkspace.goBack())
        assertEquals(listOf(root, docs), repoWorkspace.folderStackFlow.value)
        assertFalse(repoWorkspace.goBack())

        assertTrue(repoWorkspace.goForward())
        assertEquals(listOf(root, anime, show), repoWorkspace.folderStackFlow.value)

        // 走了新的一步，前进作废
        repoWorkspace.pushFolder("E", "SPs")
        assertFalse(repoWorkspace.historyFlow.value.canGoForward)
        assertTrue(repoWorkspace.historyFlow.value.canGoBack)
    }

    @Test
    fun `recent folders keep one entry per folder, newest first, without the root`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        repoWorkspace.updateFolderStack(listOf(root, anime, show))
        repoWorkspace.updateFolderStack(listOf(root, docs))
        repoWorkspace.popFolder()
        repoWorkspace.goBack()
        assertEquals(listOf(listOf(root, docs), listOf(root, anime, show)), repoWorkspace.recentFoldersFlow.value)
    }

    @Test
    fun `each tab keeps its own place and history`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, backgroundScope = scope)
        repoWorkspace.updateFolderStack(listOf(root, anime))
        val first = repoWorkspace.activeTabId.value

        // 新标签停在文档里，历史是它自己的
        val second = repoWorkspace.openTab(listOf(root, docs))
        assertEquals(listOf(root, docs), repoWorkspace.folderStackFlow.value)
        assertFalse(repoWorkspace.historyFlow.value.canGoBack)
        repoWorkspace.pushFolder("E", "SPs")

        // 切回第一个：位置与后退都是原来的
        repoWorkspace.switchTab(first)
        assertEquals(listOf(root, anime), repoWorkspace.folderStackFlow.value)
        assertTrue(repoWorkspace.goBack())
        assertEquals(listOf(root), repoWorkspace.folderStackFlow.value)

        // 标签列表里第二个带着它离开时的位置
        assertEquals(listOf(root, docs, PikoPathBreadcrumb("E", "SPs")), repoWorkspace.tabsFlow.value.first { it.id == second }.stack)

        assertFalse(repoWorkspace.closeTab(first))
        assertEquals(first, repoWorkspace.activeTabId.value)
        assertEquals(listOf(root), repoWorkspace.folderStackFlow.value)
        repoWorkspace.switchTab(second)
        assertTrue(repoWorkspace.closeTab(second))
        assertEquals(first, repoWorkspace.activeTabId.value)
        assertEquals(listOf(root), repoWorkspace.folderStackFlow.value)

    }

    @Test
    fun `open tabs come back after a restart`() = smoke { scope ->
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        val before = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val beforeWorkspace = dev.piko.shared.presentation.DriveWorkspace(before, cacheStore = cache, backgroundScope = scope)
        beforeWorkspace.updateFolderStack(listOf(root, anime))
        beforeWorkspace.openTab(listOf(root, docs))
        // 存盘有一秒的延迟，等它落下
        kotlinx.coroutines.delay(1_500)

        val after = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)

        val afterWorkspace = dev.piko.shared.presentation.DriveWorkspace(after, cacheStore = cache, backgroundScope = scope)
        assertTrue(afterWorkspace.restoreLocation())
        assertEquals(beforeWorkspace.tabsFlow.value.map { it.id }, afterWorkspace.tabsFlow.value.map { it.id }, "标签身份随位置恢复")
        assertEquals(listOf(listOf(root, anime), listOf(root, docs)), afterWorkspace.tabsFlow.value.map { it.stack })
        assertEquals(listOf(root, docs), afterWorkspace.folderStackFlow.value, "活动的是后开的那个")
        assertFalse(afterWorkspace.restoreLocation(), "已经不在初始状态，不再恢复")
    }

    @Test
    fun `opening a library is not overwritten by saved tabs`() = smoke { scope ->
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        val before = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val beforeWorkspace = dev.piko.shared.presentation.DriveWorkspace(before, cacheStore = cache, backgroundScope = scope)
        beforeWorkspace.updateFolderStack(listOf(root, docs))
        kotlinx.coroutines.delay(1_500)

        for (library in DriveLibrary.entries) {
            val repo = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
            val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, cacheStore = cache, backgroundScope = scope)
            repoWorkspace.updateFolderStack(listOf(library.crumb))
            assertFalse(repoWorkspace.restoreLocation(), library.title)
            assertEquals(listOf(library.crumb), repoWorkspace.folderStackFlow.value)
        }
    }

    @Test
    fun `navigation while saved tabs are being read wins over restoration`() = smoke { scope ->
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        val before = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val beforeWorkspace = dev.piko.shared.presentation.DriveWorkspace(before, cacheStore = cache, backgroundScope = scope)
        beforeWorkspace.updateFolderStack(listOf(root, docs))
        kotlinx.coroutines.delay(1_500)

        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val slowCache = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                if (key.startsWith("drive-tabs-")) {
                    reading.complete(Unit)
                    resume.await()
                }
                return cache.read(key)
            }

            override suspend fun write(key: String, value: String) = cache.write(key, value)
        }
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences(), slowCache)
        val repoWorkspace = dev.piko.shared.presentation.DriveWorkspace(repo, cacheStore = slowCache, backgroundScope = scope)
        val restored = scope.async { repoWorkspace.restoreLocation() }
        reading.await()
        repoWorkspace.updateFolderStack(listOf(DriveLibrary.TRASH.crumb))
        resume.complete(Unit)
        assertFalse(restored.await())
        assertEquals(listOf(DriveLibrary.TRASH.crumb), repoWorkspace.folderStackFlow.value)
    }
}
