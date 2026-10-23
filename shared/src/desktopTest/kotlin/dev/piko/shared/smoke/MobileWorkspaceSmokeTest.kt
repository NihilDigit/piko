package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.presentation.FolderHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileWorkspaceSmokeTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB

    @Test
    fun `tabs isolate paths history scroll anchors and pending highlights`() = smoke { scope ->
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
        assertTrue(workspace.pendingHighlights.value.isEmpty())
        assertEquals(null, workspace.scrollAnchor(first.id))
        workspace.pushFolder("child", "child")
        workspace.requestHighlight(setOf("second-file"))
        workspace.switchTab(firstId)
        assertEquals(listOf(root, first), workspace.folderStackFlow.value)
        assertEquals(listOf(listOf(root)), workspace.historyFlow.value.back)
        assertEquals(ScrollAnchor(3, 17), workspace.scrollAnchor(first.id))
        assertEquals(setOf("first-file"), workspace.takePendingHighlight())
        assertTrue(workspace.goBack())
        workspace.switchTab(secondId)
        assertEquals("child", workspace.folderStackFlow.value.last().id)
        assertEquals(listOf(listOf(root, second)), workspace.historyFlow.value.back)
        assertEquals(setOf("second-file"), workspace.takePendingHighlight())
        workspace.close()
    }

    @Test
    fun `return across a library entry discards all temporary back and forward history`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val source = PikoPathBreadcrumb(server.addFolder("source").id, "source")
        workspace.navigateToFolder(source)
        workspace.saveScrollAnchor(source.id, ScrollAnchor(3, 17))
        val token = workspace.enterWithToken(listOf(DriveLibrary.STARRED.crumb))
        assertEquals(workspace.activeTabId.value, token.tabId)
        assertEquals(listOf(DriveLibrary.STARRED.crumb), token.enteredStack)
        assertTrue(workspace.canReturnAcross(token))
        workspace.pushFolder("first", "first")
        workspace.saveScrollAnchor("first", ScrollAnchor(5, 19))
        workspace.pushFolder("second", "second")
        assertTrue(workspace.goBack())
        assertTrue(workspace.historyFlow.value.canGoForward)
        assertTrue(workspace.returnAcross(token))
        assertEquals(listOf(root, source), workspace.folderStackFlow.value)
        assertEquals(FolderHistory(back = listOf(listOf(root))), workspace.historyFlow.value)
        assertEquals(ScrollAnchor(3, 17), workspace.scrollAnchor(source.id))
        assertEquals(null, workspace.scrollAnchor("first"))
        assertFalse(workspace.returnAcross(token), "The entry snapshot was consumed")
        assertTrue(workspace.goBack())
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        workspace.close()
    }

    @Test
    fun `return across switches to the entry tab without changing another tab`() = smoke { scope ->
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val entryTab = workspace.activeTabId.value
        val token = workspace.enterWithToken(listOf(DriveLibrary.RECENT.crumb))
        workspace.pushFolder("nested", "nested")
        val other = workspace.openTab(listOf(root, PikoPathBreadcrumb("other", "other")))
        workspace.pushFolder("child", "child")
        val otherPath = workspace.folderStackFlow.value
        val otherHistory = workspace.historyFlow.value
        assertTrue(workspace.returnAcross(token))
        assertEquals(entryTab, workspace.activeTabId.value)
        assertEquals(entryTab, workspace.activeContextId.value)
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        assertEquals(FolderHistory(), workspace.historyFlow.value)
        workspace.switchTab(other)
        assertEquals(otherPath, workspace.folderStackFlow.value)
        assertEquals(otherHistory, workspace.historyFlow.value)
        workspace.closeTab(entryTab)
        assertFalse(workspace.returnAcross(token))
        workspace.close()
    }

    @Test
    fun `invalid or unrecorded entry boundaries leave navigation unchanged`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val token = workspace.enterWithToken(listOf(root))
        assertTrue(workspace.returnAcross(token), "An unchanged path still has an entry snapshot")
        workspace.pushFolder("folder", "folder")
        val before = workspace.tabsFlow.value
        assertFalse(workspace.returnAcross(token.copy(id = -1)))
        assertFalse(workspace.returnAcross(token.copy(id = token.id + 1)))
        assertFalse(workspace.returnAcross(token.copy(tabId = 0)))
        assertEquals(before, workspace.tabsFlow.value)
        workspace.close()
    }

    @Test
    fun `entry snapshot survives bounded history and restores original forward history`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        workspace.pushFolder("source", "source")
        workspace.pushFolder("forward", "forward")
        assertTrue(workspace.goBack())
        val beforeStack = workspace.folderStackFlow.value
        val beforeHistory = workspace.historyFlow.value
        val token = workspace.enterWithToken(listOf(DriveLibrary.RECENT.crumb))
        repeat(FolderHistory.LIMIT + 5) { workspace.pushFolder("temporary-$it", "temporary-$it") }
        assertTrue(workspace.canReturnAcross(token))
        assertTrue(workspace.returnAcross(token))
        assertEquals(beforeStack, workspace.folderStackFlow.value)
        assertEquals(beforeHistory, workspace.historyFlow.value)
        assertTrue(workspace.goForward())
        assertEquals("forward", workspace.folderStackFlow.value.last().id)
        workspace.close()
    }

    @Test
    fun `new entries tab closure and account changes invalidate old tokens`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        workspace.openTab(listOf(root))
        val first = workspace.enterWithToken(listOf(DriveLibrary.RECENT.crumb))
        val second = workspace.enterWithToken(listOf(DriveLibrary.STARRED.crumb))
        assertFalse(workspace.canReturnAcross(first))
        assertTrue(workspace.canReturnAcross(second))
        workspace.closeTab(second.tabId)
        assertFalse(workspace.canReturnAcross(second))
        workspace.switchTab(second.tabId)
        val third = workspace.enterWithToken(listOf(root))
        workspace.enterAccount("another-account")
        assertFalse(workspace.canReturnAcross(third))
        workspace.close()
    }

    @Test
    fun `sidebar libraries and external entries update only the primary browser`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val primary = workspace.primaryTabId
        val secondary = workspace.openTab(listOf(root, PikoPathBreadcrumb("secondary", "secondary")))
        workspace.pushFolder("child", "child")
        workspace.requestHighlight(setOf("secondary-file"))
        val secondarySnapshot = workspace.tabsFlow.value.first { it.id == secondary }
        for (library in listOf(DriveLibrary.RECENT, DriveLibrary.STARRED, DriveLibrary.HISTORY, DriveLibrary.TRASH)) {
            workspace.switchTab(secondary)
            val token = workspace.pushLibrary(library)!!
            assertEquals(primary, token.tabId)
            assertEquals(primary, workspace.activeContextId.value)
            assertEquals(primary, workspace.contextsFlow.value.first().id)
            assertEquals(listOf(library.crumb), workspace.folderStackFlow.value)
            assertEquals(secondarySnapshot, workspace.tabsFlow.value.first { it.id == secondary })
            assertEquals(null, workspace.pushLibrary(library))
            workspace.pushFolder("nested", "nested")
            assertEquals(null, workspace.pushLibrary(library))
            assertEquals(listOf(library.crumb), workspace.folderStackFlow.value)
        }
        workspace.switchTab(secondary)
        workspace.goToDrive()
        assertEquals(primary, workspace.activeContextId.value)
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        workspace.pushFolder("primary-folder", "primary-folder")
        val primaryStack = workspace.folderStackFlow.value
        workspace.switchTab(secondary)
        workspace.goToDrive()
        assertEquals(primaryStack, workspace.folderStackFlow.value)
        workspace.switchTab(secondary)
        val target = listOf(root, PikoPathBreadcrumb("external", "external"))
        val token = workspace.enterPrimaryWithToken(target)
        workspace.requestHighlight(setOf("external-file"))
        assertEquals(primary, token.tabId)
        assertEquals(target, workspace.folderStackFlow.value)
        assertEquals(setOf("external-file"), workspace.takePendingHighlight())
        assertTrue(workspace.returnAcross(token))
        assertEquals(primaryStack, workspace.folderStackFlow.value)
        workspace.switchTab(secondary)
        assertEquals(secondarySnapshot.stack, workspace.folderStackFlow.value)
        assertEquals(secondarySnapshot.history, workspace.historyFlow.value)
        assertEquals(setOf("secondary-file"), workspace.takePendingHighlight())
        // A secondary tab at the same library still routes the sidebar click to primary.
        workspace.updateFolderStack(listOf(DriveLibrary.STARRED.crumb))
        assertEquals(primary, workspace.pushLibrary(DriveLibrary.STARRED)!!.tabId)
        assertEquals(primary, workspace.activeContextId.value)
        workspace.close()
    }

    @Test
    fun `reentering the same library from another page replaces the previous entry`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        try {
            for (library in listOf(DriveLibrary.RECENT, DriveLibrary.STARRED, DriveLibrary.HISTORY, DriveLibrary.TRASH)) {
                workspace.goToDrive()
                workspace.pushFolder("source", "source")
                val previousEntry = workspace.pushLibrary(library)!!
                val stack = workspace.folderStackFlow.value
                val history = workspace.historyFlow.value

                // Another top-level page leaves the primary context and library unchanged.
                val newEntry = workspace.pushLibrary(library, reuseCurrentEntry = false)!!
                assertEquals(workspace.primaryTabId, newEntry.tabId)
                assertFalse(workspace.canReturnAcross(previousEntry))
                assertTrue(workspace.canReturnAcross(newEntry))
                assertEquals(stack, workspace.folderStackFlow.value)
                assertEquals(history, workspace.historyFlow.value)
                assertEquals(null, workspace.pushLibrary(library))
                assertTrue(workspace.returnAcross(newEntry))
                assertEquals(stack, workspace.folderStackFlow.value)
                assertEquals(history, workspace.historyFlow.value)

                workspace.pushFolder("nested", "nested")
                val nestedStack = workspace.folderStackFlow.value
                val nestedEntry = workspace.pushLibrary(library, reuseCurrentEntry = false)!!
                assertEquals(listOf(library.crumb), workspace.folderStackFlow.value)
                assertTrue(workspace.returnAcross(nestedEntry))
                assertEquals(nestedStack, workspace.folderStackFlow.value)
            }
        } finally {
            workspace.close()
        }
    }

    @Test
    fun `rejected primary closure preserves entry return stack and history`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        workspace.pushFolder("source", "source")
        val beforeStack = workspace.folderStackFlow.value
        val beforeHistory = workspace.historyFlow.value
        val token = workspace.enterWithToken(listOf(DriveLibrary.HISTORY.crumb))
        val draft = workspace.openImportDraftContext()
        assertFalse(workspace.closeTab(workspace.primaryTabId))
        assertEquals(null, workspace.closeContext(workspace.primaryTabId))
        assertEquals(draft, workspace.activeContextId.value)
        assertTrue(workspace.canReturnAcross(token))
        assertTrue(workspace.returnAcross(token))
        assertEquals(workspace.primaryTabId, workspace.activeContextId.value)
        assertEquals(beforeStack, workspace.folderStackFlow.value)
        assertEquals(beforeHistory, workspace.historyFlow.value)
        workspace.close()
    }

    @Test
    fun `a consumed locate request wins over tab restoration in progress`() = smoke { scope ->
        val repo = PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences())
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = object : PikoCacheStore by MemoryCacheStore() {
            override suspend fun read(key: String): String? {
                if (!key.startsWith("drive-tabs-")) return null
                reading.complete(Unit)
                release.await()
                return """{"tabs":[[{"id":"","name":"网盘"},{"id":"saved","name":"saved"}]],"active":0}"""
            }
        }
        val workspace = DriveWorkspace(repo, cache, scope)
        val restored = scope.async { workspace.restoreLocation() }
        withTimeout(5_000) { reading.await() }
        workspace.requestHighlight(setOf("new-file"))
        assertEquals(setOf("new-file"), workspace.takePendingHighlight())
        release.complete(Unit)
        assertFalse(restored.await())
        assertEquals(listOf(root), workspace.folderStackFlow.value)
        workspace.close()
    }
}
