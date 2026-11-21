package dev.piko.shared.media.reader

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.proxy.DocumentProxyResolver
import dev.piko.shared.media.proxy.DocumentProxyResource
import dev.piko.shared.media.proxy.DriveHtmlDocumentResolver
import dev.piko.shared.media.proxy.PikoMediaProxy
import dev.piko.shared.media.proxy.documentMimeTypeFor
import dev.piko.shared.media.proxy.isAllowedDocumentSubresource
import dev.piko.shared.media.proxy.normalizeDocumentRelativePath
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.testing.request
import dev.piko.shared.smoke.MemoryPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        // 1. Entry HTML served with strict CSP + security headers and NO wildcard CORS
        val entryResp = request(handle.url)
        assertEquals(200, entryResp.status)
        assertEquals("text/html; charset=utf-8", entryResp.headers["content-type"])
        assertEquals("nosniff", entryResp.headers["x-content-type-options"])
        assertEquals("no-referrer", entryResp.headers["referrer-policy"])
        val csp = assertNotNull(entryResp.headers["content-security-policy"])
        assertTrue(csp.contains("default-src 'self' data: blob:"), "CSP must lock down default-src: $csp")
        assertTrue(csp.contains("connect-src 'self'"), "CSP must block external exfiltration via connect-src: $csp")
        assertTrue(csp.contains("form-action 'none'") && csp.contains("base-uri 'none'"), "CSP must block form-action and base-uri: $csp")
        assertFalse(entryResp.headers.containsKey("access-control-allow-origin"))
        assertContentEquals(htmlBytes, entryResp.body)

        // 2. Sibling relative asset in subfolder also carries CSP
        val cssResp = request("${handle.allowedUrlPrefix}assets/app.css?v=1")
        assertEquals(200, cssResp.status)
        assertEquals("text/css; charset=utf-8", cssResp.headers["content-type"])
        assertNotNull(cssResp.headers["content-security-policy"])
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

        // 4. Directory traversal and sensitive dot-files / non-web sibling files blocked with 403
        val traversalResp = request("${handle.allowedUrlPrefix}../other.txt")
        assertEquals(403, traversalResp.status)

        val encodedTraversalResp = request("${handle.allowedUrlPrefix}%2e%2e/other.txt")
        assertEquals(403, encodedTraversalResp.status)

        assertEquals(403, request("${handle.allowedUrlPrefix}.piko/settings-1.json").status)
        assertEquals(403, request("${handle.allowedUrlPrefix}.env").status)
        assertEquals(403, request("${handle.allowedUrlPrefix}secrets.sqlite").status)
        assertEquals(403, request("${handle.allowedUrlPrefix}vault.kdbx").status)
        assertEquals(403, request("${handle.allowedUrlPrefix}id_rsa").status)

        // 5. Missing allowed sibling returns 404
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

    @Test
    fun subresourceAllowlistPermitsWebBundlesAndRejectsDotFilesAndSecrets() {
        assertTrue(isAllowedDocumentSubresource(listOf("page_files", "style.css")))
        assertTrue(isAllowedDocumentSubresource(listOf("assets", "bundle.mjs")))
        assertTrue(isAllowedDocumentSubresource(listOf("assets", "engine.wasm")))
        assertTrue(isAllowedDocumentSubresource(listOf("data", "index.json")))
        assertTrue(isAllowedDocumentSubresource(listOf("img", "hero.webp")))
        assertTrue(isAllowedDocumentSubresource(listOf("fonts", "inter.woff2")))

        assertFalse(isAllowedDocumentSubresource(listOf(".piko", "settings-123.json")))
        assertFalse(isAllowedDocumentSubresource(listOf(".git", "config")))
        assertFalse(isAllowedDocumentSubresource(listOf(".env")))
        assertFalse(isAllowedDocumentSubresource(listOf("assets", ".hidden.js")))
        assertFalse(isAllowedDocumentSubresource(listOf("passwords.kdbx")))
        assertFalse(isAllowedDocumentSubresource(listOf("drive-tree.db")))
        assertFalse(isAllowedDocumentSubresource(listOf("private.key")))
        assertFalse(isAllowedDocumentSubresource(listOf("backup.zip")))
        assertFalse(isAllowedDocumentSubresource(listOf("extensionless")))
    }

    @Test
    fun driveResolverBoundsConcurrencyToFourAndCoalescesDuplicateInFlightRequests() = runBlocking {
        val cloud = FakePikPakCloud(origin = "<html></html>".encodeToByteArray())
        val prefs = MemoryPreferences()
        val driveRepo = PikoDriveRepository(cloud.provider, prefs)
        val mediaRepo = PikoMediaRepository(cloud.provider, prefs)
        val resolver = DriveHtmlDocumentResolver(
            entryFileId = "f1",
            entryFileName = "index.html",
            parentFolderId = "root",
            localPath = null,
            driveRepository = driveRepo,
            mediaRepository = mediaRepo,
            maxConcurrentFetches = 4,
        )
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val backendLoads = AtomicInteger(0)
        resolver.assetLoaderOverride = { segments ->
            val active = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(active, ::maxOf)
            backendLoads.incrementAndGet()
            try {
                delay(30L)
                val body = segments.joinToString("/").encodeToByteArray()
                DocumentProxyResource(body, documentMimeTypeFor(segments.last(), body))
            } finally {
                inFlight.decrementAndGet()
            }
        }
        try {
            // Fire 50 parallel <img> requests spanning 10 distinct uncached assets (5 duplicates per key)
            val results = coroutineScope {
                (0 until 50).map { idx ->
                    val imgName = "img-${idx % 10}.png"
                    async { resolver.resolve(listOf("assets", imgName)) }
                }.awaitAll()
            }
            assertEquals(50, results.count { it != null })
            assertTrue(maxInFlight.get() in 1..4, "Concurrent uncached asset loads must stay <= 4, got ${maxInFlight.get()}")
            assertEquals(10, backendLoads.get(), "50 parallel requests across 10 unique keys must coalesce to exactly 10 loads")
        } finally {
            resolver.close()
        }
    }
}
