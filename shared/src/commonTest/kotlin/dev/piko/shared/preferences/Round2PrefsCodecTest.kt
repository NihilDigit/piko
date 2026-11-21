package dev.piko.shared.preferences

import dev.piko.data.auth.CachePolicyDefaults
import dev.piko.shared.auth.SecretVault
import dev.piko.shared.data.DriveAppearancePrefs
import dev.piko.shared.data.ThumbnailClarity
import dev.piko.shared.data.ThumbnailScale
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.decodePlayerLayoutPrefs
import dev.piko.shared.media.player.encodePlayerLayoutPrefs
import dev.piko.shared.sync.SyncedSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Round2PrefsCodecTest {
    private class MapSettings : PreferenceSettings {
        private val map = LinkedHashMap<String, String>()
        override fun get(key: String, default: String): String = map[key] ?: default
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keysWithPrefix(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override val downloadDirectoryPath: String = ""
        override fun setDownloadDirectory(path: String) = Unit
    }

    private class DummyVault : SecretVault {
        override fun read(name: String): ByteArray? = null
        override fun write(name: String, value: ByteArray) = Unit
        override fun delete(name: String) = Unit
    }

    @Test
    fun playerLayoutPortraitFullscreenPromptRoundTripsAndDefaultsTrueForLegacyJson() {
        val legacyJson = """{"version":1}"""
        val decodedLegacy = decodePlayerLayoutPrefs(legacyJson)
        assertTrue(decodedLegacy.showPortraitFullscreenPrompt)

        val hidden = PlayerLayoutPrefs.Default.copy(showPortraitFullscreenPrompt = false)
        val roundTripped = decodePlayerLayoutPrefs(encodePlayerLayoutPrefs(hidden))
        assertFalse(roundTripped.showPortraitFullscreenPrompt)

        assertFalse(hidden.resetMode(PlayerLayoutMode.VideoLandscape).showPortraitFullscreenPrompt)
        assertTrue(hidden.resetMode(PlayerLayoutMode.VideoPortrait).showPortraitFullscreenPrompt)
    }

    @Test
    fun driveAppearanceThumbnailScaleAndClarityRoundTripWithSafeFallback() {
        val legacy = DriveAppearancePrefs.decodeJson("""{"v":1,"list":{"compactDensity":true}}""")
        assertEquals(ThumbnailScale.CROP, legacy.thumbnailScale)
        assertEquals(ThumbnailClarity.STANDARD, legacy.thumbnailClarity)
        assertTrue(legacy.list.compactDensity)

        val custom = DriveAppearancePrefs(
            thumbnailScale = ThumbnailScale.FIT,
            thumbnailClarity = ThumbnailClarity.HIGH,
        )
        val roundTripped = DriveAppearancePrefs.decodeJson(custom.encodeJson())
        assertEquals(ThumbnailScale.FIT, roundTripped.thumbnailScale)
        assertEquals(ThumbnailClarity.HIGH, roundTripped.thumbnailClarity)

        val corrupted = DriveAppearancePrefs.decodeJson(
            """{"v":1,"thumbnailScale":"BROKEN","thumbnailClarity":"ULTRA_99K"}""",
        )
        assertEquals(ThumbnailScale.CROP, corrupted.thumbnailScale)
        assertEquals(ThumbnailClarity.STANDARD, corrupted.thumbnailClarity)
    }

    @Test
    fun skipFeedExitConfirmAndDeviceLocalCachePrefsPersistAndRespectSyncBoundaries() = runBlocking {
        val settings = MapSettings()
        val prefs = StoredPikoPreferences(settings) { DummyVault() }

        assertFalse(prefs.skipFeedExitConfirmFlow.first())
        assertFalse(prefs.currentSnapshot.skipFeedExitConfirm)
        assertEquals(CachePolicyDefaults.DEFAULT_MAX_BYTES, prefs.cacheMaxBytesFlow.first())
        assertEquals(CachePolicyDefaults.DEFAULT_MAX_AGE_DAYS, prefs.cacheMaxAgeDaysFlow.first())

        prefs.setSkipFeedExitConfirm(true)
        prefs.setCacheMaxBytes(5L * 1024L * 1024L * 1024L)
        prefs.setCacheMaxAgeDays(30)

        val reopened = StoredPikoPreferences(settings) { DummyVault() }
        assertTrue(reopened.skipFeedExitConfirmFlow.first())
        assertTrue(reopened.currentSnapshot.skipFeedExitConfirm)
        assertEquals(5L * 1024L * 1024L * 1024L, reopened.cacheMaxBytesFlow.first())
        assertEquals(5L * 1024L * 1024L * 1024L, reopened.currentSnapshot.cacheMaxBytes)
        assertEquals(30, reopened.cacheMaxAgeDaysFlow.first())
        assertEquals(30, reopened.currentSnapshot.cacheMaxAgeDays)

        // Negative values clamp to 0 (unlimited / never)
        reopened.setCacheMaxBytes(-100L)
        reopened.setCacheMaxAgeDays(-5)
        assertEquals(0L, reopened.cacheMaxBytesFlow.first())
        assertEquals(0, reopened.cacheMaxAgeDaysFlow.first())

        val syncedKeys = SyncedSettings.map { it.key }.toSet()
        assertTrue("skipFeedExitConfirm" in syncedKeys)
        assertFalse("cacheMaxBytes" in syncedKeys)
        assertFalse("cacheMaxAgeDays" in syncedKeys)
        assertFalse("askDownloadLocation" in syncedKeys)
    }

    @Test
    fun askDownloadLocationPersistsLocallyAndUpdatesSnapshot() = runBlocking {
        val settings = MapSettings()
        val prefs = StoredPikoPreferences(settings) { DummyVault() }

        assertTrue(prefs.askDownloadLocationFlow.first())
        assertTrue(prefs.currentSnapshot.askDownloadLocation)

        prefs.setAskDownloadLocation(false)
        assertFalse(prefs.askDownloadLocationFlow.first())
        assertFalse(prefs.currentSnapshot.askDownloadLocation)

        val reopened = StoredPikoPreferences(settings) { DummyVault() }
        assertFalse(reopened.askDownloadLocationFlow.first())
        assertFalse(reopened.currentSnapshot.askDownloadLocation)

        reopened.setAskDownloadLocation(true)
        assertTrue(reopened.askDownloadLocationFlow.first())
        assertTrue(reopened.currentSnapshot.askDownloadLocation)

        val reopenedAgain = StoredPikoPreferences(settings) { DummyVault() }
        assertTrue(reopenedAgain.askDownloadLocationFlow.first())
        assertTrue(reopenedAgain.currentSnapshot.askDownloadLocation)
    }
}
