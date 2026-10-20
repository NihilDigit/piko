package dev.piko.shared.smoke

import dev.piko.data.repository.FileCategory
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.presentation.DriveBrowserStore
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.state.AccountExecutionScopes
import dev.piko.shared.state.DriveBrowserSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.TrashSession
import dev.piko.shared.state.DriveListItem
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserContextSmokeTest {
    @Test
    fun `shared folder descriptions refresh when child names change without changing their count`() = smoke { scope ->
        val rig = Rig(scope)
        try {
            val folder = rig.server.addFolder("动画")
            val children = (1..4).map { episode -> rig.server.addVideo("[Group] Frieren - 0$episode [1080p].mkv", folder.id) }
            val first = rig.browsers.forTab(rig.workspace.activeTabId.value)
            val second = rig.browsers.forTab(rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB)))
            first.state.load()
            second.state.load()
            rig.repository.listAllFiles(folder.id).getOrThrow()
            awaitUntil("目录内容描述就绪") {
                first.state.folderViews[folder.id]?.title?.contains("Frieren") == true &&
                    second.state.folderViews[folder.id]?.title?.contains("Frieren") == true
            }
            children.forEachIndexed { index, file ->
                rig.repository.rename(file.id, "[Group] Kusuriya no Hitorigoto - 0${index + 1} [1080p].mkv").getOrThrow()
            }
            rig.repository.listAllFiles(folder.id).getOrThrow()
            awaitUntil("相同数量的新内容更新描述") {
                first.state.folderViews[folder.id]?.title?.contains("Kusuriya") == true &&
                    second.state.folderViews[folder.id]?.title?.contains("Kusuriya") == true
            }
        } finally { rig.close() }
    }

    @Test
    fun `expanding a section and revealing folded files do not change another context`() = smoke { scope ->
        val rig = Rig(scope)
        try {
            for (title in listOf("Alpha", "Beta")) for (episode in 1..6) {
                rig.server.addVideo("$title.S01E${episode.toString().padStart(2, '0')}.mkv")
            }
            val first = rig.browsers.forTab(rig.workspace.activeTabId.value)
            val second = rig.browsers.forTab(rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB)))
            first.state.load()
            second.state.load()
            awaitUntil("两份结构化列表就绪") {
                first.state.files.size == 12 && second.state.files.size == 12 &&
                    first.state.isDisplayStructureReady && second.state.isDisplayStructureReady
            }
            assertTrue(first.state.sectionHeaders.isNotEmpty(), "夹具应包含可展开的作品分区")
            val block = first.state.sectionHeaders.first().value
            val secondExpanded = second.state.sectionHeaders.first { it.value.blockId == block.blockId }.value.expanded
            val secondRows = second.state.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id }
            first.state.toggleSection(block.blockId)
            assertEquals(!block.expanded, first.state.sectionHeaders.first { it.value.blockId == block.blockId }.value.expanded)
            assertEquals(secondExpanded, second.state.sectionHeaders.first { it.value.blockId == block.blockId }.value.expanded)
            assertEquals(secondRows, second.state.displayItems.filterIsInstance<DriveListItem.File>().map { it.file.id })
            first.state.setShowAllFiles(true)
            assertTrue(first.state.showAllFilesTemporarily)
            assertFalse(second.state.showAllFilesTemporarily)
        } finally { rig.close() }
    }

    private suspend fun awaitList(browser: DriveBrowserSession, description: String) {
        try {
            awaitUntil(description) { browser.state.isDisplayStructureReady && browser.state.displayedFiles.size == 2 }
        } catch (failure: AssertionError) {
            val state = browser.state
            throw AssertionError("$description: loading=${state.isLoading}, ready=${state.isDisplayStructureReady}, " +
                "files=${state.files.map { it.name }}, shown=${state.displayedFiles.map { it.name }}, " +
                "query=${state.searchQuery}, filter=${state.typeFilter}, error=${state.loadError}", failure)
        }
    }

    @Test
    fun `a file reveal arriving during restoration wins even after its request is consumed`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val cache = MemoryCacheStore()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val previous = DriveWorkspace(repository, cache, scope)
        val savedFolder = server.addFolder("上次的位置")
        previous.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(savedFolder.id, savedFolder.name)))
        delay(1_500)
        previous.close()
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val slowCache = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                if (key.startsWith("drive-tabs-")) { reading.complete(Unit); resume.await() }
                return cache.read(key)
            }
            override suspend fun write(key: String, value: String) = cache.write(key, value)
        }
        val workspace = DriveWorkspace(repository, slowCache, scope)
        val duplicates = DuplicateSession({ scope }) { child, root -> DuplicateFinderState(provider, repository, child, root) }
        val browser = DriveBrowserSession(
            repository, MemoryPreferences(), duplicates, null, TrashSession(repository, scope), scope,
            fileNavigation = workspace.navigationForTab(workspace.activeTabId.value),
        )
        try {
            val file = server.addFile("定位的文件.txt")
            val restoring = scope.async { browser.state.restoreLocation() }
            withTimeout(5_000) { reading.await() }
            workspace.requestHighlight(setOf(file.id))
            awaitUntil("高亮请求已由页面接收") { browser.state.highlightedFileIds == setOf(file.id) && workspace.pendingHighlights.value.isEmpty() }
            resume.complete(Unit)
            assertFalse(restoring.await())
            assertEquals("", workspace.folderStackFlow.value.last().id)
            assertEquals(setOf(file.id), browser.state.highlightedFileIds)
        } finally { resume.complete(Unit); browser.close(); workspace.close() }
    }

    private class Rig(
        val scope: CoroutineScope,
        val server: FakePikPakServer = FakePikPakServer(),
        provider: PikoClientProvider = server.provider(),
        executionParent: CoroutineScope = scope,
    ) {
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(provider, prefs)
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val operations = AccountExecutionScopes(repository, executionParent)
        val cleanup = TrashSession(repository, scope)
        val duplicates = DuplicateSession(
            { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) },
            { child, root -> DuplicateFinderState(provider, repository, child, root) },
        )
        val browsers = DriveBrowserStore { id ->
            DriveBrowserSession(
                repository, prefs, duplicates, null, cleanup, scope,
                fileNavigation = workspace.navigationForTab(id), clipboard = workspace.clipboard,
                operationScope = operations.forAccount(requireNotNull(repository.currentAccount)),
            )
        }

        fun close() { browsers.close(); duplicates.end(); workspace.close() }
    }

    @Test
    fun `tabs in the same folder keep independent queries filters selection and scroll`() = smoke { scope ->
        val rig = Rig(scope)
        try {
            val alpha = rig.server.addFile("Alpha.mp4", content = ByteArray(4))
            val beta = rig.server.addFile("Beta.txt", content = ByteArray(4))
            val firstId = rig.workspace.activeTabId.value
            val first = rig.browsers.forTab(firstId)
            first.state.load()
            awaitList(first, "第一标签可用")
            first.state.updateSearchQuery("Alpha")
            first.state.openSearch()
            first.state.updateTypeFilter(FileCategory.VIDEO)
            first.state.enterSelection(alpha.id)
            first.navigation.saveScrollAnchor("", ScrollAnchor(18, 4))

            val secondId = rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
            val second = rig.browsers.forTab(secondId)
            awaitUntil("初始空结构就绪") { second.state.files.isEmpty() && second.state.isDisplayStructureReady }
            second.state.load()
            awaitList(second, "第二标签可用")
            second.state.updateSearchQuery("Beta")
            second.state.updateTypeFilter(FileCategory.DOCUMENT)
            second.state.enterSelection(beta.id)
            second.navigation.saveScrollAnchor("", ScrollAnchor(3, 9))
            rig.workspace.switchTab(firstId)
            assertTrue(rig.browsers.forTab(firstId) === first)
            assertEquals("Alpha", first.state.searchQuery)
            assertTrue(first.state.isSearchOpen)
            assertEquals(FileCategory.VIDEO, first.state.typeFilter)
            assertEquals(setOf(alpha.id), first.state.selectedFileIds.toSet())
            assertEquals(ScrollAnchor(18, 4), first.navigation.scrollAnchor(""))
            assertEquals("Beta", second.state.searchQuery)
            assertEquals(FileCategory.DOCUMENT, second.state.typeFilter)
            assertEquals(setOf(beta.id), second.state.selectedFileIds.toSet())
            assertEquals(ScrollAnchor(3, 9), second.navigation.scrollAnchor(""))

            val folder = rig.server.addFolder("资料")
            second.navigation.pushFolder(folder.id, folder.name)
            assertEquals(firstId, rig.workspace.activeTabId.value, "后台上下文的导航不切换活动标签")
            assertEquals("", first.navigation.folderStackFlow.value.last().id)
            assertEquals(folder.id, second.navigation.folderStackFlow.value.last().id)
            assertTrue(second.navigation.goBack())
            assertEquals("", second.navigation.folderStackFlow.value.last().id)
            assertEquals("Alpha", first.state.searchQuery)
        } finally { rig.close() }
    }

    @Test
    fun `highlight requests belong to the addressed tab and cannot be consumed by another view`() = smoke { scope ->
        val rig = Rig(scope)
        try {
            val alpha = rig.server.addFile("Alpha.txt")
            val beta = rig.server.addFile("Beta.txt")
            val firstId = rig.workspace.activeTabId.value
            val first = rig.browsers.forTab(firstId)
            first.state.load()
            val secondId = rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
            val second = rig.browsers.forTab(secondId)
            second.state.load()
            rig.workspace.requestHighlight(setOf(beta.id))
            awaitUntil("第二标签高亮") { second.state.highlightedFileIds == setOf(beta.id) }
            assertTrue(first.state.highlightedFileIds.isEmpty())
            rig.workspace.switchTab(firstId)
            rig.workspace.requestHighlight(setOf(alpha.id))
            awaitUntil("第一标签高亮") { first.state.highlightedFileIds == setOf(alpha.id) }
            assertEquals(setOf(beta.id), second.state.highlightedFileIds)
            assertTrue(rig.workspace.pendingHighlights.value.isEmpty())
        } finally { rig.close() }
    }

    @Test
    fun `an accepted rename completes and remains undoable after its tab is closed`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("old.txt")
        val started = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) { started.send(Unit); release.receive() }
            execute(request)
        }
        val client = PikPakClient(
            account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http,
        )
        val rig = Rig(scope, server, server.provider(client))
        try {
            val firstId = rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
            val first = rig.browsers.forTab(firstId)
            first.state.load()
            awaitUntil("文件可改名") { first.state.displayedFiles.any { it.id == file.id } }
            val secondId = rig.workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB), activate = false)
            first.state.rename(file.id, "new.txt")
            withTimeout(5_000) { started.receive() }
            rig.workspace.closeTab(firstId)
            assertEquals(setOf(firstId), rig.browsers.retainTabs(setOf(secondId)))
            assertTrue(scope.isActive, "关闭页面不能取消调用方或账号执行作用域")
            release.send(Unit)
            awaitUntil("关闭标签后仍完成改名") { file.name == "new.txt" && rig.repository.changes.latest.value != null }
            first.state.rename(file.id, "stale.txt")
            assertEquals("new.txt", file.name)
            val second = rig.browsers.forTab(secondId)
            second.state.load()
            awaitUntil("另一个页面可见结果") { second.state.files.any { it.id == file.id && it.name == "new.txt" } }
            assertTrue(second.state.undoLast())
            withTimeout(5_000) { started.receive() }
            release.send(Unit)
            awaitUntil("另一个页面撤销") { file.name == "old.txt" }
        } finally { release.trySend(Unit); rig.close() }
    }

    @Test
    fun `changing account releases old views and cancels only the old account execution`() = smoke { scope ->
        val firstServer = FakePikPakServer("A")
        val secondServer = FakePikPakServer("B")
        firstServer.addFile("old.txt")
        secondServer.addFile("new.txt")
        val accounts = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(firstServer.client(account = "a@piko.dev"))
        }
        val rig = Rig(scope, firstServer, accounts)
        try {
            val first = rig.browsers.forTab(rig.workspace.activeTabId.value)
            first.state.load()
            awaitUntil("旧账号列表") { first.state.files.any { it.name == "old.txt" } }
            val execution = rig.operations.forAccount("a@piko.dev")
            accounts.currentClient.value = secondServer.client(account = "b@piko.dev")
            awaitUntil("旧账号执行结束") { !execution.isActive }
            rig.workspace.enterAccount("b@piko.dev")
            rig.repository.listAllFiles("").getOrThrow()
            first.state.load()
            assertFalse(first.state.files.any { it.name == "new.txt" }, "旧页面不能读取新账号缓存")
            first.navigation.pushFolder("stale", "旧页面动作")
            assertEquals("", rig.workspace.folderStackFlow.value.last().id)
            val secondExecution = rig.operations.forAccount("b@piko.dev")
            assertTrue(secondExecution.isActive)
            assertFalse(rig.operations.forAccount("a@piko.dev").isActive)
            assertTrue(secondExecution.isActive, "旧账号的迟到请求不能取消新账号的执行")
            assertTrue(scope.isActive)
        } finally { rig.close() }
    }

    @Test
    fun `folder creation uses its accepted target even when the browser navigates before execution`() = smoke { scope ->
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val executionParent = CoroutineScope(SupervisorJob() + dispatcher)
        val rig = Rig(scope, executionParent = executionParent)
        try {
            val source = rig.server.addFolder("来源")
            val target = rig.server.addFolder("后来浏览的位置")
            val root = PikoDriveRepository.ROOT_BREADCRUMB
            rig.workspace.updateFolderStack(listOf(root, PikoPathBreadcrumb(source.id, source.name)))
            val browser = rig.browsers.forTab(rig.workspace.activeTabId.value)
            browser.state.createFolder("新目录")
            browser.navigation.updateFolderStack(listOf(root, PikoPathBreadcrumb(target.id, target.name)))
            withTimeout(5_000) {
                while (rig.server.children(source.id).none { it.name == "新目录" }) {
                    while (true) { (queue.poll() ?: break).run() }
                    delay(5)
                }
            }
            assertTrue(rig.server.children(target.id).none { it.name == "新目录" })
        } finally {
            rig.close()
            executionParent.cancel()
            while (true) { (queue.poll() ?: break).run() }
        }
    }
}
