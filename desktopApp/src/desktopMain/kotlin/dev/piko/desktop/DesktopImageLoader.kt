package dev.piko.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.shared.PikoHome
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Coil 在桌面端默认把磁盘缓存放在系统临时目录的 `coil3_disk_cache`：缩略图是网盘里的私人内容，那里不随数据根目录走，
 * 便携版会留在宿主机上，卸载也不清。改放进数据根目录的 `cache/images`，容量沿用 Coil 的默认（磁盘空间的 2%，限在 10 至 250 MiB）。
 * 旧目录不删：这个名字是 Coil 的通用默认值，别的应用可能也在用。
 * 显式调高 OkHttp 单主机并发上限（默认仅 5），避免高 RTT 线路下瀑布流与海报墙几十张同域缩略图串行排队。
 */
internal fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        // 延迟到 PikoProxySelector.install 之后再实例化 OkHttpClient，确保缩略图与海报请求跟随应用代理设置
        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 96
                maxRequestsPerHost = 32
            })
            .connectionPool(ConnectionPool(32, 2, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .diskCache { DiskCache.Builder().directory(PikoHome.root.resolve("cache/images").toOkioPath()).build() }
            .build()
    }
}
