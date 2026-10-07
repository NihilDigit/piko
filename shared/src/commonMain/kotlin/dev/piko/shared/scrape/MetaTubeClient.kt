package dev.piko.shared.scrape

import dev.piko.shared.data.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 用户自己部署的 MetaTube 服务（metatube-sdk-go 的 HTTP 接口），只读。
 *
 * Piko 不内置、不推荐任何实例，地址与令牌都由用户填写。接口照服务端源码（route/route.go）：
 * /v1/providers 公开；/v1/movies/search 与 /v1/movies/{provider}/{id} 要 `Authorization: Bearer <令牌>`，
 * 服务端没设令牌时不校验。响应包在 `{"data": …}` 里，出错是 `{"error": {"code", "message"}}`。
 *
 * 走调用方给的 HttpClient：两端传入的是 OkHttp 引擎，建客户端时取进程默认的 ProxySelector，代理设置照样生效。
 */
class MetaTubeClient(
    private val http: HttpClient,
    baseUrl: String,
    private val token: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** 服务端支持的影片数据源名称。地址填错、服务没起来时在这一步失败。 */
    suspend fun providers(): Result<List<String>> = call("/v1/providers") { body ->
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonObject
        (data?.get("movie_providers") as? JsonObject)?.keys?.toList().orEmpty()
    }

    /** 验令牌：取数据库版本，要令牌的接口里最轻的一个。令牌不对时失败为 401 的 [MetaTubeException]。 */
    suspend fun checkToken(): Result<Unit> = call("/v1/db/version") { }

    /**
     * 按番号搜影片。[provider] 为空时服务端在全部数据源里找。
     * 哪里都没有时服务端回 404「info not found」而不是空表（实测），这里按查不到处理，不算出错。
     */
    suspend fun search(query: String, provider: String? = null): Result<List<MetaTubeMovie>> =
        call("/v1/movies/search", { parameter("q", query); provider?.let { parameter("provider", it) } }) { body ->
            json.decodeFromString(MovieListResponse.serializer(), body).data
        }.recoverCatching { error ->
            if (error is MetaTubeException && error.status == HttpStatusCode.NotFound) emptyList() else throw error
        }

    /** 某个数据源上一部影片的详情。 */
    suspend fun movie(provider: String, id: String): Result<MetaTubeMovie> =
        call("/v1/movies/${provider.encodeURLPathPart()}/${id.encodeURLPathPart()}") { body ->
            json.decodeFromString(MovieResponse.serializer(), body).data
        }

    private suspend fun <T> call(
        path: String,
        params: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
        parse: (String) -> T,
    ): Result<T> = runSuspendCatching {
        // 超时要当失败返回，不能用 withTimeout：它抛的是 CancellationException，会被当成调用方自己被取消而一路抛上去
        val body = withTimeoutOrNull(timeoutMillis) {
            val response: HttpResponse = http.get(base + path) {
                if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}")
                params()
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) throw MetaTubeException(response.status, errorMessageOf(text))
            text
        } ?: throw MetaTubeTimeoutException(timeoutMillis)
        parse(body)
    }

    private fun errorMessageOf(body: String): String? = runCatching {
        json.decodeFromString(ErrorResponse.serializer(), body).error?.message
    }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** 服务端回了非 2xx。[status] 为 401 是令牌不对或没填。 */
class MetaTubeException(val status: HttpStatusCode, val serverMessage: String?) :
    Exception("MetaTube 返回 HTTP ${status.value}" + (serverMessage?.let { "：$it" } ?: ""))

class MetaTubeTimeoutException(timeoutMillis: Long) : Exception("MetaTube 在 ${timeoutMillis / 1000} 秒内没有响应")

/** 搜索结果与详情共有的几项。详情另有简介、演员、厂商等，用到时再加。 */
@Serializable
data class MetaTubeMovie(
    val id: String = "",
    val number: String = "",
    val title: String = "",
    val provider: String = "",
    val homepage: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val summary: String = "",
)

@Serializable
private class MovieListResponse(val data: List<MetaTubeMovie> = emptyList())

@Serializable
private class MovieResponse(val data: MetaTubeMovie)

@Serializable
private class ErrorResponse(val error: ErrorBody? = null)

@Serializable
private class ErrorBody(val code: Int = 0, val message: String? = null)
