package dev.piko.shared.presentation

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PinnedTabCrumb
import dev.piko.shared.data.PinnedTabRecord
import dev.piko.shared.data.PinnedTabs
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryCacheStore
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import dev.piko.shared.sync.PinnedTabSync
import dev.piko.shared.sync.RemoteSettingsStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinnedTabsWorkspaceTest {
    private val root = PikoDriveRepository.ROOT_BREADCRUMB

    @Test
    fun `pin protects every close path and unpin restores closing`() = smoke { scope ->
        val server = FakePikPakServer()
        val workspace = DriveWorkspace(PikoDriveRepository(server.provider(), MemoryPreferences()), backgroundScope = scope)
        val pinned = workspace.openTab(listOf(DriveLibrary.STARRED.crumb))
        val keep = workspace.openTab(listOf(root))
        workspace.openImportDraftContext(false)
        assertTrue(workspace.togglePinTab(pinned))
        assertTrue(workspace.isTabPinned(pinned))
        assertFalse(workspace.contexts.canClose(pinned))
        assertFalse(workspace.closeTab(pinned))
        assertNull(workspace.closeContext(pinned))
        assertNull(workspace.contexts.close(pinned))
        workspace.contextsFlow.value.map { it.id }.filter { it != keep }.forEach(workspace::closeContext)
        assertEquals(setOf(pinned, keep), workspace.contextsFlow.value.map { it.id }.toSet())
        assertTrue(workspace.togglePinTab(pinned))
        assertTrue(workspace.canCloseContext(pinned))
        assertTrue(workspace.closeTab(pinned))
        workspace.close()
    }

    @Test
    fun `pins form a leading section and outside jumps never overwrite pinned browsers`() = smoke { scope ->
        val workspace = DriveWorkspace(PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences()), backgroundScope = scope)
        val first = workspace.activeTabId.value
        val second = workspace.openTab(listOf(DriveLibrary.STARRED.crumb))
        assertTrue(workspace.togglePinTab(second))
        assertEquals(second, workspace.contextsFlow.value.first().id)
        assertTrue(workspace.togglePinTab(first))
        assertEquals(listOf(second, first), workspace.tabsFlow.value.map { it.id })
        assertTrue(workspace.moveContext(second, 100))
        assertEquals(listOf(first, second), workspace.tabsFlow.value.map { it.id })
        // Dragging across the boundary is clamped to the appropriate section.
        val ordinary = workspace.openTab(listOf(root))
        workspace.moveContext(ordinary, 0)
        assertEquals(ordinary, workspace.contextsFlow.value.last().id)
        workspace.closeTab(ordinary)
        val pinnedStacks = workspace.tabsFlow.value.associate { it.id to it.stack }
        val libraryEntry = assertNotNull(workspace.pushLibrary(DriveLibrary.RECENT))
        assertFalse(workspace.isTabPinned(libraryEntry.tabId))
        assertNotEquals(first, libraryEntry.tabId)
        pinnedStacks.forEach { (id, stack) -> assertEquals(stack, workspace.tabsFlow.value.first { it.id == id }.stack) }
        assertTrue(workspace.togglePinTab(libraryEntry.tabId))
        val beforeEntry = workspace.tabsFlow.value.associate { it.id to it.stack }
        val target = listOf(root, PikoPathBreadcrumb("outside-target", "Target"))
        val token = workspace.enterPrimaryWithToken(target)
        assertFalse(workspace.isTabPinned(token.tabId))
        beforeEntry.forEach { (id, stack) -> assertEquals(stack, workspace.tabsFlow.value.first { it.id == id }.stack) }
        workspace.close()
    }

    @Test
    fun `saved tabs restore their fixed state even before the pin ledger was flushed`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val cache = MemoryCacheStore()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val first = DriveWorkspace(repository, cache, scope)
        val id = first.openTab(listOf(DriveLibrary.STARRED.crumb))
        assertTrue(first.togglePinTab(id))
        val pinId = first.tabsFlow.value.first { it.id == id }.pinId!!
        val account = provider.currentClient.value!!.account
        val key = "drive-tabs-" + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"
        while (cache.read(key)?.contains(pinId) != true) delay(20)
        first.close()
        val restored = DriveWorkspace(repository, cache, scope)
        assertTrue(restored.restoreLocation())
        assertTrue(restored.isTabPinned(id))
        assertEquals(id, restored.tabsFlow.value.first().id)
        assertFalse(restored.canCloseContext(id))
        assertEquals(pinId, restored.pinnedTabs.activeRecords().single().pinId)
        restored.close()
    }

    @Test
    fun `sessions and import previews cannot be pinned`() = smoke { scope ->
        val workspace = DriveWorkspace(PikoDriveRepository(FakePikPakServer().provider(), MemoryPreferences()), backgroundScope = scope)
        val report = workspace.openTab(listOf(DriveLibrary.DUPLICATES.crumb))
        val preview = workspace.openTab(listOf(root, PikoPathBreadcrumb(ImportPreviewLocation("", "preview").id, "Preview")))
        val draft = workspace.openImportDraftContext()
        listOf(report, preview, draft).forEach {
            assertFalse(workspace.canPinTab(it))
            assertFalse(workspace.togglePinTab(it))
        }
        workspace.close()
    }

    @Test
    fun `remote incomplete breadcrumbs are repaired without navigation history`() = smoke { scope ->
        val server = FakePikPakServer()
        val parent = server.addFolder("Parent")
        val child = server.addFolder("Child", parent.id)
        val provider = server.provider()
        val workspace = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences()), backgroundScope = scope)
        val active = workspace.activeTabId.value
        val account = provider.currentClient.value!!.account
        workspace.pinnedTabs.mergeAndSnapshot(mapOf("remote" to PinnedTabRecord(
            "remote", listOf(PinnedTabCrumb("", root.name), PinnedTabCrumb(child.id, child.name)), updatedAt = System.currentTimeMillis(),
        )), expectedAccount = account)
        awaitUntil("parent breadcrumbs repaired") {
            workspace.tabsFlow.value.firstOrNull { it.pinId == "remote" }?.stack?.map { it.id } == listOf("", parent.id, child.id)
        }
        val tab = workspace.tabsFlow.value.first { it.pinId == "remote" }
        assertEquals(FolderHistory(), tab.history)
        assertEquals(active, workspace.activeTabId.value)
        workspace.close()
    }

    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String) = text
        override suspend fun write(account: String, text: String, stamp: Long) { this.text = text }
    }

    @Test
    fun `two devices merge pins navigation and unpin tombstones without stealing focus`() = smoke { scope ->
        val server = FakePikPakServer()
        val one = server.addFolder("One")
        val two = server.addFolder("Two")
        val three = server.addFolder("Three")
        val provider = server.provider()
        val a = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences()), backgroundScope = scope)
        val b = DriveWorkspace(PikoDriveRepository(provider, MemoryPreferences()), backgroundScope = scope)
        val aId = a.openTab(listOf(root, PikoPathBreadcrumb(one.id, one.name)))
        val bId = b.openTab(listOf(root, PikoPathBreadcrumb(two.id, two.name)))
        assertTrue(a.togglePinTab(aId))
        assertTrue(b.togglePinTab(bId))
        val remote = MemoryRemote()
        val aSync = PinnedTabSync(provider, remote, a.pinnedTabs, scope, flowOf(true))
        val bSync = PinnedTabSync(provider, remote, b.pinnedTabs, scope, flowOf(true))
        assertTrue(aSync.syncNow())
        assertTrue(bSync.syncNow())
        assertTrue(aSync.syncNow())
        awaitUntil("both devices retain two fixed directories") {
            a.tabsFlow.value.count { it.isPinned } == 2 && b.tabsFlow.value.count { it.isPinned } == 2
        }
        assertEquals(aId, a.activeTabId.value)
        assertEquals(bId, b.activeTabId.value)
        val pinId = a.tabsFlow.value.first { it.id == aId }.pinId!!
        val remoteAId = b.tabsFlow.value.first { it.pinId == pinId }.id
        val remoteHistory = b.tabsFlow.value.first { it.id == remoteAId }.history
        a.updateFolderStack(listOf(root, PikoPathBreadcrumb(three.id, three.name)))
        assertTrue(aSync.syncNow())
        assertTrue(bSync.syncNow())
        awaitUntil("pinned navigation propagates without adding history") {
            b.tabsFlow.value.first { it.id == remoteAId }.stack.last().id == three.id
        }
        assertEquals(remoteHistory, b.tabsFlow.value.first { it.id == remoteAId }.history)
        assertEquals(bId, b.activeTabId.value)
        assertTrue(a.togglePinTab(aId))
        assertTrue(aSync.syncNow())
        assertTrue(bSync.syncNow())
        assertTrue(aSync.syncNow())
        awaitUntil("remote tombstone unpins in place") { !b.isTabPinned(remoteAId) }
        assertTrue(b.tabsFlow.value.any { it.id == remoteAId })
        assertFalse(b.pinnedTabs.activeRecords().any { it.pinId == pinId })
        assertEquals(a.pinnedTabs.awaitAccountReady(provider.currentClient.value!!.account), b.pinnedTabs.awaitAccountReady(provider.currentClient.value!!.account))
        assertEquals(bId, b.activeTabId.value)
        a.close()
        b.close()
    }

    @Test
    fun `equal timestamp tombstones win and cached records remain account owned`() = smoke { scope ->
        val stamp = System.currentTimeMillis()
        val pin = PinnedTabRecord("pin", listOf(PinnedTabCrumb("", "Root")), updatedAt = stamp)
        val tombstone = pin.copy(pinned = false)
        val a = mapOf(pin.pinId to pin)
        val b = mapOf(pin.pinId to tombstone)
        assertEquals(PinnedTabs.union(a, b, stamp), PinnedTabs.union(b, a, stamp))
        assertFalse(PinnedTabs.union(a, b, stamp).getValue("pin").pinned)
        assertEquals(a, PinnedTabs.decode(PinnedTabs.encode(a)))
        assertTrue(PinnedTabs.union(emptyMap(), b, stamp + 91L * 24 * 60 * 60 * 1_000).isEmpty())
        val cache = MemoryCacheStore()
        val tabs = PinnedTabs(cache, scope, saveDelayMillis = 0)
        tabs.switchAccount("a")
        tabs.awaitAccountReady("a")
        tabs.upsert(pin.pinId, pin.crumbs)
        awaitUntil("account ledger published") { tabs.flow.value.isNotEmpty() }
        tabs.switchAccount("b")
        assertTrue(tabs.awaitAccountReady("b")!!.isEmpty())
        assertNull(tabs.mergeAndSnapshot(a, expectedAccount = "a"))
    }
}
