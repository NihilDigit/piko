package dev.piko.shared.upload

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 真实文件系统契约测试：文件夹导入的复制、上限、跳过规则与失败清理。 */
class AppDirectoryUploadFolderImporterTest {
    private lateinit var temporary: Path
    private lateinit var managed: File
    private lateinit var staging: File
    private lateinit var external: File
    private lateinit var importer: AppDirectoryUploadFolderImporter

    @BeforeTest
    fun prepare() {
        temporary = Files.createTempDirectory("piko-upload-folder-")
        managed = temporary.resolve("piko").toFile()
        staging = temporary.resolve("staging").toFile()
        external = temporary.resolve("external").toFile()
        external.mkdirs()
        importer = AppDirectoryUploadFolderImporter(managed.path, staging.path)
    }

    @AfterTest
    fun clean() {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun `nested folder is published whole and lists through upload sources`() {
        File(external, "Season 1").mkdirs()
        File(external, "Season 1/episode 1.mp4").writeText("first")
        File(external, "Season 1/episode 2.mp4").writeText("second")
        File(external, "readme.txt").writeText("note")

        val uri = importer.importFolder("Show", maxBytesPerFile = 1024, maxTotalBytes = 4096, snapshot = snapshotOf(external))

        val sources = AppDirectoryUploadSources(managed.path, NoAccess)
        val folder = sources.listFolder(uri)!!
        assertEquals("Show", folder.name)
        assertEquals(
            listOf("Season 1/episode 1.mp4", "Season 1/episode 2.mp4", "readme.txt").sorted(),
            relativePaths(uri, folder),
        )
        assertEquals("first", sources.readAt(folder.files.first { it.uri.endsWith("episode 1.mp4") }.uri, 0, 5).decodeToString())
        // 暂存目录不留下任何东西
        assertFalse(staging.exists() && (staging.list()?.isNotEmpty() == true))
    }

    @Test
    fun `only Finder leftovers are skipped, real dot files are kept`() {
        File(external, "keep.txt").writeText("keep")
        File(external, ".DS_Store").writeText("junk")
        File(external, ".hidden").writeText("junk")
        File(external, "nested").mkdirs()
        File(external, "nested/deep.txt").writeText("deep")

        val uri = importer.importFolder("Show", 1024, 4096, snapshot = snapshotOf(external))
        val folder = AppDirectoryUploadSources(managed.path, NoAccess).listFolder(uri)!!
        // .DS_Store 是 Finder 留下的，跳过；.hidden 是用户的文件，照原样导入
        assertEquals(listOf(".hidden", "keep.txt", "nested/deep.txt").sorted(), relativePaths(uri, folder))
    }

    @Test
    fun `entries reported as other are skipped`() {
        File(external, "keep.txt").writeText("keep")
        File(external, "link").writeText("target")
        val snapshot = snapshotOf(external, others = setOf("link"))

        val uri = importer.importFolder("Show", 1024, 4096, snapshot = snapshot)
        val folder = AppDirectoryUploadSources(managed.path, NoAccess).listFolder(uri)!!
        assertEquals(listOf("keep.txt"), relativePaths(uri, folder))
    }

    @Test
    fun `per file limit aborts and leaves nothing behind`() {
        File(external, "big.bin").writeBytes(ByteArray(2048))
        val failure = assertFailsWith<IOException> {
            importer.importFolder("Show", maxBytesPerFile = 1024, maxTotalBytes = 1 shl 20, snapshot = snapshotOf(external))
        }
        assertTrue(failure.message!!.contains("File exceeds import byte limit"))
        assertNoImports()
    }

    @Test
    fun `total limit aborts even when every file fits`() {
        File(external, "a.bin").writeBytes(ByteArray(700))
        File(external, "b.bin").writeBytes(ByteArray(700))
        val failure = assertFailsWith<IOException> {
            importer.importFolder("Show", maxBytesPerFile = 1024, maxTotalBytes = 1000, snapshot = snapshotOf(external))
        }
        assertTrue(failure.message!!.contains("Folder exceeds import byte limit"))
        assertNoImports()
    }

    @Test
    fun `cancellation during copy cleans the staging tree`() {
        repeat(4) { index -> File(external, "part-$index.bin").writeBytes(ByteArray(4096)) }
        var calls = 0
        assertFailsWith<IllegalStateException> {
            importer.importFolder("Show", 8192, 1 shl 20, checkCancelled = {
                if (++calls > 3) throw IllegalStateException("cancel")
            }, snapshot = snapshotOf(external))
        }
        assertNoImports()
    }

    @Test
    fun `invalid folder names are rejected`() {
        File(external, "a.txt").writeText("a")
        for (name in listOf("", ".", "..", "a/b", "a\\b", "a:b")) {
            assertFailsWith<IllegalArgumentException> { importer.importFolder(name, 1024, 4096, snapshot = snapshotOf(external)) }
        }
    }

    @Test
    fun `staging inside the managed directory is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppDirectoryUploadFolderImporter(managed.path, File(managed, "staging").path)
        }
    }

    private fun relativePaths(folderUri: String, folder: UploadFolder): List<String> =
        folder.files.map { it.uri.removePrefix("$folderUri/") }.sorted()

    private fun assertNoImports() {
        val imports = File(managed, "imports")
        assertFalse(imports.exists() && imports.isDirectory && imports.list()!!.isNotEmpty(), "imports 里留下了半截目录")
        assertFalse(staging.exists() && staging.list()!!.isNotEmpty(), "暂存目录留下了半截目录")
    }
}

private object NoAccess : UploadFileAccess {
    override fun modifiedAtMs(path: String): Long = File(path).lastModified()

    override fun openAt(path: String, offset: Long): RawSource {
        val source = SystemFileSystem.source(kotlinx.io.files.Path(path)).buffered()
        val buffer = Buffer()
        var skipped = 0L
        while (skipped < offset) {
            val step = source.readAtMostTo(buffer, offset - skipped)
            if (step <= 0) throw IOException("Source shorter than offset")
            buffer.clear()
            skipped += step
        }
        return source
    }
}

/** 用真实目录搭一个快照；[others] 里的相对路径强制报成 [UploadFolderEntryKind.OTHER]，代替符号链接。 */
private fun snapshotOf(root: File, others: Set<String> = emptySet()): UploadFolderSnapshot = object : UploadFolderSnapshot {
    override fun kindOf(relativePath: String): UploadFolderEntryKind? {
        if (relativePath in others) return UploadFolderEntryKind.OTHER
        val file = File(root, relativePath)
        return when {
            !file.exists() -> null
            file.isDirectory -> UploadFolderEntryKind.DIRECTORY
            file.isFile -> UploadFolderEntryKind.FILE
            else -> UploadFolderEntryKind.OTHER
        }
    }

    override fun listDirectory(relativePath: String): List<String> =
        File(root, relativePath).list()?.toList() ?: emptyList()

    override fun openFile(relativePath: String): RawSource =
        SystemFileSystem.source(kotlinx.io.files.Path(File(root, relativePath).path)).buffered() as RawSource
}
