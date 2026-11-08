package dev.piko.shared.preferences

import dev.piko.data.auth.AppLanguage
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.data.auth.SidePanelPrefs
import dev.piko.data.auth.SnailMode
import dev.piko.shared.auth.SecretVault
import dev.piko.shared.auth.VaultUnavailableException
import dev.piko.shared.i18n.tr
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxySetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredPikoPreferencesTest {
    @Test
    fun defaultsMatchDesktopWithoutOpeningSecrets() = runBlocking {
        val settings = FakeSettings()
        val prefs = StoredPikoPreferences(settings) { error("Secrets must stay lazy") }
        listOf(
            prefs.spoilerBlurFlow, prefs.autoCheckUpdatesFlow, prefs.hardwareDecodingFlow,
            prefs.heuristicFilterFlow, prefs.nameParsingFlow, prefs.bundleSubtitlesFlow,
            prefs.settingsSyncFlow, prefs.syncPlayHistoryFlow, prefs.showExtensionsFlow,
            prefs.foldersFirstFlow,
        ).forEach { assertTrue(it.first()) }
        listOf(
            prefs.autoDetectClipboardLinksFlow, prefs.reduceMotionFlow, prefs.folderMapOpenFlow,
            prefs.folderMapPinnedFlow, prefs.autoCleanNamesFlow, prefs.sidebarCollapsedFlow,
            prefs.renameRegexTextModeFlow, prefs.saveAsReferenceFlow,
        ).forEach { assertFalse(it.first()) }
        assertNull(prefs.themeModeFlow.first())
        assertNull(prefs.themeSeedFlow.first())
        assertNull(prefs.downloadMaxHeightFlow.first())
        assertNull(prefs.getIgnoredUpdateVersion())
        assertEquals(0, prefs.playbackMaxHeightFlow.first())
        assertEquals(PlayerGestureDefaults.SEEK_STEP_SECONDS, prefs.playerSeekStepSecondsFlow.first())
        assertEquals(PlayerGestureDefaults.BOOST_SPEED, prefs.playerBoostSpeedFlow.first())
        assertEquals("POSTER", prefs.driveViewModeFlow.first())
        assertEquals(SidePanelPrefs(false, null), prefs.clipPanelFlow.first())
        assertEquals(SnailMode(), prefs.snailModeFlow.first())
        assertEquals(ProxySetting(), prefs.proxySettingFlow.first())
        assertEquals("/downloads/Piko", prefs.getDownloadDirPath())
        assertEquals(Triple("", tr("我的网盘", "My drive", "マイドライブ", "내 드라이브", "我的雲端硬碟"), ""), prefs.getLastFolder())
        assertEquals(0L, prefs.getPlaybackPosition("missing"))
        assertTrue(settings.snapshot().isEmpty())
    }

    @Test
    fun switchesAndStructuredValuesPersistAcrossReopen() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        prefs.setSpoilerBlurEnabled(false)
        prefs.setAutoDetectClipboardLinks(true)
        prefs.setHardwareDecoding(false)
        prefs.setPlayerSeekStepSeconds(30)
        prefs.setPlayerBoostSpeed(3f)
        prefs.setFolderMapOpen(true)
        prefs.setFolderMapPinned(true)
        prefs.setClipPanelOpen(true)
        prefs.setClipPanelWidth(320f)
        prefs.setSnailMode(SnailMode(true, 250, 125))
        val proxy = ProxySetting(mode = ProxyMode.MANUAL, host = "localhost", port = 8080)
        prefs.saveProxySetting(proxy)
        prefs.setIgnoredUpdateVersion("2.0")
        val reopened = preferences(settings.reopen())
        assertFalse(reopened.spoilerBlurFlow.first())
        assertTrue(reopened.autoDetectClipboardLinksFlow.first())
        assertFalse(reopened.hardwareDecodingFlow.first())
        assertEquals(30, reopened.playerSeekStepSecondsFlow.first())
        assertEquals(3f, reopened.playerBoostSpeedFlow.first())
        assertTrue(reopened.folderMapOpenFlow.first())
        assertTrue(reopened.folderMapPinnedFlow.first())
        assertEquals(SidePanelPrefs(true, 320f), reopened.clipPanelFlow.first())
        assertEquals(SnailMode(true, 250, 125), reopened.snailModeFlow.first())
        assertEquals(proxy, reopened.proxySettingFlow.first())
        assertEquals("2.0", reopened.getIgnoredUpdateVersion())
        assertEquals("true", settings.get("ui.autoDetectClipboardLinks"))
        assertEquals("30", settings.get("player.seekStepSeconds"))
    }

    @Test
    fun release14SettingsPersistAcrossReopen() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        assertEquals(AppLanguage.SYSTEM, prefs.appLanguageFlow.first())
        assertTrue(prefs.clipAutoExtendFlow.first())
        assertTrue(prefs.galleryShowImagesFlow.first())
        assertFalse(prefs.perFolderSortFlow.first())
        prefs.setAppLanguage(AppLanguage.JA)
        prefs.setClipAutoExtend(false)
        prefs.setGalleryShowImages(false)
        prefs.setPerFolderSort(true)
        prefs.saveFolderSortOrders("sort-map")
        prefs.saveLocalMounts("mounts")
        val reopened = preferences(settings.reopen())
        assertEquals(AppLanguage.JA, reopened.appLanguageFlow.first())
        assertFalse(reopened.clipAutoExtendFlow.first())
        assertFalse(reopened.galleryShowImagesFlow.first())
        assertTrue(reopened.perFolderSortFlow.first())
        assertEquals("sort-map", reopened.folderSortOrdersFlow.first())
        assertEquals("mounts", reopened.localMountsFlow.first())
    }

    @Test
    fun currentDesktopSettingsKeepTheirKeysAndPersistAcrossReopen() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        prefs.setFoldersFirst(false)
        prefs.setSaveAsReference(true)
        prefs.setMetaTubeUrl("https://example.test")
        val reopened = preferences(settings.reopen())
        assertFalse(reopened.foldersFirstFlow.first())
        assertTrue(reopened.saveAsReferenceFlow.first())
        assertEquals("https://example.test", reopened.metaTubeUrlFlow.first())
        assertEquals("false", settings.get("drive.foldersFirst"))
        assertEquals("true", settings.get("instant.saveAsReference"))
    }

    @Test
    fun serializedDataAndFolderPersistUsingLegacyKeys() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        prefs.savePlaybackPosition("file", 12345L)
        prefs.saveLastFolder("folder", "Folder", "stack")
        prefs.saveDownloadTasks("downloads")
        prefs.saveUploadTasks("uploads")
        prefs.saveOfflinePacks("packs")
        prefs.saveRecentMoveTargets("moves")
        prefs.savePinnedFolders("pins")
        prefs.saveBatchRename("rename")
        prefs.saveVaultArchiveOptions("archive")
        val reopened = preferences(settings.reopen())
        assertEquals(12345L, reopened.getPlaybackPosition("file"))
        assertEquals(Triple("folder", "Folder", "stack"), reopened.getLastFolder())
        assertEquals("downloads", reopened.loadDownloadTasks())
        assertEquals("uploads", reopened.loadUploadTasks())
        assertEquals("packs", reopened.loadOfflinePacks())
        assertEquals("moves", reopened.recentMoveTargetsFlow.first())
        assertEquals("pins", reopened.pinnedFoldersFlow.first())
        assertEquals("rename", reopened.batchRenameFlow.first())
        assertEquals("archive", reopened.vaultArchiveOptionsFlow.first())
        assertEquals("12345", settings.get("playback.file"))
        assertEquals("downloads", settings.get("download.tasks"))
        assertEquals("uploads", settings.get("upload.tasks"))
    }

    @Test
    fun nullableSettingsKeepUnsetDistinctFromOriginalQuality() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        prefs.setDownloadMaxHeight(0)
        prefs.setThemeSeed("BLUE")
        prefs.setThemeMode("dark")
        assertEquals(0, preferences(settings.reopen()).downloadMaxHeightFlow.first())
        assertEquals("BLUE", preferences(settings.reopen()).themeSeedFlow.first())
        assertEquals("dark", preferences(settings.reopen()).themeModeFlow.first())
        prefs.setDownloadMaxHeight(null)
        prefs.setThemeSeed(null)
        assertFalse("download.defaultMaxHeight" in settings.snapshot())
        assertEquals("", settings.get("ui.themeSeed"))
        assertNull(prefs.downloadMaxHeightFlow.first())
        assertNull(prefs.themeSeedFlow.first())
        assertNull(preferences(settings.reopen()).downloadMaxHeightFlow.first())
        assertNull(preferences(settings.reopen()).themeSeedFlow.first())
    }

    @Test
    fun invalidNumbersUseDefaultsAndOldDownloadQualityIsIgnored() = runBlocking {
        val prefs = preferences(FakeSettings(mapOf(
            "player.maxHeight" to "bad", "download.defaultMaxHeight" to "bad",
            "download.maxHeight" to "480", "player.seekStepSeconds" to "bad",
            "player.boostSpeed" to "bad",
            "network.proxy" to "bad", "playback.file" to "bad",
        )))
        assertEquals(0, prefs.playbackMaxHeightFlow.first())
        assertNull(prefs.downloadMaxHeightFlow.first())
        assertEquals(10, prefs.playerSeekStepSecondsFlow.first())
        assertEquals(2f, prefs.playerBoostSpeedFlow.first())
        assertEquals(ProxySetting(), prefs.proxySettingFlow.first())
        assertEquals(0L, prefs.getPlaybackPosition("file"))
    }

    @Test
    fun explicitViewModeWinsOverLegacyGridFlag() = runBlocking {
        val settings = FakeSettings(mapOf("ui.gridView" to "false"))
        assertEquals("LIST", preferences(settings).driveViewModeFlow.first())
        preferences(settings).setDriveViewMode("GALLERY")
        assertEquals("GALLERY", preferences(settings.reopen()).driveViewModeFlow.first())
    }

    @Test
    fun tileFlowsCreatedConcurrentlyKeepReceivingTheSameUpdates() = runBlocking {
        val settings = FakeSettings(mapOf("ui.drive.tileSize.POSTER" to "SMALL"))
        val prefs = preferences(settings)
        val flows = List(100) { async(Dispatchers.Default) { prefs.driveTileSizeFlow("POSTER") } }.awaitAll()
        flows.forEach { assertEquals("SMALL", it.first()) }
        val gallery = prefs.driveTileSizeFlow("GALLERY")
        prefs.setDriveTileSize("POSTER", "LARGE")
        flows.forEach { assertEquals("LARGE", it.first()) }
        assertEquals("", gallery.first())
        assertEquals("LARGE", preferences(settings.reopen()).driveTileSizeFlow("POSTER").first())
    }

    @Test
    fun playbackHistoryIsBoundedAndOnlyPlaybackKeysArePruned() = runBlocking {
        val entries = (0..500).associate { "playback.${it.toString().padStart(4, '0')}" to "1" }
        val settings = FakeSettings(entries + ("other" to "keep"))
        preferences(settings).savePlaybackPosition("9999", 99L)
        assertEquals(500, settings.keysWithPrefix("playback.").size)
        assertFalse("playback.0000" in settings.snapshot())
        assertFalse("playback.0001" in settings.snapshot())
        assertEquals("99", settings.get("playback.9999"))
        assertEquals("keep", settings.get("other"))
    }

    @Test
    fun downloadDirectoryUsesBackendAndPreservesEmptyReset() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        prefs.setDownloadDirPath("/custom")
        assertEquals("/custom", settings.downloadDirectoryPath)
        assertEquals("/custom", prefs.downloadDirPathFlow.first())
        assertEquals("/custom", preferences(settings.reopen()).getDownloadDirPath())
        prefs.setDownloadDirPath("")
        assertEquals("/downloads/Piko", settings.downloadDirectoryPath)
        assertEquals("", settings.get("download.directory"))
        assertEquals("", preferences(settings.reopen()).getDownloadDirPath())
    }

    @Test
    fun legacyPasswordsReadAndClearOnlyTheOriginalPlaintextKey() = runBlocking {
        val settings = FakeSettings(mapOf("drive.archivePasswords" to "[\"legacy\"]", "unrelated" to "keep"))
        val prefs = StoredPikoPreferences(settings) { error("Legacy migration must not open vault") }
        assertEquals("[\"legacy\"]", prefs.loadLegacyArchivePasswords())
        prefs.clearLegacyArchivePasswords()
        assertEquals("", preferences(settings.reopen()).loadLegacyArchivePasswords())
        assertFalse("drive.archivePasswords" in settings.snapshot())
        assertEquals("keep", settings.get("unrelated"))
    }

    @Test
    fun secretsAreLazyPersistOnlyInVaultAndUploadClearIsIsolated() = runBlocking {
        val settings = FakeSettings()
        val vault = FakeVault()
        val opens = MutableStateFlow(0)
        val prefs = StoredPikoPreferences(settings) { opens.value += 1; vault }
        prefs.setMetaTubeUrl("https://example.test")
        assertEquals(0, opens.value)
        prefs.saveUploadCredentials("a", "credential-a")
        prefs.saveUploadCredentials("b", "credential-b")
        prefs.setMetaTubeToken("private-token")
        assertEquals(1, opens.value)
        assertEquals("credential-a", vault.read("upload-a")?.decodeToString())
        assertEquals("private-token", vault.read("metatube-token")?.decodeToString())
        assertEquals(mapOf("scrape.metaTubeUrl" to "https://example.test"), settings.snapshot())
        val reopened = StoredPikoPreferences(settings.reopen()) { vault }
        assertEquals("private-token", reopened.metaTubeTokenFlow.first())
        prefs.clearUploadCredentials("a")
        assertNull(reopened.loadUploadCredentials("a"))
        assertEquals("credential-b", reopened.loadUploadCredentials("b"))
        prefs.setMetaTubeToken("")
        assertNull(vault.read("metatube-token"))
        assertEquals("", prefs.metaTubeTokenFlow.first())
    }

    @Test
    fun failedSecretWritesAndDeletesDoNotPublishOptimisticValues() = runBlocking {
        val settings = FakeSettings()
        val vault = FakeVault()
        val prefs = StoredPikoPreferences(settings) { vault }
        prefs.setMetaTubeToken("old-token")
        prefs.saveUploadCredentials("task", "old-credential")
        vault.failWrites = true
        assertFailsWith<VaultUnavailableException> { prefs.setMetaTubeToken("new-token") }
        assertFailsWith<VaultUnavailableException> { prefs.saveUploadCredentials("task", "new-credential") }
        assertEquals("old-token", prefs.metaTubeTokenFlow.first())
        assertEquals("old-credential", prefs.loadUploadCredentials("task"))
        vault.failDeletes = true
        assertFailsWith<VaultUnavailableException> { prefs.setMetaTubeToken("") }
        assertFailsWith<VaultUnavailableException> { prefs.clearUploadCredentials("task") }
        assertEquals("old-token", prefs.metaTubeTokenFlow.first())
        assertEquals("old-credential", prefs.loadUploadCredentials("task"))
        assertTrue(settings.snapshot().isEmpty())
    }

    @Test
    fun failedSettingsWriteDoesNotPublishOptimisticFlowValue() = runBlocking {
        val settings = FakeSettings()
        val prefs = preferences(settings)
        settings.failWrites = true
        assertFailsWith<IllegalStateException> { prefs.setSpoilerBlurEnabled(false) }
        assertTrue(prefs.spoilerBlurFlow.first())
        assertFailsWith<IllegalStateException> { prefs.setSaveAsReference(true) }
        assertFalse(prefs.saveAsReferenceFlow.first())
        assertTrue(settings.snapshot().isEmpty())
    }

    @Test
    fun uploadReadFailurePropagatesInsteadOfLookingLikeMissingCredentials() = runBlocking {
        val vault = FakeVault()
        val prefs = StoredPikoPreferences(FakeSettings()) { vault }
        assertNull(prefs.loadUploadCredentials("missing"))
        vault.failReads = true
        assertFailsWith<VaultUnavailableException> { prefs.loadUploadCredentials("task") }
        Unit
    }

    private fun preferences(settings: FakeSettings) = StoredPikoPreferences(settings) { FakeVault() }

    private class FakeSettings(initial: Map<String, String> = emptyMap()) : PreferenceSettings {
        private val values = MutableStateFlow(initial)
        var failWrites = false
        fun snapshot(): Map<String, String> = values.value
        fun reopen() = FakeSettings(snapshot())
        override fun get(key: String, default: String): String = values.value[key] ?: default
        override fun set(key: String, value: String) {
            check(!failWrites) { "Settings write failed" }
            while (true) {
                val current = values.value
                if (values.compareAndSet(current, current + (key to value))) return
            }
        }
        override fun remove(key: String) {
            check(!failWrites) { "Settings write failed" }
            while (true) {
                val current = values.value
                if (values.compareAndSet(current, current - key)) return
            }
        }
        override fun keysWithPrefix(prefix: String): List<String> =
            values.value.keys.filter { it.startsWith(prefix) }.sorted()
        override val downloadDirectoryPath: String get() = get("downloadDirectory", "/downloads/Piko")
        override fun setDownloadDirectory(path: String) {
            if (path.isBlank()) remove("downloadDirectory") else set("downloadDirectory", path)
        }
    }

    private class FakeVault : SecretVault {
        private val values = mutableMapOf<String, ByteArray>()
        var failReads = false
        var failWrites = false
        var failDeletes = false
        override fun read(key: String): ByteArray? {
            if (failReads) throw VaultUnavailableException("Vault read failed")
            return values[key]?.copyOf()
        }
        override fun write(key: String, data: ByteArray) {
            if (failWrites) throw VaultUnavailableException("Vault write failed")
            values[key] = data.copyOf()
        }
        override fun delete(key: String) {
            if (failDeletes) throw VaultUnavailableException("Vault delete failed")
            values.remove(key)
        }
    }
}
