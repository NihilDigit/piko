package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import java.io.File
import kotlin.math.abs

/** 打包时类路径上 jar 统一的修改时间（毫秒），由 build.gradle.kts 在 AOT 训练前写进启动配置。开发版没有。 */
private const val CLASSPATH_MTIME_PROPERTY = "piko.classpath-mtime"

private const val QUARTER_HOUR_MILLIS = 15 * 60_000L
private const val MAX_ZONE_OFFSET_MILLIS = 14 * 3_600_000L

/**
 * 把类路径上 jar 的修改时间改回打包时的值。
 *
 * AOT 缓存逐个核对类路径上 jar 的大小与修改时间，对不上就整份不用；JDK 25 没有放宽这项的选项，
 * 只有 lib/modules 不核对时间（aotClassLocation.cpp 的 check_time）。MSI 的 cab 存的是不带时区的本地时间，
 * 安装时按安装机的时区解释：CI 在 UTC 打包，装到东八区的 jar 早 8 小时。偏移随安装机而变，打包时取什么值都避不开，
 * 便携版改发 7z 才躲过去，MSI 没有可换的格式。
 *
 * 校验在 JVM 启动时已经做完，这次改回只让下一次启动用上缓存。只改偏移恰为时区差（15 分钟的整数倍、不超过 14 小时）的：
 * 别的差值说明 jar 不是打包时那一份，改了时间反倒让缓存认下它。改回之后 Windows Installer 修复（`/fomus`）不动这个 jar（实测）；
 * `/fa` 强制重装会把时间重新带偏，下次启动再改回。
 */
internal fun restoreClasspathTimes() {
    val expected = System.getProperty(CLASSPATH_MTIME_PROPERTY)?.toLongOrNull() ?: return
    val jars = System.getProperty("java.class.path").orEmpty()
        .split(File.pathSeparator)
        .map(::File)
        .filter { it.isFile && it.extension == "jar" }
    for (jar in jars) {
        val skew = jar.lastModified() - expected
        when {
            skew == 0L -> Unit
            !isZoneOffset(skew) -> PikoLog.w("App", "${jar.name} 的修改时间与打包时差 ${skew / 1000} 秒，不是时区差，AOT 缓存不可用")
            jar.setLastModified(expected) ->
                PikoLog.i("App", "${jar.name} 的修改时间按安装机时区偏了 ${skew / 60_000} 分钟，已改回，AOT 缓存下次启动起可用")
            else -> PikoLog.w("App", "${jar.name} 的修改时间偏了 ${skew / 60_000} 分钟，改不回，AOT 缓存不可用")
        }
    }
}

private fun isZoneOffset(skew: Long): Boolean = skew % QUARTER_HOUR_MILLIS == 0L && abs(skew) <= MAX_ZONE_OFFSET_MILLIS
