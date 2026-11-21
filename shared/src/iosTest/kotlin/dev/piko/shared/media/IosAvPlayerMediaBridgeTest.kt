package dev.piko.shared.media

import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAvPlayerMediaBridgeTest {
    @Test
    fun mpegTsAndMimeDetectionHandlesQueriesAndCases() {
        assertTrue(isMpegTsUrl("http://127.0.0.1:41234/abc/media.ts"))
        assertTrue(isMpegTsUrl("https://cdn.example.com/v/CLIP.TS?token=1"))
        assertTrue(isMpegTsUrl("/tmp/clip.m2ts"))
        assertFalse(isMpegTsUrl("http://127.0.0.1:41234/abc/media.mp4"))
        assertFalse(isMpegTsUrl("http://127.0.0.1:41234/abc/media"))

        assertEquals("video/mp2t", mimeTypeForMediaUrl("http://127.0.0.1:1234/t/media.ts"))
        assertEquals("video/mp4", mimeTypeForMediaUrl("http://127.0.0.1:1234/t/media.mp4"))
        assertEquals("video/quicktime", mimeTypeForMediaUrl("http://127.0.0.1:1234/t/media.mov"))
        assertEquals("audio/mp4", mimeTypeForMediaUrl("http://127.0.0.1:1234/t/media.m4a"))
        assertEquals("application/octet-stream", mimeTypeForMediaUrl("http://127.0.0.1:1234/t/media"))
    }

    @Test
    fun parseLoopbackAndRewriteHeadersPreserveRangeAndReplaceMime() {
        val target = parseLoopbackUrl("http://127.0.0.1:54321/tok/media.ts")
        assertNotNull(target)
        assertEquals(54321, target.port)
        assertEquals("/tok/media.ts", target.path)
        assertNull(parseLoopbackUrl("https://example.com/media.ts"))
        assertNull(parseLoopbackUrl("http://127.0.0.1:99999/media.ts"))

        val rewritten = rewriteResponseHeaders(
            listOf(
                "Content-Type" to "application/octet-stream",
                "Accept-Ranges" to "bytes",
                "Content-Length" to "1024",
                "Content-Range" to "bytes 0-1023/4096",
                "Connection" to "close",
            ),
            contentType = "video/mp2t",
        )
        assertEquals(
            listOf(
                "Content-Type" to "video/mp2t",
                "Accept-Ranges" to "bytes",
                "Content-Length" to "1024",
                "Content-Range" to "bytes 0-1023/4096",
            ),
            rewritten,
        )
    }

    @Test
    fun vodPlaylistContainsRequiredHlsTags() {
        val playlist = buildVodPlaylist("http://127.0.0.1:8080/abc/segment.ts", durationSeconds = 180L)
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-VERSION:3\n" in playlist)
        assertTrue("#EXT-X-TARGETDURATION:180\n" in playlist)
        assertTrue("#EXT-X-PLAYLIST-TYPE:VOD\n" in playlist)
        assertTrue("#EXTINF:180.0,\nhttp://127.0.0.1:8080/abc/segment.ts\n" in playlist)
        assertTrue(playlist.endsWith("#EXT-X-ENDLIST\n"))
    }

    @Test
    fun bridgeWrapsLoopbackTsIntoHlsPlaylistAndRewritesSegmentMime() = runBlocking {
        val payload = ByteArray(188 * 4) { idx -> (idx % 251).toByte() }
        val proxy = PikoMediaProxy(Dispatchers.IO)
        val bridge = IosAvPlayerMediaBridge(Dispatchers.IO)
        try {
            val stream = proxy.register(MemoryByteSource(payload), fileName = "clip.ts")
            val wrapped = bridge.wrap(stream.url, durationSeconds = 30L)
            try {
                assertTrue(wrapped.isHls)
                assertTrue(wrapped.playbackUrl.endsWith("/playlist.m3u8"))
                val m3u8 = httpGet(wrapped.playbackUrl)
                assertEquals(200, m3u8.status)
                assertEquals(IosAvPlayerMediaBridge.HLS_MIME_TYPE, m3u8.headers["content-type"])
                val bodyText = m3u8.body.decodeToString()
                val segmentLine = bodyText.lineSequence().firstOrNull { it.startsWith("http://127.0.0.1:") }
                assertNotNull(segmentLine)

                val ranged = httpGet(segmentLine, range = "bytes=0-187")
                assertEquals(206, ranged.status)
                assertEquals(IosAvPlayerMediaBridge.VIDEO_MP2T_MIME, ranged.headers["content-type"])
                assertEquals("bytes 0-187/${payload.size}", ranged.headers["content-range"])
                assertContentEquals(payload.copyOfRange(0, 188), ranged.body)
            } finally {
                wrapped.close()
                stream.close()
            }
        } finally {
            bridge.close()
            proxy.close()
        }
    }

    @Test
    fun bridgeRewritesMp4LoopbackMimeType() = runBlocking {
        val payload = "ftypisom0123456789".encodeToByteArray()
        val proxy = PikoMediaProxy(Dispatchers.IO)
        val bridge = IosAvPlayerMediaBridge(Dispatchers.IO)
        try {
            val stream = proxy.register(MemoryByteSource(payload), fileName = "movie.mp4")
            val wrapped = bridge.wrap(stream.url)
            try {
                assertFalse(wrapped.isHls)
                assertTrue(wrapped.playbackUrl.endsWith("/media.mp4"))
                val resp = httpGet(wrapped.playbackUrl)
                assertEquals(200, resp.status)
                assertEquals("video/mp4", resp.headers["content-type"])
                assertContentEquals(payload, resp.body)
            } finally {
                wrapped.close()
                stream.close()
            }
        } finally {
            bridge.close()
            proxy.close()
        }
    }
}

private data class SimpleHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private suspend fun httpGet(url: String, range: String? = null): SimpleHttpResponse {
    val target = requireNotNull(parseLoopbackUrl(url)) { "Expected loopback URL: $url" }
    val selector = SelectorManager(Dispatchers.IO)
    try {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", target.port)
        try {
            val out = socket.openWriteChannel(autoFlush = false)
            val req = buildString {
                append("GET ").append(target.path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(target.port).append("\r\n")
                if (range != null) append("Range: ").append(range).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.writeFully(req.encodeToByteArray())
            out.flush()
            val raw = socket.openReadChannel().readRemaining().readByteArray()
            val text = raw.decodeToString()
            val sep = text.indexOf("\r\n\r\n")
            val headerPart = if (sep >= 0) text.substring(0, sep) else text
            val bodyStart = if (sep >= 0) sep + 4 else raw.size
            val lines = headerPart.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            return SimpleHttpResponse(status, headers, raw.copyOfRange(bodyStart, raw.size))
        } finally {
            socket.close()
        }
    } finally {
        selector.close()
    }
}

private class MemoryByteSource(private val data: ByteArray) : ProxyByteSource {
    override val size: Long get() = data.size.toLong()
    override suspend fun openReader(): ProxyReader = object : ProxyReader {
        private var pos = 0L
        override val position: Long get() = pos
        override suspend fun seekTo(position: Long) {
            pos = position.coerceIn(0L, size)
        }
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= size) return -1
            val count = minOf(length.toLong(), size - pos).toInt()
            data.copyInto(buffer, destinationOffset = offset, startIndex = pos.toInt(), endIndex = pos.toInt() + count)
            pos += count
            return count
        }
        override fun close() = Unit
    }
    override fun close() = Unit
}
