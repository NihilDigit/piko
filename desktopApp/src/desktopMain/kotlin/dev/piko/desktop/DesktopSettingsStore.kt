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

    var downloadDirectory: File
        get() = File(
            properties.getProperty("downloadDirectory")
                ?: File(System.getProperty("user.home"), "Downloads/Piko").absolutePath,
        )
        set(value) {
            properties.setProperty("downloadDirectory", value.absolutePath)
            save()
        }

    var themeMode: String
        get() = properties.getProperty("themeMode") ?: "system"
        set(value) {
            properties.setProperty("themeMode", value)
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
