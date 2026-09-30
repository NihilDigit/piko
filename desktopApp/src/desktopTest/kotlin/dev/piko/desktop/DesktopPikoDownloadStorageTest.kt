package dev.piko.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件夹下载的相对路径在普通目录上的落盘。收拾空文件夹最怕删多：取消一批时还有别的文件留在里面
 * （同名文件夹下过别的批次、用户自己放的），换成递归删除就连它们一起没了。
 */
class DesktopPikoDownloadStorageTest {
    @Test
    fun `an isolated environment keeps its cache inside its own directory`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("piko-isolated-storage").toFile()
        val cache = root.resolve("cache")
        val storage = DesktopPikoDownloadStorage(cache) { root.resolve("Downloads") }
        val target = File(storage.cacheTarget("content"))
        assertEquals(cache.resolve("content.data").canonicalFile, target.canonicalFile)
        assertTrue(cache.isDirectory)
        root.deleteRecursively()
    }

    @Test
    fun `relative paths land in subfolders and pruning keeps folders that still hold files`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("piko-storage").toFile()
        val storage = DesktopPikoDownloadStorage { root }

        val target = File(storage.downloadTarget("Show/Extras/making.mp4"))
        target.writeBytes(ByteArray(5))
        File(storage.downloadTarget("Show/Empty/Deeper/gone.mkv")).parentFile.let { assertTrue(it.isDirectory) }

        assertEquals(root.resolve("Show/Extras/making.mp4").canonicalFile, target.canonicalFile)
        assertEquals(5L, storage.existingLength("Show/Extras/making.mp4"))

        storage.pruneEmptyFolders("Show")
        assertTrue(target.isFile)
        assertFalse(root.resolve("Show/Empty").exists())
        assertEquals(root.resolve("Show").canonicalPath, storage.locate("Show")?.let { File(it).canonicalPath })

        storage.delete(target.absolutePath)
        storage.pruneEmptyFolders("Show")
        assertNull(storage.locate("Show"))
        root.deleteRecursively()
    }
}
