package dev.piko.shared.upload

import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Production common importer, exercised against an actual filesystem, not a mock copy operation. */
class AppDirectoryUploadImporterTest {
    private lateinit var temp: Path
    private lateinit var root: Path
    private lateinit var stage: Path
    private lateinit var importer: AppDirectoryUploadImporter
    private lateinit var uploads: AppDirectoryUploadSources

    @BeforeTest fun prepare() {
        temp = Files.createTempDirectory("piko-document-import-")
        root = temp.resolve("documents")
        stage = temp.resolve("caches/staging")
        importer = AppDirectoryUploadImporter(root.toString(), stage.toString())
        uploads = AppDirectoryUploadSources(root.toString(), object : UploadFileAccess {
            override fun modifiedAtMs(path: String): Long = java.io.File(path).lastModified()
            override fun openAt(path: String, offset: Long): RawSource = FileInputStream(path).let {
                it.channel.position(offset)
                it.asSource()
            }
        })
    }

    @AfterTest fun clean() {
        Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test fun `unicode document is copied and returned through the production upload reader`() {
        val bytes = "视频 100% 中文".encodeToByteArray()
        val source = RecordingSource(bytes)
        val uri = importer.importFile("视频 100%.txt", bytes.size.toLong()) { source }
        assertContentEquals(bytes, uploads.readAt(uri, 0, bytes.size))
        assertEquals("视频 100%.txt", uploads.describe(uri)?.name)
        assertEquals(1, source.closes)
        assertFalse(uri.contains(temp.toString()))
        assertNoStagingFiles()
    }

    @Test fun `duplicate names receive independent snapshots and partial is a valid document name`() {
        val first = importer.importFile(".partial", 10) { Buffer().apply { write(byteArrayOf(1)) } }
        val second = importer.importFile(".partial", 10) { Buffer().apply { write(byteArrayOf(2)) } }
        assertNotEquals(first, second)
        assertContentEquals(byteArrayOf(1), uploads.readAt(first, 0, 1))
        assertContentEquals(byteArrayOf(2), uploads.readAt(second, 0, 1))
        assertNoStagingFiles()
    }

    @Test fun `invalid names fail before opening external documents or creating directories`() {
        for (name in listOf("", " ", ".", "..", "../bad", "a/b", "a\\b", "C:bad", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException> { importer.importFile(name, 10) { error("must not open") } }
        }
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(stage))
    }

    @Test fun `negative limit fails before opening while empty document accepts zero limit`() {
        assertFailsWith<IllegalArgumentException> { importer.importFile("x", -1) { error("must not open") } }
        val empty = RecordingSource(byteArrayOf())
        val uri = importer.importFile("empty", 0) { empty }
        assertEquals(0L, uploads.describe(uri)?.size)
        assertEquals(1, empty.closes)
    }

    @Test fun `streaming is bounded and complete source is invisible until publication`() {
        val bytes = ByteArray(256 * 1024 + 7) { (it % 251).toByte() }
        val source = RecordingSource(bytes) {
            assertTrue(uploads.listFolder(uploads.uriFor(""))!!.files.isEmpty())
        }
        val uri = importer.importFile("large.bin", bytes.size.toLong()) { source }
        assertTrue(source.largestRequest <= 64L * 1024)
        assertContentEquals(bytes, uploads.readAt(uri, 0, bytes.size))
        assertEquals(1, uploads.listFolder(uploads.uriFor(""))!!.files.size)
    }

    @Test fun `exceeding a zero or positive limit closes source and removes incomplete imports`() {
        for (limit in listOf(0L, 3L)) {
            val source = RecordingSource(byteArrayOf(1, 2, 3, 4))
            assertFailsWith<IOException> { importer.importFile("too-large", limit) { source } }
            assertEquals(1, source.closes)
            assertNoImportedFiles()
            assertNoStagingFiles()
        }
    }

    @Test fun `opening and mid-copy errors remove staging without touching a successful import`() {
        val good = importer.importFile("keep", 10) { Buffer().apply { write(byteArrayOf(42)) } }
        assertFailsWith<IOException> { importer.importFile("broken", 10) { throw IOException("open failed") } }
        val failed = RecordingSource(byteArrayOf(1, 2)) { throw IOException("read failed") }
        assertFailsWith<IOException> { importer.importFile("broken", 10) { failed } }
        assertEquals(1, failed.closes)
        assertEquals(1, uploads.listFolder(uploads.uriFor(""))!!.files.size)
        assertContentEquals(byteArrayOf(42), uploads.readAt(good, 0, 1))
        assertNoStagingFiles()
    }

    @Test fun `source close failure prevents publication`() {
        val source = RecordingSource(byteArrayOf(1), failClose = true)
        val error = assertFailsWith<IOException> { importer.importFile("x", 10) { source } }
        assertEquals("close failed", error.message)
        assertEquals(1, source.closes)
        assertNoImportedFiles()
        assertNoStagingFiles()
    }

    @Test fun `cancellation between chunks cleans source and never returns a truncated upload`() {
        var cancelled = false
        val source = RecordingSource(ByteArray(70_000)) { cancelled = true }
        assertFailsWith<CancellationException> {
            importer.importFile("x", 100_000, checkCancelled = {
                if (cancelled) throw CancellationException("cancelled")
            }) { source }
        }
        assertEquals(1, source.closes)
        assertNoImportedFiles()
        assertNoStagingFiles()
    }

    @Test fun `cancellation before opening has no filesystem side effects`() {
        assertFailsWith<CancellationException> {
            importer.importFile("x", 1, checkCancelled = { throw CancellationException() }) { error("must not open") }
        }
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(stage))
    }

