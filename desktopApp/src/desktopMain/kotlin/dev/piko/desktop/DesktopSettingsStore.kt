package dev.piko.desktop

import dev.piko.shared.PikoHome
import java.io.File
import java.util.Properties

class DesktopSettingsStore(
    private val file: File = PikoHome.root.resolve("settings.properties").toFile(),
    programDirectory: File? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parentFile },
    workingDirectory: File = File("").absoluteFile,
) {
    private val properties = Properties().also { values ->
        if (file.isFile) file.inputStream().use(values::load)
    }

    init {
        dropBrokenDownloadDirectory(programDirectory, workingDirectory)
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, "Piko desktop settings") }
    }

    /**
     * 1.0.0 的「恢复默认」把空路径当成 File("") 存下，得到的是当时的工作目录：从开始菜单或 zip 里打开时是程序目录，
     * macOS 从访达打开时是 /。删掉这个键，回到默认位置。
     *
     * 只认当前工作目录不够：经磁力链接或种子关联拉起时工作目录是别处，写坏的值认不出，下载就落进安装目录，
     * 卸载与整包升级时随安装目录一起删掉。所以另与程序目录比较，落在它之内的都算：没人会特意把下载放进程序目录。
     */
    private fun dropBrokenDownloadDirectory(programDirectory: File?, workingDirectory: File) {
        val stored = properties.getProperty("downloadDirectory")?.let { File(it).toPath().normalize() } ?: return
        val inProgram = programDirectory != null && stored.startsWith(programDirectory.toPath().normalize())
        if (inProgram || stored == workingDirectory.toPath().normalize()) {
            properties.remove("downloadDirectory")
            save()
        }
    }

    /** 下载位置，没选过时是 ~/Downloads/Piko。 */
    val downloadDirectory: File
        get() = properties.getProperty("downloadDirectory")?.let(::File)
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
