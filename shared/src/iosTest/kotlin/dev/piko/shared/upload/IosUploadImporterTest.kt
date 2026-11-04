@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.upload

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Foundation coordinator + actual common copy + POSIX upload reader. Requires a macOS simulator. */
class IosUploadImporterTest {
    @Test fun selectedFileBecomesIndependentManagedSnapshot() = fixture { dirs, external ->
        val bytes = "视频 contents".encodeToByteArray()
        val selected = write(external, "视频 100%.txt", bytes)
        val uri = importIosUploadDocument(dirs, NSURL.fileURLWithPath(selected.toString()), bytes.size.toLong())
        SystemFileSystem.delete(selected)
        val sources = createIosUploadSources(dirs)
        assertContentEquals(bytes, sources.readAt(uri, 0, bytes.size))
        assertFalse(uri.contains(dirs.downloads))
        assertFalse(uri.contains(external.toString()))
        assertEquals("视频 100%.txt", sources.describe(uri)?.name)
        assertStagingEmpty(dirs)
    }

    @Test fun repeatedSelectionDoesNotOverwriteFirstSnapshot() = fixture { dirs, external ->
        val selected = write(external, ".partial", byteArrayOf(1))
        val url = NSURL.fileURLWithPath(selected.toString())
        val first = importIosUploadDocument(dirs, url, 1)
        write(external, ".partial", byteArrayOf(2))
        val second = importIosUploadDocument(dirs, url, 1)
        assertNotEquals(first, second)
        val sources = createIosUploadSources(dirs)
        assertContentEquals(byteArrayOf(1), sources.readAt(first, 0, 1))
        assertContentEquals(byteArrayOf(2), sources.readAt(second, 0, 1))
        assertStagingEmpty(dirs)
    }

    @Test fun exceededLimitNeverPublishesSelection() = fixture { dirs, external ->
        val selected = write(external, "large.bin", ByteArray(70_000))
        assertFailsWith<IOException> {
            importIosUploadDocument(dirs, NSURL.fileURLWithPath(selected.toString()), 65_536)
        }
        val sources = createIosUploadSources(dirs)
        assertTrue(sources.listFolder(sources.uriFor(""))?.files.orEmpty().isEmpty())
        assertTrue(SystemFileSystem.exists(selected))
        assertStagingEmpty(dirs)
    }

    @Test fun directoryAndNetworkSelectionsAreRejected() = fixture { dirs, external ->
        SystemFileSystem.createDirectories(external)
        assertFailsWith<IOException> { importIosUploadDocument(dirs, NSURL.fileURLWithPath(external.toString()), 1) }
        assertFailsWith<IllegalArgumentException> { importIosUploadDocument(dirs, NSURL(string = "https://example.invalid/file"), 1) }
        assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
    }

    @Test fun negativeLimitFailsWithoutCreatingImportDirectories() = fixture { dirs, external ->
        val selected = write(external, "file", byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { importIosUploadDocument(dirs, NSURL.fileURLWithPath(selected.toString()), -1) }
        assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
        assertFalse(SystemFileSystem.exists(Path(dirs.caches)))
    }

    @Test fun stagingAliasIntoUploadTreeIsRejected() = fixture { dirs, external ->
        val selected = write(external, "file", byteArrayOf(1))
        SystemFileSystem.createDirectories(Path(dirs.downloads))
        assertEquals(0, symlink(dirs.downloads, dirs.caches))
        assertFailsWith<IllegalArgumentException> {
            importIosUploadDocument(dirs, NSURL.fileURLWithPath(selected.toString()), 1)
        }
        assertTrue(SystemFileSystem.list(Path(dirs.downloads)).isEmpty())
    }

    @Test fun zeroByteFileAndRelocatedContainerRemainReadable() = fixture { dirs, external ->
        val selected = write(external, "empty", byteArrayOf())
        val uri = importIosUploadDocument(dirs, NSURL.fileURLWithPath(selected.toString()), 0)
        val relocated = dirs.copy(downloads = "${dirs.support}/relocated")
        SystemFileSystem.createDirectories(Path(dirs.support))
        SystemFileSystem.atomicMove(Path(dirs.downloads), Path(relocated.downloads))
        val sources = createIosUploadSources(relocated)
        assertEquals(0L, sources.describe(uri)?.size)
        assertContentEquals(byteArrayOf(), sources.readAt(uri, 0, 0))
        assertStagingEmpty(dirs)
    }

    private fun assertStagingEmpty(dirs: IosAppDirectories) {
        val path = Path(dirs.caches, "upload-imports")
        if (SystemFileSystem.exists(path)) assertTrue(SystemFileSystem.list(path).isEmpty())
    }
    private fun write(directory: Path, name: String, bytes: ByteArray): Path {
        SystemFileSystem.createDirectories(directory)
        return Path(directory, name).also { path ->
            SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
        }
    }
    private fun fixture(block: suspend (IosAppDirectories, Path) -> Unit) = runBlocking {
        val id = NSUUID().UUIDString
        val prefix = NSTemporaryDirectory().trimEnd('/') + "/piko-import-"
        val root = prefix + id
        try {
            block(IosAppDirectories("$root/support", "$root/cache", "$root/documents"), Path(root, "external"))
        } finally {
            check(root == prefix + id && root.startsWith(prefix))
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                check(NSFileManager.defaultManager.removeItemAtPath(root, error = null))
            }
        }
    }
}
