package dev.piko.shared.state

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderVaultSessionTest {
    @Test
    fun `directory confirmation stays within eight slots and cancellation leaves originals intact`() = smoke { scope ->
        val drive = Drive().apply { stalledWrites = true; sampleDelayMs = 1 }
        drive.directories["Dramas"] = (0..31).map { folder("dir-$it") }
        (0..31).forEach { drive.directories["dir-$it"] = listOf(file("file-$it")) }
        val session = FolderVaultSession(drive, scope)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("八个目录等待确认") { drive.writes.get() == 8 }
        assertEquals(8, drive.maximumWrites.get())
        assertTrue(drive.removed.isEmpty())
        session.cancel()
        awaitUntil("目录写入已取消") { drive.writes.get() == 0 }
        assertTrue(drive.removed.isEmpty())
        assertTrue(drive.manifests.isEmpty())
    }
    @Test
    fun `large file archive keeps files below 50 MiB without sampling them`() = smoke { scope ->
        val drive = Drive()
        val threshold = 50L * 1024 * 1024
        drive.directories["Dramas"] = listOf(
            file("small").copy(size = (threshold - 1).toString()),
            file("boundary").copy(size = threshold.toString()),
            file("large").copy(size = (threshold + 1).toString()),
            file("unsourced").copy(size = threshold.toString(), params = emptyMap()),
        )
        val session = FolderVaultSession(drive, scope)
        val survey = session.survey(PikoPathBreadcrumb("Dramas", "Dramas")).getOrThrow()
        assertEquals(4, survey.files)
        assertEquals(3, survey.largeFiles?.files)
        assertEquals(1, survey.largeFiles?.unsourcedFiles)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), includeUnsourced = false, onlyLargeFiles = true)
        awaitUntil("大文件归档完成") { drive.changes.isNotEmpty() }
        assertEquals(setOf("boundary", "large"), drive.removed.flatten().toSet())
        assertEquals(setOf("boundary", "large"), drive.samples.toSet())
        assertEquals(2, drive.manifests.getValue("Dramas").size)
    }

    private class Drive(private val free: Boolean = false) : FolderVaultOperations {
        val directories = ConcurrentHashMap<String, List<FileStat>>()
        val manifests = ConcurrentHashMap<String, List<VaultEntry>>()
        val removed = CopyOnWriteArrayList<List<String>>()
        val changes = CopyOnWriteArrayList<DriveChangeJournal.Change.Vault>()
        val samples = CopyOnWriteArrayList<String>()
        val inFlight = AtomicInteger()
        val maximum = AtomicInteger()
        val writes = AtomicInteger()
        val maximumWrites = AtomicInteger()
        var failFolder: String? = null
        var stalled: String? = null
        var failRemove = false
        var sampleDelayMs = 50L
        var stalledWrites = false
        override suspend fun list(folderId: String) = directories[folderId].orEmpty()
        override suspend fun deletesOriginals() = free
        override suspend fun sampleCid(file: FileStat): String? {
            samples += file.hash
            val active = inFlight.incrementAndGet()
            maximum.updateAndGet { maxOf(it, active) }
            try {
                if (file.hash == stalled) awaitCancellation()
                delay(sampleDelayMs)
                return "CID-${file.hash}"
            } finally { inFlight.decrementAndGet() }
        }
        override suspend fun write(folderId: String, entries: List<VaultEntry>) {
            val active = writes.incrementAndGet()
            maximumWrites.updateAndGet { maxOf(it, active) }
            try {
                if (stalledWrites) awaitCancellation()
                delay(200)
                if (folderId == failFolder) throw IOException("write failed")
                manifests[folderId] = entries
            } finally { writes.decrementAndGet() }
        }
        override suspend fun remove(ids: List<String>, permanently: Boolean) {
            assertEquals(free, permanently)
            for (id in ids) {
                val folder = directories.entries.single { (_, files) -> files.any { it.id == id } }.key
                assertTrue(manifests[folder]?.isNotEmpty() == true, "清单确认之前不得处置原文件")
            }
            if (failRemove) throw IOException("unknown remove outcome")
            removed += ids
        }
        override fun record(change: DriveChangeJournal.Change.Vault) { changes += change }
        override fun refresh() = Unit
    }

    private fun file(id: String, hash: String = id) = FileStat(
        id = id, name = "$id.mkv", hash = hash, size = "100", phase = TaskPhase.COMPLETE,
        params = mapOf("url" to "magnet:?xt=urn:btih:$hash"),
    )
    private fun folder(id: String, name: String = id) = FileStat(id = id, name = name, kind = FileKind.FOLDER)

    @Test
    fun `sampling and directory commits overlap while each original waits for its manifest`() = smoke { scope ->
        val drive = Drive()
        drive.directories["Dramas"] = listOf(folder("one"), folder("two"), folder("three"), folder("temp", "Piko-Temp"))
        for (name in listOf("one", "two", "three")) drive.directories[name] = (0..4).map { file("$name-$it") }
        drive.directories["temp"] = listOf(file("ignored"))
        val session = FolderVaultSession(drive, scope)
        val started = System.nanoTime()
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("归档完成") { drive.changes.isNotEmpty() }
        println("15 个文件模拟取样及清单确认：${(System.nanoTime() - started) / 1_000_000} ms；最大取样并发 ${drive.maximum.get()}，目录并发 ${drive.maximumWrites.get()}")
        assertTrue(drive.maximum.get() in 5..16)
        assertTrue(drive.maximumWrites.get() in 1..8)
        assertEquals(15, drive.samples.size)
        assertEquals(3, drive.removed.size, "每个目录一次批量处置")
        assertEquals(setOf("one", "two", "three"), drive.manifests.keys)
        assertEquals(15, drive.changes.single().untrashOnRevert.size)
        assertNull(session.progress)
    }

    @Test
    fun `equivalent content is sampled once and a stalled optional cid does not block archiving`() = smoke { scope ->
        val drive = Drive(true)
        drive.stalled = "SLOW"
        drive.directories["Dramas"] = listOf(file("a", "SAME"), file("b", "SAME"), file("c", "SLOW"))
        val session = FolderVaultSession(drive, scope, cidTimeoutMillis = 100)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("慢取样仍可归档") { drive.changes.isNotEmpty() }
        assertEquals(2, drive.samples.size)
        assertNull(drive.manifests.getValue("Dramas").single { it.name == "c.mkv" }.cid)
        assertEquals(3, drive.changes.single().recreateOnRevert.getValue("Dramas").size)
    }

    @Test
    fun `failed manifest never removes its originals`() = smoke { scope ->
        val drive = Drive()
        drive.failFolder = "Dramas"
        drive.directories["Dramas"] = listOf(file("one"))
        val session = FolderVaultSession(drive, scope)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("取样开始") { drive.samples.isNotEmpty() }
        awaitUntil("失败后收口") { session.progress == null }
        assertTrue(drive.removed.isEmpty())
        assertTrue(drive.changes.isEmpty())
    }

    @Test
    fun `cancelled sampling releases all slots without changing files`() = smoke { scope ->
        val drive = Drive()
        drive.sampleDelayMs = 10_000
        drive.directories["Dramas"] = (0..31).map { file("$it") }
        val session = FolderVaultSession(drive, scope)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("取样并发启动") { drive.inFlight.get() == 16 }
        session.cancel()
        awaitUntil("取样已取消") { drive.inFlight.get() == 0 }
        assertTrue(drive.removed.isEmpty())
        assertFalse(drive.manifests.isNotEmpty())
        assertNull(session.progress)
    }

    @Test
    fun `an uncertain deletion keeps the manifest and all recovery information`() = smoke { scope ->
        val drive = Drive(true)
        drive.failRemove = true
        drive.directories["Dramas"] = listOf(file("one"), file("two"))
        val session = FolderVaultSession(drive, scope)
        session.archive(PikoPathBreadcrumb("Dramas", "Dramas"), true)
        awaitUntil("失败仍记录恢复依据") { drive.changes.isNotEmpty() }
        assertEquals(2, drive.manifests.getValue("Dramas").size)
        assertEquals(2, drive.changes.single().recreateOnRevert.getValue("Dramas").size)
        assertNull(session.progress)
    }
}
