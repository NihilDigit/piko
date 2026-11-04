@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.preferences

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Foundation preferences, isolated suites/temp paths. No system Keychain access. */
class IosPreferenceSettingsTest {
    @Test fun stringValuesReopenAndOtherPreferenceTypesFallBack() = fixture { defaults, root ->
        val settings = IosPreferenceSettings(defaults, root)
        assertEquals("fallback", settings.get("missing", "fallback"))
        settings.set("theme", "dark")
        assertEquals("dark", IosPreferenceSettings(defaults, root).get("theme"))
        defaults.setBool(true, forKey = "piko.wrongType")
        assertEquals("fallback", settings.get("wrongType", "fallback"))
    }

    @Test fun prefixEnumerationIsSortedAndDoesNotTouchForeignKeys() = fixture { defaults, root ->
        defaults.setObject("foreign", forKey = "foreign.playback.0")
        val settings = IosPreferenceSettings(defaults, root)
        settings.set("playback.b", "2")
        settings.set("playback.a", "1")
        settings.set("theme", "dark")
        assertEquals(listOf("playback.a", "playback.b"), settings.keysWithPrefix("playback."))
        settings.remove("playback.a")
        assertEquals(listOf("playback.b"), settings.keysWithPrefix("playback."))
        assertEquals("foreign", defaults.objectForKey("foreign.playback.0"))
    }

    @Test fun namespacesRemainIndependent() = fixture { defaults, root ->
        val first = IosPreferenceSettings(defaults, root, "one.")
        val second = IosPreferenceSettings(defaults, root, "two.")
        first.set("value", "first")
        second.set("value", "second")
        first.remove("value")
        assertEquals("", first.get("value"))
        assertEquals("second", second.get("value"))
    }

    @Test fun downloadDirectoriesUseRelativeStorageAndRebaseWithTheContainer() = fixture { defaults, root ->
        val settings = IosPreferenceSettings(defaults, root)
        settings.setDownloadDirectory("$root/Season 1")
        settings.set("download.directory", "$root/Season 1")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$root/Season 1"))
        assertEquals("$root/Season 1", settings.downloadDirectoryPath)
        assertNull(defaults.objectForKey("piko.download.directory"))
        assertEquals("Season 1", defaults.objectForKey("piko.__storage.downloadRelative"))
        val relocated = IosPreferenceSettings(defaults, "$root/relocated")
        assertEquals("$root/relocated/Season 1", relocated.get("download.directory"))
        settings.setDownloadDirectory("")
        assertEquals(root, settings.downloadDirectoryPath)
        assertNull(defaults.objectForKey("piko.__storage.downloadRelative"))
    }

    @Test fun rejectedExternalFolderLeavesTheSelectionUnchanged() = fixture { defaults, root ->
        val settings = IosPreferenceSettings(defaults, root)
        settings.setDownloadDirectory("$root/selected")
        assertFailsWith<IllegalArgumentException> { settings.setDownloadDirectory("${root}-external") }
        assertEquals("$root/selected", settings.downloadDirectoryPath)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("${root}-external"))
    }

    @Test fun corruptRelativePathDoesNotEscapeOrSilentlyReset() = fixture { defaults, root ->
        val settings = IosPreferenceSettings(defaults, root)
        defaults.setObject("../outside", forKey = "piko.__storage.downloadRelative")
        assertFailsWith<IllegalArgumentException> { settings.get("download.directory") }
        assertEquals("../outside", defaults.objectForKey("piko.__storage.downloadRelative"))
    }

    @Test fun factoryInitializesOrdinarySettingsWithoutOpeningKeychain() = fixture { defaults, root ->
        runBlocking {
            val directories = IosAppDirectories(root, root, root)
            val prefs = createIosPreferences(directories, "test.preferences", defaults)
            assertFalse(prefs.autoDetectClipboardLinksFlow.first())
            prefs.setThemeMode("dark")
            assertEquals("dark", createIosPreferences(directories, "test.preferences", defaults).themeModeFlow.first())
            assertFailsWith<IllegalArgumentException> { createIosPreferences(directories, "", defaults) }
        }
    }

    private fun fixture(block: (NSUserDefaults, String) -> Unit) {
        val id = NSUUID().UUIDString
        val suite = "dev.piko.tests.preferences.$id"
        val defaults = NSUserDefaults(suiteName = suite)
        val prefix = NSTemporaryDirectory().trimEnd('/') + "/piko-preferences-"
        val root = prefix + id
        try {
            block(defaults, root)
        } finally {
            defaults.removePersistentDomainForName(suite)
            check(root == prefix + id && root.startsWith(prefix))
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                check(NSFileManager.defaultManager.removeItemAtPath(root, error = null))
            }
        }
    }
}
