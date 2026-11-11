package dev.piko.shared.scrape

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoCacheStore
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.naming.av.AvKind
import dev.piko.shared.naming.av.normalizeAvCode
import io.ktor.client.HttpClient
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 测试连接的结果：[movieProviders] 是服务端的影片数据源数；[tokenAccepted] 为 false 是令牌不对或该填没填。 */
data class MetaTubeCheck(val movieProviders: Int, val tokenAccepted: Boolean)

/** 一批番号的查询结果：查到的片名，与因出错没查成的番号数（查不到不算出错）。 */
data class MetaTubeTitles(val titles: Map<String, String>, val failed: Int)

/**
 * 进程级的 MetaTube 入口：地址与令牌取自偏好，查到的片名按「地址|番号」缓存在进程里，非空片名另存 [cacheStore] 跨进程复用。
 * 查不到的也记在进程内存里（不落盘，以免刚发售的新片次日收录后重启仍查不到），出错的既不记内存也不落盘，下次再试。
 * 同一时刻对同一番号的并发查询单飞合并，且共享请求挂在服务级作用域上，首个等待方取消不会连累其他等待方。
 * 地址留空时 [enabled] 为 false，界面不出现任何刮削入口。
 *
 * [newHttpClient] 由两端传入 OkHttp 引擎的客户端，第一次用到时才建。
 */
