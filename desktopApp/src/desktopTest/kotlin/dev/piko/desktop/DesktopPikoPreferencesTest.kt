package dev.piko.desktop

import dev.piko.shared.auth.PlainFileVault
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopPikoPreferencesTest {

    private fun tempFile(): File = File.createTempFile("piko-prefs-test", ".properties").also { it.delete() }

    private val secretsDir = Files.createTempDirectory("piko-prefs-secrets")
    private val secrets = PlainFileVault(secretsDir)

    @Test
    fun archivePasswordsMoveFromSettingsFileIntoVault() = runBlocking {
        val file = tempFile()
        val store = DesktopSettingsStore(file)
        // 1.1.0 的明文位置
        store.set("drive.archivePasswords", """["hunter2"]""")

        val prefs = DesktopPikoPreferences(store) { secrets }
        assertEquals("""["hunter2"]""", prefs.archivePasswordsFlow.first())
        assertEquals("", store.get("drive.archivePasswords"))
        assertEquals("""["hunter2"]""", secrets.read("archive-passwords")?.decodeToString())

        prefs.saveArchivePasswords("""["a","hunter2"]""")
        val reloaded = DesktopPikoPreferences(DesktopSettingsStore(file)) { secrets }
        assertEquals("""["a","hunter2"]""", reloaded.archivePasswordsFlow.first())
        assertFalse("hunter2" in file.readText())
    }

    @Test
    fun playbackFolderAndSwitches_roundTrip() = runBlocking {
        val store = DesktopSettingsStore(tempFile())
        val prefs = DesktopPikoPreferences(store) { secrets }
        prefs.savePlaybackPosition("f1", 12345L)
        prefs.setSpoilerBlurEnabled(false)
        prefs.setHeuristicFilterEnabled(false)
        prefs.saveLastFolder("fid", "我的文件夹", "stack-data")

        val reloaded = DesktopPikoPreferences(store) { secrets }
        assertEquals(12345L, reloaded.getPlaybackPosition("f1"))
        assertEquals(0L, reloaded.getPlaybackPosition("missing"))
        assertEquals(Triple("fid", "我的文件夹", "stack-data"), reloaded.getLastFolder())
        assertFalse((reloaded.spoilerBlurFlow as StateFlow<Boolean>).value)
        assertFalse((reloaded.heuristicFilterFlow as StateFlow<Boolean>).value)
        assertEquals(listOf("playback.f1"), store.keysWithPrefix("playback."))
    }
}
