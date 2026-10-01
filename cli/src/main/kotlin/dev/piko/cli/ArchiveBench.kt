package dev.piko.cli

import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.sampleCid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

internal object ArchiveHttpMetrics {
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val attempts = AtomicInteger()
    val limited = AtomicInteger()
    fun reset() { peak.set(0); attempts.set(0); limited.set(0) }
}

internal fun archiveBenchmarkHttpClient() = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
    expectSuccess = false
    engine {
        config {
            dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 128 })
            eventListenerFactory {
                object : okhttp3.EventListener() {
                    private var inFlight = false
                    override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) {
                        if (!inFlight) {
                            inFlight = true
                            ArchiveHttpMetrics.attempts.incrementAndGet()
                            ArchiveHttpMetrics.peak.accumulateAndGet(ArchiveHttpMetrics.active.incrementAndGet(), ::maxOf)
                        }
                    }
                    override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                        if (response.code == 429) ArchiveHttpMetrics.limited.incrementAndGet()
                    }
                    private fun finish() {
                        if (inFlight) { inFlight = false; ArchiveHttpMetrics.active.decrementAndGet() }
                    }
                    override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) = finish()
                    override fun callEnd(call: okhttp3.Call) = finish()
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = finish()
                }
            }
        }
    }
}

/** 归档的只读部分：单独量 API 与三个 CID 窗口，结果不包含直链或账号凭据。 */
suspend fun benchArchiveReads(client: PikPakClient, path: String, levels: List<Int>, count: Int, timeoutSeconds: Long, apiRate: Int, combined: Boolean) {
    require(count in 1..128 && levels.all { it in 1..64 } && timeoutSeconds in 1..60)
    val root = resolvePath(client, path)
    val files = mutableListOf<FileStat>()
    val seen = HashSet<String>()
    var folders = listOf(root)
    while (folders.isNotEmpty() && files.size < count) {
        val next = mutableListOf<String>()
        for (id in folders) {
            if (!seen.add(id)) continue
            val listed = client.listFiles(parentId = id)
            files += listed.filter { !it.isFolder && it.hash.isNotBlank() && it.sizeBytes >= 50L * 1024 * 1024 }.take(count - files.size)
            next += listed.filter { it.isFolder && it.name !in setOf(".piko", "Piko-Temp") }.map { it.id }
            if (files.size >= count) break
        }
        folders = next
    }
    require(files.isNotEmpty()) { "Dramas 内没有可取样的大文件" }
    println("scope=$path files=${files.size} requested=$count client_rate_limit=$apiRate/s read_only=true")
    println("stage,parallel,requests,success,failed,peak,http_peak,http_attempts,http_429,wall_ms,p50_ms,p95_ms,ops_per_sec,errors")
    if (combined) {
        for (parallel in levels) {
            val phases = ConcurrentHashMap<Int, String>()
            if (!measure("archive-read", parallel, files.size, timeoutSeconds, { "${phases[it]}@$it" }) {
                phases[it] = "detail"
                val detail = client.getFile(files[it].id)
                phases[it] = "cid"
                client.sampleCid(detail)
            }) break
            delay(1_000)
        }
        return
    }
    for (parallel in levels) {
        if (!measure("detail", parallel, files.size, timeoutSeconds) { client.getFile(files[it].id) }) break
        delay(1_000)
    }
    val details: List<FileDetail> = coroutineScope {
        val permits = Semaphore(4)
        files.map { async { permits.withPermit { client.getFile(it.id) } } }.awaitAll()
    }
    for (parallel in levels) {
        if (!measure("cid", parallel, details.size, timeoutSeconds) { client.sampleCid(details[it]) }) break
        delay(1_000)
    }
}

internal suspend fun measure(stage: String, parallel: Int, count: Int, timeoutSeconds: Long, failurePhase: (Int) -> String = { "" }, operation: suspend (Int) -> Any): Boolean {
    val permits = Semaphore(parallel)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val errors = ConcurrentHashMap<String, AtomicInteger>()
    val start = TimeSource.Monotonic.markNow()
    ArchiveHttpMetrics.reset()
    val samples = coroutineScope {
        (0 until count).map { index -> async {
            permits.withPermit {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                val mark = TimeSource.Monotonic.markNow()
                try {
                    withTimeout(timeoutSeconds * 1000) { operation(index) }
                    mark.elapsedNow().inWholeMilliseconds
                } catch (error: Exception) {
                    if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                    val label = if (error is io.github.nihildigit.pikpak.PikPakException)
                        "PikPakException(http=${error.httpStatus};code=${error.errorCode})" else error::class.simpleName ?: "Exception"
                    errors.computeIfAbsent(label + failurePhase(index).let { if (it.isEmpty()) "" else ":$it" }) { AtomicInteger() }.incrementAndGet()
                    null
                } finally { active.decrementAndGet() }
            }
        } }.awaitAll().filterNotNull().sorted()
    }
    val wall = start.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
    fun percentile(fraction: Double) = samples.getOrNull(((samples.size - 1) * fraction).toInt()) ?: -1
    println("$stage,$parallel,$count,${samples.size},${count - samples.size},${peak.get()},${ArchiveHttpMetrics.peak.get()},${ArchiveHttpMetrics.attempts.get()},${ArchiveHttpMetrics.limited.get()},$wall,${percentile(.5)},${percentile(.95)},${"%.2f".format(java.util.Locale.ROOT, samples.size * 1000.0 / wall)},${errors.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.get()}" }}")
    return samples.size == count
}
