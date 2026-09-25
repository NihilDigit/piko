package dev.piko.desktop.winrt

import java.awt.Desktop
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Windows 原生能力：Toast、AUMID、magnet 协议、防锁屏、在资源管理器里打开。
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
    const val APP_USER_MODEL_ID = "dev.piko.Piko"

    private val comThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Piko-WinRT").also { it.isDaemon = true }
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
     * 把 magnet: 协议登记到当前用户（HKCU，无需管理员权限），指向 [exe]。已登记且指向它时直接返回 true。
     *
     * 只该由 MSI 装的那份调用（见 WindowsInstaller.installedExecutable）。原先按进程命令行是否以 .exe
     * 结尾判断，gradle run 的 java.exe 也算，一次开发运行就把协议改成打不开链接的 java.exe。
     */
    fun ensureMagnetProtocolHandler(exe: File): Boolean {
        if (!isWindows) return false
        return runCatching {
            val expected = "\"${exe.absolutePath}\" \"%1\""
            if (currentMagnetCommand()?.equals(expected, ignoreCase = true) == true) return true
            regAdd("HKCU\\Software\\Classes\\magnet", "/ve", "/t", "REG_SZ", "/d", "URL:Magnet Protocol", "/f") &&
                regAdd("HKCU\\Software\\Classes\\magnet", "/v", "URL Protocol", "/t", "REG_SZ", "/d", "Piko", "/f") &&
                regAdd("HKCU\\Software\\Classes\\magnet\\shell\\open\\command", "/ve", "/t", "REG_SZ", "/d", expected, "/f")
        }.getOrDefault(false)
    }

    /**
     * 在当前用户下登记 AUMID 的显示名与图标，Toast 才会真正显示。jpackage 生成的开始菜单
     * 快捷方式不带 System.AppUserModel.ID 属性，靠快捷方式登记这条路走不通。
     * 与 magnet 协议一样只由 MSI 装的那份调用：便携版或测试镜像写的话，图标会指向它们的目录。
     */
    fun ensureNotificationRegistration(icon: File?): Boolean {
        if (!isWindows) return false
        val key = "HKCU\\Software\\Classes\\AppUserModelId\\$APP_USER_MODEL_ID"
        val iconOk = icon?.takeIf { it.isFile }?.let {
            regAdd(key, "/v", "IconUri", "/t", "REG_SZ", "/d", it.absolutePath, "/f")
        } ?: true
        return regAdd(key, "/v", "DisplayName", "/t", "REG_SZ", "/d", "Piko", "/f") && iconOk
    }

    private fun regAdd(vararg args: String): Boolean =
        runCatching {
            ProcessBuilder(listOf("reg", "add") + args)
                .redirectErrorStream(true)
                .start()
                .waitFor(15, TimeUnit.SECONDS)
        }.getOrDefault(false)

    private fun currentMagnetCommand(): String? =
        runCatching {
            val process = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Classes\\magnet\\shell\\open\\command", "/ve",
            ).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            Regex("REG_SZ\\s+(.*)").find(output)?.groupValues?.get(1)?.trim()
        }.getOrNull()

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
                WindowsToast.show(APP_USER_MODEL_ID, xml)
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

    fun openFolder(folder: File) {
        runCatching {
            if (!folder.exists()) folder.mkdirs()
            if (openWithDesktop(folder)) return
            if (isWindows) {
                ProcessBuilder("explorer", folder.absolutePath).start()
            }
        }
    }

    fun openFile(file: File) {
        if (!file.exists()) return
        runCatching {
            if (openWithDesktop(file)) return
            if (isWindows) {
                // start "" <path> 经 shell 走默认关联，比 explorer 更稳。
                ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
            }
        }
    }

    /** 在资源管理器里打开所在文件夹并选中该文件；文件已不在时退回打开文件夹。 */
    fun revealInExplorer(file: File) {
        if (!file.exists()) {
            file.parentFile?.let(::openFolder)
            return
        }
        runCatching {
            if (isWindows) {
                // /select 与路径之间是逗号，整段作为一个参数交给 explorer
                ProcessBuilder("explorer", "/select,${file.absolutePath}").start()
            } else {
                openFolder(file.parentFile ?: file)
            }
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
