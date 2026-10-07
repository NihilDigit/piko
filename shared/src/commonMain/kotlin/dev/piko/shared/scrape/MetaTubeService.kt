package dev.piko.shared.scrape

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.log.PikoLog
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.naming.av.AvKind
import dev.piko.shared.naming.av.normalizeAvCode
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 测试连接的结果：[movieProviders] 是服务端的影片数据源数；[tokenAccepted] 为 false 是令牌不对或该填没填。 */
data class MetaTubeCheck(val movieProviders: Int, val tokenAccepted: Boolean)

/** 一批番号的查询结果：查到的片名，与因出错没查成的番号数（查不到不算出错）。 */
data class MetaTubeTitles(val titles: Map<String, String>, val failed: Int)

/**
 * 进程级的 MetaTube 入口：地址与令牌取自偏好，查到的片名按「地址|番号」缓存在进程里，同一番号不重复查。
 * 查不到的也记下（记为没有），出错的不记，下次再试。地址留空时 [enabled] 为 false，界面不出现任何刮削入口。
 *
 * [newHttpClient] 由两端传入 OkHttp 引擎的客户端，第一次用到时才建。
 */
class MetaTubeService(
    private val preferences: PikoUserPreferences,
    newHttpClient: () -> HttpClient,
) {
    private val http by lazy(newHttpClient)
    private val cache = HashMap<String, String?>()
    private val cacheLock = Mutex()

    val enabled: Flow<Boolean> = preferences.metaTubeUrlFlow.map { it.isNotBlank() }

    private suspend fun client(): MetaTubeClient? {
        val url = preferences.metaTubeUrlFlow.first().trim().ifEmpty { return null }
        return MetaTubeClient(http, url, preferences.metaTubeTokenFlow.first())
    }

    /**
     * 用还没保存的地址与令牌试一次：先取数据源列表（公开接口，连得上就有），再取数据库版本验令牌
     * （要令牌的接口里最轻的一个，搜索会让服务端去外部站点查）。
     */
    suspend fun check(url: String, token: String): Result<MetaTubeCheck> {
        val client = MetaTubeClient(http, url, token)
        val providers = client.providers().getOrElse { return Result.failure(it) }
        val probe = client.checkToken()
        val rejected = (probe.exceptionOrNull() as? MetaTubeException)?.status?.value == 401
        if (probe.isFailure && !rejected) return Result.failure(probe.exceptionOrNull()!!)
        return Result.success(MetaTubeCheck(providers.size, tokenAccepted = !rejected))
    }

    /**
     * 查一批番号的片名。没配置时返回空表。[onProgress] 每查完一个回调一次（已完成数，总数）。
     * 并发取得保守：每次搜索服务端都要去外部站点查，压得太多只会排队超时。
     */
    suspend fun titles(infos: List<AvInfo>, onProgress: (Int, Int) -> Unit = { _, _ -> }): MetaTubeTitles {
        val client = client() ?: return MetaTubeTitles(emptyMap(), 0)
        val unique = infos.distinctBy { it.code }
        val base = preferences.metaTubeUrlFlow.first().trim()
        var done = 0
        var failed = 0
        val permits = Semaphore(CONCURRENCY)
        val found = coroutineScope {
            unique.map { info ->
                async {
                    val key = "$base|${info.code}"
                    val cached = cacheLock.withLock { if (key in cache) Result.success(cache[key]) else null }
                    val result = cached ?: permits.withPermit { lookUp(client, info) }
                        .onSuccess { title -> cacheLock.withLock { cache[key] = title } }
                    cacheLock.withLock {
                        done++
                        if (result.isFailure) failed++
                        onProgress(done, unique.size)
                    }
                    result.getOrNull()?.let { info.code to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        PikoLog.i(TAG, "查片名：${unique.size} 个番号，查到 ${found.size} 个，出错 $failed 个")
        return MetaTubeTitles(found, failed)
    }

    /** 片名，查不到时为 null。只认番号对得上的结果，宁可不用也不套上别的片子的名字。 */
    private suspend fun lookUp(client: MetaTubeClient, info: AvInfo): Result<String?> {
        for (query in queriesOf(info)) {
            val movies = client.search(query).getOrElse { return Result.failure(it) }
            val match = movies.firstOrNull { sameCode(it.number, info) } ?: continue
            if (match.title.isNotBlank()) return Result.success(match.title.trim())
            return client.movie(match.provider, match.id).map { it.title.trim().ifEmpty { null } }
        }
        return Result.success(null)
    }

    /**
     * 先按规范番号搜。无码厂牌的番号（1PON-092415_001）在一些数据源里只记日期序号，搜不到再去掉厂牌名搜一次。
     */
    private fun queriesOf(info: AvInfo): List<String> = listOfNotNull(
        info.code,
        info.number.takeIf { info.kind == AvKind.UNCENSORED_LABEL && info.prefix.isNotEmpty() },
    )

    private fun sameCode(number: String, info: AvInfo): Boolean {
        if (number.isBlank()) return false
        if (normalizeAvCode(number) == info.code) return true
        return number.equals(info.code, ignoreCase = true) || number.equals(info.number, ignoreCase = true) ||
            // FC2 在一些数据源里写作 FC2-1234567
            (info.kind == AvKind.FC2 && number.filter { it in '0'..'9' }.endsWith(info.number))
    }

    private companion object {
        const val TAG = "MetaTube"
        const val CONCURRENCY = 2
    }
}
