package dev.piko.shared.upload

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests the production common implementation against real files; the seek adapter alone is JVM. */
class AppDirectoryUploadSourcesTest {
    private lateinit var temporary: Path
    private lateinit var root: File
    private lateinit var access: RecordingAccess
    private lateinit var sources: AppDirectoryUploadSources

    @BeforeTest fun prepare() {
        temporary = Files.createTempDirectory("piko-app-upload-")
        root = temporary.resolve("documents").toFile()
        access = RecordingAccess()
        sources = AppDirectoryUploadSources(root.path, access)
    }

    @AfterTest fun clean() {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test fun `missing queries and identifiers never create directories`() {
        val uri = sources.uriFor("missing/file.bin")
        assertEquals("piko-app-upload:missing/file.bin", uri)
        assertNull(sources.describe(uri))
        assertNull(sources.listFolder(sources.uriFor("")))
        assertFalse(root.exists())
        assertTrue(access.opened.isEmpty())
    }

    @Test fun `identifiers survive container relocation without storing absolute paths`() {
        val content = "actual bytes".encodeToByteArray()
        write("album/视频 100%.bin", content)
        val uri = sources.uriFor("album/视频 100%.bin")
        assertFalse(uri.contains(root.path))
        val relocated = temporary.resolve("other-container").toFile()
        root.renameTo(relocated).also { assertTrue(it) }
        val reopened = AppDirectoryUploadSources(relocated.path, access)
        assertEquals(UploadSourceInfo("视频 100%.bin", content.size.toLong(), relocated.resolve("album/视频 100%.bin").lastModified()), reopened.describe(uri))
        assertContentEquals(content, reopened.readAt(uri, 0, content.size))
        assertFalse(root.exists())
    }

    @Test fun `description reflects file changes and persisted timestamp`() {
        val file = write("movie.bin", byteArrayOf(1, 2, 3))
        val uri = sources.uriFor("movie.bin")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_700_000_000_000L))
        assertEquals(UploadSourceInfo("movie.bin", 3L, 1_700_000_000_000L), sources.describe(uri))
        file.appendBytes(byteArrayOf(4))
        assertEquals(4L, sources.describe(uri)!!.size)
        assertTrue(file.delete())
        assertNull(sources.describe(uri))
    }

    @Test fun `directory has no file description and cannot be opened`() {
        write("folder/item", byteArrayOf(1))
        val uri = sources.uriFor("folder")
        assertNull(sources.describe(uri))
        assertFailsWith<IOException> { sources.open(uri, 0) }
        assertFailsWith<IOException> { sources.readAt(uri, 0, 0) }
    }

    @Test fun `random reads open directly at requested offset and close their source`() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        write("payload", bytes)
        val uri = sources.uriFor("payload")
        for (offset in listOf(0, 50_000, 100_000)) {
            assertContentEquals(bytes.copyOfRange(offset, offset + 20_000), sources.readAt(uri, offset.toLong(), 20_000))
        }
        assertEquals(listOf(0L, 50_000L, 100_000L), access.opened.map { it.second })
        assertEquals(3, access.closes)
    }

    @Test fun `large sparse file keeps 64 bit offsets`() {
        root.mkdirs()
        val file = root.resolve("large.bin")
        val offset = 3L * 1024 * 1024 * 1024 + 19
        // SPARSE + CREATE_NEW avoids allocating/zero-filling three gigabytes on Windows.
        Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE)).use {
            it.position(offset)
            val bytes = ByteBuffer.wrap(byteArrayOf(7, 8, 9))
            while (bytes.hasRemaining()) it.write(bytes)
        }
        assertContentEquals(byteArrayOf(7, 8, 9), sources.readAt(sources.uriFor("large.bin"), offset, 3))
        assertEquals(offset, access.opened.single().second)
    }

    @Test fun `empty reads at file end and empty file are valid`() {
        write("empty", byteArrayOf())
        write("short", byteArrayOf(1))
        assertContentEquals(byteArrayOf(), sources.readAt(sources.uriFor("empty"), 0, 0))
        assertContentEquals(byteArrayOf(), sources.readAt(sources.uriFor("short"), 1, 0))
        sources.open(sources.uriFor("empty"), 0).use { assertEquals(-1L, it.readAtMostTo(Buffer(), 1)) }
        assertEquals(3, access.closes)
    }

    @Test fun `invalid and overflowing ranges fail without opening a stream`() {
        write("short", byteArrayOf(1, 2, 3))
        val uri = sources.uriFor("short")
        assertFailsWith<IllegalArgumentException> { sources.readAt(uri, -1, 1) }
        assertFailsWith<IllegalArgumentException> { sources.readAt(uri, 0, -1) }
        assertFailsWith<IllegalArgumentException> { sources.open(uri, -1) }
        assertFailsWith<IOException> { sources.readAt(uri, 2, 2) }
        assertFailsWith<IOException> { sources.readAt(uri, Long.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<IOException> { sources.open(uri, Long.MAX_VALUE) }
        assertTrue(access.opened.isEmpty())
    }

    @Test fun `resume sources own independent positions and close independently`() {
        write("letters", "abcdefghij".encodeToByteArray())
        val uri = sources.uriFor("letters")
        sources.open(uri, 4).buffered().use { first ->
            sources.open(uri, 1).buffered().use { second ->
                assertContentEquals("ef".encodeToByteArray(), first.readByteArray(2))
                assertContentEquals("bc".encodeToByteArray(), second.readByteArray(2))
                assertContentEquals("ghij".encodeToByteArray(), first.readByteArray())
            }
        }
        assertEquals(2, access.closes)
    }

    @Test fun `source truncated during open raises EOF and closes the stream`() {
        val file = write("changing", byteArrayOf(1, 2, 3))
        val changingAccess = object : UploadFileAccess {
            override fun modifiedAtMs(path: String) = 0L
            override fun openAt(path: String, offset: Long): RawSource {
                file.writeBytes(byteArrayOf(1))
                return access.openAt(path, offset)
            }
        }
        val changing = AppDirectoryUploadSources(root.path, changingAccess)
        assertFailsWith<kotlinx.io.EOFException> { changing.readAt(changing.uriFor("changing"), 0, 3) }
        assertEquals(1, access.closes)
    }

    @Test fun `metadata failure returns null but stream failures propagate`() {
        write("item", byteArrayOf(1))
        val failing = AppDirectoryUploadSources(root.path, object : UploadFileAccess {
            override fun modifiedAtMs(path: String): Long = throw IOException("metadata unavailable")
            override fun openAt(path: String, offset: Long): RawSource = throw IOException("read unavailable")
        })
        val uri = failing.uriFor("item")
        assertNull(failing.describe(uri))
        assertFailsWith<IOException> { failing.open(uri, 0) }
        assertFailsWith<IOException> { failing.readAt(uri, 0, 1) }
    }

    @Test fun `recursive folder enumeration preserves relative hierarchy and hidden files`() {
        write("selected/top.bin", byteArrayOf(1))
        write("selected/nested/deeper/video.bin", byteArrayOf(2))
        write("selected/nested/.config", byteArrayOf(3))
        root.resolve("selected/empty").mkdirs()
        val folder = sources.listFolder(sources.uriFor("selected"))!!
        assertEquals("selected", folder.name)
        assertEquals(setOf(
            UploadTreeEntry(sources.uriFor("selected/top.bin"), ""),
            UploadTreeEntry(sources.uriFor("selected/nested/.config"), "nested"),
            UploadTreeEntry(sources.uriFor("selected/nested/deeper/video.bin"), "nested/deeper"),
        ), folder.files.toSet())
        assertTrue(access.opened.isEmpty())
    }

    @Test fun `empty folder is represented but files are not folders`() {
        root.mkdirs()
        assertEquals(UploadFolder("documents", emptyList()), sources.listFolder(sources.uriFor("")))
        write("item", byteArrayOf(1))
        assertNull(sources.listFolder(sources.uriFor("item")))
    }

    @Test fun `retain release and permission keys never delete source files`() {
        val file = write("keep", byteArrayOf(1))
        val uri = sources.uriFor("keep")
        assertEquals(uri, sources.permissionKey(uri))
        sources.retain(uri)
        sources.release(uri)
        assertTrue(file.exists())
        assertContentEquals(byteArrayOf(1), file.readBytes())
    }

    @Test fun `malformed identifiers traversal absolute and ambiguous names are rejected`() {
        for (bad in listOf("../secret", "a/../secret", "./a", "a//b", "a/", "/absolute", "C:/item", "a\\b", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>(bad) { sources.uriFor(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { sources.describe("piko-app-upload:$bad") }
        }
        assertFailsWith<IllegalArgumentException> { sources.describe("file:///tmp/secret") }
        assertFailsWith<IllegalArgumentException> { sources.describe(sources.uriFor("")) }
        assertFailsWith<IllegalArgumentException> { sources.open(sources.uriFor(""), 0) }
    }

    @Test fun `literal percent escape names are not URL decoded`() {
        write("%2e%2e/literal%2Fname", byteArrayOf(5))
        val uri = sources.uriFor("%2e%2e/literal%2Fname")
        assertContentEquals(byteArrayOf(5), sources.readAt(uri, 0, 1))
    }

    @Test fun `invalid managed roots fail without creating files`() {
        assertFailsWith<IllegalArgumentException> { AppDirectoryUploadSources("relative", access) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryUploadSources(temporary.resolve("x/../y").toString(), access) }
        val file = write("not-directory", byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { AppDirectoryUploadSources(file.path, access) }
    }

    @Test fun `folder listing skips links and does not follow a cycle`() {
        write("folder/real", byteArrayOf(1))
        val outside = temporary.resolve("outside").toFile().apply { mkdirs(); resolve("private").writeText("outside") }
        if (!tryLink(root.resolve("folder/outside").toPath(), outside.toPath())) return
        if (!tryLink(root.resolve("folder/cycle").toPath(), root.resolve("folder").toPath())) return
        assertEquals(listOf(UploadTreeEntry(sources.uriFor("folder/real"), "")), sources.listFolder(sources.uriFor("folder"))!!.files)
        assertFailsWith<IllegalArgumentException> { sources.describe("piko-app-upload:folder/outside/private") }
    }

    @Test fun `file and dangling links are rejected and excluded from selection`() {
        write("real", byteArrayOf(1))
        if (!tryLink(root.resolve("alias").toPath(), root.resolve("real").toPath())) return
        if (!tryLink(root.resolve("dangling").toPath(), root.resolve("missing").toPath())) return
        assertFailsWith<IllegalArgumentException> { sources.uriFor("alias") }
        assertFailsWith<IllegalArgumentException> { sources.uriFor("dangling") }
        assertEquals(listOf(UploadTreeEntry(sources.uriFor("real"), "")), sources.listFolder(sources.uriFor(""))!!.files)
    }

    @Test fun `root redirection after construction is rejected`() {
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        if (!tryLink(root.toPath(), outside.toPath())) return
        assertFailsWith<IllegalArgumentException> { sources.uriFor("secret") }
        assertFailsWith<IllegalArgumentException> { sources.listFolder("piko-app-upload:") }
    }

    private fun write(relative: String, bytes: ByteArray): File = root.resolve(relative).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun tryLink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (failure: java.nio.file.FileAlreadyExistsException) {
        throw failure
    } catch (_: java.nio.file.FileSystemException) {
        assumeTrue("Host does not permit symbolic link creation", false); false
    } catch (_: UnsupportedOperationException) {
        assumeTrue("Host filesystem does not support symbolic links", false); false
    } catch (_: SecurityException) {
        assumeTrue("Host denies symbolic link creation", false); false
    }

    private class RecordingAccess : UploadFileAccess {
        val opened = mutableListOf<Pair<String, Long>>()
        var closes = 0
        override fun modifiedAtMs(path: String): Long = File(path).lastModified()
        override fun openAt(path: String, offset: Long): RawSource {
            val input = FileInputStream(path)
            try { input.channel.position(offset) } catch (failure: Throwable) { input.close(); throw failure }
            opened += path to offset
            val source = input.asSource()
            return object : RawSource {
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = source.readAtMostTo(sink, byteCount)
                override fun close() { closes++; source.close() }
            }
        }
    }
}
