package dev.piko.desktop

import dev.piko.shared.auth.PlainFileVault
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsStoreTest {
    private val base = Files.createTempDirectory("piko-settings").toFile()
    private val file = base.resolve("settings.properties")
    private val program = base.resolve("Piko")
    private val elsewhere = base.resolve("cwd")
    private val default = File(System.getProperty("user.home"), "Downloads/Piko")

    @AfterTest
    fun cleanUp() {
        DesktopSettingsStore.flushPendingFor(file.toPath())
        base.deleteRecursively()
    }

    private fun storeDownloadDirectory(path: File) {
        file.outputStream().use { Properties().apply { setProperty("downloadDirectory", path.absolutePath) }.store(it, null) }
    }

    @Test
    fun replacesCompleteSettingsAndCleansSiblingTemporaryFiles() {
        val store = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        store.set("language", "日本語")
        store.set("theme", "dark")
        store.setDownloadDirectory(base.resolve("downloads").absolutePath)
        store.remove("theme")

        val reopened = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        assertEquals("日本語", reopened.get("language"))
        assertEquals("", reopened.get("theme"))
        assertEquals(base.resolve("downloads").absoluteFile, reopened.downloadDirectory)
        assertEquals(listOf(file.name), base.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun concurrentWritersPersistEverySetting() {
        val store = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        val writers = Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 40).map { index ->
                writers.submit { store.set("key.$index", "value.$index") }
            }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            writers.shutdownNow()
            assertTrue(writers.awaitTermination(10, TimeUnit.SECONDS))
        }
        val reopened = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        repeat(40) { index -> assertEquals("value.$index", reopened.get("key.$index")) }
        assertEquals(listOf(file.name), base.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun programDirectoryWrittenBy100IsDroppedWhateverTheWorkingDirectory() {
        storeDownloadDirectory(program)

        val store = DesktopSettingsStore(file, programDirectory = program, workingDirectory = elsewhere)

        assertEquals(default, store.downloadDirectory)
        assertFalse("downloadDirectory" in file.readText())
        assertEquals(default, DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere).downloadDirectory)
    }

    @Test
    fun folderInsideProgramDirectoryIsDropped() {
        storeDownloadDirectory(program.resolve("app"))

        assertEquals(default, DesktopSettingsStore(file, programDirectory = program, workingDirectory = elsewhere).downloadDirectory)
    }

    @Test
    fun chosenFolderOutsideProgramDirectoryIsKept() {
        val chosen = base.resolve("Piko Downloads")
        storeDownloadDirectory(chosen)

        assertEquals(chosen.absoluteFile, DesktopSettingsStore(file, programDirectory = program, workingDirectory = elsewhere).downloadDirectory)
    }

    @Test
    fun legacyArchivePasswordsComeFromWhere110PutThem() = runBlocking {
        DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere).set("drive.archivePasswords", """["hunter2"]""")
        val secrets = PlainFileVault(base.resolve("secrets").toPath())
        val prefs = DesktopPikoPreferences(DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)) { secrets }
        assertEquals("""["hunter2"]""", prefs.loadLegacyArchivePasswords())
        prefs.clearLegacyArchivePasswords()
        assertFalse("hunter2" in file.readText())
    }

    @Test
    fun batchesMutationsAndFlushesOnDemandOrReopen() {
        val store = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        store.setAll(mapOf("window.main.w" to "1280", "window.main.h" to "800"))
        store.set("window.main.x", "120")
        assertEquals(listOf("window.main.h", "window.main.w", "window.main.x"), store.keysWithPrefix("window.main."))

        store.flush()
        assertTrue(file.isFile)
        assertTrue("1280" in file.readText())
        assertEquals(listOf(file.name), base.listFiles().orEmpty().map { it.name })

        // 未手动调 flush 时直接用同一路径重开新实例，构造前会自动刷写待落盘快照
        store.set("pending.reopen", "persisted")
        val reopened = DesktopSettingsStore(file, programDirectory = null, workingDirectory = elsewhere)
        assertEquals("persisted", reopened.get("pending.reopen"))
        assertEquals(listOf(file.name), base.listFiles().orEmpty().map { it.name })
    }
}
