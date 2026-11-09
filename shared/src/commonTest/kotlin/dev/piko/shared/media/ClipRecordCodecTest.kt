package dev.piko.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipRecordCodecTest {
    private fun record(
        fileId: String = "VmRlc2t0b3A",
        gcid: String = "LT1abcDEF23",
        name: String = "第一集.mkv",
        parentId: String = "",
        originalBytes: Long = 1_500_000_000L,
        mediaId: String = "media-1",
        streamBytes: Long = 1_400_000_000L,
        sliceOffset: Long = 4096,
        sliceLength: Long = 65_536,
        sliceStartMs: Long = 12_345,
    ) = ClipRecord(
        fileId = fileId,
        gcid = gcid,
        name = name,
        parentId = parentId,
        originalBytes = originalBytes,
        mediaId = mediaId,
        streamBytes = streamBytes,
        sliceOffset = sliceOffset,
        sliceLength = sliceLength,
        sliceStartMs = sliceStartMs,
    )

    @Test
    fun roundTripsEveryField() {
        val original = record()
        val decoded = ClipRecordCodec.decode(ClipRecordCodec.encode(original))
        assertEquals(original.fileId, decoded?.fileId)
        assertEquals(original.gcid, decoded?.gcid)
        assertEquals(original.name, decoded?.name)
        assertEquals(original.parentId, decoded?.parentId)
        assertEquals(original.originalBytes, decoded?.originalBytes)
        assertEquals(original.mediaId, decoded?.mediaId)
        assertEquals(original.streamBytes, decoded?.streamBytes)
        assertEquals(original.sliceOffset, decoded?.sliceOffset)
        assertEquals(original.sliceLength, decoded?.sliceLength)
        assertEquals(original.sliceStartMs, decoded?.sliceStartMs)
    }

    // 文件名里的换行与反斜杠不能把字段读错位
    @Test
    fun roundTripsEscapedCharacters() {
        val original = record(name = "a\\b\nc\rd", parentId = "x\\ny")
        val decoded = ClipRecordCodec.decode(ClipRecordCodec.encode(original))
        assertEquals("a\\b\nc\rd", decoded?.name)
        assertEquals("x\\ny", decoded?.parentId)
    }

    @Test
    fun roundTripsExtremeNumbers() {
        val original = record(originalBytes = Long.MAX_VALUE, streamBytes = 0, sliceStartMs = -1)
        val decoded = ClipRecordCodec.decode(ClipRecordCodec.encode(original))
        assertEquals(Long.MAX_VALUE, decoded?.originalBytes)
        assertEquals(0L, decoded?.streamBytes)
        assertEquals(-1L, decoded?.sliceStartMs)
    }

    @Test
    fun rejectsOtherVersions() {
        val text = ClipRecordCodec.encode(record()).replaceFirst("v${ClipRecordCodec.VERSION}", "v1")
        assertNull(ClipRecordCodec.decode(text))
    }

    @Test
    fun rejectsMissingFields() {
        val text = ClipRecordCodec.encode(record())
        assertNull(ClipRecordCodec.decode(text.substringBeforeLast('\n')))
        assertNull(ClipRecordCodec.decode(""))
    }

    @Test
    fun rejectsNonNumericFields() {
        val text = ClipRecordCodec.encode(record()) + "\n0"
        assertNull(ClipRecordCodec.decode(text))
        val broken = ClipRecordCodec.encode(record()).replace("\n12345", "\n12x45")
        assertNull(ClipRecordCodec.decode(broken))
    }

    @Test
    fun rejectsUnknownEscapes() {
        val text = ClipRecordCodec.encode(record()).replace("第一集.mkv", "a\\qb")
        assertNull(ClipRecordCodec.decode(text))
        // 末尾单个反斜杠也算坏记录
        val dangling = ClipRecordCodec.encode(record()).replace("第一集.mkv", "ab\\")
        assertNull(ClipRecordCodec.decode(dangling))
    }

    @Test
    fun safeNameKeepsOnlyFileNameCharacters() {
        assertEquals("LT1abcDEF23", clipCacheSafeName("LT1abcDEF23"))
        assertEquals("media_1", clipCacheSafeName("media/1"))
        assertEquals("ab____c", clipCacheSafeName("ab/../c"))
        assertEquals("___", clipCacheSafeName("中文/"))
        assertEquals("a-b_c", clipCacheSafeName("a-b_c"))
    }
}
