package dev.piko.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.shared.PikoHome
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.memory.cacheBudget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

private const val DESKTOP_IMAGE_DISK_CACHE_BYTES = 512L * 1024L * 1024L
private const val DESKTOP_IMAGE_MIN_MEMORY_BYTES = 32L * 1024L * 1024L
private const val DESKTOP_IMAGE_MAX_MEMORY_BYTES = 128L * 1024L * 1024L

/**
 * Coil 在桌面端默认把磁盘缓存放在系统临时目录的 `coil3_disk_cache`：缩略图是网盘里的私人内容，那里不随数据根目录走，
 * 便携版会留在宿主机上，卸载也不清。改放进数据根目录的 `cache/images`，并显式设定 256 MiB 磁盘上限，避免低磁盘余量时
 * 被 Coil 默认比例缩得过小，使海报墙与列表缩略图跨重启稳定复用。
 * 显式调高 OkHttp 单主机并发上限（默认仅 5），避免高 RTT 线路下瀑布流与海报墙几十张同域缩略图串行排队。
 */
fun installImageLoader() {
    val maxHeap = Runtime.getRuntime().maxMemory()
    val baseMemoryBytes = (maxHeap / 8L)
        .coerceIn(DESKTOP_IMAGE_MIN_MEMORY_BYTES.coerceAtMost(maxHeap / 4L), DESKTOP_IMAGE_MAX_MEMORY_BYTES)
    SingletonImageLoader.setSafe { context ->
        // 延迟到 PikoProxySelector.install 之后再实例化 OkHttpClient，确保缩略图与海报请求跟随应用代理设置
        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 96
                    maxRequestsPerHost = 32
                },
            )
            .connectionPool(ConnectionPool(32, 2, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val memoryCache = MemoryCache.Builder()
            .maxSizeBytes(PikoMemoryGovernor.Shared.policy.value.cacheBudget(baseMemoryBytes))
            .build()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            PikoMemoryGovernor.Shared.policy.collect { policy ->
                memoryCache.maxSize = policy.cacheBudget(baseMemoryBytes)
                memoryCache.trimToSize(memoryCache.maxSize)
            }
        }
        ImageLoader.Builder(context)
            .memoryCache { memoryCache }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(PikoHome.root.resolve("cache/images").toOkioPath())
                    .maxSizeBytes(DESKTOP_IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }
}
