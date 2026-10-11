package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.presentation.LibraryNavigation
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.DriveScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveWorkspaceSmokeTest {
    @Test
    fun `clipboard moves files across browser contexts and stays local to its workspace`() = smoke { scope ->
        val server = FakePikPakServer()
        val sourceFolder = server.addFolder("动画")
        val targetFolder = server.addFolder("收藏")
        val file = server.addFile("video.mkv", sourceFolder.id, content = ByteArray(4))
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val otherWorkspace = DriveWorkspace(repository, backgroundScope = scope)
        workspace.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(sourceFolder.id, sourceFolder.name)))
        val source = DriveScreenState(repository, prefs, scope, navigation = workspace, clipboard = workspace.clipboard)
        val targetNavigation = LibraryNavigation(repository, DriveLibrary.STARRED, scope)
        targetNavigation.pushFolder(targetFolder.id, targetFolder.name)
        val target = DriveScreenState(repository, prefs, scope, navigation = targetNavigation, clipboard = workspace.clipboard)
        source.load()
        target.load()
        awaitUntil("原文件可选择") { source.displayedFiles.any { it.id == file.id } }
        source.putOnClipboard(listOf(file.id), cut = true)
        assertEquals(mapOf(file.id to sourceFolder.id), target.clipboardFlow.value?.sources)
        assertEquals(null, otherWorkspace.clipboard.contents.value)

        target.paste()
        awaitUntil("从资料页粘贴完成") { file.parentId == targetFolder.id && repository.changes.latest.value != null }
        assertEquals(null, source.clipboardFlow.value)
        assertTrue(target.undoLast())
        awaitUntil("撤销回原目录") { file.parentId == sourceFolder.id }
    }

    @Test
    fun `closing a report tab leaves the scan and its report available`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("动画")
        server.addFile("video.mkv", folder.id, content = ByteArray(4), hash = "SAME")
        server.addFile("copy.mkv", folder.id, content = ByteArray(4), hash = "SAME")
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val session = DuplicateSession(
            newScope = { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) },
            newState = { child, root -> DuplicateFinderState(provider, repository, child, root) },
        )
        try {
            val root = PikoPathBreadcrumb(folder.id, folder.name)
            session.open(root)
            val finder = session.state
            val tab = workspace.openTab(listOf(DriveLibrary.DUPLICATES.crumb))
            workspace.closeTab(tab)
            awaitUntil("结果可恢复") { session.state?.phase == DuplicateFinderState.Phase.DONE }
            assertTrue(session.state === finder)
            assertEquals(1, session.state!!.report.identical.size)
            session.open(root)
            assertTrue(session.state === finder, "重新查看相同起点不会重新扫描")
        } finally {
            session.end()
        }
    }

    @Test
    fun `late folder changes cannot rewrite the previous account workspace`() = smoke { _ ->
        val firstServer = FakePikPakServer("A")
        val secondServer = FakePikPakServer("B")
        val folder = firstServer.addFolder("动画")
        val accounts = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<io.github.nihildigit.pikpak.PikPakClient?>(firstServer.client(account = "a@piko.dev"))
        }
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = PikoDriveRepository(accounts, MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = owner)
        try {
            repository.listAllFiles("").getOrThrow()
            val path = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folder.id, folder.name))
            workspace.updateFolderStack(path)
            workspace.clipboard.put(DriveClipboard(mapOf(folder.id to ""), cut = true))
            accounts.currentClient.value = secondServer.client(account = "b@piko.dev")
            repository.enterAccount("b@piko.dev")
            repository.applyChange(DriveChange.Renamed(folder.id, "晚到的名字", ""))
            assertEquals(path, workspace.folderStackFlow.value, "工作区尚未切号时不接受另一运行期的目录变更")
            while (true) { (queue.poll() ?: break).run() }
            assertEquals(listOf(PikoDriveRepository.ROOT_BREADCRUMB), workspace.folderStackFlow.value)
            assertEquals(null, workspace.clipboard.contents.value)

            val secondFolder = secondServer.addFolder("乙目录")
            val secondPath = listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(secondFolder.id, secondFolder.name))
            workspace.updateFolderStack(secondPath)
            workspace.clipboard.put(DriveClipboard(mapOf(secondFolder.id to ""), cut = true))
            accounts.currentClient.value = null
            while (true) { (queue.poll() ?: break).run() }
            assertEquals(null, workspace.clipboard.contents.value, "退出登录不保留待粘贴的文件身份")
            assertEquals(secondPath, workspace.folderStackFlow.value, "同账号重新登录仍可保留浏览位置")
        } finally {
            workspace.close()
            owner.cancel()
            while (true) { (queue.poll() ?: break).run() }
        }
    }

    @Test
    fun `workspaces share file changes but own their navigation and lifetime`() = smoke { scope ->
        val server = FakePikPakServer()
        val firstFolder = server.addFolder("动画")
        val otherFolder = server.addFolder("文档")
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences())
        val first = DriveWorkspace(repository, backgroundScope = scope)
        val second = DriveWorkspace(repository, backgroundScope = scope)
        val root = PikoDriveRepository.ROOT_BREADCRUMB
        repository.listAllFiles("").getOrThrow()
        first.updateFolderStack(listOf(root, PikoPathBreadcrumb(firstFolder.id, firstFolder.name)))
        second.updateFolderStack(listOf(root, PikoPathBreadcrumb(otherFolder.id, otherFolder.name)))
        first.saveScrollAnchor(firstFolder.id, ScrollAnchor(18, 4))
        second.saveScrollAnchor(otherFolder.id, ScrollAnchor(3, 9))
        assertEquals(otherFolder.id, second.folderStackFlow.value.last().id)
        assertEquals(null, second.scrollAnchor(firstFolder.id))

        repository.rename(firstFolder.id, "Anime").getOrThrow()
        assertEquals("Anime", first.folderStackFlow.value.last().name)
        assertEquals("文档", second.folderStackFlow.value.last().name)
        assertEquals(ScrollAnchor(18, 4), first.scrollAnchor(firstFolder.id))
        second.updateFolderStack(first.folderStackFlow.value)
        first.close()
        repository.rename(firstFolder.id, "动画合集").getOrThrow()
        assertEquals("Anime", first.folderStackFlow.value.last().name, "已释放的工作区不再接收文件变更")
        assertEquals("动画合集", second.folderStackFlow.value.last().name)
        assertTrue(second.goBack())
        assertEquals(otherFolder.id, second.folderStackFlow.value.last().id)
        assertTrue(second.historyFlow.value.canGoBack)
    }
}
