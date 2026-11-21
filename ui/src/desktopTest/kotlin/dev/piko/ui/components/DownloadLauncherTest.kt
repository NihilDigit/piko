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
    fun `disabled setting or unsupported platform skips picker and uses default directory`() {
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
            assertEquals(0, pickerCalls)
            assertNull(coordinator.tasks.value.getValue("f2").targetDirectory)
        }
    }

    @Test
    fun `enabled setting prompts once with default directory and aborts cleanly on cancel`() {
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

            val file = FileStat(id = "f1", name = "movie.mkv", size = "4096", hash = "H1", kind = FileKind.FILE)
            var queuedCount = 0

            // Cancel first attempt: no task enqueued, no callback invoked
            nextPick = null
            launcher.download(listOf(file)) { queuedCount++ }
            awaitCondition("picker called once for cancel") { seenInitials.size == 1 }
            delay(50L)
            assertEquals(defaultDir, seenInitials.single())
            assertEquals(0, queuedCount)
            assertTrue(coordinator.tasks.value.isEmpty())

            // Pick a directory on second attempt: single prompt, enqueues with chosen targetDirectory
            nextPick = customDir
            launcher.download(listOf(file)) { queuedCount++ }
            awaitCondition("custom download enqueued") { queuedCount == 1 && coordinator.tasks.value.containsKey("f1") }
            assertEquals(2, seenInitials.size)
            assertEquals(customDir, coordinator.tasks.value.getValue("f1").targetDirectory)
        }
    }

    @Test
    fun `picker throwing exception recovers cleanly without wedging subsequent downloads`() {
        var shouldThrow = true
        var customPath = ""
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                if (shouldThrow) error("Simulated picker failure")
                onResult(customPath)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            prefs.setAskDownloadLocation(true)
            customPath = rootDir.resolve("recovered").absolutePath
            val file = FileStat(id = "f-recover", name = "recover.bin", size = "1024", hash = "HREC", kind = FileKind.FILE)
            var queuedCount = 0

            launcher.download(listOf(file)) { queuedCount++ }
            delay(50L)
            assertEquals(0, queuedCount)
            assertTrue(coordinator.tasks.value.isEmpty())

            shouldThrow = false
            launcher.download(listOf(file)) { queuedCount++ }
            awaitCondition("download recovers after picker error") {
                queuedCount == 1 && coordinator.tasks.value.containsKey("f-recover")
            }
            assertEquals(customPath, coordinator.tasks.value.getValue("f-recover").targetDirectory)
        }
    }

    @Test
    fun `quality selection, resolved files, and segment downloads respect askDownloadLocation`() {
        var nextPick: String? = null
        var pickerCalls = 0
        withFixture(
            canPickLocation = true,
            picker = { _, onResult ->
                pickerCalls++
                onResult(nextPick)
            },
        ) { prefs, coordinator, launcher, rootDir ->
            prefs.setAskDownloadLocation(true)
            val customDir = rootDir.resolve("chosen").absolutePath
            val video = FileStat(id = "v1", name = "video.mp4", size = "8192", hash = "HV1", kind = FileKind.FILE)

            // Quality confirm cancelled at folder picker: does not persist default quality or enqueue
            var qualityQueuedNotice: String? = null
            var qualityQueued = false
            launcher.downloadAtQuality(listOf(video)) { notice ->
                qualityQueued = true
                qualityQueuedNotice = notice
            }
            val pendingCancel = assertNotNull(launcher.request)
            nextPick = null
            launcher.confirm(pendingCancel, DownloadChoice.Cap(720), keep = true)
            awaitCondition("quality picker called for cancel") { pickerCalls == 1 }
            delay(50L)
            assertFalse(qualityQueued)
            assertNull(prefs.downloadMaxHeightFlow.first())
            assertTrue(coordinator.tasks.value.isEmpty())

            // Quality confirm with folder selected: persists default quality and enqueues into chosen folder
            launcher.downloadAtQuality(listOf(video)) { notice ->
                qualityQueued = true
                qualityQueuedNotice = notice
            }
            val pendingOk = assertNotNull(launcher.request)
            nextPick = customDir
            launcher.confirm(pendingOk, DownloadChoice.Cap(720), keep = true)
            awaitCondition("quality download enqueued") { qualityQueued && coordinator.tasks.value.containsKey("v1") }
            assertEquals(720, prefs.downloadMaxHeightFlow.first())
            assertEquals(DEFAULT_QUALITY_SAVED_NOTICE, qualityQueuedNotice)
            assertEquals(customDir, coordinator.tasks.value.getValue("v1").targetDirectory)

            // Resolved file download
            var resolvedQueued = false
            nextPick = null
            launcher.downloadResolved(listOf(ResolvedFile("instant.bin", 512L, "GCIDINST")), parentId = "") {
                resolvedQueued = true
            }
            awaitCondition("resolved cancel picker called") { pickerCalls == 3 }
            delay(50L)
            assertFalse(resolvedQueued)

            nextPick = customDir
            launcher.downloadResolved(listOf(ResolvedFile("instant.bin", 512L, "GCIDINST")), parentId = "") {
                resolvedQueued = true
            }
            awaitCondition("resolved download enqueued") {
                resolvedQueued && coordinator.tasks.value.values.any { it.fileName == "instant.bin" }
            }
            assertEquals(
                customDir,
                coordinator.tasks.value.values.first { it.fileName == "instant.bin" }.targetDirectory,
            )

            // Segment download
            var segmentQueued = false
            nextPick = null
            launcher.downloadSegment(
                file = video,
                startMs = 1_000L,
                endMs = 5_000L,
                timeRangeLabel = "00-01_00-05",
                streamUrl = null,
                startByte = 0L,
                lengthBytes = 0L,
            ) {
                segmentQueued = true
            }
            awaitCondition("segment cancel picker called") { pickerCalls == 5 }
            delay(50L)
            assertFalse(segmentQueued)

            nextPick = customDir
            launcher.downloadSegment(
                file = video,
                startMs = 1_000L,
                endMs = 5_000L,
                timeRangeLabel = "00-01_00-05",
                streamUrl = null,
                startByte = 0L,
                lengthBytes = 0L,
            ) {
                segmentQueued = true
            }
            awaitCondition("segment download enqueued") {
                segmentQueued && coordinator.tasks.value.values.any { it.isSegment }
            }
            val segTask = coordinator.tasks.value.values.first { it.isSegment }
            assertEquals(customDir, segTask.targetDirectory)
            assertTrue(File(segTask.destinationPath).canonicalPath.startsWith(File(customDir).canonicalPath))
        }
    }
}
