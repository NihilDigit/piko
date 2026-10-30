package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TrashSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceContextsSmokeTest {
    @Test
    fun `reordering mixed tabs retains identities active work and file browser order`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Folder")
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        workspace.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(folder.id, folder.name)))
        val first = workspace.activeTabId.value
        val second = workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
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
        val root = PikoPathBreadcrumb("folder", "Folder")
        val first = contexts.openDuplicate(root)
        val second = contexts.openDuplicate(root)
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
        val newFile = workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB))
        assertTrue(workspace.closeTab(newFile))
        workspace.closeContext(duplicate)
        assertEquals(listOf(WorkspaceContext.File(file)), workspace.contextsFlow.value)
        assertEquals(file, workspace.activeContextId.value)
        workspace.close()
    }

    @Test
    fun `any file tab can close or move while at least one file tab remains`() {
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
        assertEquals(1L, contexts.activeContextId.value)
        contexts.resetFiles(61L)
        assertEquals(61L, contexts.primaryFileId)
        assertNull(contexts.close(61L))
    }

    @Test
    fun `resetting files preserves auxiliary contexts behind the new primary`() {
        val contexts = WorkspaceContexts()
        val duplicate = contexts.openDuplicate(PikoPathBreadcrumb("folder", "Folder"))
        val draft = contexts.openImportDraft()
        contexts.resetFiles(51L)
        assertEquals(51L, contexts.primaryFileId)
        assertEquals(listOf(51L, duplicate, draft), contexts.contextsFlow.value.map { it.id })
        assertEquals(51L, contexts.activeContextId.value)
        assertNull(contexts.close(51L))
    }

    @Test
    fun `published tabs restore the file position without a vanished report`() = smoke { scope ->
        val server = FakePikPakServer()
        val cache = MemoryCacheStore()
        cache.write(
            "drive-tabs-smoke@piko.dev.json",
            """{"tabs":[[{"id":"","name":"网盘"}],[{"id":"piko:duplicates","name":"查找重复"}]],"active":1}""",
        )
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val workspace = DriveWorkspace(repository, cacheStore = cache, backgroundScope = scope)
        assertTrue(workspace.restoreLocation())
        assertEquals(listOf(PikoDriveRepository.ROOT_BREADCRUMB), workspace.folderStackFlow.value)
        assertEquals(1, workspace.contextsFlow.value.size)
        assertTrue(workspace.tabsFlow.value.none { it.stack.firstOrNull()?.id == DriveLibrary.DUPLICATES.id })
    }

    @Test
    fun `an import opened during cache read stays active while untouched file tabs restore`() = smoke { scope ->
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
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences(), cache)
        val workspace = DriveWorkspace(repository, cacheStore = cache, backgroundScope = scope)
        val restoring = scope.async { workspace.restoreLocation() }
        readStarted.await()
        val importId = workspace.openImportDraftContext()
        releaseRead.complete(Unit)

        assertTrue(restoring.await())
        assertEquals(importId, workspace.activeContextId.value)
        assertTrue(workspace.contextsFlow.value.any { it.id == importId && it is WorkspaceContext.ImportDraft })
        assertEquals(73L, workspace.activeTabId.value)
        assertEquals(folder.id, workspace.folderStackFlow.value.last().id)
        assertEquals(listOf(73L, importId), workspace.contextsFlow.value.map { it.id })
    }

    @Test
    fun `desktop file navigation follows context selection without losing the other file`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Folder")
        val repository = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val root = PikoDriveRepository.ROOT_BREADCRUMB
        val path = listOf(root, PikoPathBreadcrumb(folder.id, folder.name))

        workspace.updateFolderStack(path)
        val first = workspace.activeTabId.value
        val second = workspace.openTab(listOf(root))
        val duplicate = workspace.openDuplicateContext(PikoPathBreadcrumb(folder.id, folder.name))
        assertEquals(duplicate, workspace.activeContextId.value)
        assertTrue(workspace.contexts.activate(first))
        assertEquals(path, workspace.folderStackFlow.value)
        assertEquals(first, workspace.activeTabId.value)
        assertTrue(workspace.switchContext(second))
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        assertEquals(second, workspace.activeContextId.value)

        val closed = mutableListOf<WorkspaceContext>()
        workspace.onContextClosed = closed::add
        assertIs<WorkspaceContext.Duplicate>(workspace.closeContext(duplicate))
        assertEquals(listOf(duplicate), closed.map { it.id })
        assertEquals(path, workspace.tabsFlow.value.first { it.id == first }.stack)
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
        val session = dev.piko.shared.state.InstantSession(
            newScope = { kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob()) },
            newState = { draftScope, magnet ->
                dev.piko.shared.state.InstantSheetState(instantRepo, repository, prefs, temp, packs, records, draftScope, magnet)
            },
        )
        val addLink = dev.piko.shared.presentation.AddLinkViewModel(session)
        val magnet = "magnet:?xt=urn:btih:" + "f".repeat(40)

        // 模拟冷启动首帧：restoreLocation 尚未从磁盘读完，外部链接先绑在初始占位标签 1L 上
        assertEquals(1L, workspace.activeTabId.value)
        addLink.open(magnet, tabId = workspace.activeTabId.value)
        assertEquals(1L, addLink.ownerTabId)
        assertTrue(addLink.isExpanded)

        // 随后 restoreLocation 将初始占位标签 1L 替换为存盘标签 73L
        assertTrue(workspace.restoreLocation())
        assertEquals(73L, workspace.activeTabId.value)
        addLink.rebindTabIfMissing(workspace.tabsFlow.value.mapTo(HashSet()) { it.id }, workspace.activeTabId.value)

        assertEquals(73L, addLink.ownerTabId, "初始占位标签被恢复标签替换后，添加链接面板必须跟随迁移到恢复后的活动标签")
        assertTrue(addLink.isExpanded)
        assertEquals(magnet, addLink.state?.input)
        session.clear()
        workspace.close()
    }

}
