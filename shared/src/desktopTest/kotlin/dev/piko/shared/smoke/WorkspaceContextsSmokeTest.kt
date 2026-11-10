package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.presentation.AddLinkViewModel
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FolderHistory
import dev.piko.shared.presentation.LibraryNavigation
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceContextsSmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB
    private val anime = PikoPathBreadcrumb("A", "动画")
    private val show = PikoPathBreadcrumb("S", "Frieren")
    private val docs = PikoPathBreadcrumb("D", "文档")

    @Test
    fun `reordering mixed tabs retains identities active work and file browser order`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Folder")
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        workspace.updateFolderStack(listOf(root, PikoPathBreadcrumb(folder.id, folder.name)))
        val first = workspace.activeTabId.value
        val second = workspace.openTab(listOf(root))
        val draft = workspace.openImportDraftContext()
        val browsers = workspace.tabsFlow.value.associateBy { it.id }
        var creations = 0
        workspace.contexts.onCreated = { creations++ }
        assertTrue(workspace.moveContext(draft, 1))
        assertEquals(listOf(first, draft, second), workspace.contextsFlow.value.map { it.id })
        assertEquals(listOf(first, second), workspace.tabsFlow.value.map { it.id })
        assertEquals(browsers, workspace.tabsFlow.value.associateBy { it.id })
        assertEquals(draft, workspace.activeContextId.value)
        assertEquals(0, creations)
        assertFalse(workspace.moveContext(Long.MIN_VALUE, 0))
        assertTrue(workspace.moveContext(second, -10))
        assertEquals(listOf(second, first, draft), workspace.contextsFlow.value.map { it.id })
        assertEquals(second, workspace.contextsFlow.value.first().id)
        assertEquals(draft, workspace.activeContextId.value)
        workspace.close()
    }

    @Test
    fun `creation signal includes background tabs but excludes restoring switching and closing`() {
        val contexts = WorkspaceContexts()
        val created = mutableListOf<WorkspaceContext>()
        contexts.onCreated = { created += it }
        contexts.restoreFilesPreservingAux(listOf(51L, 52L), 52L)
        contexts.activate(51L)
        assertTrue(created.isEmpty())

        contexts.openFile(53L, activate = false)
        val duplicate = contexts.openDuplicate(PikoPathBreadcrumb("folder", "Folder"))
        val draft = contexts.openImportDraft()
        assertEquals(listOf(53L, duplicate, draft), created.map { it.id })
        contexts.activate(53L)
        contexts.close(draft)
        contexts.resetFiles(54L)
        assertEquals(3, created.size)
    }

    @Test
    fun `independent duplicate contexts survive switching and only closed one is discarded`() {
        val contexts = WorkspaceContexts()
        val folder = PikoPathBreadcrumb("folder", "Folder")
        val first = contexts.openDuplicate(folder)
        val second = contexts.openDuplicate(folder)
        assertNotEquals(first, second)
        assertEquals(second, contexts.activeContextId.value)
        assertTrue(contexts.activate(first))
        assertEquals(first, contexts.activeContextId.value)

        assertIs<WorkspaceContext.Duplicate>(contexts.close(first))
        assertEquals(second, contexts.activeContextId.value)
        assertTrue(contexts.contextsFlow.value.any { it.id == second })
        assertFalse(contexts.activate(first))
        assertNull(contexts.close(1L))
        assertIs<WorkspaceContext.Duplicate>(contexts.close(second))
        assertEquals(listOf(WorkspaceContext.File(1L)), contexts.contextsFlow.value)
    }

    @Test
    fun `primary file stays available while auxiliary contexts open and close`() = smoke { scope ->
        val repository = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val file = workspace.primaryTabId
        val duplicate = workspace.openDuplicateContext(PikoPathBreadcrumb("folder", "Folder"))
        assertNull(workspace.closeContext(file))
        assertEquals(listOf(file, duplicate), workspace.contextsFlow.value.map { it.id })
        assertEquals(duplicate, workspace.activeContextId.value)
        assertEquals(file, workspace.activatePrimaryFileBrowser())
        val newFile = workspace.openTab(listOf(root))
        assertTrue(workspace.closeTab(newFile))
        workspace.closeContext(duplicate)
        assertEquals(listOf(WorkspaceContext.File(file)), workspace.contextsFlow.value)
        assertEquals(file, workspace.activeContextId.value)
        workspace.close()
    }

    @Test
    fun `any file tab can close or move while at least one file tab remains and resetting preserves aux`() {
        val contexts = WorkspaceContexts()
        val draft = contexts.openImportDraft()
        contexts.restoreFilesPreservingAux(listOf(51L, 51L, 52L), -1L)
        assertEquals(51L, contexts.primaryFileId)
        assertEquals(listOf(51L, 52L, draft), contexts.contextsFlow.value.map { it.id })
        assertEquals(draft, contexts.activeContextId.value)
        assertTrue(contexts.canClose(51L))
        assertTrue(contexts.canClose(52L))
        assertFalse(contexts.canClose(-1L))
        assertEquals(WorkspaceContext.File(51L), contexts.close(51L))
        assertEquals(52L, contexts.primaryFileId)
        assertFalse(contexts.canClose(52L))
        assertNull(contexts.close(52L))
        contexts.activate(52L)
        contexts.restoreFilesPreservingAux(emptyList(), -1L)
        assertEquals(1L, contexts.primaryFileId)
        assertEquals(listOf(1L, draft), contexts.contextsFlow.value.map { it.id })
        contexts.resetFiles(61L)
        assertEquals(61L, contexts.primaryFileId)
        assertEquals(listOf(61L, draft), contexts.contextsFlow.value.map { it.id })
        assertNull(contexts.close(61L))
    }

    @Test
    fun `published tabs restore file position without vanished reports and assign distinct ids`() = smoke { scope ->
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        cache.write(
            "drive-tabs-smoke@piko.dev.json",
            """{"tabs":[[{"id":"","name":"网盘"}],[{"id":"piko:duplicates","name":"查找重复"}]],"active":1}""",
        )
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val workspace = DriveWorkspace(repository, cacheStore = cache, backgroundScope = scope)
        assertTrue(workspace.restoreLocation())
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        assertEquals(1, workspace.contextsFlow.value.size)
        workspace.close()

        for (metadata in listOf("", ",\"ids\":[7,7]")) {
            val idCache = MemoryCacheStore()
            idCache.write("drive-tabs-smoke@piko.dev.json", """{"tabs":[[{"id":"","name":"网盘"},{"id":"A","name":"动画"}],[{"id":"","name":"网盘"},{"id":"D","name":"文档"}]],"active":1$metadata}""")
            val ws = DriveWorkspace(PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences()), idCache, scope)
            try {
                assertTrue(ws.restoreLocation())
                assertEquals(listOf("A", "D"), ws.tabsFlow.value.map { it.stack.last().id })
                assertEquals("D", ws.folderStackFlow.value.last().id)
                assertEquals(2, ws.tabsFlow.value.map { it.id }.distinct().size)
            } finally { ws.close() }
        }
    }

    @Test
    fun `an import opened or locate consumed during cache read interacts cleanly with tab restoration`() = smoke { scope ->
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val server = FakePikPakServer()
        val folder = server.addFolder("Saved")
        val cache = object : PikoCacheStore {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("drive-tabs-")) return null
                readStarted.complete(Unit)
                releaseRead.await()
                return """{"tabs":[[{"id":"","name":"网盘"},{"id":"${folder.id}","name":"Saved"}]],"active":0,"ids":[73]}"""
            }
            override suspend fun write(key: String, value: String) = Unit
        }
        val workspace = DriveWorkspace(PikoDriveRepository(server.provider(), MemoryPreferences(), cache), cacheStore = cache, backgroundScope = scope)
        val restoring = scope.async { workspace.restoreLocation() }
        readStarted.await()
        val importId = workspace.openImportDraftContext()
        releaseRead.complete(Unit)
        assertTrue(restoring.await())
        assertEquals(importId, workspace.activeContextId.value)
        assertEquals(73L, workspace.activeTabId.value)
        assertEquals(listOf(73L, importId), workspace.contextsFlow.value.map { it.id })
        workspace.close()

        val locateReading = CompletableDeferred<Unit>()
        val locateRelease = CompletableDeferred<Unit>()
        val locateCache = object : PikoCacheStore by MemoryCacheStore() {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("drive-tabs-")) return null
                locateReading.complete(Unit)
                locateRelease.await()
                return """{"tabs":[[{"id":"","name":"网盘"},{"id":"saved","name":"saved"}]],"active":0}"""
            }
        }
        val locateWs = DriveWorkspace(PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences()), locateCache, scope)
        val locateRestored = scope.async { locateWs.restoreLocation() }
        withTimeout(5_000) { locateReading.await() }
        locateWs.requestHighlight(setOf("new-file"))
        assertEquals(setOf("new-file"), locateWs.takePendingHighlight())
        locateRelease.complete(Unit)
        assertFalse(locateRestored.await())
        assertEquals(listOf(root), locateWs.folderStackFlow.value)
        locateWs.close()
    }

    @Test
    fun `cold start incoming link bound to placeholder tab rebinds to restored active tab`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Movies")
        val cache = MemoryCacheStore()
        cache.write(
            "drive-tabs-smoke@piko.dev.json",
            """{"tabs":[[{"id":"","name":"网盘"},{"id":"${folder.id}","name":"Movies"}]],"active":0,"ids":[73]}""",
        )
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences(), cache)
        val workspace = DriveWorkspace(repository, cacheStore = cache, backgroundScope = scope)
        val instantRepo = InstantMagnetRepository(provider)
        val prefs = MemoryPreferences()
        val temp = PreviewTempFolder(provider, repository, instantRepo, scope)
        val packs = OfflinePackTracker(instantRepo, repository, prefs)
        val records = InstantSaveRecords(provider, cache, scope)
        val session = InstantSession(
            newScope = { CoroutineScope(scope.coroutineContext + SupervisorJob()) },
            newState = { draftScope, magnet -> InstantSheetState(instantRepo, repository, prefs, temp, packs, records, draftScope, magnet) },
        )
        val addLink = AddLinkViewModel(session)
        val magnet = "magnet:?xt=urn:btih:" + "f".repeat(40)

        assertEquals(1L, workspace.activeTabId.value)
        addLink.open(magnet, tabId = workspace.activeTabId.value)
        assertTrue(workspace.restoreLocation())
        assertEquals(73L, workspace.activeTabId.value)
        addLink.rebindTabIfMissing(workspace.tabsFlow.value.mapTo(HashSet()) { it.id }, workspace.activeTabId.value)

        assertEquals(73L, addLink.ownerTabId)
        assertTrue(addLink.isExpanded)
        assertEquals(magnet, addLink.state?.input)
        session.clear()
        workspace.close()
    }

    @Test
    fun `back forward recent folders and flush persistence work across restarts without debounce sleep`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val cache = MemoryCacheStore()
        val ws = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences(), cache), cacheStore = cache, backgroundScope = scope)
        ws.enterAccount(provider.currentClient.value!!.account)
        ws.restoreFolderStack(listOf(root, docs))
        assertFalse(ws.historyFlow.value.canGoBack)

        ws.updateFolderStack(listOf(root, anime, show))
        ws.popFolder()
        assertEquals(listOf(root, anime), ws.folderStackFlow.value)
        assertTrue(ws.goBack())
        assertEquals(listOf(root, anime, show), ws.folderStackFlow.value)
        assertTrue(ws.goBack())
        assertEquals(listOf(root, docs), ws.folderStackFlow.value)
        assertTrue(ws.goForward())
        ws.pushFolder("E", "SPs")
        assertFalse(ws.historyFlow.value.canGoForward)
        assertEquals(listOf(listOf(root, anime, show, PikoPathBreadcrumb("E", "SPs")), listOf(root, docs)), ws.recentFoldersFlow.value.take(2))

        ws.openTab(listOf(root, docs))
        ws.flushPersistence()
        val savedIds = ws.tabsFlow.value.map { it.id }
        ws.close()

        val restarted = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences(), cache), cacheStore = cache, backgroundScope = scope)
        assertTrue(restarted.restoreLocation())
        assertEquals(savedIds, restarted.tabsFlow.value.map { it.id })
        assertEquals(listOf(root, docs), restarted.folderStackFlow.value)
        assertFalse(restarted.restoreLocation())
        restarted.close()

        for (library in DriveLibrary.entries) {
            val libWs = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences(), cache), cacheStore = cache, backgroundScope = scope)
            libWs.updateFolderStack(listOf(library.crumb))
            assertFalse(libWs.restoreLocation(), library.title)
            assertEquals(listOf(library.crumb), libWs.folderStackFlow.value)
            libWs.close()
        }
    }

    @Test
    fun `workspace flush caller can time out while accepted write finishes`() = smoke { scope ->
        val provider = FakePikPakServer().provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val memory = MemoryCacheStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val cache = object : PikoCacheStore by memory {
            override suspend fun write(key: String, value: String) {
                if (key.startsWith("drive-tabs-")) {
                    entered.complete(Unit)
                    release.await()
                    memory.write(key, value)
                    written.complete(Unit)
                } else memory.write(key, value)
            }
        }
        val workspace = DriveWorkspace(repository, cache, scope)
        try {
            workspace.enterAccount(provider.currentClient.value!!.account)
            workspace.openTab(listOf(root, PikoPathBreadcrumb("slow-folder", "Folder")))
            assertEquals(null, withTimeoutOrNull(100) { workspace.flushPersistence(); true })
            withTimeout(5000) { entered.await() }
            assertFalse(written.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { written.await() }
            assertTrue(memory.read("drive-tabs-smoke@piko.dev.json")?.contains("slow-folder") == true)
        } finally {
            release.complete(Unit)
            workspace.close()
        }
    }

    @Test
    fun `entry tokens isolate tab history anchors and sidebar library routing`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val first = PikoPathBreadcrumb(server.addFolder("first").id, "first")
        val second = PikoPathBreadcrumb(server.addFolder("second").id, "second")
        val firstId = workspace.activeTabId.value
        workspace.navigateToFolder(first)
        workspace.saveScrollAnchor(first.id, ScrollAnchor(3, 17))
        workspace.requestHighlight(setOf("first-file"))
        val secondId = workspace.openTab(listOf(root, second))
        assertEquals(FolderHistory(), workspace.historyFlow.value)
        assertNull(workspace.scrollAnchor(first.id))
        workspace.switchTab(firstId)
        assertEquals(ScrollAnchor(3, 17), workspace.scrollAnchor(first.id))
        assertEquals(setOf("first-file"), workspace.takePendingHighlight())

        val token = workspace.enterWithToken(listOf(DriveLibrary.STARRED.crumb))
        repeat(FolderHistory.LIMIT + 3) { workspace.pushFolder("temp-$it", "temp-$it") }
        assertTrue(workspace.canReturnAcross(token))
        workspace.switchTab(secondId)
        assertTrue(workspace.returnAcross(token))
        assertEquals(firstId, workspace.activeTabId.value)
        assertEquals(listOf(root, first), workspace.folderStackFlow.value)
        assertFalse(workspace.returnAcross(token))

        workspace.switchTab(secondId)
        val libToken = workspace.pushLibrary(DriveLibrary.RECENT)!!
        assertEquals(workspace.primaryTabId, libToken.tabId)
        assertEquals(listOf(DriveLibrary.RECENT.crumb), workspace.folderStackFlow.value)
        assertNull(workspace.pushLibrary(DriveLibrary.RECENT))
        val replaced = workspace.pushLibrary(DriveLibrary.RECENT, reuseCurrentEntry = false)!!
        assertFalse(workspace.canReturnAcross(libToken))
        assertTrue(workspace.returnAcross(replaced))
        workspace.enterAccount("another-account")
        assertFalse(workspace.canReturnAcross(replaced))
        workspace.close()
    }

    @Test
    fun `clipboard duplicate sessions and account boundaries stay isolated across workspaces`() = smoke { scope ->
        val server = FakePikPakServer()
        val sourceFolder = server.addFolder("动画")
        val targetFolder = server.addFolder("收藏")
        val file = server.addFile("video.mkv", sourceFolder.id, content = ByteArray(4), hash = "SAME")
        server.addFile("copy.mkv", sourceFolder.id, content = ByteArray(4), hash = "SAME")
        val prefs = MemoryPreferences()
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, prefs)
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val otherWorkspace = DriveWorkspace(repository, backgroundScope = scope)
        workspace.updateFolderStack(listOf(root, PikoPathBreadcrumb(sourceFolder.id, sourceFolder.name)))
        val source = DriveScreenState(repository, prefs, scope, navigation = workspace, clipboard = workspace.clipboard)
        val targetNavigation = LibraryNavigation(repository, DriveLibrary.STARRED, scope)
        targetNavigation.pushFolder(targetFolder.id, targetFolder.name)
        val target = DriveScreenState(repository, prefs, scope, navigation = targetNavigation, clipboard = workspace.clipboard)
        source.load()
        target.load()
        awaitUntil("source loaded") { source.displayedFiles.any { it.id == file.id } }
        source.putOnClipboard(listOf(file.id), cut = true)
        assertNull(otherWorkspace.clipboard.contents.value)
        target.paste()
        awaitUntil("pasted") { file.parentId == targetFolder.id && repository.changes.latest.value != null }
        assertTrue(target.undoLast())
        awaitUntil("undone") { file.parentId == sourceFolder.id }

        val session = DuplicateSession(
            newScope = { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) },
            newState = { child, r -> DuplicateFinderState(provider, repository, child, r) },
        )
        try {
            val scanRoot = PikoPathBreadcrumb(sourceFolder.id, sourceFolder.name)
            session.open(scanRoot)
            val finder = session.state
            val tab = workspace.openTab(listOf(DriveLibrary.DUPLICATES.crumb))
            workspace.closeTab(tab)
            awaitUntil("scan finished") { session.state?.phase == DuplicateFinderState.Phase.DONE }
            session.open(scanRoot)
            assertTrue(session.state === finder)
        } finally {
            session.end()
        }

        val firstServer = FakePikPakServer("A")
        val secondServer = FakePikPakServer("B")
        val aFolder = firstServer.addFolder("动画")
        val accounts = object : PikoClientProvider {
            override val currentClient = MutableStateFlow<PikPakClient?>(firstServer.client(account = "a@piko.dev"))
        }
        val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val accountRepo = PikoDriveRepository(accounts, MemoryPreferences())
        val accountWs = DriveWorkspace(accountRepo, backgroundScope = owner)
        try {
            accountRepo.listAllFiles("").getOrThrow()
            val path = listOf(root, PikoPathBreadcrumb(aFolder.id, aFolder.name))
            accountWs.updateFolderStack(path)
            accountWs.clipboard.put(DriveClipboard(mapOf(aFolder.id to ""), cut = true))
            accounts.currentClient.value = secondServer.client(account = "b@piko.dev")
            accountRepo.enterAccount("b@piko.dev")
            accountRepo.applyChange(DriveChange.Renamed(aFolder.id, "晚到的名字", ""))
            assertEquals(path, accountWs.folderStackFlow.value)
            while (true) { (queue.poll() ?: break).run() }
            assertEquals(listOf(root), accountWs.folderStackFlow.value)
            assertNull(accountWs.clipboard.contents.value)
        } finally {
            accountWs.close()
            owner.cancel()
            while (true) { (queue.poll() ?: break).run() }
            workspace.close()
            otherWorkspace.close()
        }
    }
}
