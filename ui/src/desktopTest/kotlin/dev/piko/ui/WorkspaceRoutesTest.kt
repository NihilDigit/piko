package dev.piko.ui

import dev.piko.ui.navigation.Screen
import dev.piko.ui.navigation.isWorkspaceSurface
import dev.piko.ui.navigation.MainTab
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceRoutesTest {
    @Test
    fun fileWorkControlsAreScopedAwayFromTasksSettingsAndMedia() {
        assertEquals(true, isWorkspaceSurface(Screen.Home, MainTab.FILES))
        assertEquals(false, isWorkspaceSurface(Screen.Home, MainTab.TRANSFERS))
        assertEquals(false, isWorkspaceSurface(Screen.Home, MainTab.SETTINGS))
        for (tab in MainTab.entries) {
            for (screen in listOf(null, Screen.Settings, Screen.MyShares, Screen.WebDav, Screen.VideoPlayer("file", "video"))) {
                assertEquals(false, isWorkspaceSurface(screen, tab))
            }
        }
    }
}
