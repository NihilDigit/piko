package dev.piko.ui

import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.WorkspaceContexts
import dev.piko.ui.workbench.minimizeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WorkspaceMinimizeTest {
    @Test
    fun minimizingWorkRetainsItsIdentityAndReturnsToTheMostRecentFileTab() {
        val contexts = WorkspaceContexts(1L)
        contexts.openFile(2L)
        val report = contexts.openDuplicate(PikoPathBreadcrumb("movies", "电影"))
        val retained = contexts.contextsFlow.value

        contexts.minimizeToFile(report, 2L)

        assertEquals(2L, contexts.activeContextId.value)
        assertSame(retained, contexts.contextsFlow.value)
        contexts.activate(report)
        assertEquals(report, contexts.activeContextId.value)
    }

    @Test
    fun lateMinimizeOfPreviousWorkDoesNotStealFocusFromItsReplacement() {
        val contexts = WorkspaceContexts()
        val previous = contexts.openImportDraft()
        val active = contexts.openDuplicate(PikoPathBreadcrumb("movies", "电影"))
        contexts.minimizeToFile(previous, 1L)
        assertEquals(active, contexts.activeContextId.value)
        contexts.close(previous)
        contexts.minimizeToFile(previous, 1L)
        assertEquals(active, contexts.activeContextId.value)
    }

    @Test
    fun staleFileTargetDoesNotSelectAnotherAuxiliaryContext() {
        val contexts = WorkspaceContexts()
        val draft = contexts.openImportDraft()
        val report = contexts.openDuplicate(PikoPathBreadcrumb("movies", "电影"))
        contexts.minimizeToFile(report, draft)
        assertEquals(report, contexts.activeContextId.value)
        contexts.minimizeToFile(report, Long.MAX_VALUE)
        assertEquals(report, contexts.activeContextId.value)
    }
}
