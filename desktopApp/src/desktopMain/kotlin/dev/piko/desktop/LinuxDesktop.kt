package dev.piko.desktop

import dev.piko.shared.log.PikoLog
import java.awt.SystemTray
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.jetbrains.skia.FontMgr

internal val isLinux: Boolean = System.getProperty("os.name").startsWith("Linux")

/**
 * Linux 上与 WinRTSupport、MacOs 对应的系统能力，调用方先判断 [isLinux]。
 *
 * 打开链接与文件、通知、在文件管理器里显示都交给 xdg-open 与 D-Bus（经 gdbus），不走 AWT 的 Desktop：
 * 它在 Linux 上靠 GTK 实现，会把系统的 GTK 与 glib 载入本进程，而 mpv 的运行库自带一份 glib，
 * 两份同名库谁先进来谁生效，另一方可能缺符号。xdg-open 在 Flatpak 里也照样能用，沙箱把它转给门户。
 */
internal object LinuxDesktop {
    private const val TAG = "Linux"

    /** .desktop、AppStream、图标与 WM_CLASS 共用的应用 ID，与 build.gradle.kts 的 linuxAppId 相同，不再改。 */
    const val APP_ID = "dev.nihildigit.Piko"

    /** 在 Flatpak 沙箱里运行。更新由 flatpak 负责，应用内更新整个关掉。 */
    val isFlatpak: Boolean = System.getenv("FLATPAK_ID") != null || File("/.flatpak-info").exists()

    /** 以 AppImage 运行时，AppImage 文件本身。它的运行时把路径写进 APPIMAGE 环境变量。 */
    val appImage: File? = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    // 外部程序一律在这条线程上起：xdg-open 与 gdbus 要等对方答复，不该压在界面线程上
    private val launcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-Xdg").also { it.isDaemon = true }
    }

    /**
     * 把 X11 窗口的 WM_CLASS 改成应用 ID。AWT 默认取主类名（dev-piko-desktop-MainKt），桌面环境按它找
     * .desktop 文件，对不上时 Dock 与任务栏里只是一个没有图标的 java。没有公开的设置方法，改 XToolkit 的
     * 静态字段，要 --add-opens java.desktop/sun.awt.X11（见 build.gradle.kts 的 linuxJvmArgs）。
     * 在建任何窗口之前调；XToolkit 在首次取 Toolkit 时初始化，之后再改才不会被它盖回去。
     */
    fun setWmClass() {
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(null, APP_ID)
        }.onFailure { PikoLog.w(TAG, "设置 WM_CLASS 失败，桌面环境可能认不出窗口", it) }
    }

    /**
     * 界面字体。汉字交给 fontconfig 的后备时按系统语言挑，英文环境下常落到日文字形，一个词里两种字形，
     * 与 Windows 上指定雅黑、mac 上指定苹方同理，这里按顺序挑一个装了的简体中文字体。都没有时为 null，用默认字体。
     */
    val cjkFontFamily: String? by lazy {
        val candidates = listOf("Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "Source Han Sans CN", "WenQuanYi Micro Hei", "Droid Sans Fallback")
        runCatching {
            val fonts = FontMgr.default
            candidates.firstOrNull { name -> fonts.matchFamily(name).use { it.count() > 0 } }
        }.getOrNull()
    }

    /** 关窗后在后台传输时的提示。GNOME 默认没有托盘，AWT 的托盘在那里不可用，只能再次打开 Piko 叫回窗口。 */
    fun backgroundHint(): String =
        if (runCatching { SystemTray.isSupported() }.getOrDefault(false)) "可从系统托盘图标重新打开" else "再次打开 Piko 即可回到窗口"

    /**
     * 系统通知，经 org.freedesktop.Notifications。gdbus 不在时退到 notify-send。
     * desktop-entry 提示让通知中心按应用 ID 归到 Piko 名下。
     */
    fun showNotification(title: String, message: String): Boolean {
        val icon = System.getProperty("compose.application.resources.dir")?.let { File(it, "app-icon.png") }?.takeIf { it.isFile }?.absolutePath ?: APP_ID
        val viaDbus = run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            gvariantString("Piko"), "0", gvariantString(icon), gvariantString(title), gvariantString(message),
            "[]", "{'desktop-entry': <${gvariantString(APP_ID)}>}", "-1",
        )
        return viaDbus || run("notify-send", "--app-name=Piko", "--icon=$icon", title, message)
    }

    /**
     * 阻止空闲熄屏与休眠，直到返回值被关闭。经 systemd-inhibit 向 logind 登记，它守着的子进程
     * 用 tail --pid 盯着本进程：Piko 崩溃时子进程随之退出，登记也就撤了，不会一直不让睡。
     */
    fun preventSleep(): AutoCloseable? = runCatching {
        val process = ProcessBuilder(
            "systemd-inhibit", "--what=idle:sleep", "--who=Piko", "--why=正在播放视频", "--mode=block",
            "tail", "--pid=${ProcessHandle.current().pid()}", "-f", "/dev/null",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        AutoCloseable { process.descendants().forEach { it.destroy() }; process.destroy() }
    }.getOrNull()

    /** 用默认程序打开链接、文件或文件夹。 */
    fun open(target: String) {
        launcher.execute {
            if (!run("xdg-open", target)) PikoLog.w(TAG, "xdg-open 没能打开")
        }
    }

    /**
     * 在文件管理器里打开所在文件夹并选中该文件，经 org.freedesktop.FileManager1.ShowItems（Nautilus、Dolphin、
     * Nemo 等都实现了它）。没有文件管理器接这个接口、或文件已不在时，退回打开所在文件夹。
     */
    fun reveal(file: File) {
        launcher.execute { revealNow(file) }
    }

    /** [reveal] 的同步版本，返回是否经 FileManager1 选中了文件。自检用。 */
    fun revealNow(file: File): Boolean {
        val selected = file.exists() && run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.FileManager1",
            "--object-path", "/org/freedesktop/FileManager1",
            "--method", "org.freedesktop.FileManager1.ShowItems",
            "[${gvariantString(fileUri(file))}]", gvariantString(""),
        )
        if (!selected) (file.takeIf { it.isDirectory } ?: file.parentFile)?.let { run("xdg-open", it.absolutePath) }
        return selected
    }

    /**
     * file:// URI，除 RFC 3986 的非保留字符与斜杠外一律百分号编码。FileManager1 只收 URI，
     * 编码后也不会有引号与反斜杠，拼进 GVariant 的字符串字面量里不必另外转义。
     */
    fun fileUri(file: File): String {
        val bytes = file.absoluteFile.normalize().path.toByteArray(Charsets.UTF_8)
        val path = buildString {
            for (byte in bytes) {
                val c = byte.toInt() and 0xFF
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~/")) append(c.toChar()) else append("%%%02X".format(c))
            }
        }
        return "file://$path"
    }

    /** GVariant 文本格式的字符串字面量。gdbus 把参数按 GVariant 解析，不加引号的话 "true"、"1" 这类文字会被当成别的类型。 */
    private fun gvariantString(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    /** 跑一个外部程序等它退出，退出码为 0 即成功。程序不存在或超时算失败。 */
    fun run(vararg command: String, timeoutSeconds: Long = 15): Boolean = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    /** 跑一个外部程序，退出码为 0 时返回它的标准输出。 */
    fun output(vararg command: String, timeoutSeconds: Long = 15): String? = runCatching {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching null
        }
        text.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}
