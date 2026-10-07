package dev.piko.desktop.winrt

import dev.piko.desktop.update.WindowsInstaller
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows 原生能力：Toast、AUMID、防锁屏、在资源管理器里打开。magnet 与种子的关联见 [WindowsLinkAssociation]。
 *
 * 设计约束（对照 docmirror4a/winrt-capability-map.md）：
 * - 本应用是非打包（unpackaged）桌面应用：Toast 走 Windows.UI.Notifications，
 *   不碰 AppNotifications / StartupTask 等打包独占 API。
 * - 原生调用全部经 JDK 的 FFM（java.lang.foreign），要求 JDK 22+（见 desktopApp jvmToolchain(25)）。
 *   调用失败一律收敛为 null/false，不向界面抛异常。
 * - WinRT 调用串行跑在同一条专用线程上，线程首次使用时初始化一次 WinRT，见 [WindowsToast]。
 */
object WinRTSupport {
    val isWindows: Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    /**
     * Toast 用的应用标识。未打包的应用要让系统认这个 AUMID，得在
     * HKCU\Software\Classes\AppUserModelId 下登记，见 [ensureNotificationRegistration]。
     * 没登记时 Show 照样返回成功，通知却不会出现，所以开发机上 gradle run 看不到 Toast。
     */
    val APP_USER_MODEL_ID = ShellIdentity.current.appUserModelId