    @Test fun `cancellation after source close still prevents publication`() {
        var checks = 0
        val source = RecordingSource(byteArrayOf(1))
        assertFailsWith<CancellationException> {
            importer.importFile("x", 1, checkCancelled = {
                if (++checks == 4) throw CancellationException("before publish")
            }) { source }
        }
        assertEquals(1, source.closes)
        assertNoImportedFiles()
        assertNoStagingFiles()
    }

    @Test fun `a non-progressing source fails rather than spinning forever`() {
        var closes = 0
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = 0L
            override fun close() { closes++ }
        }
        assertFailsWith<IOException> { importer.importFile("x", 1) { source } }
        assertEquals(1, closes)
        assertNoImportedFiles()
        assertNoStagingFiles()
    }

    @Test fun `large byte limit does not overflow and identifiers survive container relocation`() {
        val bytes = byteArrayOf(1, 2, 3)
        val uri = importer.importFile("x", Long.MAX_VALUE) { RecordingSource(bytes) }
        val relocated = temp.resolve("new-documents")
        Files.move(root, relocated)
        val moved = AppDirectoryUploadSources(relocated.toString(), object : UploadFileAccess {
            override fun modifiedAtMs(path: String): Long = java.io.File(path).lastModified()
            override fun openAt(path: String, offset: Long): RawSource = FileInputStream(path).let {
                it.channel.position(offset)
                it.asSource()
            }
        })
        assertContentEquals(bytes, moved.readAt(uri, 0, bytes.size))
        assertFalse(Files.exists(root))
        assertNoStagingFiles()
    }

    @Test fun `staging must be separate from upload tree and both roots must be app directories`() {
        for ((a, b) in listOf(root to root, root to root.resolve("stage"), root to temp)) {
            assertFailsWith<IllegalArgumentException> { AppDirectoryUploadImporter(a.toString(), b.toString()) }
        }
        assertFailsWith<IllegalArgumentException> { AppDirectoryUploadImporter("relative", stage.toString()) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryUploadImporter(root.toString(), "relative") }
        assertFalse(Files.exists(root))
    }

    private fun assertNoImportedFiles() {
        assertTrue(uploads.listFolder(uploads.uriFor(""))?.files.orEmpty().isEmpty())
    }
    private fun assertNoStagingFiles() {
        if (Files.exists(stage)) Files.list(stage).use { assertEquals(0L, it.count()) }
    }

    private class RecordingSource(
        bytes: ByteArray,
        private val failClose: Boolean = false,
        private val beforeRead: () -> Unit = {},
    ) : RawSource {
        private val data = Buffer().apply { write(bytes) }
        var closes = 0
        var largestRequest = 0L
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            largestRequest = maxOf(largestRequest, byteCount)
            beforeRead()
            return data.readAtMostTo(sink, byteCount)
        }
        override fun close() {
            closes++
            data.close()
            if (failClose) throw IOException("close failed")
        }
    }
}
