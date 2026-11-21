package dev.piko.desktop

import dev.piko.shared.PikoHome
import dev.piko.shared.data.DesktopPikoTreeDatabaseFactory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        val expectedExt = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "pack" else "data"
        assertEquals(cache.resolve("content.$expectedExt").canonicalFile, target.canonicalFile)
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

    @Test
    fun `forDirectory scopes file operations to override folder while sharing cache directory`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("piko-override-storage").toFile()
        try {
            val cacheDir = root.resolve("cache")
            val defaultDir = root.resolve("default")
            val customDir = root.resolve("custom")
            val base = DesktopPikoDownloadStorage(cacheDir) { defaultDir }

            assertEquals(base, base.forDirectory(null))
            assertEquals(base, base.forDirectory("   "))

            val scoped = base.forDirectory(customDir.absolutePath)
            assertEquals(base.cacheTarget("same-key"), scoped.cacheTarget("same-key"))
            assertEquals(customDir.resolve("clip.mp4").canonicalFile, File(scoped.pathFor("clip.mp4")).canonicalFile)

            val target = File(scoped.downloadTarget("Album/track.mp3"))
            target.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertEquals(customDir.resolve("Album/track.mp3").canonicalFile, target.canonicalFile)
            assertTrue(scoped.exists("Album/track.mp3"))
            assertFalse(base.exists("Album/track.mp3"))
            assertEquals(4L, scoped.existingLength("Album/track.mp3"))
            assertEquals(customDir.resolve("Album").canonicalPath, scoped.locate("Album")?.let { File(it).canonicalPath })

            assertTrue(scoped.delete(target.absolutePath))
            scoped.pruneEmptyFolders("Album")
            assertNull(scoped.locate("Album"))
            assertFalse(defaultDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bundled sqlite library opens WAL database without extracting a fresh native dll`() {
        val osName = System.getProperty("os.name") ?: ""
        if (!osName.startsWith("Windows", ignoreCase = true)) return

        val sqliteDirPath = System.getProperty("piko.test.sqlite")
        assertNotNull(sqliteDirPath, "Expected piko.test.sqlite to be set on Windows")
        val sqliteDir = File(sqliteDirPath)
        assertTrue(sqliteDir.resolve("sqlitejdbc.dll").isFile, "Missing bundled sqlitejdbc.dll in $sqliteDir")

        val prevResourcesDir = System.getProperty("compose.application.resources.dir")
        val prevLibPath = System.getProperty("org.sqlite.lib.path")
        val prevLibName = System.getProperty("org.sqlite.lib.name")
        val tempDbDir = Files.createTempDirectory("piko-bundled-sqlite-test").toFile()
        try {
            System.setProperty("compose.application.resources.dir", sqliteDir.parentFile.absolutePath)
            useBundledSqlite()
            assertEquals(sqliteDir.absolutePath, System.getProperty("org.sqlite.lib.path"))
            assertEquals("sqlitejdbc.dll", System.getProperty("org.sqlite.lib.name"))

            val nativesDir = PikoHome.root.resolve("cache/natives").toFile()
            val beforeDlls = nativesDir.listFiles()
                ?.map { it.name }
                ?.filter { it.endsWith("sqlitejdbc.dll", ignoreCase = true) }
                ?.toSet()
                .orEmpty()

            DesktopPikoTreeDatabaseFactory(tempDbDir).open("bundled-test").use { db ->
                db.query("PRAGMA journal_mode").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("wal", cursor.string(0)?.lowercase())
                }
                db.query("SELECT 1").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1L, cursor.long(0))
                }
            }

            val afterDlls = nativesDir.listFiles()
                ?.map { it.name }
                ?.filter { it.endsWith("sqlitejdbc.dll", ignoreCase = true) }
                ?.toSet()
                .orEmpty()
            // SQLiteJDBCLoader.cleanup() 会清理之前残留的旧 DLL，因此只需断言期间没有新释放的 DLL
            assertEquals(emptySet(), afterDlls - beforeDlls)
        } finally {
            tempDbDir.deleteRecursively()
            restoreProperty("compose.application.resources.dir", prevResourcesDir)
            restoreProperty("org.sqlite.lib.path", prevLibPath)
            restoreProperty("org.sqlite.lib.name", prevLibName)
        }
    }

    @Test
    fun `windows default download cache moves to LocalAppData while preserving legacy partial caches`() = runBlocking<Unit> {
        val tempRoot = Files.createTempDirectory("piko-cache-loc").toFile()
        try {
            val homeRoot = tempRoot.resolve("home/.piko").toPath()
            val localAppData = tempRoot.resolve("Local").absolutePath
            val overrideDir = tempRoot.resolve("custom-cache")

            // Windows 默认安装版走 LocalAppData\PikoCache\download-cache
            assertEquals(
                tempRoot.resolve("Local/PikoCache/download-cache").toPath().toAbsolutePath().normalize(),
                PikoHome.resolveDownloadCacheRoot(
                    isDefault = true,
                    root = homeRoot,
                    osName = "Windows 11",
                    localAppData = localAppData,
                    overridePath = null,
                ),
            )
            // 非默认根目录（piko.home / 便携版）与 macOS / Linux 沿用 root/download-cache
            assertEquals(
                homeRoot.resolve("download-cache"),
                PikoHome.resolveDownloadCacheRoot(
                    isDefault = false,
                    root = homeRoot,
                    osName = "Windows 11",
                    localAppData = localAppData,
                    overridePath = null,
                ),
            )
            assertEquals(
                homeRoot.resolve("download-cache"),
                PikoHome.resolveDownloadCacheRoot(
                    isDefault = true,
                    root = homeRoot,
                    osName = "Linux",
                    localAppData = localAppData,
                    overridePath = null,
                ),
            )
            // 显式覆盖路径优先级最高
            assertEquals(
                overrideDir.toPath().toAbsolutePath().normalize(),
                PikoHome.resolveDownloadCacheRoot(
                    isDefault = true,
                    root = homeRoot,
                    osName = "Windows 11",
                    localAppData = localAppData,
                    overridePath = overrideDir.absolutePath,
                ),
            )

            // Windows 使用 .pack 且不触碰旧目录中的稀疏 .data 文件；非 Windows 稀疏模式保留旧目录续传兜底
            val primaryCache = tempRoot.resolve("primary-cache")
            val legacyCache = tempRoot.resolve("legacy-cache")
            legacyCache.mkdirs()
            val legacyData = legacyCache.resolve("legacy-a.data").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val legacyBlocksOnly = legacyCache.resolve("legacy-b.data").also {
                File("${it.path}.blocks").writeBytes(byteArrayOf(4, 5))
            }
            val legacyPack = legacyCache.resolve("legacy-p.pack").apply { writeBytes(byteArrayOf(6, 7, 8)) }

            val winStorage = DesktopPikoDownloadStorage(
                cacheDirectory = primaryCache,
                legacyCacheDirectory = legacyCache,
                usePackCache = true,
                directoryProvider = { tempRoot.resolve("Downloads") },
            )
            assertEquals(primaryCache.resolve("legacy-a.pack").canonicalFile, File(winStorage.cacheTarget("legacy-a")).canonicalFile)
            assertEquals(legacyPack.canonicalFile, File(winStorage.cacheTarget("legacy-p")).canonicalFile)
            assertEquals(primaryCache.resolve("fresh.pack").canonicalFile, File(winStorage.cacheTarget("fresh")).canonicalFile)
            assertEquals(primaryCache.resolve("probe.pack").canonicalFile, File(winStorage.cacheTarget("probe")).canonicalFile)
            assertTrue(legacyData.isFile)

            val posixStorage = DesktopPikoDownloadStorage(
                cacheDirectory = primaryCache,
                legacyCacheDirectory = legacyCache,
                usePackCache = false,
                directoryProvider = { tempRoot.resolve("Downloads") },
            )
            assertEquals(legacyData.canonicalFile, File(posixStorage.cacheTarget("legacy-a")).canonicalFile)
            assertEquals(legacyBlocksOnly.canonicalFile, File(posixStorage.cacheTarget("legacy-b")).canonicalFile)
            assertEquals(primaryCache.resolve("fresh.data").canonicalFile, File(posixStorage.cacheTarget("fresh")).canonicalFile)
            assertTrue(primaryCache.isDirectory)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun restoreProperty(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
}
