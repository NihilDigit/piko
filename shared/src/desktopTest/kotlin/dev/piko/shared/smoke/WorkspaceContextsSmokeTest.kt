package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.TaskContinuations
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
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
        assertNull(contexts.close(1L), "the main file position remains available")
    }

    @Test
    fun `restoring files discards transient contexts but preserves restored file ids`() {
        val contexts = WorkspaceContexts()
        val duplicate = contexts.openDuplicate(PikoPathBreadcrumb("folder", "Folder"))
        val draft = contexts.openImportDraft()
        val discarded = contexts.resetFiles(listOf(51L, 52L), 52L)
        assertEquals(setOf(duplicate, draft), discarded.map { it.id }.toSet())
        assertEquals(listOf(51L, 52L), contexts.contextsFlow.value.map { it.id })
        assertEquals(52L, contexts.activeContextId.value)
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
    fun `continuation entries count both reports and ignore a stale close action`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val sessions = mutableMapOf<Long, DuplicateSession>()
        fun newSession(root: PikoPathBreadcrumb): DuplicateSession = DuplicateSession(
            newScope = { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) },
            newState = { child, folder -> DuplicateFinderState(provider, repository, child, folder) },
        ).also { it.open(root) }
        sessions[11L] = newSession(PikoDriveRepository.ROOT_BREADCRUMB)
        sessions[12L] = newSession(PikoPathBreadcrumb(server.addFolder("Other").id, "Other"))
        val continuations = TaskContinuations(
            drafts = { emptyList() },
            duplicates = { sessions.mapNotNull { (id, session) -> session.state?.let { finder ->
                TaskContinuations.DuplicateContinuation(id, finder, {}, { sessions.remove(id)?.end() })
            } } },
            cleanup = TrashSession(repository, scope),
        )
        assertEquals(2, continuations.count(TransferKind.FILES))
        val old = continuations.duplicateEntries.first { it.contextId == 11L }
        continuations.discardDuplicate(old)
        assertEquals(1, continuations.count(TransferKind.FILES))
        assertTrue(sessions[12L]?.state != null)

        sessions[11L] = newSession(PikoDriveRepository.ROOT_BREADCRUMB)
        continuations.discardDuplicate(old)
        assertTrue(sessions[11L]?.state != null, "a late action cannot close a replacement with the same ID")
        sessions.values.forEach { it.end() }
    }
}
