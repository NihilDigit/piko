package dev.piko.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarLayoutTest {
    @Test fun mediumWorkspaceAlwaysKeepsInlineRailRegardlessOfPreference() {
        listOf(600f, 700f, 839f, 999f).forEach { width ->
            assertTrue(inlineSidebarIsCollapsed(width, persistedCollapsed = false))
            assertTrue(inlineSidebarIsCollapsed(width, persistedCollapsed = true))
        }
    }

    @Test fun largeWorkspaceUsesPersistedSidebarPreference() {
        listOf(1000f, 1080f, 1290f).forEach { width ->
            assertFalse(inlineSidebarIsCollapsed(width, persistedCollapsed = false))
            assertTrue(inlineSidebarIsCollapsed(width, persistedCollapsed = true))
        }
    }
}
