package dev.piko.shared.media.cache

import dev.piko.shared.smoke.smoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackFileStoreTest {
    private val block = 256 * 1024

    @Test
    fun `concurrent out of order writes append compactly and export reconstructs exact logical order`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-concurrent").toFile()
        try {
            val key = "acct\u0000GCID123\u0000origin"
            val path = directory.resolve("${fileCacheName(key)}.pack").path
            val tailBytes = 12345
            val totalBlocks = 17
            val size = block * 16L + tailBytes
            val expectedBlocks = Array(totalBlocks) { idx ->
                val len = if (idx == totalBlocks - 1) tailBytes else block
                ByteArray(len) { b -> ((idx * 31 + b) and 0xFF).toByte() }
            }

            val store = persistentFileStore(path, key, size, scope, durable = true)
            assertTrue(store is PackFileStore)

            // 8 条协程逆序/交错写入全部逻辑块，物理文件始终只按实际已写块大小紧凑增长（远小于大跨度稀疏逻辑长度）
            val shuffled = (0 until totalBlocks).reversed()
            shuffled.chunked(2).map { pair ->
                scope.launch(Dispatchers.IO) {
                    for (idx in pair) {
                        store.write(key, idx.toLong() * block, expectedBlocks[idx])
                    }
                }
            }.joinAll()

            assertEquals(size, store.heldBytes.value)
            assertTrue(store.missing(key, listOf(0L until size)).isEmpty())
            assertFalse(File("$path.blocks").exists())

            val exported = directory.resolve("exported.mp4")
            store.flush()
            store.exportTo(exported.path)
            store.close()

            assertEquals(size, persistedFileBytes(path, key, size))
            assertEquals(size, exported.length())
            val actualBytes = exported.readBytes()
            var cursor = 0
            for (idx in 0 until totalBlocks) {
                val expected = expectedBlocks[idx]
                assertContentEquals(expected, actualBytes.copyOfRange(cursor, cursor + expected.size))
                cursor += expected.size
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `salvages uncheckpointed records and abandons torn tail without truncating file`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-salvage").toFile()
        try {
            val key = "salvage-key"
            val size = block * 5L + 77
            val path = directory.resolve("${fileCacheName(key)}.pack").path

            val store = PackFileStore(path, key, size, scope, durable = false)
            // 先写第 4 块并手动刷一次检查点，再在 durable=false 下追加第 1 块与末块（不写新检查点）
            store.write(key, block * 4L, ByteArray(block) { 4 })
            store.flush()
            store.write(key, block.toLong(), ByteArray(block) { 1 })
            store.write(key, block * 5L, ByteArray(77) { 9 })
            store.close()

            // 在文件末尾人为追加半截损坏残帧（模拟进程崩溃未写完的记录，且含伪造 PKFT 魔数干扰反向扫描）
            val tornGarbage = ByteArray(193) { 0x5A }
            tornGarbage[10] = 0x50; tornGarbage[11] = 0x4B; tornGarbage[12] = 0x46; tornGarbage[13] = 0x54 // 伪造 "PKFT"
            RandomAccessFile(path, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write(tornGarbage)
            }
            val lengthWithTornTail = File(path).length()

            // persistedFileBytes 与重新打开均应恢复出检查点内的第 4 块 + 检查点后的第 1 块与末块，且绝不截断文件
            assertEquals(block * 2L + 77L, persistedFileBytes(path, key, size))
            assertEquals(lengthWithTornTail, File(path).length())

            val reopened = PackFileStore(path, key, size, scope, durable = true)
            assertEquals(block * 2L + 77L, reopened.heldBytes.value)
            assertContentEquals(ByteArray(block) { 4 }, reopened.read(key, block * 4L, block))
            assertContentEquals(ByteArray(block) { 1 }, reopened.read(key, block.toLong(), block))
            assertContentEquals(ByteArray(77) { 9 }, reopened.read(key, block * 5L, 77))
            assertNull(reopened.read(key, 0L, block))

            // 继续追加第 0 块：文件从原物理末尾继续增长（不发生 truncate），新检查点封住旧残帧
            reopened.write(key, 0L, ByteArray(block) { 2 })
            reopened.close()
            assertTrue(File(path).length() > lengthWithTornTail)
            assertEquals(block * 3L + 77L, persistedFileBytes(path, key, size))

            val third = PackFileStore(path, key, size, scope, durable = true)
            assertContentEquals(ByteArray(block) { 2 }, third.read(key, 0L, block))
            assertContentEquals(ByteArray(block) { 1 }, third.read(key, block.toLong(), block))
            assertContentEquals(ByteArray(block) { 4 }, third.read(key, block * 4L, block))
            assertContentEquals(ByteArray(77) { 9 }, third.read(key, block * 5L, 77))
            third.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt uncheckpointed payload is rejected without admitting bad bytes`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-corrupt").toFile()
        try {
            val key = "corrupt-tail"
            val size = block * 3L
            val path = directory.resolve("${fileCacheName(key)}.pack").path

            val store = PackFileStore(path, key, size, scope, durable = false)
            store.write(key, 0L, ByteArray(block) { 11 })
            store.flush()
            val afterCheckpointLen = File(path).length()
            store.write(key, block.toLong(), ByteArray(block) { 22 })
            store.close()

            // 篡改未进检查点的第 1 块载荷尾字节，触发 payloadCrc32 校验失败
            RandomAccessFile(path, "rw").use { raf ->
                val corruptPos = afterCheckpointLen + 60L
                raf.seek(corruptPos)
                val orig = raf.readByte()
                raf.seek(corruptPos)
                raf.writeByte(orig.toInt() xor 0xFF)
            }

            assertEquals(block.toLong(), persistedFileBytes(path, key, size))
            val reopened = PackFileStore(path, key, size, scope)
            assertEquals(block.toLong(), reopened.heldBytes.value)
            assertContentEquals(ByteArray(block) { 11 }, reopened.read(key, 0L, block))
            assertNull(reopened.read(key, block.toLong(), block))
            reopened.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `mismatched key or size resets cleanly and prune ignores legacy sparse data files`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-mismatch").toFile()
        try {
            val keyA = "key-a"
            val size = block * 2L
            val packName = "${"a".repeat(64)}.pack"
            val legacySparseName = "${"b".repeat(64)}.data"
            val path = directory.resolve(packName).path
            val legacyPath = directory.resolve(legacySparseName)
            legacyPath.writeBytes(byteArrayOf(1, 2, 3))
            File("${legacyPath.path}.blocks").writeBytes(byteArrayOf(4, 5))

            val store = PackFileStore(path, keyA, size, scope)
            store.write(keyA, 0L, ByteArray(block) { 3 })
            store.close()

            assertEquals(block.toLong(), persistedFileBytes(path, keyA, size))
            assertEquals(0L, persistedFileBytes(path, "key-b", size))
            assertEquals(0L, persistedFileBytes(path, keyA, size + 1L))

            // .pack 模式的 ownedCacheFiles 只枚举 .pack，绝不触碰遗留 .data 稀疏文件
            assertEquals(listOf(path), ownedCacheFiles(directory.resolve("probe.pack").path))
            assertTrue(legacyPath.isFile)

            val replaced = PackFileStore(path, "key-b", size, scope)
            assertEquals(0L, replaced.heldBytes.value)
            replaced.write("key-b", block.toLong(), ByteArray(block) { 6 })
            replaced.close()

            assertEquals(0L, persistedFileBytes(path, keyA, size))
            assertEquals(block.toLong(), persistedFileBytes(path, "key-b", size))

            deleteCacheFiles(path)
            assertFalse(File(path).exists())
            assertTrue(legacyPath.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `logical random access source reads across block boundaries and fails on missing blocks`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-source").toFile()
        try {
            val key = "remux-stream"
            val size = block * 2L + 500
            val path = directory.resolve("stream.pack").path
            val b0 = ByteArray(block) { i -> (i and 0x7F).toByte() }
            val b1 = ByteArray(block) { i -> ((i + 37) and 0x7F).toByte() }
            val b2 = ByteArray(500) { i -> ((i + 91) and 0x7F).toByte() }

            val store = PackFileStore(path, key, size, scope)
            // 先只写 b0 与 b2，空出中间的 b1：跨进缺失块必须抛 IOException，绝不填零
            store.write(key, 0L, b0)
            store.write(key, block * 2L, b2)

            val factory = checkNotNull(store.logicalSourceFactory())
            factory().use { src ->
                assertEquals(size, src.size)
                assertFailsWith<IOException> {
                    src.readAt(block - 16L, ByteArray(64), 0, 64)
                }
            }

            // 补齐 b1 后，任意非对齐跨块读取与末尾截断读取均精确一致
            store.write(key, block.toLong(), b1)
            val fullLogical = b0 + b1 + b2
            factory().use { src ->
                val sliceStart = block - 137
                val sliceLen = block + 400
                val out = ByteArray(sliceLen + 10)
                val n = src.readAt(sliceStart.toLong(), out, 5, sliceLen)
                assertEquals(sliceLen, n)
                assertContentEquals(
                    fullLogical.copyOfRange(sliceStart, sliceStart + sliceLen),
                    out.copyOfRange(5, 5 + sliceLen),
                )

                val tailBuf = ByteArray(1024)
                val tailRead = src.readAt(size - 120L, tailBuf, 0, tailBuf.size)
                assertEquals(120, tailRead)
                assertContentEquals(fullLogical.copyOfRange((size - 120L).toInt(), size.toInt()), tailBuf.copyOfRange(0, 120))
                assertEquals(-1, src.readAt(size, tailBuf, 0, 16))
                assertEquals(0, src.readAt(size, tailBuf, 0, 0))
            }

            store.delete()
            assertFalse(File(path).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `zero byte pack store flushes exports and reads cleanly`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-pack-empty").toFile()
        try {
            val key = "empty-file"
            val path = directory.resolve("empty.pack").path
            val exported = directory.resolve("empty.bin")
            val store = PackFileStore(path, key, 0L, scope)
            store.flush()
            assertTrue(File(path).isFile)
            assertEquals(0L, persistedFileBytes(path, key, 0L))
            store.exportTo(exported.path)
            assertTrue(exported.isFile)
            assertEquals(0L, exported.length())
            store.logicalSourceFactory().invoke().use { src ->
                assertEquals(0L, src.size)
                assertEquals(-1, src.readAt(0L, ByteArray(8), 0, 8))
            }
            store.delete()
            assertFalse(File(path).exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
