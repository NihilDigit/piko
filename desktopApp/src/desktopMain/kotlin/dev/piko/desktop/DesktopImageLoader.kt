package dev.piko.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import dev.piko.shared.PikoHome
import okio.Path.Companion.toOkioPath

/**
 * Coil 在桌面端默认把磁盘缓存放在系统临时目录的 `coil3_disk_cache`：缩略图是网盘里的私人内容，那里不随数据根目录走，
 * 便携版会留在宿主机上，卸载也不清。改放进数据根目录的 `cache/images`，容量沿用 Coil 的默认（磁盘空间的 2%，限在 10 至 250 MiB）。
 * 旧目录不删：这个名字是 Coil 的通用默认值，别的应用可能也在用。
 * 网络取图仍由 coil-network-okhttp 经 ServiceLoader 自动接上，这里不另配。
 */
internal fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .diskCache { DiskCache.Builder().directory(PikoHome.root.resolve("cache/images").toOkioPath()).build() }
            .build()
    }
}
