package dev.piko.shared.media.testing

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientProvider
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模拟的 PikPak：API 与 CDN 都由 MockEngine 应答，前面接的是真实的 PikPakClient、
 * PikPakFileHandle 与 PikPakStreamReader。
 *
 * 复现了影响播放的几个真实行为：签名直链用到一半开始回 403（SDK 应重取详情换新链接）、
 * 慢块、单个直链超过 8 个并发连接回 503、转码流的长度不在元数据里只能靠 1 字节探测。
 */
internal class FakePikPakCloud(
    val origin: ByteArray,
    val transcode: ByteArray? = null,
    private val blockDelayMillis: Long = 0,
    /** 每一代直链服务这么多次 CDN 请求后开始回 403。null 表示不过期。 */
    private val expireAfterRequests: Int? = null,
    val fileName: String = "movie.mkv",
    private val gcid: String = "GCIDFAKE0001",
    private val durationSeconds: Long = 600,
) {
    val detailCalls = AtomicInteger()
    val cdnRequests = AtomicInteger()
    val expiredResponses = AtomicInteger()
    val overBudgetResponses = AtomicInteger()

    private val generationLock = Any()
    private var generation = 1
    private var servedInGeneration = 0
    private val inFlightByUrl = ConcurrentHashMap<String, AtomicInteger>()

    val provider: PikoClientProvider by lazy {
        val client = newClient()
        object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)
        }
    }

    private fun newClient(): PikPakClient {
        val engine = MockEngine { request -> handle(request) }
        return PikPakClient(
            account = "mock@example.com",
            password = "pw",
            sessionStore = InMemorySessionStore(),
            httpClient = HttpClient(engine),
            cdnHttpClient = HttpClient(engine),
        )
    }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        return when {
            request.url.host == CDN_HOST -> serveCdn(request)
            path.endsWith("/v1/shield/captcha/init") -> json("""{"captcha_token":"C","expires_in":300,"url":""}""")
            path.endsWith("/v1/auth/signin") ->
                json("""{"access_token":"AT","refresh_token":"RT","sub":"U","expires_in":3600}""")
            request.method == HttpMethod.Get && path.contains("/drive/v1/files/") -> {
                detailCalls.incrementAndGet()
                json(detailJson())
            }
            else -> respond("", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun detailJson(): String {
        val gen = synchronized(generationLock) { generation }
        val originUrl = "https://$CDN_HOST/origin/g$gen"
        val transcodeEntry = if (transcode == null) {
            ""
        } else {
            """,{"media_id":"m480","media_name":"480P","resolution_name":"480P","is_origin":false,
                "video":{"width":854,"height":480,"duration":$durationSeconds,"video_type":"mpegts"},
                "link":{"url":"https://$CDN_HOST/t480/g$gen","expire":""}}"""
        }
        return """{"kind":"drive#file","id":"f1","parent_id":"root","name":"$fileName","size":"${origin.size}",
            "phase":"PHASE_TYPE_COMPLETE","hash":"$gcid","mime_type":"video/x-matroska",
            "links":{"application/octet-stream":{"url":"$originUrl","expire":""}},
            "medias":[{"media_id":"morigin","media_name":"Original","is_origin":true,
                "video":{"width":1920,"height":1080,"duration":$durationSeconds,"video_type":"matroska,webm"},
                "link":{"url":"$originUrl","expire":""}}$transcodeEntry]}"""
    }

    private suspend fun MockRequestHandleScope.serveCdn(request: HttpRequestData): HttpResponseData {
        cdnRequests.incrementAndGet()
        val (resource, generationPart) = request.url.encodedPath.trim('/').split('/')
        val body = if (resource == "origin") origin else checkNotNull(transcode)
        val requestedGeneration = generationPart.removePrefix("g").toInt()

        if (isExpired(requestedGeneration)) {
            expiredResponses.incrementAndGet()
            return respond("", HttpStatusCode.Forbidden)
        }

        val url = request.url.toString()
        val inFlight = inFlightByUrl.getOrPut(url) { AtomicInteger() }
        if (inFlight.incrementAndGet() > PER_URL_CONNECTION_CAP) {
            inFlight.decrementAndGet()
            overBudgetResponses.incrementAndGet()
            return respond("", HttpStatusCode.ServiceUnavailable)
        }
        try {
            if (blockDelayMillis > 0) delay(blockDelayMillis)
            val (start, endInclusive) = parseRange(request.headers[HttpHeaders.Range], body.size.toLong())
            val slice = body.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
            return respond(
                content = ByteReadChannel(slice),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentRange to listOf("bytes $start-$endInclusive/${body.size}"),
                    HttpHeaders.ContentLength to listOf(slice.size.toString()),
                    HttpHeaders.ContentType to listOf("application/octet-stream"),
                ),
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun isExpired(requestedGeneration: Int): Boolean = synchronized(generationLock) {
        val limit = expireAfterRequests ?: return false
        if (requestedGeneration < generation) return true
        servedInGeneration += 1
        if (servedInGeneration > limit) {
            generation += 1
            servedInGeneration = 0
            return true
        }
        false
    }

    private fun parseRange(header: String?, size: Long): Pair<Long, Long> {
        val spec = header?.removePrefix("bytes=") ?: return 0L to size - 1
        val start = spec.substringBefore('-').toLong()
        val end = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        return start to end
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    companion object {
        const val CDN_HOST = "cdn.test"
        const val PER_URL_CONNECTION_CAP = 8

        /** 每个字节都与位置相关的负载，错位一个字节就对不上。 */
        fun payload(size: Int) = ByteArray(size) { ((it * 31 + it / 251) % 256).toByte() }
    }
}

/**
 * 只实现续播位置两个方法的偏好存储。
 *
 * 用动态代理而不是手写实现：接口还有二十来个与播放无关的成员，手写的假实现会在
 * 别处给接口加成员时跟着编译失败。suspend 函数在代理里多一个 Continuation 参数，
 * 直接返回结果即视为同步完成。
 */
internal class RecordedPositions {
    val saved = ConcurrentHashMap<String, Long>()

    val preferences: PikoUserPreferences = Proxy.newProxyInstance(
        PikoUserPreferences::class.java.classLoader,
        arrayOf(PikoUserPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "savePlaybackPosition" -> {
                saved[args[0] as String] = args[1] as Long
                Unit
            }
            "getPlaybackPosition" -> saved[args[0] as String] ?: 0L
            else -> throw UnsupportedOperationException(method.name)
        }
    } as PikoUserPreferences
}
