package dev.piko.ui.adaptive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceCapacityTest {
    @Test fun persistentChromeRequiresUsefulHeightAsWellAsWidth() {
        assertFalse(supportsWorkspaceChrome(860f, 400f))
        assertFalse(supportsWorkspaceChrome(700f, 800f))
        assertTrue(supportsWorkspaceChrome(1280f, 800f))
        assertTrue(supportsWorkspaceChrome(840f, 480f))
    }
}
