package dev.piko.shared.download

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real filesystem contract tests; no mocks and no production JVM dependencies. */
class AppDirectoryDownloadStorageTest {
    private lateinit var temporary: Path
    private lateinit var managed: File
    private lateinit var cache: File
    private lateinit var storage: AppDirectoryDownloadStorage

    @BeforeTest
    fun prepare() {
        temporary = Files.createTempDirectory("piko-app-download-")
        managed = temporary.resolve("downloads").toFile()
        cache = temporary.resolve("independent-cache").toFile()
        storage = AppDirectoryDownloadStorage(managed.path, cache.path)
    }

    @AfterTest
    fun clean() {
        // Files.walk does not follow symlinks (unlike a recursive traversal of their targets).
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun `nested download creates only parents and commits final path`() = runBlocking<Unit> {
        val path = storage.downloadTarget("Show/Season 1/episode.mkv")
        assertEquals(managed.resolve("Show/Season 1/episode.mkv").path, path)
        assertTrue(File(path).parentFile.isDirectory)
        assertFalse(File(path).exists())
        assertFalse(cache.exists())
        File(path).writeText("exported bytes")
        assertEquals(path, storage.commit("Show/Season 1/episode.mkv", path))
        assertEquals("exported bytes", File(path).readText())
        assertEquals(listOf("episode.mkv"), File(path).parentFile.list()!!.toList())
    }

    @Test
    fun `cache creates independent root only and appends data extension`() = runBlocking<Unit> {
        val path = storage.cacheTarget("content-hash")
        assertEquals(cache.resolve("content-hash.data").path, path)
        assertTrue(cache.isDirectory)
        assertFalse(File(path).exists())
        assertFalse(managed.exists())
    }

    @Test
    fun `construction and missing queries never create directories`() = runBlocking<Unit> {
        assertEquals(managed.resolve("missing/film").path, storage.pathFor("missing/film"))
        assertFalse(storage.exists("missing/film"))
        assertNull(storage.locate("missing/film"))
        assertNull(storage.locate(""))
        assertEquals(0L, storage.existingLength("missing/film"))
        assertFalse(storage.delete("missing/film"))
        storage.pruneEmptyFolders("missing")
        assertFalse(managed.exists())
        assertFalse(cache.exists())
    }

    @Test
    fun `empty locate returns existing selected directory and blank provider selects root`() = runBlocking<Unit> {
        managed.mkdirs()
        var selected = managed.resolve("selected").apply { mkdirs() }.path
        val dynamic = AppDirectoryDownloadStorage(managed.path, cache.path) { selected }
        assertEquals(selected, dynamic.locate(""))
        selected = "  "
        assertEquals(managed.path, dynamic.locate(""))
        assertTrue(dynamic.exists(""))
    }

    @Test
    fun `existing length counts regular files including zero bytes but not directories`() = runBlocking<Unit> {
        val path = storage.downloadTarget("a/bytes")
        File(path).writeBytes(ByteArray(123) { 4 })
        File(storage.downloadTarget("empty")).writeBytes(byteArrayOf())
        assertEquals(123L, storage.existingLength("a/bytes"))
        assertEquals(0L, storage.existingLength("empty"))
        assertEquals(0L, storage.existingLength("a"))
        assertTrue(storage.exists("empty"))
        assertTrue(storage.exists("a"))
        assertEquals(managed.resolve("a").path, storage.locate("a"))
        assertEquals(mapOf("a/bytes" to 123L, "absent" to 0L), storage.existingLengths(listOf("a/bytes", "absent")))
    }

    @Test
    fun `invalid relative names are rejected before any writes`() = runBlocking<Unit> {
        for (name in listOf("", ".", "..", "../escape", "a/../b", "a/./b", "a//b", "/absolute", "a/", "a\\b", "a\u0000b", "C:stream")) {
            assertFailsWith<IllegalArgumentException>(name) { storage.downloadTarget(name) }
            assertFailsWith<IllegalArgumentException>(name) { storage.pathFor(name) }
        }
        assertFalse(managed.exists())
    }

    @Test
    fun `cache names are strict single components`() = runBlocking<Unit> {
        for (name in listOf("", ".", "..", "nested/name", "../escape", "/absolute", "a\\b", "a\u0000b", "a:stream")) {
            assertFailsWith<IllegalArgumentException>(name) { storage.cacheTarget(name) }
        }
        assertFalse(cache.exists())
    }

    @Test
    fun `roots must be absolute non filesystem roots and disjoint`() {
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage("relative", cache.path) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(managed.path, "relative") }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(temporary.root.toString(), cache.path) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(managed.path, managed.path) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(managed.path, managed.resolve("cache").path) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(cache.resolve("downloads").path, cache.path) }
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(managed.resolve("../other").path, cache.path) }
    }

    @Test
    fun `provider rejects outside relative and dot segment directories`() = runBlocking<Unit> {
        for (selection in listOf(temporary.resolve("outside").toString(), "relative", managed.resolve("a/../b").path)) {
            val dynamic = AppDirectoryDownloadStorage(managed.path, cache.path) { selection }
            assertFailsWith<IllegalArgumentException> { dynamic.downloadTarget("film") }
            assertFailsWith<IllegalArgumentException> { dynamic.locate("") }
        }
        assertFalse(managed.exists())
    }

    @Test
    fun `commit preserves captured directory across provider changes`() = runBlocking<Unit> {
        var selected = managed.resolve("before").path
        val dynamic = AppDirectoryDownloadStorage(managed.path, cache.path) { selected }
        val captured = dynamic.downloadTarget("nested/movie")
        File(captured).writeText("complete")
        selected = managed.resolve("after").path
        assertEquals(captured, dynamic.commit("nested/movie", captured))
        assertFalse(File(selected).exists())
        // Even a now-invalid preference must not invalidate the already captured export.
        selected = temporary.resolve("external").toString()
        assertEquals(captured, dynamic.commit("nested/movie", captured))
    }

    @Test
    fun `commit rejects missing directories mismatches cache and external files`() = runBlocking<Unit> {
        val captured = storage.downloadTarget("nested/movie")
        assertFailsWith<IOException> { storage.commit("nested/movie", captured) }
        File(captured).mkdir()
        assertFailsWith<IOException> { storage.commit("nested/movie", captured) }
        assertFailsWith<IllegalArgumentException> { storage.commit("other", captured) }
        val cachePath = storage.cacheTarget("movie")
        File(cachePath).writeText("cache")
        assertFailsWith<IllegalArgumentException> { storage.commit("movie.data", cachePath) }
        val external = temporary.resolve("movie").toFile().apply { writeText("external") }
        assertFailsWith<IllegalArgumentException> { storage.commit("movie", external.path) }
        assertFailsWith<IllegalArgumentException> { storage.commit("movie", managed.resolve("nested/../movie").path) }
    }

    @Test
    fun `delete supports managed relative and absolute paths plus absolute cache files`() = runBlocking<Unit> {
        val first = storage.downloadTarget("a/first")
        File(first).writeText("one")
        val second = storage.downloadTarget("second")
        File(second).writeText("two")
        val cached = storage.cacheTarget("third")
        File(cached).writeText("three")
        assertTrue(storage.delete("a/first"))
        assertTrue(storage.delete(second))
        assertTrue(storage.delete(cached))
        assertFalse(storage.delete(cached))
        assertTrue(storage.delete("a"))
        assertTrue(managed.isDirectory)
        assertTrue(cache.isDirectory)
    }

    @Test
    fun `delete rejects roots external paths prefix siblings and dot traversal`() = runBlocking<Unit> {
        managed.mkdirs()
        cache.mkdirs()
        val external = temporary.resolve("downloads-other").toFile().apply { writeText("keep") }
        for (path in listOf("", ".", "..", "../downloads-other", managed.path, cache.path, external.path, managed.resolve("x/../film").path)) {
            assertFailsWith<IllegalArgumentException>(path) { storage.delete(path) }
        }
        assertEquals("keep", external.readText())
        assertTrue(managed.isDirectory)
        assertTrue(cache.isDirectory)
    }

    @Test
    fun `delete never recursively removes nonempty directories`() = runBlocking<Unit> {
        val content = File(storage.downloadTarget("nested/keep")).apply { writeText("keep") }
        assertFalse(storage.delete("nested"))
        assertTrue(content.isFile)
    }

    @Test
    fun `prune is bottom up preserves files nonempty directories and managed root`() = runBlocking<Unit> {
        managed.resolve("empty/one/two").mkdirs()
        managed.resolve("nonempty/empty/leaf").mkdirs()
        val content = managed.resolve("nonempty/keep").apply { writeText("keep") }
        storage.pruneEmptyFolders("")
        assertFalse(managed.resolve("empty").exists())
        assertFalse(managed.resolve("nonempty/empty").exists())
        assertTrue(content.isFile)
        assertTrue(managed.isDirectory)
        storage.delete("nonempty/keep")
        storage.pruneEmptyFolders("nonempty")
        assertFalse(managed.resolve("nonempty").exists())
        assertTrue(managed.isDirectory)
        assertFailsWith<IllegalArgumentException> { storage.pruneEmptyFolders("../outside") }
    }

    @Test
    fun `filesystem errors propagate when file obstructs parent or cache root`() = runBlocking<Unit> {
        File(storage.downloadTarget("obstruction")).writeText("not a directory")
        assertFailsWith<IOException> { storage.downloadTarget("obstruction/film") }
        cache.writeText("not a directory")
        assertFailsWith<IOException> { storage.cacheTarget("hash") }
    }

    @Test
    fun `links outside roots cannot be read written committed deleted or pruned`() = runBlocking<Unit> {
        managed.mkdirs()
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        val content = outside.resolve("keep").apply { writeText("keep") }
        val link = managed.resolve("link").toPath()
        if (!tryLink(link, outside.toPath())) return@runBlocking
        assertFailsWith<IllegalArgumentException> { storage.pathFor("link/keep") }
        assertFailsWith<IllegalArgumentException> { storage.downloadTarget("link/new") }
        assertFailsWith<IllegalArgumentException> { storage.locate("link/keep") }
        assertFailsWith<IllegalArgumentException> { storage.exists("link/keep") }
        assertFailsWith<IllegalArgumentException> { storage.existingLength("link/keep") }
        assertFailsWith<IllegalArgumentException> { storage.commit("keep", managed.resolve("link/keep").path) }
        assertFailsWith<IllegalArgumentException> { storage.delete(managed.resolve("link/keep").path) }
        assertFailsWith<IllegalArgumentException> { storage.pruneEmptyFolders("link") }
        storage.pruneEmptyFolders("")
        assertEquals("keep", content.readText())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `internal directory links and dangling links are rejected without looping`() = runBlocking<Unit> {
        managed.mkdirs()
        val inside = managed.resolve("real").apply { mkdirs() }
        if (!tryLink(managed.resolve("loop").toPath(), managed.toPath())) return@runBlocking
        assertFailsWith<IllegalArgumentException> { storage.downloadTarget("loop/new") }
        storage.pruneEmptyFolders("")
        assertFalse(inside.exists())
        assertTrue(managed.isDirectory)
        if (!tryLink(managed.resolve("dangling").toPath(), temporary.resolve("absent"))) return@runBlocking
        assertFailsWith<IllegalArgumentException> { storage.downloadTarget("dangling/new") }
        storage.pruneEmptyFolders("")
        assertTrue(Files.isSymbolicLink(managed.resolve("dangling").toPath()))
    }

    @Test
    fun `physical root overlap through alias is rejected`() {
        managed.mkdirs()
        val alias = temporary.resolve("alias")
        if (!tryLink(alias, managed.toPath())) return
        assertFailsWith<IllegalArgumentException> { AppDirectoryDownloadStorage(managed.path, alias.resolve("cache").toString()) }
    }

    @Test
    fun `absolute deletion retains old selected destination after preference changes`() = runBlocking<Unit> {
        var selected = managed.resolve("before").path
        val dynamic = AppDirectoryDownloadStorage(managed.path, cache.path) { selected }
        val captured = dynamic.downloadTarget("movie")
        File(captured).writeText("complete")
        selected = managed.resolve("after").path
        assertTrue(dynamic.delete(captured))
        assertFalse(File(captured).exists())
        assertFalse(File(selected).exists())
    }

    @Test
    fun `root redirection after construction is rejected`() = runBlocking<Unit> {
        val outside = temporary.resolve("outside").toFile().apply { mkdirs() }
        if (!tryLink(managed.toPath(), outside.toPath())) return@runBlocking
        assertFailsWith<IllegalArgumentException> { storage.downloadTarget("movie") }
        assertFailsWith<IllegalArgumentException> { storage.locate("") }
        assertFalse(outside.resolve("movie").exists())
    }

    private fun tryLink(link: Path, destination: Path): Boolean = try {
        Files.createSymbolicLink(link, destination)
        true
    } catch (_: UnsupportedOperationException) {
        skipLinkTest("Host filesystem does not support symbolic links")
    } catch (error: java.nio.file.FileAlreadyExistsException) {
        throw error
    } catch (_: java.nio.file.FileSystemException) {
        skipLinkTest("Host does not permit symbolic link creation")
    } catch (_: SecurityException) {
        skipLinkTest("Host denies symbolic link creation")
    }

    private fun skipLinkTest(reason: String): Boolean {
        // Report a real JUnit skip: returning early would misleadingly record a passing test.
        assumeTrue(reason, false)
        return false
    }
}
