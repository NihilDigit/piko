package dev.piko.shared.data

import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReadBytesRetryTest {
    @Test
    fun missingLinksRefreshDetailsUntilReady() = runBlocking {
        // 两种 MIME 均须可读：不能只识别 octet-stream。
        for (mime in listOf("application/octet-stream", "application/json")) {
            Fixture(detail = { call -> if (call < 3) "" else link(mime, "fresh") }).use { fixture ->
                assertContentEquals(PAYLOAD.encodeToByteArray(), fixture.read())
                assertEquals(3, fixture.detailCalls)
                assertEquals(listOf(500.milliseconds, 1.seconds), fixture.waits)
                assertEquals(listOf("/fresh"), fixture.downloads)
            }
        }
    }

    @Test
    fun fallsBackToWebContentLink() = runBlocking {
        Fixture(detail = { "\"links\":{},\"web_content_link\":\"https://cdn.test/web\"," }).use { fixture ->
            assertContentEquals(PAYLOAD.encodeToByteArray(), fixture.read())
            assertEquals(1, fixture.detailCalls)
            assertEquals(emptyList(), fixture.waits)
            assertEquals(listOf("/web"), fixture.downloads)
        }
    }

    @Test
    fun coldStorageResponseRefreshesSignedLinkBeforeRetry() = runBlocking {
        Fixture(detail = { call -> link("application/octet-stream", "signed-$call") }, coldFirst = true).use { fixture ->
            assertContentEquals(PAYLOAD.encodeToByteArray(), fixture.read())
            assertEquals(2, fixture.detailCalls)
            assertEquals(listOf(500.milliseconds), fixture.waits)
            assertEquals(listOf("/signed-1", "/signed-2"), fixture.downloads)
        }
    }

    @Test
    fun recycledFileFailsWithoutWaiting() = runBlocking {
        Fixture(detail = { "" }, recycled = true).use { fixture ->
            val error = assertFailsWith<PikPakException> { fixture.read() }
            assertEquals("file_in_recycle_bin", error.errorMessage)
            assertEquals(1, fixture.detailCalls)
            assertEquals(emptyList(), fixture.waits)
            assertEquals(emptyList(), fixture.downloads)
        }
    }

    private class Fixture(
        detail: (Int) -> String,
        coldFirst: Boolean = false,
        recycled: Boolean = false,
    ) : AutoCloseable {
        var detailCalls = 0
        val waits = mutableListOf<Duration>()
        val downloads = mutableListOf<String>()
        private val engine = MockEngine { request ->
            when {
                request.url.host == "cdn.test" -> {
                    downloads += request.url.encodedPath
                    assertEquals("bytes=0-${PAYLOAD.length - 1}", request.headers[HttpHeaders.Range])
                    if (coldFirst && downloads.size == 1) {
                        respond("", HttpStatusCode.OK, headersOf("X-Xos-Err-Desc", "5"))
                    } else {
                        respond(
                            PAYLOAD,
                            HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ContentRange to listOf("bytes 0-${PAYLOAD.length - 1}/${PAYLOAD.length}"),
                                HttpHeaders.ContentLength to listOf(PAYLOAD.length.toString()),
                            ),
                        )
                    }
                }
                request.url.encodedPath.endsWith("/v1/shield/captcha/init") ->
                    respond("""{"captcha_token":"C","expires_in":300,"url":""}""", headers = JSON_HEADERS)
                request.url.encodedPath.endsWith("/v1/auth/signin") ->
                    respond("""{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""", headers = JSON_HEADERS)
                request.url.encodedPath.endsWith("/drive/v1/files/file-id") -> {
                    detailCalls++
                    if (recycled) {
                        respond("""{"error_code":9,"error":"file_in_recycle_bin"}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                    } else {
                        respond(
                            """{${detail(detailCalls)}"id":"file-id","size":"${PAYLOAD.length}","phase":"PHASE_TYPE_COMPLETE"}""",
                            headers = JSON_HEADERS,
                        )
                    }
                }
                else -> error("Unexpected test request path: ${request.url.encodedPath}")
            }
        }
        private val http = HttpClient(engine)
        private val cdn = HttpClient(engine)
        private val client = PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            cdnHttpClient = cdn,
        )

        suspend fun read(): ByteArray = PikoDriveRepository.readSmallFileBytes(client, "file-id") { waits += it }

        override fun close() {
            http.close()
            cdn.close()
            engine.close()
        }
    }

    companion object {
        private const val PAYLOAD = "{\"entries\":[]}"
        private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        private fun link(mime: String, signature: String) =
            """"links":{"$mime":{"url":"https://cdn.test/$signature"}},"""
    }
}
