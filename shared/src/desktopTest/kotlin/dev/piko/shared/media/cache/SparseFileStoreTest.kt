package dev.piko.shared.media.cache

import dev.piko.shared.smoke.smoke
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SparseFileStoreTest {
    private val block = 256 * 1024

    @Test
    fun `tail first leaves holes and restart resumes only durable blocks`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-sparse").toFile()
        val path = directory.resolve("movie.data").path
        val size = block * 4L + 17
        val store = SparseFileStore(path, "content", size, scope)
        store.write("content", block * 4L, ByteArray(17) { 9 })
        store.write("content", block.toLong(), ByteArray(block) { 7 })
        assertEquals(size, File(path).length())
        assertEquals(block + 17L, store.heldBytes.value)
        assertNull(store.read("content", 0, block))
        assertEquals(listOf(0L until block, block * 2L until block * 4L), store.missing("content", listOf(0L until size)))
        store.close()
        val restored = SparseFileStore(path, "content", size, scope)
        assertEquals(block + 17L, restored.heldBytes.value)
        assertContentEquals(ByteArray(17) { 9 }, restored.read("content", block * 4L, 17))
        restored.close()
        directory.deleteRecursively()
    }

    @Test
    fun `failed data sync never publishes a bitmap`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-sync").toFile()
        val path = directory.resolve("data").path
        val store = SparseFileStore(path, "key", block.toLong(), scope) { throw IOException("sync failed") }
        store.write("key", 0, ByteArray(block))
        assertFailsWith<IOException> { store.close() }
        assertEquals(0, persistedFileBytes(path, "key", block.toLong()))
        directory.deleteRecursively()
    }

    @Test
    fun `truncated data and corrupt or mismatched bitmap never count as complete`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-bitmap").toFile()
        val path = directory.resolve("data").path
        val store = SparseFileStore(path, "key", block * 2L, scope)
        store.write("key", 0, ByteArray(block) { 1 })
        store.write("key", block.toLong(), ByteArray(block) { 2 })
        store.close()
        RandomAccessFile(path, "rw").use { it.setLength(block.toLong()) }
        assertEquals(block.toLong(), persistedFileBytes(path, "key", block * 2L))
        assertEquals(0, persistedFileBytes(path, "other", block * 2L))
        val bitmap = File("$path.blocks")
        bitmap.writeBytes(bitmap.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertEquals(0, persistedFileBytes(path, "key", block * 2L))
        directory.deleteRecursively()
    }

    @Test
    fun `legacy sequential prefix imports complete blocks without trusting a hole`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-prefix").toFile()
        val legacy = directory.resolve("legacy").apply { writeBytes(ByteArray(block + 100) { 4 }) }
        val path = directory.resolve("data").path
        val store = SparseFileStore(path, "key", block * 2L, scope)
        store.importPrefix(legacy.path)
        assertEquals(block.toLong(), store.heldBytes.value)
        assertContentEquals(ByteArray(block) { 4 }, store.read("key", 0, block))
        store.delete()
        assertFalse(File(path).exists())
        assertFalse(File("$path.blocks").exists())
        directory.deleteRecursively()
    }

    @Test
    fun `sparse offsets beyond two gigabytes stay long`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-large").toFile()
        val path = directory.resolve("data").path
        val offset = 3L * 1024 * 1024 * 1024
        val store = SparseFileStore(path, "key", offset + block, scope)
        store.write("key", offset, ByteArray(block) { 5 })
        assertEquals(block.toLong(), store.heldBytes.value)
        assertNull(store.read("key", 0, block))
        assertContentEquals(ByteArray(block) { 5 }, store.read("key", offset, block))
        store.delete()
        directory.deleteRecursively()
    }
}
