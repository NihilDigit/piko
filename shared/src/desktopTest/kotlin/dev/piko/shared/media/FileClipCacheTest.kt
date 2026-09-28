package dev.piko.shared.media

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileClipCacheTest {
    // 淘汰失效了缓存就只涨不落；按修改时间淘汰，读一次要算作用过
    @Test
    fun `over the cap the least recently used block goes`() = runBlocking {
        val directory = Files.createTempDirectory("clip-cache").toFile()
        try {
            // 放不下三块。超了之后删到上限的八成（200），正好删掉一块：只删最久没用过的那一块
            val cache = FileClipCache(directory, capBytes = 250L)
            val blocks = cache.blocks
            blocks.write("gcid/media", 0, ByteArray(100) { 1 })
            Thread.sleep(20)
            blocks.write("gcid/media", 100, ByteArray(100) { 2 })
            Thread.sleep(20)
            assertNotNull(blocks.read("gcid/media", 0, 100), "reading a block counts as using it")
            Thread.sleep(20)
            blocks.write("other/media", 0, ByteArray(100) { 3 })

            assertNull(blocks.read("gcid/media", 100, 100), "the block read least recently was the one to go")
            assertContentEquals(ByteArray(100) { 1 }, blocks.read("gcid/media", 0, 100))
            assertNull(blocks.read("gcid/media", 0, 50), "a block asked for at another length is not that block")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a record reads back as written`() = runBlocking {
        val directory = Files.createTempDirectory("clip-cache").toFile()
        try {
            val record = ClipRecord("f1", "GCID", "第 1 集.mkv", "p1", 5_000_000_000, "m1", 800_000_000, 188_000, 37_600_000, 61_480)
            FileClipCache(directory).remember("f1_60000_720P", record)
            // 另建一个实例读，确认读的是盘上的而不是内存里的
            val back = assertNotNull(FileClipCache(directory).record("f1_60000_720P"))
            assertEquals(
                listOf(record.fileId, record.gcid, record.name, record.parentId, record.mediaId),
                listOf(back.fileId, back.gcid, back.name, back.parentId, back.mediaId),
            )
            assertEquals(
                listOf(record.originalBytes, record.streamBytes, record.sliceOffset, record.sliceLength, record.sliceStartMs),
                listOf(back.originalBytes, back.streamBytes, back.sliceOffset, back.sliceLength, back.sliceStartMs),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
