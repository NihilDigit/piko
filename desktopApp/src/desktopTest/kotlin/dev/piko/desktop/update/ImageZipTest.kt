package dev.piko.desktop.update

import dev.piko.shared.update.ChecksumMismatchException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ImageZipTest {
    private val root: File = Files.createTempDirectory("piko-image").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun spec(path: String, content: ByteArray) = ManifestFile(
        path = path,
        size = content.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(content).toHex(),
        mtime = 1_700_000_000_000L,
    )

    // 随机字节压不小，中央目录落在结尾那 64 KB 之外，要单独取一次
    private val modules = Random(1).nextBytes(200_000)
    private val release = "JAVA_VERSION=\"25\"\n".toByteArray()
    private val jar = ByteArray(5_000) { (it % 7).toByte() }

    /**
     * 照 packageReleaseUpdate 打法，另有两种它不用、但 zip 里合法的条目：带时间戳的（JDK 在本地文件头写的扩展字段
     * 比中央目录的长）与不压缩的。
     */
    private fun image(): File = root.resolve("image.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("runtime/lib/modules"))
            zip.write(modules)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("runtime/release").apply { lastModifiedTime = FileTime.fromMillis(1_600_000_000_000L) })
            zip.write(release)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("app/x.jar").apply {
                method = ZipEntry.STORED
                size = jar.size.toLong()
                crc = CRC32().apply { update(jar) }.value
            })
            zip.write(jar)
            zip.closeEntry()
        }
    }

    /** 模拟 Range：从本地文件切出闭区间，按 [chunk] 字节一块交出，块的边界落在文件头与数据中间。 */
    private fun ranges(file: File, chunk: Int, log: MutableList<LongRange> = mutableListOf()): suspend (LongRange, (ByteArray, Int) -> Unit) -> Unit {
        val bytes = file.readBytes()
        return { range, onChunk ->
            log += range
            val slice = bytes.copyOfRange(range.first.toInt(), minOf(range.last + 1, bytes.size.toLong()).toInt())
            slice.toList().chunked(chunk).forEach { part -> onChunk(part.toByteArray(), part.size) }
        }
    }

    private suspend fun catalogOf(file: File): ImageCatalog {
        val fetch = ranges(file, Int.MAX_VALUE)
        return readImageCatalog(file.length()) { range ->
            val out = java.io.ByteArrayOutputStream()
            fetch(range) { buffer, length -> out.write(buffer, 0, length) }
            out.toByteArray()
        }
    }

    @Test
    fun rangesFetchOnlyTheWantedEntries(): Unit = runBlocking {
        val zip = image()
        val catalog = catalogOf(zip)
        val wanted = listOf(spec("runtime/release", release), spec("app/x.jar", jar))
        val requested = mutableListOf<LongRange>()
        fetchImageEntries(catalog, wanted, root.resolve("out"), ranges(zip, 7, requested))
        assertEquals(release.decodeToString(), root.resolve("out/runtime/release").readText())
        assertEquals(jar.toList(), root.resolve("out/app/x.jar").readBytes().toList())
        assertEquals(1_700_000_000_000L, root.resolve("out/app/x.jar").lastModified())
        // 不取 200 KB 的 modules
        assertEquals(true, requested.sumOf { it.last - it.first + 1 } < 20_000)
    }

    @Test
    fun withoutRangeTheWholeImageServesTheSameEntries() {
        val wanted = listOf(spec("runtime/lib/modules", modules), spec("app/x.jar", jar))
        readImageEntries(image(), wanted, root.resolve("out"))
        assertEquals(modules.toList(), root.resolve("out/runtime/lib/modules").readBytes().toList())
    }

    @Test
    fun contentThatDoesNotMatchTheManifestIsRejected(): Unit = runBlocking {
        val zip = image()
        val catalog = catalogOf(zip)
        assertFailsWith<ChecksumMismatchException> {
            fetchImageEntries(catalog, listOf(spec("runtime/release", "other".toByteArray())), root.resolve("a"), ranges(zip, 64))
        }
        assertFailsWith<ChecksumMismatchException> {
            fetchImageEntries(catalog, listOf(spec("runtime/missing", release)), root.resolve("b"), ranges(zip, 64))
        }
    }

    @Test
    fun changedFilesAreThePatchOnesPlusWhatDiffersLocally() {
        val install = root.resolve("install")
        install.resolve("runtime/lib").mkdirs()
        install.resolve("runtime/lib/modules").writeBytes(modules)
        install.resolve("runtime/release").writeText("JAVA_VERSION=\"21\"\n")
        val manifest = UpdateManifest(
            version = "2",
            files = listOf(
                spec("runtime/lib/modules", modules),
                spec("runtime/release", release),
                spec("app/x.jar", jar).copy(patch = true),
                spec("app/resources/new.dll", byteArrayOf(1)),
            ),
        )
        assertEquals(listOf("runtime/release", "app/x.jar", "app/resources/new.dll"), changedFiles(install, manifest).map { it.path })
    }
}