class MetaTubeService(
    private val preferences: PikoUserPreferences,
    private val cacheStore: PikoCacheStore? = null,
    scope: CoroutineScope? = null,
    private val saveDelayMillis: Long = 1_500L,
    newHttpClient: () -> HttpClient,
) {
    private val http by lazy(newHttpClient)
    private val serviceScope = scope?.let { CoroutineScope(SupervisorJob(it.coroutineContext[Job]) + Dispatchers.Default) }
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = HashMap<String, String?>()
    private val persisted = LinkedHashMap<String, String>()
    private val inFlight = HashMap<String, Deferred<Result<String?>>>()
    private val cacheLock = Mutex()
    private val permits = Semaphore(CONCURRENCY)
    private var diskLoaded = cacheStore == null
    private var pendingSave: Job? = null

    val enabled: Flow<Boolean> = preferences.metaTubeUrlFlow.map { it.isNotBlank() }

    private suspend fun ensureDiskCacheLoaded() {
        if (diskLoaded) return
        val store = cacheStore ?: run {
            diskLoaded = true
            return
        }
        val text = runSuspendCatching { store.read(CACHE_FILE_KEY) }
            .logFailure(TAG, "Failed to read persisted title cache ($CACHE_FILE_KEY)")
            .getOrNull()
        val decoded = if (text != null) {
            decodeCache(text) ?: run {
                PikoLog.w(TAG, "Corrupted persisted title cache ($CACHE_FILE_KEY); resetting in-memory snapshot")
                emptyMap()
            }
        } else {
            emptyMap()
        }
        cacheLock.withLock {
            if (diskLoaded) return
            diskLoaded = true
            for ((k, v) in decoded) {
                if (v.isBlank()) continue
                persisted[k] = v
                if (k !in cache) cache[k] = v
            }
            trimPersistedLocked()
        }
    }

    private fun trimPersistedLocked() {
        while (persisted.size > MAX_PERSISTED_ENTRIES) {
            val eldest = persisted.entries.iterator().next().key
            persisted.remove(eldest)
        }
    }

    private fun schedulePersistLocked() {
        val store = cacheStore ?: return
        if (pendingSave?.isActive == true) return
        pendingSave = serviceScope.launch {
            delay(saveDelayMillis)
            val snapshot = cacheLock.withLock { LinkedHashMap(persisted) }
            runSuspendCatching { store.write(CACHE_FILE_KEY, encodeCache(snapshot)) }
                .logFailure(TAG, "Failed to persist title cache (${snapshot.size} entries)")
        }
    }

    internal suspend fun flushPendingSave() {
        val store = cacheStore ?: return
        val job = cacheLock.withLock {
            val active = pendingSave
            pendingSave = null
            active
        }
        job?.cancel()
        val snapshot = cacheLock.withLock { LinkedHashMap(persisted) }
        if (snapshot.isNotEmpty()) {
            runSuspendCatching { store.write(CACHE_FILE_KEY, encodeCache(snapshot)) }
                .logFailure(TAG, "Failed to flush persisted title cache (${snapshot.size} entries)")
        }
    }

    /**
     * 用还没保存的地址与令牌试一次：先取数据源列表（公开接口，连得上就有），再取数据库版本验令牌
     * （要令牌的接口里最轻的一个，搜索会让服务端去外部站点查）。
     */
    suspend fun check(url: String, token: String): Result<MetaTubeCheck> {
        val client = MetaTubeClient(http, url, token)
        val providers = client.providers()
            .logFailure(TAG, "MetaTube connection check failed while fetching /v1/providers")
            .getOrElse { return Result.failure(it) }
        val probe = client.checkToken()
        val rejected = (probe.exceptionOrNull() as? MetaTubeException)?.status?.value == 401
        if (rejected) {
            PikoLog.w(TAG, "MetaTube connection check: access token rejected (HTTP 401), providers=${providers.size}")
        } else if (probe.isFailure) {
            probe.logFailure(TAG, "MetaTube connection check failed while verifying token at /v1/db/version")
            return Result.failure(probe.exceptionOrNull()!!)
        }
        return Result.success(MetaTubeCheck(providers.size, tokenAccepted = !rejected))
    }

    /**
     * 查一批番号的片名。没配置时返回空表。[onProgress] 每查完一个回调一次（已完成数，总数）。
     * 全进程共享 [CONCURRENCY] 个并发槽位，兼顾多番号吞吐与自建服务端上游压力。
     */
    suspend fun titles(
        infos: List<AvInfo>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        /** 每查到一个片名回调一次（番号，片名）。调用方中途取消时手上仍有已查到的这些。 */
        onFound: (String, String) -> Unit = { _, _ -> },
    ): MetaTubeTitles {
        val base = preferences.metaTubeUrlFlow.first().trim().trimEnd('/').ifEmpty { return MetaTubeTitles(emptyMap(), 0) }
        val token = preferences.metaTubeTokenFlow.first().trim()
        val client = MetaTubeClient(http, base, token)
        val unique = infos.distinctBy { it.code }
        if (unique.isEmpty()) return MetaTubeTitles(emptyMap(), 0)
        ensureDiskCacheLoaded()
        val started = TimeSource.Monotonic.markNow()
        var done = 0
        var failed = 0
        var notFound = 0
        var positiveCacheHits = 0
        var negativeCacheHits = 0
        var coalescedHits = 0
        var networkRequests = 0
        var firstFailure: Pair<String, Throwable>? = null
        val progressLock = Mutex()
        val found = try {
            coroutineScope {
                unique.map { info ->
                    async {
                        val outcome = resolveOne(base, token, client, info)
                        progressLock.withLock {
                            done++
                            when (outcome.source) {
                                LookupSource.POSITIVE_CACHE -> positiveCacheHits++
                                LookupSource.NEGATIVE_CACHE -> negativeCacheHits++
                                LookupSource.COALESCED -> coalescedHits++
                                LookupSource.NETWORK -> networkRequests++
                            }
                            outcome.result.fold(
                                onSuccess = { title ->
                                    if (title == null) notFound++ else onFound(info.code, title)
                                },
                                onFailure = { err ->
                                    failed++
                                    if (firstFailure == null) firstFailure = info.code to err
                                },
                            )
                            onProgress(done, unique.size)
                        }
                        outcome.result.getOrNull()?.let { info.code to it }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        } catch (e: CancellationException) {
            PikoLog.d(
                TAG,
                "MetaTube title lookup cancelled: completed=$done/${unique.size}, failed=$failed, " +
                    "elapsedMs=${started.elapsedNow().inWholeMilliseconds}",
            )
            throw e
        }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        firstFailure?.let { (code, err) ->
            PikoLog.w(
                TAG,
                "MetaTube title lookup failed for $failed/${unique.size} codes " +
                    "(first=$code, detail=${describeLookupError(err)}, elapsedMs=$elapsedMs)",
                err,
            )
        }
        val level = if (networkRequests == 0 && coalescedHits == 0 && failed == 0) LogLevel.DEBUG else LogLevel.INFO
        PikoLog.log(
            level,
            TAG,
            "MetaTube title lookup finished: total=${unique.size}, found=${found.size}, notFound=$notFound, " +
                "failed=$failed, cacheHits=$positiveCacheHits, negativeCacheHits=$negativeCacheHits, " +
                "coalesced=$coalescedHits, network=$networkRequests, elapsedMs=$elapsedMs",
        )
        return MetaTubeTitles(found, failed)
    }

    private enum class LookupSource { POSITIVE_CACHE, NEGATIVE_CACHE, COALESCED, NETWORK }

    private class LookupOutcome(val result: Result<String?>, val source: LookupSource)

    private suspend fun resolveOne(
        base: String,
        token: String,
        client: MetaTubeClient,
        info: AvInfo,
    ): LookupOutcome {
        // cacheKey 刻意不含 token，使修改令牌后仍可复用已查到的有效片名；
        // flightKey 必须包含 token，防止用户改对令牌重试时复用到旧错误令牌尚未结束的失败单飞请求。
        val cacheKey = "$base|${info.code}"
        val flightKey = "$base|$token|${info.code}"
        var joinedExisting = false
        val deferred = cacheLock.withLock {
            if (cacheKey in cache) {
                val cached = cache[cacheKey]
                val kind = if (cached == null) LookupSource.NEGATIVE_CACHE else LookupSource.POSITIVE_CACHE
                return LookupOutcome(Result.success(cached), kind)
            }
            inFlight[flightKey]?.let {
                joinedExisting = true
                return@withLock it
            }
            val created = serviceScope.async(start = CoroutineStart.LAZY) {
                try {
                    val fetched = permits.withPermit { lookUp(client, info) }
                    fetched.onSuccess { title ->
                        cacheLock.withLock {
                            // null 负结果仅缓存在当前进程内存，不写入 persisted 磁盘快照，避免新片次日收录后跨重启仍查不到。
                            cache[cacheKey] = title
                            if (!title.isNullOrBlank()) {
                                persisted.remove(cacheKey)
                                persisted[cacheKey] = title
                                trimPersistedLocked()
                                schedulePersistLocked()
                            }
                        }
                    }.onFailure { err ->
                        PikoLog.d(TAG, "MetaTube lookup failed for code=${info.code}: ${describeLookupError(err)}")
                    }
                } finally {
                    cacheLock.withLock {
                        inFlight.remove(flightKey)
                    }
                }
            }
            inFlight[flightKey] = created
            created
        }
        deferred.start()
        return LookupOutcome(
            result = deferred.await(),
            source = if (joinedExisting) LookupSource.COALESCED else LookupSource.NETWORK,
        )
    }

    private fun describeLookupError(err: Throwable): String = when (err) {
        is MetaTubeException -> "HTTP ${err.status.value}" + (err.serverMessage?.let { " ($it)" } ?: "")
        is MetaTubeTimeoutException -> "timeout (${err.timeoutMillis}ms)"
        else -> err::class.simpleName ?: "error"
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
     *
     * FC2 搜「FC2-1234567」：服务端搜索前先经 number.Trim，FC2-PPV-1234567 也会被它改成这个写法，
     * 直接发它省掉一层依赖；FC2、FC2PPVDB 两个数据源再从中取出纯数字当 ID。
     */
    private fun queriesOf(info: AvInfo): List<String> = when (info.kind) {
        AvKind.FC2 -> listOf("FC2-${info.number}")
        else -> listOfNotNull(
            info.code,
            info.number.takeIf { info.kind == AvKind.UNCENSORED_LABEL && info.prefix.isNotEmpty() },
        )
    }

    private fun sameCode(number: String, info: AvInfo): Boolean {
        if (number.isBlank()) return false
        if (normalizeAvCode(number) == info.code) return true
        return number.equals(info.code, ignoreCase = true) || number.equals(info.number, ignoreCase = true) ||
            // FC2 在一些数据源里写作 FC2-1234567
            (info.kind == AvKind.FC2 && number.filter { it in '0'..'9' }.endsWith(info.number))
    }

    internal companion object {
        const val TAG = "MetaTube"
        const val CONCURRENCY = 8
        const val CACHE_FILE_KEY = "metatube-titles.json"
        const val MAX_PERSISTED_ENTRIES = 20_000
        private val CACHE_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        private val JSON = Json { ignoreUnknownKeys = true }

        fun encodeCache(entries: Map<String, String>): String =
            JSON.encodeToString(CACHE_SERIALIZER, entries)

        fun decodeCache(text: String): Map<String, String>? =
            runCatching { JSON.decodeFromString(CACHE_SERIALIZER, text) }.getOrNull()
    }
}
