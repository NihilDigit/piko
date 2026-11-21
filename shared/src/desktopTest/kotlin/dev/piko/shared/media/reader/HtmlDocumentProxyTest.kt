package dev.piko.shared.media.reader

import dev.piko.shared.media.proxy.DocumentProxyResolver
import dev.piko.shared.media.proxy.DocumentProxyResource
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.documentMimeTypeFor
import dev.piko.shared.media.proxy.normalizeDocumentRelativePath
import dev.piko.shared.media.testing.request
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlDocumentProxyTest {
    private val proxy = PikoMediaProxy()

    @AfterTest
    fun tearDown() = proxy.close()

    @Test
    fun normalizesValidRelativePathsAndBlocksTraversal() {
        assertEquals(listOf("index.html"), normalizeDocumentRelativePath("index.html"))
        assertEquals(listOf("assets", "style.css"), normalizeDocumentRelativePath("assets/style.css?v=2#top"))
        assertEquals(listOf("assets", "logo.png"), normalizeDocumentRelativePath("assets/sub/.././logo.png"))
        assertEquals(listOf("文档", "说明.html"), normalizeDocumentRelativePath("%E6%96%87%E6%A1%A3/%E8%AF%B4%E6%98%8E.html"))

        // Directory traversal & dangerous inputs must all be rejected
        assertNull(normalizeDocumentRelativePath(""))
        assertNull(normalizeDocumentRelativePath("../secret.txt"))
        assertNull(normalizeDocumentRelativePath("assets/../../secret.txt"))
        assertNull(normalizeDocumentRelativePath("%2e%2e/secret.txt"))
        assertNull(normalizeDocumentRelativePath("assets/%2e%2e/%2e%2e/secret.txt"))
        assertNull(normalizeDocumentRelativePath("assets\\secret.txt"))
        assertNull(normalizeDocumentRelativePath("assets%5csecret.txt"))
        assertNull(normalizeDocumentRelativePath("index.html%00.png"))
        assertNull(normalizeDocumentRelativePath("bad%ZZname.html"))
        assertNull(normalizeDocumentRelativePath("C:/Windows/win.ini"))
    }

    @Test
    fun servesEntryAndSiblingAssetsWithStrictSecurityHeadersAndBlocksTraversal() = runBlocking {
        val htmlBytes = "<!doctype html><html><link rel=\"stylesheet\" href=\"assets/app.css\"><body>Hello</body></html>".toByteArray()
        val cssBytes = "body { color: #000; }".toByteArray()
        val files = mapOf(
            listOf("index.html") to DocumentProxyResource(htmlBytes, documentMimeTypeFor("index.html", htmlBytes)),
            listOf("assets", "app.css") to DocumentProxyResource(cssBytes, documentMimeTypeFor("app.css", cssBytes)),
        )
        val handle = proxy.registerDocument(
            entryFileName = "index.html",
            resolver = DocumentProxyResolver { segments -> files[segments] },
        )

        val uri = URI(handle.url)
        assertEquals("127.0.0.1", uri.host)
        assertTrue(uri.rawPath.startsWith("/doc/"))
        val token = uri.rawPath.removePrefix("/doc/").substringBefore('/')
        assertTrue(token.length >= 64, "Document session token should be high-entropy 256-bit hex")
        assertTrue(handle.url.startsWith(handle.allowedUrlPrefix))

        // 1. Entry HTML served with proper headers and NO wildcard CORS
        val entryResp = request(handle.url)
        assertEquals(200, entryResp.status)
        assertEquals("text/html; charset=utf-8", entryResp.headers["content-type"])
        assertEquals("nosniff", entryResp.headers["x-content-type-options"])
        assertEquals("no-referrer", entryResp.headers["referrer-policy"])
        assertFalse(entryResp.headers.containsKey("access-control-allow-origin"))
        assertContentEquals(htmlBytes, entryResp.body)

        // 2. Sibling relative asset in subfolder
        val cssResp = request("${handle.allowedUrlPrefix}assets/app.css?v=1")
        assertEquals(200, cssResp.status)
        assertEquals("text/css; charset=utf-8", cssResp.headers["content-type"])
        assertFalse(cssResp.headers.containsKey("access-control-allow-origin"))
        assertContentEquals(cssBytes, cssResp.body)

        // 3. HEAD and Range support
        val headResp = request("${handle.allowedUrlPrefix}assets/app.css", method = "HEAD")
        assertEquals(200, headResp.status)
        assertEquals(cssBytes.size.toString(), headResp.headers["content-length"])
        assertEquals(0, headResp.body.size)

        val rangeResp = request("${handle.allowedUrlPrefix}assets/app.css", range = "bytes=0-3")
        assertEquals(206, rangeResp.status)
        assertContentEquals("body".toByteArray(), rangeResp.body)

        // 4. Directory traversal blocked with 403
        val traversalResp = request("${handle.allowedUrlPrefix}../other.txt")
        assertEquals(403, traversalResp.status)

        val encodedTraversalResp = request("${handle.allowedUrlPrefix}%2e%2e/other.txt")
        assertEquals(403, encodedTraversalResp.status)

        // 5. Missing sibling returns 404
        val missingResp = request("${handle.allowedUrlPrefix}missing.js")
        assertEquals(404, missingResp.status)

        // 6. Unknown token returns 404
        val badTokenResp = request("http://127.0.0.1:${uri.port}/doc/not-a-valid-token/index.html")
        assertEquals(404, badTokenResp.status)

        // 7. Closed session returns 404
        handle.close()
        val closedResp = request(handle.url)
        assertEquals(404, closedResp.status)
    }
}
