package dev.piko.shared.media.cache

import dev.piko.shared.smoke.smoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `cache pool preserves retained and active files during completion and pruning`() = smoke { scope ->
        val root = Files.createTempDirectory("piko-cache-pool").toFile()
        val cloud = dev.piko.shared.media.testing.FakePikPakCloud(ByteArray(262144))
        val client = cloud.provider.currentClient.value!!
        val pool = PikoFileCachePool(scope) { root.resolve("$it.data").path }
        val first = pool.acquire(client, "f1", "GCID", 262144, "one", retained = true, owner = "one")
        pool.remember(client.account, "GCID", 262144, "two")
        first.entry.store.write("key", 0, ByteArray(262144))
        pool.complete(first, "one")
        val saved = File(first.entry.store.path)
        first.release()
        kotlin.test.assertTrue(saved.exists())
        val orphan = root.resolve("${"a".repeat(64)}.data").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = root.resolve("notes.data").apply { writeBytes(byteArrayOf(2)) }
        pool.prune()
        kotlin.test.assertTrue(saved.exists())
        assertFalse(orphan.exists())
        kotlin.test.assertTrue(unrelated.exists())
        val playing = pool.acquire(client, "f1", "GCID", 262144, "one")
        pool.discard(client.account, "GCID", 262144, "two")
        pool.prune()
        kotlin.test.assertTrue(saved.exists())
        playing.release()
        assertFalse(saved.exists())
        assertFalse(File("${saved.path}.blocks").exists())
        root.deleteRecursively()
    }

    @Test
    fun `ephemeral stores skip bitmap writes until promoted and delete skips sync`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-ephemeral").toFile()
        try {
            val ephemeralPath = directory.resolve("ephemeral.data").path
            val ephemeral = SparseFileStore(ephemeralPath, "eph", block.toLong(), scope, durable = false)
            ephemeral.write("eph", 0, ByteArray(block) { 3 })
            assertEquals(block.toLong(), ephemeral.heldBytes.value)
            ephemeral.close()
            assertTrue(File(ephemeralPath).exists())
            assertFalse(File("$ephemeralPath.blocks").exists())
            assertEquals(0L, persistedFileBytes(ephemeralPath, "eph", block.toLong()))

            val promoted = SparseFileStore(ephemeralPath, "eph", block.toLong(), scope, durable = false)
            promoted.write("eph", 0, ByteArray(block) { 4 })
            promoted.setDurable(true)
            promoted.close()
            assertTrue(File("$ephemeralPath.blocks").exists())
            assertEquals(block.toLong(), persistedFileBytes(ephemeralPath, "eph", block.toLong()))

            var syncCalls = 0
            val discardPath = directory.resolve("discard.data").path
            val discardStore = SparseFileStore(discardPath, "dis", block.toLong(), scope, durable = true) {
                syncCalls++
                throw IOException("sync must not run on delete")
            }
            discardStore.write("dis", 0, ByteArray(block) { 5 })
            File("$discardPath.blocks.tmp").writeBytes(byteArrayOf(1))
            discardStore.delete()
            assertEquals(0, syncCalls)
            assertFalse(File(discardPath).exists())
            assertFalse(File("$discardPath.blocks").exists())
            assertFalse(File("$discardPath.blocks.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `two slot ping pong alternates without temp files and recovers when slot 0 header is torn`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pingpong").toFile()
        try {
            val path = directory.resolve("video.data").path
            val size = block * 3L
            val layout = SparseFileStore.layoutFor("key", size)
            val bitmap = File("$path.blocks")
            val store = SparseFileStore(path, "key", size, scope)

            store.write("key", 0L, ByteArray(block) { 1 })
            store.flush()
            assertEquals(layout.v2RecordBytes.toLong(), bitmap.length())
            assertFalse(File("$path.blocks.tmp").exists())

            store.write("key", block.toLong(), ByteArray(block) { 2 })
            store.flush()
            val expectedBoundedSize = layout.slotStride.toLong() + layout.v2RecordBytes.toLong()
            assertEquals(expectedBoundedSize, bitmap.length())
            assertFalse(File("$path.blocks.tmp").exists())

            store.write("key", block * 2L, ByteArray(block) { 3 })
            store.flush()
            assertEquals(expectedBoundedSize, bitmap.length())
            assertEquals(block * 3L, persistedFileBytes(path, "key", size))
            store.close()

            // Torn Slot 0 header must fall back cleanly to Slot 1 (which committed blocks 0 and 1 at gen=2)
            RandomAccessFile(bitmap, "rw").use { it.write(ByteArray(16)) }
            assertEquals(block * 2L, persistedFileBytes(path, "key", size))

            // Reopening recovers Slot 1, and next commit overwrites torn Slot 0 at gen=3
            val reopened = SparseFileStore(path, "key", size, scope)
            assertEquals(block * 2L, reopened.heldBytes.value)
            reopened.write("key", block * 2L, ByteArray(block) { 3 })
            reopened.close()
            assertEquals(block * 3L, persistedFileBytes(path, "key", size))

            // Corrupting Slot 1's CRC must still recover newer valid Slot 0 (gen=3)
            RandomAccessFile(bitmap, "rw").use { raf ->
                raf.seek(expectedBoundedSize - 1L)
                raf.write(0x55)
            }
            assertEquals(block * 3L, persistedFileBytes(path, "key", size))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed bitmap force preserves previous checkpoint and retries same target slot`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-bmp-fail").toFile()
        try {
            val path = directory.resolve("data").path
            val size = block * 2L
            val layout = SparseFileStore.layoutFor("key", size)
            val bitmap = File("$path.blocks")
            var failBitmapSync = false
            val store = SparseFileStore(
                path,
                "key",
                size,
                scope,
                syncBitmap = { _, _ -> if (failBitmapSync) throw IOException("bmp force failed") },
            )

            store.write("key", 0L, ByteArray(block) { 1 })
            store.flush()
            assertEquals(block.toLong(), persistedFileBytes(path, "key", size))
            val slot0BeforeRetry = bitmap.readBytes().copyOf(layout.v2RecordBytes)

            failBitmapSync = true
            store.write("key", block.toLong(), ByteArray(block) { 2 })
            assertFailsWith<IOException> { store.flush() }
            assertContentEquals(slot0BeforeRetry, bitmap.readBytes().copyOf(layout.v2RecordBytes))

            failBitmapSync = false
            store.flush()
            assertContentEquals(slot0BeforeRetry, bitmap.readBytes().copyOf(layout.v2RecordBytes))
            assertEquals(block * 2L, persistedFileBytes(path, "key", size))
            store.close()

            // Corrupt Slot 1; Slot 0 must still hold block 0 untouched (retry went to Slot 1, not Slot 0)
            RandomAccessFile(bitmap, "rw").use { raf ->
                raf.seek(layout.slotStride.toLong() + layout.v2RecordBytes.toLong() - 1L)
                raf.write(0x5A)
            }
            assertEquals(block.toLong(), persistedFileBytes(path, "key", size))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy V1 prefix survives interrupted conversion to V2 slot 1 and advances cleanly`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-v1-migrate").toFile()
        try {
            val key = "v1-key"
            val size = block * 3L
            val path = directory.resolve("data").path
            val layout = SparseFileStore.layoutFor(key, size)
            val dataFile = File(path)
            dataFile.writeBytes(ByteArray(block) { 7 } + ByteArray(block * 2))

            val payload = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(0x50494b31)
                    out.writeUTF(key)
                    out.writeLong(size)
                    out.writeLong(block.toLong())
                    out.writeInt(3)
                    out.writeByte(0x01)
                }
            }.toByteArray()
            val v1Bytes = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use { dos ->
                    dos.write(payload)
                    dos.writeLong(CRC32().apply { update(payload) }.value)
                }
            }.toByteArray()

            // Simulate V1 prefix plus torn partial Slot 1 write beyond slotStride
            val bitmap = File("$path.blocks")
            RandomAccessFile(bitmap, "rw").use { raf ->
                raf.write(v1Bytes)
                raf.seek(layout.slotStride.toLong())
                raf.write(ByteArray(64) { 0x7F })
            }
            assertEquals(block.toLong(), persistedFileBytes(path, key, size))

            val store = SparseFileStore(path, key, size, scope)
            assertEquals(block.toLong(), store.heldBytes.value)
            store.write(key, block.toLong(), ByteArray(block) { 8 })
            store.flush()
            // V2 commit #1 landed in Slot 1 while preserving V1 prefix at Slot 0
            assertEquals(block * 2L, persistedFileBytes(path, key, size))

            store.write(key, block * 2L, ByteArray(block) { 9 })
            store.flush()
            // V2 commit #2 overwrote V1 prefix in Slot 0
            assertEquals(block * 3L, persistedFileBytes(path, key, size))
            store.close()

            val reopened = SparseFileStore(path, key, size, scope)
            assertEquals(block * 3L, reopened.heldBytes.value)
            assertContentEquals(ByteArray(block) { 7 }, reopened.read(key, 0L, block))
            assertContentEquals(ByteArray(block) { 8 }, reopened.read(key, block.toLong(), block))
            assertContentEquals(ByteArray(block) { 9 }, reopened.read(key, block * 2L, block))
            reopened.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stale bit resurrection is prevented after data file loss before extending writes`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-stale-resurrect").toFile()
        try {
            val size = block * 4L
            val path = directory.resolve("data").path
            val first = SparseFileStore(path, "key", size, scope)
            first.write("key", 0L, ByteArray(block) { 1 })
            first.write("key", block * 3L, ByteArray(block) { 4 })
            first.close()

            assertTrue(File(path).delete())
            assertEquals(0L, persistedFileBytes(path, "key", size))

            // Opening and extending .data at offset 3*block without flushing must NOT resurrect stale bit 0
            val second = SparseFileStore(path, "key", size, scope, durable = false)
            second.write("key", block * 3L, ByteArray(block) { 4 })
            assertEquals(0L, persistedFileBytes(path, "key", size))
            second.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent flush racing with delete closes handles cleanly without recreating files`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-flush-delete").toFile()
        try {
            val path = directory.resolve("race.data").path
            val size = block * 4L
            val store = SparseFileStore(path, "race", size, scope)
            val flushJobs = (0 until 4).map { index ->
                store.write("race", index.toLong() * block, ByteArray(block) { index.toByte() })
                scope.launch(Dispatchers.IO) {
                    runCatching { store.flush() }
                }
            }
            store.delete()
            flushJobs.joinAll()
            runCatching { store.flush() }
            assertFalse(File(path).exists())
            assertFalse(File("$path.blocks").exists())
            assertFalse(File("$path.blocks.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
