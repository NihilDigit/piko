package dev.piko.ui.components

import dev.piko.shared.auth.SecretVault
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.download.PikoDownloadStorage
import dev.piko.shared.preferences.PreferenceSettings
import dev.piko.shared.preferences.StoredPikoPreferences
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadLauncherTest {
    private class MapSettings(initialDownloadDir: String = "/default/downloads") : PreferenceSettings {
        private val map = LinkedHashMap<String, String>()
        override var downloadDirectoryPath: String = initialDownloadDir
            private set

        override fun get(key: String, default: String): String = map[key] ?: default
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keysWithPrefix(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override fun setDownloadDirectory(path: String) {
            downloadDirectoryPath = path
        }
    }

    private class DummyVault : SecretVault {
        override fun read(name: String): ByteArray? = null
        override fun write(name: String, value: ByteArray) = Unit
        override fun delete(name: String) = Unit
    }

    private class TempDownloadStorage(private val directory: File) : PikoDownloadStorage {
        override fun forDirectory(directory: String?): PikoDownloadStorage =
            if (directory.isNullOrBlank()) this else TempDownloadStorage(File(directory))

        override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath
        override suspend fun downloadTarget(fileName: String): String =
            directory.resolve(fileName).also { it.parentFile?.mkdirs() }.absolutePath
        override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath
        override suspend fun existingLength(fileName: String): Long = directory.resolve(fileName).length()
        override suspend fun exists(fileName: String): Boolean = directory.resolve(fileName).exists()
        override suspend fun delete(path: String): Boolean = File(path).delete()
        override suspend fun locate(fileName: String): String? =
            directory.resolve(fileName).takeIf { it.exists() }?.absolutePath
        override suspend fun pruneEmptyFolders(folder: String) = Unit
    }

    private suspend fun awaitCondition(description: String, condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) {
                delay(10L)
            }
        }
        assertTrue(condition(), "Timed out waiting for: $description")
    }

    private fun withFixture(
        canPickLocation: Boolean = true,
        picker: ((initialPath: String, onResult: (String?) -> Unit) -> Unit)? = null,
        block: suspend (
            prefs: StoredPikoPreferences,
            coordinator: PikoDownloadCoordinator,
            launcher: DownloadLauncher,
            rootDir: File,
        ) -> Unit,
    ) = runBlocking {
        val rootDir = Files.createTempDirectory("piko-launcher-test").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + job)
        try {
            val prefs = StoredPikoPreferences(MapSettings(rootDir.resolve("default").absolutePath)) { DummyVault() }
            val provider = object : PikoClientProvider {
                override val currentClient = MutableStateFlow<PikPakClient?>(null)
            }
            val coordinator = PikoDownloadCoordinator(provider, prefs, TempDownloadStorage(rootDir.resolve("default")), scope)
            val launcher = DownloadLauncher(
                coordinator = coordinator,
                preferences = prefs,
                scope = scope,
                canPickLocation = canPickLocation,
                pickLocation = picker,
            )
            block(prefs, coordinator, launcher, rootDir)
        } finally {
            job.cancelAndJoin()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `disabled setting or unsupported platform skips dialog and picker and uses default directory`() {
        var pickerCalls = 0
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                pickerCalls++
                onResult("/should-not-be-used")
            },
        ) { prefs, coordinator, launcher, _ ->
            assertTrue(prefs.askDownloadLocationFlow.first())
            prefs.setAskDownloadLocation(false)
            var queued = false
            val file = FileStat(id = "f1", name = "doc.pdf", size = "1024", hash = "H1", kind = FileKind.FILE)
            launcher.download(listOf(file)) { queued = true }
            awaitCondition("direct download enqueued") { queued && coordinator.tasks.value.containsKey("f1") }
            assertNull(launcher.confirmRequest)
            assertNull(launcher.request)
            assertEquals(0, pickerCalls)
            assertNull(coordinator.tasks.value.getValue("f1").targetDirectory)
        }

        withFixture(
            canPickLocation = false,
            picker = { _, onResult ->
                pickerCalls++
                onResult("/should-not-be-used")
            },
        ) { prefs, coordinator, launcher, _ ->
            prefs.setAskDownloadLocation(true)
            var queued = false
            val file = FileStat(id = "f2", name = "clip.mp4", size = "2048", hash = "H2", kind = FileKind.FILE)
            launcher.download(listOf(file)) { queued = true }
            awaitCondition("unsupported platform enqueued") { queued && coordinator.tasks.value.containsKey("f2") }
            assertNull(launcher.confirmRequest)
            assertNull(launcher.request)
            assertEquals(0, pickerCalls)
            assertNull(coordinator.tasks.value.getValue("f2").targetDirectory)
        }
    }

    @Test
    fun `download opens in-app confirmation dialog and supports in-place change, cancel, and unchecked confirm`() {
        val seenInitials = mutableListOf<String>()
        var nextPick: String? = null
        withFixture(
            canPickLocation = true,
            picker = { initial, onResult ->
                seenInitials += initial
                onResult(nextPick)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            val defaultDir = rootDir.resolve("default").absolutePath
            val customDir = rootDir.resolve("picked").absolutePath
            prefs.setDownloadDirPath(defaultDir)
            prefs.setAskDownloadLocation(true)

            val file1 = FileStat(id = "f1", name = "movie.mkv", size = "4096", hash = "H1", kind = FileKind.FILE)
            val file2 = FileStat(id = "f2", name = "notes.txt", size = "1024", hash = "H2", kind = FileKind.FILE)
            var queuedCount = 0
            var lastNotice: String? = "unset"

            // Opens in-app confirmation dialog immediately without invoking OS picker
            launcher.download(listOf(file1, file2)) { notice ->
                queuedCount++
                lastNotice = notice
            }
            awaitCondition("confirm dialog shown") { launcher.confirmRequest != null }
            val pending = assertNotNull(launcher.confirmRequest)
            assertEquals(0, seenInitials.size)
            assertEquals(defaultDir, pending.targetDirectory)
            assertEquals("movie.mkv", pending.summary.primaryText)
            assertEquals(2, pending.summary.itemCount)
            assertEquals(5120L, pending.summary.totalKnownBytes)

            // Cancelling OS folder picker keeps dialog open with previous path intact
            nextPick = null
            launcher.chooseLocation(pending)
            assertEquals(listOf(defaultDir), seenInitials)
            assertSame(pending, launcher.confirmRequest)
            assertEquals(defaultDir, pending.targetDirectory)

            // Picking a new folder updates targetDirectory in-place without dismissing dialog
            nextPick = customDir
            launcher.chooseLocation(pending)
            assertEquals(listOf(defaultDir, defaultDir), seenInitials)
            assertSame(pending, launcher.confirmRequest)
            assertEquals(customDir, pending.targetDirectory)

            // Dismissing dialog cancels cleanly without enqueuing or changing preferences
            launcher.dismissConfirm(pending)
            assertNull(launcher.confirmRequest)
            delay(40L)
            assertEquals(0, queuedCount)
            assertTrue(coordinator.tasks.value.isEmpty())
            assertEquals(defaultDir, prefs.getDownloadDirPath())
            assertTrue(prefs.askDownloadLocationFlow.first())

            // Re-open, change folder, confirm unchecked: enqueues to chosen folder, keeps askDownloadLocation = true
            launcher.download(listOf(file1)) { notice ->
                queuedCount++
                lastNotice = notice
            }
            awaitCondition("confirm dialog reopened") { launcher.confirmRequest != null }
            val second = assertNotNull(launcher.confirmRequest)
            nextPick = customDir
            launcher.chooseLocation(second)
            assertEquals(customDir, second.targetDirectory)

            launcher.confirmLocation(second, saveAsDefault = false)
            awaitCondition("custom download enqueued") { queuedCount == 1 && coordinator.tasks.value.containsKey("f1") }
            assertNull(launcher.confirmRequest)
            assertNull(lastNotice)
            assertEquals(customDir, coordinator.tasks.value.getValue("f1").targetDirectory)
            assertEquals(defaultDir, prefs.getDownloadDirPath())
            assertTrue(prefs.askDownloadLocationFlow.first())
        }
    }

    @Test
    fun `confirming checked saves new default path, disables askDownloadLocation, emits notice, and skips future dialogs`() {
        var nextPick: String? = null
        var pickerCalls = 0
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                pickerCalls++
                onResult(nextPick)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            val defaultDir = rootDir.resolve("default").absolutePath
            val newDefaultDir = rootDir.resolve("preferred").absolutePath
            prefs.setDownloadDirPath(defaultDir)
            prefs.setAskDownloadLocation(true)

            val file1 = FileStat(id = "f1", name = "archive.zip", size = "2048", hash = "H1", kind = FileKind.FILE)
            val file2 = FileStat(id = "f2", name = "second.zip", size = "1024", hash = "H2", kind = FileKind.FILE)
            var receivedNotice: String? = null
            var queuedCount = 0

            launcher.download(listOf(file1)) { notice ->
                queuedCount++
                receivedNotice = notice
            }
            awaitCondition("confirm dialog shown") { launcher.confirmRequest != null }
            val pending = assertNotNull(launcher.confirmRequest)
            nextPick = newDefaultDir
            launcher.chooseLocation(pending)
            assertEquals(1, pickerCalls)
            assertEquals(newDefaultDir, pending.targetDirectory)

            launcher.confirmLocation(pending, saveAsDefault = true)
            awaitCondition("first download enqueued") { queuedCount == 1 && coordinator.tasks.value.containsKey("f1") }
            assertEquals(newDefaultDir, prefs.getDownloadDirPath())
            assertFalse(prefs.askDownloadLocationFlow.first())
            assertEquals(DEFAULT_LOCATION_SAVED_NOTICE, receivedNotice)
            assertEquals(newDefaultDir, coordinator.tasks.value.getValue("f1").targetDirectory)

            // Subsequent download skips dialog and picker completely
            launcher.download(listOf(file2)) { queuedCount++ }
            awaitCondition("second download enqueued directly") {
                queuedCount == 2 && coordinator.tasks.value.containsKey("f2")
            }
            assertNull(launcher.confirmRequest)
            assertEquals(1, pickerCalls)
            assertNull(coordinator.tasks.value.getValue("f2").targetDirectory)
        }
    }

    @Test
    fun `picker throwing exception keeps dialog intact and recovers cleanly`() {
        var shouldThrow = true
        var customPath = ""
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                if (shouldThrow) error("Simulated picker failure")
                onResult(customPath)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            val defaultDir = rootDir.resolve("default").absolutePath
            prefs.setDownloadDirPath(defaultDir)
            prefs.setAskDownloadLocation(true)
            customPath = rootDir.resolve("recovered").absolutePath
            val file = FileStat(id = "f-recover", name = "recover.bin", size = "1024", hash = "HREC", kind = FileKind.FILE)
            var queuedCount = 0

            launcher.download(listOf(file)) { queuedCount++ }
            awaitCondition("confirm dialog shown") { launcher.confirmRequest != null }
            val pending = assertNotNull(launcher.confirmRequest)

            launcher.chooseLocation(pending)
            assertSame(pending, launcher.confirmRequest)
            assertEquals(defaultDir, pending.targetDirectory)
            assertEquals(0, queuedCount)

            shouldThrow = false
            launcher.chooseLocation(pending)
            assertEquals(customPath, pending.targetDirectory)

            launcher.confirmLocation(pending, saveAsDefault = false)
            awaitCondition("download recovers after picker error") {
                queuedCount == 1 && coordinator.tasks.value.containsKey("f-recover")
            }
            assertEquals(customPath, coordinator.tasks.value.getValue("f-recover").targetDirectory)
        }
    }

    @Test
    fun `resolved files and segment downloads use in-app confirmation dialog and propagate saved notice`() {
        var nextPick: String? = null
        withFixture(
            canPickLocation = true,
            picker = { _, onResult -> onResult(nextPick) },
        ) { prefs, coordinator, launcher, rootDir ->
            val defaultDir = rootDir.resolve("default").absolutePath
            val customDir = rootDir.resolve("chosen").absolutePath
            val segmentDir = rootDir.resolve("segments").absolutePath
            prefs.setDownloadDirPath(defaultDir)
            prefs.setAskDownloadLocation(true)

            // Resolved file: cancel then confirm with custom folder
            var resolvedQueued = false
            launcher.downloadResolved(listOf(ResolvedFile("instant.bin", 512L, "GCIDINST")), parentId = "") {
                resolvedQueued = true
            }
            awaitCondition("resolved confirm dialog shown") { launcher.confirmRequest != null }
            val resolvedCancel = assertNotNull(launcher.confirmRequest)
            assertEquals("instant.bin", resolvedCancel.summary.primaryText)
            assertEquals(512L, resolvedCancel.summary.totalKnownBytes)
            launcher.dismissConfirm(resolvedCancel)
            delay(40L)
            assertFalse(resolvedQueued)

            launcher.downloadResolved(listOf(ResolvedFile("instant.bin", 512L, "GCIDINST")), parentId = "") {
                resolvedQueued = true
            }
            awaitCondition("resolved confirm dialog reopened") { launcher.confirmRequest != null }
            val resolvedOk = assertNotNull(launcher.confirmRequest)
            nextPick = customDir
            launcher.chooseLocation(resolvedOk)
            launcher.confirmLocation(resolvedOk, saveAsDefault = false)
            awaitCondition("resolved download enqueued") {
                resolvedQueued && coordinator.tasks.value.values.any { it.fileName == "instant.bin" }
            }
            assertEquals(
                customDir,
                coordinator.tasks.value.values.first { it.fileName == "instant.bin" }.targetDirectory,
            )

            // Segment download: cancel then confirm with saveAsDefault = true
            val video = FileStat(id = "v1", name = "video.mp4", size = "8192", hash = "HV1", kind = FileKind.FILE)
            var segmentQueued = false
            var segmentNotice: String? = null
            launcher.downloadSegment(
                file = video,
                startMs = 1_000L,
                endMs = 5_000L,
                timeRangeLabel = "00:01_00:05",
                streamUrl = null,
                startByte = 0L,
                lengthBytes = 2048L,
                onNotice = { segmentNotice = it },
            ) {
                segmentQueued = true
            }
            awaitCondition("segment confirm dialog shown") { launcher.confirmRequest != null }
            val segPending = assertNotNull(launcher.confirmRequest)
            assertEquals("video.mp4", segPending.summary.primaryText)
            assertEquals("00:01_00:05", segPending.summary.timeRangeLabel)
            assertEquals("00:01 - 00:05", formatSegmentRange("00:01_00:05"))
            assertEquals(2048L, segPending.summary.totalKnownBytes)

            nextPick = segmentDir
            launcher.chooseLocation(segPending)
            launcher.confirmLocation(segPending, saveAsDefault = true)
            awaitCondition("segment download enqueued") {
                segmentQueued && coordinator.tasks.value.values.any { it.isSegment }
            }
            val segTask = coordinator.tasks.value.values.first { it.isSegment }
            assertEquals(segmentDir, segTask.targetDirectory)
            assertTrue(File(segTask.destinationPath).canonicalPath.startsWith(File(segmentDir).canonicalPath))
            assertEquals(DEFAULT_LOCATION_SAVED_NOTICE, segmentNotice)
            assertEquals(segmentDir, prefs.getDownloadDirPath())
            assertFalse(prefs.askDownloadLocationFlow.first())
        }
    }

    @Test
    fun `downloadAtQuality uses a single unified dialog without double modal`() {
        var nextPick: String? = null
        var pickerCalls = 0
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                pickerCalls++
                onResult(nextPick)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            val defaultDir = rootDir.resolve("default").absolutePath
            val customDir = rootDir.resolve("quality-dir").absolutePath
            val savedDir = rootDir.resolve("saved-quality-dir").absolutePath
            prefs.setDownloadDirPath(defaultDir)
            prefs.setAskDownloadLocation(true)

            val video1 = FileStat(id = "v1", name = "video1.mp4", size = "8192", hash = "HV1", kind = FileKind.FILE)
            val video2 = FileStat(id = "v2", name = "video2.mp4", size = "8192", hash = "HV2", kind = FileKind.FILE)
            val video3 = FileStat(id = "v3", name = "video3.mp4", size = "8192", hash = "HV3", kind = FileKind.FILE)

            // 1. Dismissing quality dialog persists nothing and never opens a second dialog
            var queued = false
            var notice: String? = null
            launcher.downloadAtQuality(listOf(video1)) { n ->
                queued = true
                notice = n
            }
            awaitCondition("quality dialog shown") { launcher.request != null }
            val pendingCancel = assertNotNull(launcher.request)
            assertTrue(pendingCancel.askLocation)
            assertEquals(defaultDir, pendingCancel.targetDirectory)
            assertNull(launcher.confirmRequest)
            assertEquals(0, pickerCalls)

            nextPick = customDir
            launcher.chooseLocation(pendingCancel)
            assertEquals(customDir, pendingCancel.targetDirectory)
            launcher.dismissRequest(pendingCancel)
            delay(40L)
            assertFalse(queued)
            assertNull(launcher.confirmRequest)
            assertNull(prefs.downloadMaxHeightFlow.first())
            assertEquals(defaultDir, prefs.getDownloadDirPath())
            assertTrue(prefs.askDownloadLocationFlow.first())

            // 2. Confirm keeping quality only: single dialog, enqueues to picked dir, leaves askDownloadLocation = true
            launcher.downloadAtQuality(listOf(video1)) { n ->
                queued = true
                notice = n
            }
            awaitCondition("quality dialog shown second time") { launcher.request != null }
            val pendingQualityOnly = assertNotNull(launcher.request)
            nextPick = customDir
            launcher.chooseLocation(pendingQualityOnly)
            launcher.confirm(pendingQualityOnly, DownloadChoice.Cap(720), keep = true, keepLocation = false)
            awaitCondition("quality download enqueued") { queued && coordinator.tasks.value.containsKey("v1") }
            assertNull(launcher.request)
            assertNull(launcher.confirmRequest)
            assertEquals(720, prefs.downloadMaxHeightFlow.first())
            assertEquals(defaultDir, prefs.getDownloadDirPath())
            assertTrue(prefs.askDownloadLocationFlow.first())
            assertEquals(DEFAULT_QUALITY_SAVED_NOTICE, notice)
            assertEquals(customDir, coordinator.tasks.value.getValue("v1").targetDirectory)

            // 3. Confirm keeping both quality and location: combined notice + persists both + disables askDownloadLocation
            queued = false
            notice = null
            launcher.downloadAtQuality(listOf(video2)) { n ->
                queued = true
                notice = n
            }
            awaitCondition("quality dialog shown third time") { launcher.request != null }
            val pendingBoth = assertNotNull(launcher.request)
            nextPick = savedDir
            launcher.chooseLocation(pendingBoth)
            launcher.confirm(pendingBoth, DownloadChoice.Cap(1080), keep = true, keepLocation = true)
            awaitCondition("second quality download enqueued") { queued && coordinator.tasks.value.containsKey("v2") }
            assertNull(launcher.confirmRequest)
            assertEquals(1080, prefs.downloadMaxHeightFlow.first())
            assertEquals(savedDir, prefs.getDownloadDirPath())
            assertFalse(prefs.askDownloadLocationFlow.first())
            assertEquals(DEFAULT_QUALITY_AND_LOCATION_SAVED_NOTICE, notice)
            assertEquals(savedDir, coordinator.tasks.value.getValue("v2").targetDirectory)

            // 4. Now that askDownloadLocation is false, quality dialog hides location section and uses default directory
            queued = false
            notice = null
            launcher.downloadAtQuality(listOf(video3)) { n ->
                queued = true
                notice = n
            }
            awaitCondition("quality dialog without location") { launcher.request != null }
            val pendingNoLoc = assertNotNull(launcher.request)
            assertFalse(pendingNoLoc.askLocation)
            launcher.confirm(pendingNoLoc, DownloadChoice.Cap(0), keep = false, keepLocation = false)
            awaitCondition("third quality download enqueued") { queued && coordinator.tasks.value.containsKey("v3") }
            assertNull(notice)
            assertNull(coordinator.tasks.value.getValue("v3").targetDirectory)
        }
    }
}
