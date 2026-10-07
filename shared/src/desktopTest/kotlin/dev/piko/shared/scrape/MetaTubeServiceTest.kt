package dev.piko.shared.scrape

import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.smoke.MemoryPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** MetaTube 的接口用 MockEngine 顶替，不连任何真实服务。 */
class MetaTubeServiceTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()

    private fun engine(token: String = "secret") = MockEngine { request ->
        requests += request.url.encodedPath + "?" + request.url.encodedQuery
        if (request.url.encodedPath != "/v1/providers" && request.headers[HttpHeaders.Authorization] != "Bearer $token") {
            return@MockEngine respond("""{"error":{"code":401,"message":"unauthorized"}}""", HttpStatusCode.Unauthorized, json)
        }
        when (request.url.encodedPath) {
            "/v1/providers" -> respond("""{"data":{"actor_providers":{"A":"x"},"movie_providers":{"A":"x","B":"y"}}}""", headers = json)
            "/v1/db/version" -> respond("""{"data":{"version":"1"}}""", headers = json)
            "/v1/movies/search" -> when (request.url.parameters["q"]) {
                "ABC-123" -> respond(
                    """{"data":[{"id":"x1","number":"XYZ-999","title":"别的片子","provider":"A"},{"id":"a1","number":"abc-123","title":"查到的片名","provider":"A"}]}""",
                    headers = json,
                )
                "XYZ-456" -> respond("""{"data":[{"id":"z1","number":"XYZ-456","title":"","provider":"B"}]}""", headers = json)
                "092415_001" -> respond("""{"data":[{"id":"p1","number":"092415_001","title":"一本道的片名","provider":"C"}]}""", headers = json)
                // FC2 与 FC2PPVDB 两个数据源只认 number.Trim 之后的写法，回来的番号也是这样写的
                "FC2-1234567" -> respond("""{"data":[{"id":"1234567","number":"FC2-1234567","title":"FC2 的片名","provider":"FC2"}]}""", headers = json)
                // 真实服务端哪里都查不到时回 404，不是空表
                else -> respond("""{"error":{"code":404,"message":"info not found"}}""", HttpStatusCode.NotFound, json)
            }
            "/v1/movies/B/z1" -> respond("""{"data":{"id":"z1","number":"XYZ-456","title":"详情里的片名","provider":"B"}}""", headers = json)
            else -> respond("""{"error":{"code":404,"message":"not found"}}""", HttpStatusCode.NotFound, json)
        }
    }

    private fun service(url: String = "http://metatube.test", token: String = "secret") = MetaTubeService(
        MemoryPreferences().apply { metaTubeUrlFlow.value = url; metaTubeTokenFlow.value = token },
    ) { HttpClient(engine()) }

    private fun av(name: String) = parseMediaName(name).av!!

    @Test
    fun `titles come from results whose code matches and are cached per code`() = runBlocking<Unit> {
        val service = service()
        val infos = listOf(
            av("abc00123.mp4"), av("XYZ-456.mp4"), av("1pon-092415_001.mp4"), av("NONE-001.mp4"), av("ABC-123-C.mp4"), av("fc2ppv_1234567_1.mp4"),
        )
        val first = service.titles(infos)
        assertEquals(
            mapOf("ABC-123" to "查到的片名", "XYZ-456" to "详情里的片名", "1PON-092415_001" to "一本道的片名", "FC2-PPV-1234567" to "FC2 的片名"),
            first.titles,
        )
        assertEquals(0, first.failed)
        val count = requests.size
        assertEquals(first.titles, service.titles(infos).titles)
        assertEquals(count, requests.size, "查过的番号（含查不到的）不再发请求")
    }

    @Test
    fun `nothing is asked when no address is set`() = runBlocking<Unit> {
        val result = service(url = "").titles(listOf(av("ABC-123.mp4")))
        assertEquals(emptyMap(), result.titles)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a wrong token is told apart from an unreachable server`() = runBlocking<Unit> {
        val service = service()
        assertEquals(MetaTubeCheck(movieProviders = 2, tokenAccepted = true), service.check("http://metatube.test/", "secret").getOrThrow())
        assertEquals(false, service.check("http://metatube.test", "wrong").getOrThrow().tokenAccepted)
        // 令牌不对时查片名算出错，不缓存，改对之后能查到
        val failed = service(token = "wrong").titles(listOf(av("ABC-123.mp4")))
        assertEquals(1, failed.failed)
    }

    @Test
    fun `a slow server fails instead of hanging`() = runBlocking<Unit> {
        val slow = MockEngine {
            delay(5_000)
            respond("{}", headers = json)
        }
        val result = MetaTubeClient(HttpClient(slow), "http://metatube.test", "", timeoutMillis = 100).search("ABC-123")
        assertIs<MetaTubeTimeoutException>(result.exceptionOrNull())
    }
}
