package dev.piko.shared.net

import dev.piko.shared.network.createPikoAuxiliaryHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 辅助传输（MetaTube 与图片取图）只承担无鉴权的普通请求：错误状态码要能回到调用方自己判断，
 * 重定向要跟着走。两端传进来的引擎不同，这两条行为必须由共用配置保证。
 */
class PikoAuxiliaryHttpClientTest {
    @Test
    fun `error responses are returned instead of thrown`() = runBlocking {
        val client = createPikoAuxiliaryHttpClient(MockEngine) {
            engine { addHandler { respond("nope", HttpStatusCode.NotFound) } }
        }

        val response = client.get("https://example.invalid/meta")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("nope", response.bodyAsText())
        client.close()
    }

    @Test
    fun `redirects are followed`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = createPikoAuxiliaryHttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath == "/old") {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "https://example.invalid/new"),
                        )
                    } else {
                        respond("payload", HttpStatusCode.OK)
                    }
                }
            }
        }

        val response = client.get("https://example.invalid/old")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("payload", response.bodyAsText())
        assertEquals(listOf("/old", "/new"), paths)
        client.close()
    }

    @Test
    fun `auxiliary requests carry no authorization header`() = runBlocking {
        val seenHeaders = mutableListOf<List<String>>()
        val client = createPikoAuxiliaryHttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seenHeaders += request.headers.entries().map { it.key.lowercase() }
                    respond("ok", HttpStatusCode.OK)
                }
            }
        }

        client.get("https://example.invalid/image").bodyAsText()
        client.get("https://example.invalid/other").bodyAsText()

        val authorization = HttpHeaders.Authorization.lowercase()
        assertEquals(2, seenHeaders.size)
        assertTrue(seenHeaders.all { authorization !in it }, "辅助传输不该带账号鉴权头")
        client.close()
    }
}
