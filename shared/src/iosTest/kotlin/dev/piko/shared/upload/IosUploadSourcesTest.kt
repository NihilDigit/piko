@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.upload

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Foundation/POSIX integration, no credentials or network. Requires macOS/iOS simulator. */
class IosUploadSourcesTest {
    @Test fun factoryAndQueriesDoNotCreateSourceDirectories() = fixture { dirs ->
        val sources = createIosUploadSources(dirs)
        assertNull(sources.describe(sources.uriFor("missing")))
        assertNull(sources.listFolder(sources.uriFor("")))
        assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
        assertFalse(SystemFileSystem.exists(Path(dirs.caches)))
    }

    @Test fun metadataAndRandomCidProbesUseRealFiles() = fixture { dirs ->
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        write(dirs, "folder/视频 100%.bin", bytes)
        val sources = createIosUploadSources(dirs)
        val uri = sources.uriFor("folder/视频 100%.bin")
        val info = sources.describe(uri)!!
        assertEquals("视频 100%.bin", info.name)
        assertEquals(bytes.size.toLong(), info.size)
        assertTrue(info.lastModifiedMs > 0)
        for (offset in listOf(0, 50_000, 100_000)) {
            assertContentEquals(bytes.copyOfRange(offset, offset + 20_000), sources.readAt(uri, offset.toLong(), 20_000))
        }
        sources.release(uri)
        assertTrue(SystemFileSystem.exists(Path(dirs.downloads, "folder/视频 100%.bin")))
    }

    @Test fun resumeStreamsHaveIndependentDescriptorsAndPositions() = fixture { dirs ->
        write(dirs, "letters", "abcdefghij".encodeToByteArray())
        val sources = createIosUploadSources(dirs)
        val uri = sources.uriFor("letters")
        sources.open(uri, 4).buffered().use { first ->
            sources.open(uri, 1).buffered().use { second ->
                assertContentEquals("ef".encodeToByteArray(), first.readByteArray(2))
                assertContentEquals("bcdefghij".encodeToByteArray(), second.readByteArray())
                assertContentEquals("ghij".encodeToByteArray(), first.readByteArray())
            }
        }
    }

    @Test fun emptyFileAndEndOfFileReadContracts() = fixture { dirs ->
        write(dirs, "empty", byteArrayOf())
        val sources = createIosUploadSources(dirs)
        val uri = sources.uriFor("empty")
        assertContentEquals(byteArrayOf(), sources.readAt(uri, 0, 0))
        sources.open(uri, 0).use { source ->
            assertEquals(0L, source.readAtMostTo(Buffer(), 0))
            assertEquals(-1L, source.readAtMostTo(Buffer(), 1))
        }
        assertFailsWith<IOException> { sources.readAt(uri, 0, 1) }
    }

    @Test fun invalidRangesAndClosedStreamsFail() = fixture { dirs ->
        write(dirs, "bytes", byteArrayOf(1, 2, 3))
        val sources = createIosUploadSources(dirs)
        val uri = sources.uriFor("bytes")
        assertFailsWith<IllegalArgumentException> { sources.open(uri, -1) }
        assertFailsWith<IllegalArgumentException> { sources.readAt(uri, 0, -1) }
        assertFailsWith<IOException> { sources.open(uri, Long.MAX_VALUE) }
        assertFailsWith<IOException> { sources.readAt(uri, 2, 2) }
        val source = sources.open(uri, 1)
        assertFailsWith<IllegalArgumentException> { source.readAtMostTo(Buffer(), -1) }
        source.close()
        source.close()
        assertFailsWith<IllegalStateException> { source.readAtMostTo(Buffer(), 1) }
    }

    @Test fun hierarchyEnumerationSkipsSymlinksAndCycles() = fixture { dirs ->
        write(dirs, "selected/top", byteArrayOf(1))
        write(dirs, "selected/nested/deep", byteArrayOf(2))
        val sources = createIosUploadSources(dirs)
        val selected = "${dirs.downloads}/selected"
        assertEquals(0, symlink(selected, "$selected/cycle"))
        assertEquals(0, symlink("$selected/top", "$selected/alias"))
        assertEquals(0, symlink("$selected/missing", "$selected/dangling"))
        assertEquals(setOf(
            UploadTreeEntry(sources.uriFor("selected/top"), ""),
            UploadTreeEntry(sources.uriFor("selected/nested/deep"), "nested"),
        ), sources.listFolder(sources.uriFor("selected"))!!.files.toSet())
        assertFailsWith<IllegalArgumentException> { sources.uriFor("selected/alias") }
        assertFailsWith<IllegalArgumentException> { sources.uriFor("selected/dangling") }
    }

    @Test fun savedIdentifiersResolveAfterContainerMove() = fixture { dirs ->
        write(dirs, "nested/file", byteArrayOf(7, 8, 9))
        val uri = createIosUploadSources(dirs).uriFor("nested/file")
        assertFalse(uri.contains(dirs.downloads))
        val relocated = dirs.copy(downloads = "${dirs.support}/relocated")
        SystemFileSystem.createDirectories(Path(dirs.support))
        SystemFileSystem.atomicMove(Path(dirs.downloads), Path(relocated.downloads))
        assertContentEquals(byteArrayOf(7, 8, 9), createIosUploadSources(relocated).readAt(uri, 0, 3))
        assertFalse(SystemFileSystem.exists(Path(dirs.downloads)))
    }

    @Test fun vanishedFilesAreNotDescribedAsEmptyFiles() = fixture { dirs ->
        write(dirs, "vanishing", byteArrayOf(1))
        val sources = createIosUploadSources(dirs)
        val uri = sources.uriFor("vanishing")
        SystemFileSystem.delete(Path(dirs.downloads, "vanishing"))
        assertNull(sources.describe(uri))
        assertFailsWith<IOException> { sources.open(uri, 0) }
    }

    private fun write(dirs: IosAppDirectories, relative: String, bytes: ByteArray) {
        val path = Path(dirs.downloads, relative)
        SystemFileSystem.createDirectories(checkNotNull(path.parent))
        SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    }

    private fun fixture(block: (IosAppDirectories) -> Unit) {
        val id = NSUUID().UUIDString
        val prefix = NSTemporaryDirectory().trimEnd('/') + "/piko-upload-"
        val root = prefix + id
        try {
            block(IosAppDirectories("$root/support", "$root/cache", "$root/documents"))
        } finally {
            check(root == prefix + id && root.startsWith(prefix))
            if (NSFileManager.defaultManager.fileExistsAtPath(root)) {
                check(NSFileManager.defaultManager.removeItemAtPath(root, error = null))
            }
        }
    }
}
