package dev.piko.desktop

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopPikoPreferencesTest {

    private fun tempFile(): File = File.createTempFile("piko-prefs-test", ".properties").also { it.delete() }

    @Test
    fun playbackFolderAndSwitches_roundTrip() = runBlocking {
        val store = DesktopSettingsStore(tempFile())
        val prefs = DesktopPikoPreferences(store)
        prefs.savePlaybackPosition("f1", 12345L)
        prefs.setSpoilerBlurEnabled(false)
        prefs.setHeuristicFilterEnabled(false)
        prefs.saveLastFolder("fid", "我的文件夹", "stack-data")

        val reloaded = DesktopPikoPreferences(store)
        assertEquals(12345L, reloaded.getPlaybackPosition("f1"))
        assertEquals(0L, reloaded.getPlaybackPosition("missing"))
        assertEquals(Triple("fid", "我的文件夹", "stack-data"), reloaded.getLastFolder())
        assertFalse((reloaded.spoilerBlurFlow as StateFlow<Boolean>).value)
        assertFalse((reloaded.heuristicFilterFlow as StateFlow<Boolean>).value)
        assertEquals(listOf("playback.f1"), store.keysWithPrefix("playback."))
    }
}
