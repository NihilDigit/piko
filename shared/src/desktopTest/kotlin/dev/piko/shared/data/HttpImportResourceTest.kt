package dev.piko.shared.data

import io.ktor.http.Headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HttpImportResourceTest {
    @Test
    fun rangeUsesTotalSizeAndUtf8AttachmentName() {
        val result = assertNotNull(httpResource("https://example.test/download", 206, Headers.build {
            append("Content-Type", "application/octet-stream")
            append("Content-Disposition", "attachment; filename*=UTF-8''%E8%A7%86%E9%A2%91.mkv")
            append("Content-Length", "1")
            append("Content-Range", "bytes 0-0/123456")
        }))
        assertEquals("视频.mkv", result.resource.name)
        assertEquals(123456L, result.items.single().file.size)
        assertFalse(result.items.single().isInstantReady)
        assertNull(result.items.single().file.gcid)
    }

    @Test
    fun partialResponseWithoutTotalDoesNotUseTheProbeByteCount() {
        val result = assertNotNull(httpResource("https://example.test/video.mp4", 206, Headers.build {
            append("Content-Type", "video/mp4")
            append("Content-Length", "1")
            append("Content-Range", "bytes 0-0/*")
        }))
        assertEquals(0L, result.items.single().file.size)
    }

    @Test
    fun rejectsHtmlEvenWhenUrlLooksLikeMedia() {
        assertNull(httpResource("https://example.test/video.mp4", 200, Headers.build {
            append("Content-Type", "text/html; charset=UTF-8")
        }))
        assertNull(httpResource("https://example.test/file.zip", 404, Headers.Empty))
    }

    @Test
    fun knownFileWithoutLengthKeepsUnknownSize() {
        val result = assertNotNull(httpResource("https://example.test/file%20name.zip?token=1", 200, Headers.Empty))
        assertEquals("file name.zip", result.resource.name)
        assertEquals(0L, result.items.single().file.size)
    }
}
