package dev.piko.shared.scrape

import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.smoke.MemoryPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 对真实的 MetaTube 服务走一遍：数据源列表、令牌、搜索、详情与片名查询。只在本机手动跑，CI 不跑：
 * 设环境变量 METATUBE_PROBE_URL（令牌另设 METATUBE_PROBE_TOKEN），再跑
 * `./gradlew :shared:desktopTest --tests '*MetaTubeLiveProbe*' -i`。地址只经环境变量传入，不写进仓库。
 */
class MetaTubeLiveProbe {
    private val url = System.getenv("METATUBE_PROBE_URL").orEmpty()
    private val token = System.getenv("METATUBE_PROBE_TOKEN").orEmpty()

    @Test
    fun `a real server answers the calls Piko makes`() = runBlocking<Unit> {
        assumeTrue("未设 METATUBE_PROBE_URL，跳过", url.isNotBlank())
        val http = HttpClient(OkHttp)
        // 休眠的服务首个请求要冷启动，超时放宽
        val client = MetaTubeClient(http, url, token, timeoutMillis = 120_000)
        val providers = client.providers().getOrThrow()
        println("数据源：$providers")
        assertTrue(providers.isNotEmpty())
        println("令牌：${client.checkToken()}")

        val query = System.getenv("METATUBE_PROBE_QUERY") ?: "SSIS-001"
        println("原始搜索响应：" + http.get(url.trimEnd('/') + "/v1/movies/search?q=$query").bodyAsText().take(2000))
        val results = client.search(query).getOrThrow()
        results.take(3).forEach { println("搜索结果：$it") }
        val first = results.first()
        println("原始详情响应：" + http.get(url.trimEnd('/') + "/v1/movies/${first.provider}/${first.id}").bodyAsText().take(2000))
        println("详情：${client.movie(first.provider, first.id).getOrThrow()}")

        val prefs = MemoryPreferences().apply { metaTubeUrlFlow.value = url; metaTubeTokenFlow.value = token }
        val service = MetaTubeService(prefs) { http }
        val names = listOf("ssis00001hhb.mp4", "SSIS-001-C.mp4", "fc2-ppv-1234567.mp4", "1pon-092415_001.mp4", "NOPE-99999.mp4")
        val titles = service.titles(names.map { parseMediaName(it).av!! }) { done, total -> println("进度 $done/$total") }
        println("片名：$titles")
        println("出错的请求：" + MetaTubeClient(http, url, token, timeoutMillis = 120_000).movie("NO_SUCH_PROVIDER", "x"))
    }

    /**
     * FC2 的几种查询写法各发一次，看是写法的问题还是数据源取不到。编号经 METATUBE_PROBE_FC2 传入（纯数字），
     * 数据源报错时服务端回 500，与「查不到」的 404 区分得开。
     */
    @Test
    fun `fc2 query forms`() = runBlocking<Unit> {
        val fc2 = System.getenv("METATUBE_PROBE_FC2").orEmpty()
        assumeTrue("未设 METATUBE_PROBE_URL 或 METATUBE_PROBE_FC2，跳过", url.isNotBlank() && fc2.isNotBlank())
        val http = HttpClient(OkHttp)
        val base = url.trimEnd('/')
        val paths = listOf(
            "/v1/movies/search?q=FC2-PPV-$fc2",
            "/v1/movies/search?q=FC2-$fc2",
            "/v1/movies/search?q=$fc2",
            "/v1/movies/search?q=FC2-$fc2&provider=FC2",
            "/v1/movies/search?q=FC2-$fc2&provider=FC2PPVDB",
            "/v1/movies/search?q=FC2-$fc2&provider=fc2hub",
            "/v1/movies/FC2/$fc2",
        )
        paths.forEach { path ->
            val body = http.get(base + path) { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.bodyAsText()
            println("$path → ${body.take(300)}")
        }
    }
}
