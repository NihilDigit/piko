package dev.piko.ui.adaptive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceCapacityTest {
    @Test fun persistentChromeRequiresUsefulHeightAsWellAsWidth() {
        assertFalse(supportsWorkspaceChrome(860f, 400f))
        assertFalse(supportsWorkspaceChrome(599f, 800f))
        assertFalse(supportsWorkspaceChrome(600f, 479f))
        assertTrue(supportsWorkspaceChrome(600f, 480f))
        assertTrue(supportsWorkspaceChrome(700f, 800f))
        assertTrue(supportsWorkspaceChrome(839f, 480f))
        assertTrue(supportsWorkspaceChrome(1280f, 800f))
        assertTrue(supportsWorkspaceChrome(840f, 480f))
    }
}
