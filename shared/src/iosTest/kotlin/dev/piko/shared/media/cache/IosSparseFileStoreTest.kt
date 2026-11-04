@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.media.cache

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosSparseFileStoreTest {
    private val block = 256 * 1024

    @Test
    fun sha256NamesMatchJvm() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fileCacheName(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fileCacheName("abc"))
        assertEquals(64, fileCacheName("account\u0000内容").length)
    }

    @Test
    fun tailFirstLeavesHolesAndRestarts() = fixture { directory, scope ->
        val path = "$directory/movie.data"
        val size = block * 4L + 17
        val store = IosSparseFileStore(path, "content", size, scope)
        store.write("sdk-key", block * 4L, ByteArray(17) { 9 })
        store.write("sdk-key", block.toLong(), ByteArray(block) { 7 })
        assertEquals(block + 17L, store.heldBytes.value)
        assertNull(store.read("sdk-key", 0, block))
        assertEquals(listOf(0L until block, block * 2L until block * 4L), store.missing("sdk-key", listOf(0L until size)))
        assertEquals(0L, persistedFileBytes(path, "content", size))
        store.close()
        val restored = IosSparseFileStore(path, "content", size, scope)
        try {
            assertEquals(block + 17L, restored.heldBytes.value)
            assertContentEquals(ByteArray(17) { 9 }, restored.read("sdk-key", block * 4L, 17))
            assertEquals(block + 17L, persistedFileBytes(path, "content", size))
            assertNull(restored.read("sdk-key", block.toLong(), 2))
        } finally { restored.close() }
    }

    @Test
    fun failedDataSyncNeverPublishesBitmapAndCloseStillCloses() = fixture { directory, scope ->
        val path = "$directory/data"
        val store = IosSparseFileStore(path, "key", block.toLong(), scope) { throw IOException("sync failed") }
        store.write("key", 0, ByteArray(block))
        assertFailsWith<IOException> { store.close() }
        assertEquals(0L, persistedFileBytes(path, "key", block.toLong()))
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$path.ios-blocks"))
        assertFailsWith<IllegalStateException> { store.write("key", 0, ByteArray(block)) }
        store.close() // idempotent even after a failed final flush
        store.delete()
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path))
    }

    @Test
    fun failedReplacementDoesNotResurrectOldIdentityBitmap() = fixture { directory, scope ->
        val path = "$directory/data"
        val old = IosSparseFileStore(path, "old", block.toLong(), scope)
        old.write("sdk", 0, ByteArray(block) { 1 })
        old.close()
        assertEquals(block.toLong(), persistedFileBytes(path, "old", block.toLong()))
        val replacement = IosSparseFileStore(path, "new", block.toLong(), scope) {
            throw IOException("sync failed")
        }
        replacement.write("sdk", 0, ByteArray(block) { 2 })
        assertFailsWith<IOException> { replacement.close() }
        assertEquals(0L, persistedFileBytes(path, "old", block.toLong()))
        assertEquals(0L, persistedFileBytes(path, "new", block.toLong()))
    }

    @Test
    fun truncationCorruptionAndIdentityMismatchRedownload() = fixture { directory, scope ->
        val path = "$directory/data"
        val size = block * 2L
        val store = IosSparseFileStore(path, "key", size, scope)
        store.write("key", 0, ByteArray(block) { 1 })
        store.write("key", block.toLong(), ByteArray(block) { 2 })
        store.close()
        val fd = open(path, O_RDWR)
        assertTrue(fd >= 0)
        try { assertEquals(0, ftruncate(fd, block.toLong())) } finally { close(fd) }
        assertEquals(block.toLong(), persistedFileBytes(path, "key", size))
        assertEquals(0L, persistedFileBytes(path, "other", size))
        assertEquals(0L, persistedFileBytes(path, "key", size + 1))
        overwrite("$path.ios-blocks", byteArrayOf(1, 2, 3))
        assertEquals(0L, persistedFileBytes(path, "key", size))
        val restored = IosSparseFileStore(path, "key", size, scope)
        try {
            assertEquals(listOf(0L until size), restored.missing("key", listOf(0L until size)))
            assertNull(restored.read("key", 0, block))
        } finally { restored.close() }
    }

    @Test
    fun crcRejectsSameLengthBitFlip() = fixture { directory, scope ->
        val path = "$directory/data"
        val store = IosSparseFileStore(path, "key", block.toLong(), scope)
        store.write("key", 0, ByteArray(block))
        store.close()
        val fd = open("$path.ios-blocks", O_RDWR)
        assertTrue(fd >= 0)
        try {
            val offset = lseek(fd, 0, SEEK_END) - 5 // packed bits just before CRC
            byteArrayOf(3).usePinned { assertEquals(1L, pwrite(fd, it.addressOf(0), 1uL, offset)) }
        } finally { close(fd) }
        assertEquals(0L, persistedFileBytes(path, "key", block.toLong()))
    }

    @Test
    fun prefixImportsOnlyCompleteBlocksAndShortFinalBlock() = fixture { directory, scope ->
        val legacy = "$directory/legacy"
        overwrite(legacy, ByteArray(block + 100) { 4 })
        val store = IosSparseFileStore("$directory/data", "key", block * 2L, scope)
        try {
            store.importPrefix(legacy)
            assertEquals(block.toLong(), store.heldBytes.value)
            assertContentEquals(ByteArray(block) { 4 }, store.read("key", 0, block))
        } finally { store.delete() }
        val short = IosSparseFileStore("$directory/short", "key", block + 100L, scope)
        try {
            short.importPrefix(legacy)
            assertEquals(block + 100L, short.heldBytes.value)
            assertContentEquals(ByteArray(100) { 4 }, short.read("key", block.toLong(), 100))
        } finally { short.close() }
    }

    @Test
    fun concurrentWritesAreSerializedAndProgressDoesNotDoubleCount() = fixture { directory, scope ->
        val store = IosSparseFileStore("$directory/data", "key", block * 3L, scope)
        try {
            coroutineScope {
                (0 until 18).map { value -> async(Dispatchers.Default) {
                    val index = value % 3
                    store.write("key", block * index.toLong(), ByteArray(block) { index.toByte() })
                } }.awaitAll()
            }
            assertEquals(block * 3L, store.heldBytes.value)
            assertEquals(emptyList(), store.missing("key", listOf(-10L..Long.MAX_VALUE)))
            store.flush()
            assertEquals(block * 3L, persistedFileBytes(store.path, "key", block * 3L))
        } finally { store.close() }
    }

    @Test
    fun cancelledCallerCanCloseAndClosedStoreRejectsAccess() = fixture { directory, scope ->
        val path = "$directory/data"
        val store = IosSparseFileStore(path, "key", block.toLong(), scope)
        store.write("key", 0, ByteArray(block) { 8 })
        coroutineScope {
            val closer = launch {
                cancel()
                store.close()
            }
            closer.join()
        }
        assertEquals(block.toLong(), persistedFileBytes(path, "key", block.toLong()))
        assertFailsWith<IllegalStateException> { store.read("key", 0, block) }
        assertFailsWith<IllegalStateException> { store.missing("key", listOf(0L until block)) }
        store.close()
    }

    @Test
    fun largeSparseOffsetsAndRangeClipping() = fixture { directory, scope ->
        val offset = 3L * 1024 * 1024 * 1024
        val store = IosSparseFileStore("$directory/data", "key", offset + block, scope)
        try {
            store.write("key", offset, ByteArray(block) { 5 })
            assertEquals(block.toLong(), store.heldBytes.value)
            assertNull(store.read("key", 0, block))
            assertContentEquals(ByteArray(block) { 5 }, store.read("key", offset, block))
            assertEquals(listOf(0L..9L), store.missing("key", listOf(-20L..9L, 10L..5L, offset..Long.MAX_VALUE)))
            assertFailsWith<IllegalArgumentException> { store.write("key", 1, ByteArray(block)) }
            assertFailsWith<IllegalArgumentException> { store.write("key", 0, ByteArray(1)) }
        } finally { store.delete() }
    }

    @Test
    fun emptyExportOwnedFilesAndDeletion() = fixture { directory, scope ->
        val path = "$directory/${fileCacheName("key")}.data"
        val store = IosSparseFileStore(path, "key", 0, scope)
        store.close()
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
        assertEquals(0L, persistedFileBytes(path, "key", 0))
        overwrite("$path.ios-blocks.tmp", byteArrayOf(1))
        overwrite("$directory/unrelated.data", byteArrayOf(2))
        assertEquals(listOf(path), ownedCacheFiles("$directory/probe"))
        val destination = "$directory/nested/copy"
        copyCachedFile(path, destination)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(destination))
        copyCachedFile(destination, destination)
        overwrite(path, byteArrayOf(7, 8, 9))
        copyCachedFile(path, destination)
        val fd = open(destination, O_RDONLY)
        try {
            val bytes = ByteArray(3)
            bytes.usePinned { assertEquals(3L, pread(fd, it.addressOf(0), 3uL, 0)) }
            assertContentEquals(byteArrayOf(7, 8, 9), bytes)
        } finally { close(fd) }
        deleteCacheFiles(path)
        deleteCacheFiles(path)
        assertTrue(ownedCacheFiles("$directory/probe").isEmpty())
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$directory/unrelated.data"))
    }

    private fun fixture(test: suspend (String, CoroutineScope) -> Unit): Unit = runBlocking {
        val directory = "${NSTemporaryDirectory().trimEnd('/')}/piko-sparse-${NSUUID().UUIDString}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        try { test(directory, scope) } finally {
            withContext(NonCancellable) {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                assertTrue(NSFileManager.defaultManager.removeItemAtPath(directory, null))
            }
        }
    }

    private fun overwrite(path: String, bytes: ByteArray) {
        val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 384)
        assertTrue(fd >= 0)
        try {
            if (bytes.isNotEmpty()) bytes.usePinned {
                assertEquals(bytes.size.toLong(), pwrite(fd, it.addressOf(0), bytes.size.toULong(), 0))
            }
        } finally { close(fd) }
    }
}
