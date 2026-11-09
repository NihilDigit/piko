package dev.piko.ui.image

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient

/**
 * iOS 上 Coil 既没有 OkHttp 也没有 JVM 的 ServiceLoader，取图的网络层必须显式接上：
 * 用宿主传入的辅助传输（`dev.piko.shared.network.createIosAuxiliaryHttpClient`）建 fetcher。
 * 磁盘缓存沿用 Coil 在 iOS 的默认位置（应用的 Caches 目录下的 image_cache），系统清理后可再取回；
 * 缩略图属于账号私有内容，不另外放到 Documents 或全局临时目录。
 *
 * 客户端由宿主持有，在退出时关闭；这里只借用，不负责关闭。必须在创建第一个图片请求之前调用一次。
 * 该文件不实现后台下载、断点续传或预热，只提供前台取图。
 */
fun installIosImageLoader(httpClient: HttpClient) {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = httpClient)) }
            .build()
    }
}