    private val comThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-WinRT").also { it.isDaemon = true }
    }

    // Desktop.browse/open 同步走 ShellExecute，要等浏览器或关联程序接手才返回，冷启动时界面卡住一两秒。
    // 与 comThread 分开：那边的 Toast 调用带超时等待，不该排在一次慢的打开后面
    private val shellThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-Shell").also { it.isDaemon = true }
    }

    /** 在专用线程上执行一次 WinRT 调用。线程第一次用时初始化 WinRT，之后一直保持。 */
    private fun <T> onComThread(action: () -> T): T =
        comThread.submit(
            Callable {
                WindowsToast.initializeThread()
                action()
            },
        ).get(15, TimeUnit.SECONDS)

    /**
     * 设置进程级 AppUserModelID。弹 Toast 的前提之一，必须在建窗口/发 Toast 之前调
     * （main() 入口同步调）。另一半是注册表里的登记，见 [ensureNotificationRegistration]。
     */
    fun ensureAppUserModelId(): Boolean {
        if (!isWindows) return false
        return Shell32AppId.set(APP_USER_MODEL_ID)
    }

    /**
     * 在当前用户下登记 AUMID 的显示名与图标，Toast 才会真正显示。MSI 的开始菜单快捷方式虽也带着这个 AUMID，
     * 用户删了快捷方式通知就没了，所以照样登记。
     * 只由 MSI 装的那份调用（见 WindowsInstaller.installedExecutable）：便携版或测试镜像写的话，
     * 图标会指向它们的目录。便携版另用自己的标识，见 [toastAppId]。卸载时由 MSI 撤掉。
     */
    fun ensureNotificationRegistration(icon: File?): Boolean {
        if (!isWindows) return false
        return registerToastIdentity(APP_USER_MODEL_ID, icon)
    }

    private fun registerToastIdentity(appId: String, icon: File?): Boolean {
        val key = "Software\\Classes\\AppUserModelId\\$appId"
        // 安装版每次启动都会走到这里，值没变就不写，只读两次
        return runCatching {
            val iconOk = icon?.takeIf { it.isFile }?.let { HkcuRegistry.ensureString(key, "IconUri", it.absolutePath) } ?: true
            HkcuRegistry.ensureString(key, "DisplayName", "Piko") && iconOk
        }.getOrDefault(false)
    }

    @Volatile
    private var resolvedToastAppId: String? = null

    /**
     * 发 Toast 用的标识。安装版与开发版是 [APP_USER_MODEL_ID]；便携版没有安装版那份登记（实测未登记的 AUMID
     * 发出的 Toast 进了通知历史，却不弹出横幅），在第一次发通知时按自己的路径另登记一个，
     * 与安装版、别处的便携版互不覆盖图标。不在启动时登记：从不发通知的便携版就不在注册表里留东西。
     * 便携版挪了位置会另登记一个新的，旧的留在原处。
     */
    private fun toastAppId(): String = resolvedToastAppId ?: resolveToastAppId().also { resolvedToastAppId = it }

    private fun resolveToastAppId(): String {
        val exe = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile } ?: return APP_USER_MODEL_ID
        if (runCatching { WindowsInstaller.installedExecutable() }.getOrNull() != null) return APP_USER_MODEL_ID
        val pathHash = MessageDigest.getInstance("SHA-256")
            .digest(exe.absolutePath.lowercase().toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
        val appId = "$APP_USER_MODEL_ID.Portable.$pathHash"
        val icon = System.getProperty("compose.application.resources.dir")?.let { File(it, "app-icon.png") }
        return if (registerToastIdentity(appId, icon)) appId else APP_USER_MODEL_ID
    }

    /** shell32.SetCurrentProcessExplicitAppUserModelID 的最小 FFM 绑定。 */
    private object Shell32AppId {
        private val setAppId: java.lang.invoke.MethodHandle by lazy {
            val linker = java.lang.foreign.Linker.nativeLinker()
            val shell32 = java.lang.foreign.SymbolLookup.libraryLookup(
                "shell32",
                java.lang.foreign.Arena.global(),
            )
            linker.downcallHandle(
                shell32.find("SetCurrentProcessExplicitAppUserModelID").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.JAVA_INT, // HRESULT
                    java.lang.foreign.ValueLayout.ADDRESS, // PCWSTR
                ),
            )
        }

        fun set(appId: String): Boolean =
            runCatching {
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    // NUL 结尾的 UTF-16LE（PCWSTR），手工编码以兼容各 JDK 的 FFM 形态。
                    val utf16 = (appId + "\u0000").toByteArray(Charsets.UTF_16LE)
                    val native = arena.allocate(utf16.size.toLong())
                    native.copyFrom(java.lang.foreign.MemorySegment.ofArray(utf16))
                    val hr = setAppId.invokeWithArguments(native) as Int
                    hr >= 0 // SUCCEEDED(hr)
                }
            }.getOrDefault(false)
    }

    /**
     * 发送系统 Toast。返回 true 表示已提交给系统（用户是否可见还取决于
     * 系统通知设置），false 表示本次没发出去，调用方应做应用内降级提示。
     */
    fun showNotification(title: String, message: String): Boolean {
        if (!isWindows) return false
        return runCatching {
            onComThread {
                val xml = """
                    <toast>
                        <visual>
                            <binding template="ToastGeneric">
                                <text>${escapeXml(title)}</text>
                                <text>${escapeXml(message)}</text>
                            </binding>
                        </visual>
                    </toast>
                """.trimIndent()
                WindowsToast.show(toastAppId(), xml)
            }
            true
        }.getOrDefault(false)
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /**
     * 持有"屏幕常亮"请求，直到返回的 AutoCloseable 被关闭。
     *
     * 刻意走 Win32 `SetThreadExecutionState` 而不是 WinRT `DisplayRequest`：
     * 前者是非打包桌面应用保持唤醒的正统 API，无需 COM apartment；后者在这版
     * 投影里有生成代码 bug（IDisplayRequest 的 ABI glue 类 <clinit> 传了空
     * typeSignature，requestActive 直接 NPE），等上游 kotlin-winrt 修好再评估切回。
     * FFM 在 JDK 22+ 已是稳定 API（要求 JDK 25，见 desktopApp jvmToolchain）。
     */
    fun acquireDisplayRequest(): AutoCloseable? {
        if (!isWindows) return null
        return runCatching {
            Win32ExecutionState.preventSleep()
            AutoCloseable {
                runCatching { Win32ExecutionState.allowSleep() }
            }
        }.getOrNull()
    }

    /** Win32 电源状态：kernel32.SetThreadExecutionState 的最小 FFM 绑定。 */
    private object Win32ExecutionState {
        const val ES_CONTINUOUS = Int.MIN_VALUE // 0x80000000
        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002

        private val setState: java.lang.invoke.MethodHandle by lazy {
            val linker = java.lang.foreign.Linker.nativeLinker()
            val kernel32 = java.lang.foreign.SymbolLookup.libraryLookup(
                "kernel32",
                java.lang.foreign.Arena.global(),
            )
            linker.downcallHandle(
                kernel32.find("SetThreadExecutionState").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.JAVA_INT,
                    java.lang.foreign.ValueLayout.JAVA_INT,
                ),
            )
        }

        fun preventSleep() {
            setState.invokeWithArguments(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
        }

        fun allowSleep() {
            setState.invokeWithArguments(ES_CONTINUOUS)
        }
    }

    fun openUrl(url: String) {
        shellThread.execute { runCatching { Desktop.getDesktop().browse(URI(url)) } }
    }

    fun openFolder(folder: File) = shellThread.execute {
        runCatching {
            if (!folder.exists()) folder.mkdirs()
            if (openWithDesktop(folder)) return@execute
            if (isWindows) {
                ProcessBuilder("explorer", folder.absolutePath).start()
            }
        }
    }

    fun openFile(file: File) = shellThread.execute {
        if (!file.exists()) return@execute
        runCatching {
            if (openWithDesktop(file)) return@execute
            if (isWindows) {
                // start "" <path> 经 shell 走默认关联，比 explorer 更稳。
                ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
            }
        }
    }

    /**
     * 在资源管理器里打开所在文件夹并选中该文件；文件已不在时退回打开文件夹。
     *
     * 经 SHOpenFolderAndSelectItems，不拼 explorer /select 的命令行：路径带空格时 Java 给整个参数
     * 「/select,路径」加上引号，explorer 不认，退回打开「文档」（2026-09-29 出过，下载的剧集名里常有空格）。
     * 在 COM 线程上异步做，不让点击等资源管理器起来。
     */
    fun revealInExplorer(file: File) {
        if (!file.exists()) {
            file.parentFile?.let(::openFolder)
            return
        }
        if (!isWindows) {
            openFolder(file.parentFile ?: file)
            return
        }
        comThread.execute {
            val selected = runCatching {
                WindowsToast.initializeThread()
                ShellReveal.select(file.absolutePath)
            }.getOrDefault(false)
            if (!selected) file.parentFile?.let(::openFolder)
        }
    }

    private fun openWithDesktop(file: File): Boolean =
        runCatching {
            if (!Desktop.isDesktopSupported()) return false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) return false
            desktop.open(file)
            true
        }.getOrDefault(false)
}
