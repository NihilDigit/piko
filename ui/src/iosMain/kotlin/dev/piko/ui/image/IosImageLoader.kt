package dev.piko.ui.image

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.memory.cacheBudget
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

private const val IOS_IMAGE_DISK_CACHE_BYTES = 256L * 1024L * 1024L
private const val IOS_IMAGE_BASE_MEMORY_BYTES = 32L * 1024L * 1024L

/**
 * iOS 上 Coil 既没有 OkHttp 也没有 JVM 的 ServiceLoader，取图的网络层必须显式接上：
 * 用宿主传入的辅助传输（`dev.piko.shared.network.createIosAuxiliaryHttpClient`）建 fetcher。
 * 磁盘缓存显式定向至应用私有 Caches 下的 `image_cache`（与 `IosCacheManagerFactory` 对齐），
 * 内存缓存遵循 [PikoMemoryGovernor.Shared] 水位策略并在低内存告警时自动收缩。
 *
 * 客户端由宿主持有；必须在创建第一个图片请求之前调用一次。
 */
fun installIosImageLoader(
    httpClient: HttpClient,
    imageCacheDir: String? = null,
) {
    SingletonImageLoader.setSafe { context ->
        val memoryCache = MemoryCache.Builder()
            .maxSizeBytes(PikoMemoryGovernor.Shared.policy.value.cacheBudget(IOS_IMAGE_BASE_MEMORY_BYTES))
            .build()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            PikoMemoryGovernor.Shared.policy.collect { policy ->
                val target = policy.cacheBudget(IOS_IMAGE_BASE_MEMORY_BYTES)
                memoryCache.maxSize = target
                memoryCache.trimToSize(target)
            }
        }
        ImageLoader.Builder(context)
            .memoryCache { memoryCache }
            .components { add(KtorNetworkFetcherFactory(httpClient = httpClient)) }
            .apply {
                if (!imageCacheDir.isNullOrBlank()) {
                    diskCache {
                        DiskCache.Builder()
                            .directory(imageCacheDir.toPath())
                            .maxSizeBytes(IOS_IMAGE_DISK_CACHE_BYTES)
                            .build()
                    }
                }
            }
            .build()
    }
}
