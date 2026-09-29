package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Linux 上把 magnet: 链接与 .torrent 文件的默认打开方式设为 Piko，照 freedesktop 的做法：
 * 在 ~/.local/share/applications 写一个声明了 MimeType 的 .desktop 文件，再由 xdg-mime default 写进 mimeapps.list。
 * xdg-open、浏览器与文件管理器都按 mimeapps.list 找处理程序，改完当场生效，不必用户再确认。
 *
 * 只在以 AppImage 运行时可用：.desktop 的 Exec 要一个稳定的可执行文件，AppImage 每次挂载在不同的临时目录下，
 * 只有 AppImage 文件本身的路径不变。开发版与解开的 app-image 报 Unavailable。
 * Flatpak 里由 flatpak 导出的 .desktop 声明 MimeType，默认程序由用户在系统设置里选，同样报 Unavailable。
 *
 * .desktop 设 NoDisplay：它只为接住链接，不往应用菜单里加一项。菜单项交给 AppImageLauncher、Gear Lever
 * 这类集成工具，它们另写自己的 .desktop，两者并存不冲突。
 */
internal object LinuxLinkAssociation : LinkAssociation {
    private const val TAG = "LinkAssociation"
    private val MIME_TYPES = listOf("x-scheme-handler/magnet", "application/x-bittorrent")
    private const val DESKTOP_FILE = "${LinuxDesktop.APP_ID}.desktop"

    override val needsSystemConfirmation: Boolean = false

    // 删掉自己写的 .desktop 与 mimeapps.list 里指向它的几行，系统就回到用户原先的选择或没有默认
    override val canUnregister: Boolean = true

    private val dataHome: File
        get() = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")

    private val configHome: File
        get() = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")

    private val desktopFile: File get() = dataHome.resolve("applications/$DESKTOP_FILE")

    private fun available(): Boolean = !LinuxDesktop.isFlatpak && LinuxDesktop.appImage != null

    override suspend fun state(): LinkAssociationState = withContext(Dispatchers.IO) {
        if (!available()) return@withContext LinkAssociationState.Unavailable
        // xdg-mime 不在（没装 xdg-utils）时既读不了也设不了
        if (!LinuxDesktop.run("xdg-mime", "--version")) return@withContext LinkAssociationState.Unavailable
        // 没有默认程序时它什么也不输出，有的版本还以非零退出，都算不是 Piko
        val defaults = MIME_TYPES.map { LinuxDesktop.output("xdg-mime", "query", "default", it)?.trim().orEmpty() }
        val ours = defaults.all { it == DESKTOP_FILE } && desktopFile.isFile
        if (ours) LinkAssociationState.Default else LinkAssociationState.NotDefault
    }

    override suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        val appImage = LinuxDesktop.appImage ?: return@withContext false
        if (!available()) return@withContext false
        runCatching {
            writeDesktopFile(appImage)
            installIcon()
            // 刷新 mimeinfo.cache：有的桌面环境按它列「打开方式」，没有这个命令也不影响 mimeapps.list 生效
            LinuxDesktop.run("update-desktop-database", "-q", desktopFile.parentFile.absolutePath)
            check(LinuxDesktop.run("xdg-mime", "default", DESKTOP_FILE, *MIME_TYPES.toTypedArray())) { "xdg-mime default 失败" }
        }.onFailure { PikoLog.w(TAG, "设为默认打开方式失败", it) }.getOrElse { return@withContext false }
        state() == LinkAssociationState.Default
    }

    override suspend fun unregister(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            removeFromMimeApps(configHome.resolve("mimeapps.list"))
            desktopFile.delete()
            LinuxDesktop.run("update-desktop-database", "-q", desktopFile.parentFile.absolutePath)
        }.onFailure { PikoLog.w(TAG, "取消关联失败", it) }.isSuccess && state() != LinkAssociationState.Default
    }

    /**
     * AppImage 挪了位置或换了名字之后，登记过的 .desktop 仍指着旧路径，链接就打不开了。
     * 每次以 AppImage 启动时，登记过的话把 Exec 改成眼下的路径；没登记过不写。
     */
    fun refreshIfRegistered() {
        val appImage = LinuxDesktop.appImage ?: return
        if (LinuxDesktop.isFlatpak || !desktopFile.isFile) return
        runCatching {
            if (desktopFile.readText() != desktopEntry(appImage)) {
                writeDesktopFile(appImage)
                PikoLog.i(TAG, "AppImage 位置变了，已更新 .desktop 的 Exec")
            }
        }.onFailure { PikoLog.w(TAG, "更新 .desktop 失败", it) }
    }

    private fun writeDesktopFile(appImage: File) {
        desktopFile.parentFile.mkdirs()
        val temp = File(desktopFile.parentFile, ".$DESKTOP_FILE.tmp")
        temp.writeText(desktopEntry(appImage))
        check(temp.renameTo(desktopFile)) { "写不进 ${desktopFile.parentFile}" }
    }

    private fun desktopEntry(appImage: File): String = """
        [Desktop Entry]
        Type=Application
        Name=Piko
        Comment=PikPak 第三方客户端
        Exec=${execArgument(appImage.absolutePath)} %U
        Icon=${LinuxDesktop.APP_ID}
        Terminal=false
        NoDisplay=true
        StartupWMClass=${LinuxDesktop.APP_ID}
        MimeType=${MIME_TYPES.joinToString(";")};
        Categories=Network;FileTransfer;

    """.trimIndent()

    /**
     * Exec 里的一个参数。按桌面项规范加双引号，引号里的 "、`、$、\ 前加反斜杠；键值本身又把反斜杠当转义，
     * 所以反斜杠要再翻一倍。% 写成 %%，免得被当成域代码。
     */
    private fun execArgument(path: String): String {
        val quoted = buildString {
            for (c in path) {
                if (c in "\"`$\\") append('\\')
                append(c)
            }
        }
        return "\"" + quoted.replace("\\", "\\\\").replace("%", "%%") + "\""
    }

    /** 通知与 .desktop 用的图标，放进用户的 hicolor 主题，按应用 ID 取名。 */
    private fun installIcon() {
        val source = System.getProperty("compose.application.resources.dir")?.let { File(it, "app-icon.png") }?.takeIf { it.isFile } ?: return
        val target = dataHome.resolve("icons/hicolor/256x256/apps/${LinuxDesktop.APP_ID}.png")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    /** 从 mimeapps.list 各节里去掉指向 Piko 的项；一行只剩 Piko 时整行删掉。 */
    private fun removeFromMimeApps(file: File) {
        if (!file.isFile) return
        val lines = file.readLines().mapNotNull { line ->
            val key = line.substringBefore('=', "").trim()
            if (key !in MIME_TYPES) return@mapNotNull line
            val rest = line.substringAfter('=').split(';').map { it.trim() }.filter { it.isNotEmpty() && it != DESKTOP_FILE }
            if (rest.isEmpty()) null else "$key=${rest.joinToString(";")};"
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
