package dev.piko.desktop

import java.io.File
import java.util.Properties

class DesktopSettingsStore(
    private val file: File = File(System.getProperty("user.home"), ".piko/settings.properties"),
) {
    private val properties = Properties().also { values ->
        if (file.isFile) file.inputStream().use(values::load)
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Piko desktop settings") }
    }

    /**
     * 下载位置，没选过时是 ~/Downloads/Piko。旧版的「恢复默认」把空路径当成 File("") 存下，
     * 得到的是工作目录，安装版里即程序自己的目录；存的恰好是工作目录时视同没选过，
     * 已被写坏的配置由此自愈，没人会特意把下载放进程序目录。
     */
    val downloadDirectory: File
        get() = properties.getProperty("downloadDirectory")?.let(::File)?.takeUnless { it == File("").absoluteFile }
            ?: File(System.getProperty("user.home"), "Downloads/Piko")

    /** [path] 为空时回到默认位置，删掉存下的值，而不是存一个空路径。 */
    fun setDownloadDirectory(path: String) {
        if (path.isBlank()) properties.remove("downloadDirectory") else properties.setProperty("downloadDirectory", File(path).absolutePath)
        save()
    }

    /** 通用 KV：给 DesktopPikoPreferences 做写穿持久化。调用方约定 key 命名空间。 */
    fun get(key: String, default: String = ""): String =
        properties.getProperty(key) ?: default

    fun set(key: String, value: String) {
        properties.setProperty(key, value)
        save()
    }

    fun keysWithPrefix(prefix: String): List<String> =
        properties.stringPropertyNames().filter { it.startsWith(prefix) }.sorted()

    fun remove(key: String) {
        properties.remove(key)
        save()
    }
}
