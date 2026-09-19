package dev.piko.desktop

import java.io.File
import java.util.Properties

class DesktopSettingsStore(
    private val file: File = File(System.getProperty("user.home"), ".piko/settings.properties"),
) {
    private val properties = Properties().also { values ->
        if (file.isFile) file.inputStream().use(values::load)
    }

    var downloadDirectory: File
        get() = File(
            properties.getProperty("downloadDirectory")
                ?: File(System.getProperty("user.home"), "Downloads/Piko").absolutePath,
        )
        set(value) {
            properties.setProperty("downloadDirectory", value.absolutePath)
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "Piko desktop settings") }
        }
}
