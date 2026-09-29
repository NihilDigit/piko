package dev.piko.desktop

import dev.piko.desktop.winrt.ShellReveal
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsLinkAssociation
import dev.piko.desktop.winrt.WindowsToast
import dev.piko.ui.platform.LinkAssociationState
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 装好的包里跑一段依赖系统真实行为的操作，写出结果后退出，不开窗口。给 CI 的安装冒烟用
 * （desktopApp/package/package-smoke/）：这些行为只有装在真机器上、由真的启动器跑起来才测得出，
 * JVM 单测里的注册表与 LaunchServices 都是假的。
 *
 * 带 `-Dpiko.selftest=<名字>` 启动，结果逐行追加到 `-Dpiko.selftest.out` 指的文件（启动器是窗口程序，
 * 标准输出接不出来），退出码 0 为通过。在单实例锁之前处理：开着的 Piko 不会把它当成后来者转交走。
 */
internal const val SELF_TEST_PROPERTY = "piko.selftest"
private const val SELF_TEST_OUT_PROPERTY = "piko.selftest.out"
private const val SELF_TEST_PATH_ENV = "PIKO_SELFTEST_PATH"

internal fun runSelfTest(name: String): Int {
    val out = System.getProperty(SELF_TEST_OUT_PROPERTY)?.let(::File)
    fun report(line: String) {
        println(line)
        out?.appendText("$line\n")
    }
    val passed = runCatching {
        when (name) {
            // 设为默认打开方式，读回来应当是 Piko。Windows 只写登记、不打开系统设置：全新的 runner 没有 UserChoice，
            // 登记当场生效
            "link-register" -> runBlocking {
                val registered = when {
                    WinRTSupport.isWindows -> WindowsLinkAssociation.writeAndNotify()
                    isMacOs -> MacLinkAssociation.register()
                    isLinux -> LinuxLinkAssociation.register()
                    else -> false
                }
                val state = currentLinkState()
                report("registered=$registered state=$state")
                registered && state == LinkAssociationState.Default
            }
            "link-unregister" -> runBlocking {
                val removed = when {
                    WinRTSupport.isWindows -> WindowsLinkAssociation.unregister()
                    isLinux -> LinuxLinkAssociation.unregister()
                    else -> false
                }
                val state = currentLinkState()
                report("unregistered=$removed state=$state")
                removed && state == LinkAssociationState.NotDefault
            }
            // 在资源管理器里选中环境变量 PIKO_SELFTEST_PATH 指的文件，与传输页「打开所在文件夹」同一个调用。
            // 路径要带空格才验得到那个问题，经 JAVA_TOOL_OPTIONS 传会被空格拆开，所以走环境变量。
            // 这里只看接口答成功；窗口是否真的开在那个文件夹、选中了那个文件，由冒烟脚本经 Shell.Application 查
            // Linux 上看 org.freedesktop.FileManager1 答没答成功，要有实现了它的文件管理器在跑
            "reveal" -> {
                val path = System.getenv(SELF_TEST_PATH_ENV).orEmpty()
                val selected = File(path).isFile && when {
                    WinRTSupport.isWindows -> {
                        WindowsToast.initializeThread()
                        ShellReveal.select(File(path).absolutePath)
                    }
                    isLinux -> LinuxDesktop.revealNow(File(path))
                    else -> false
                }
                report("selected=$selected")
                selected
            }
            // 开窗放 PIKO_SELFTEST_PATH 指的本机视频，见 playbackSelfTest。PIKO_SELFTEST_HOLD 为放通之后窗口再留的秒数
            "play" -> playbackSelfTest(
                File(System.getenv(SELF_TEST_PATH_ENV).orEmpty()),
                holdMillis = (System.getenv("PIKO_SELFTEST_HOLD")?.toLongOrNull() ?: 0L) * 1000,
                report = ::report,
            )
            // 系统接没接下通知。Linux 上要有通知服务（org.freedesktop.Notifications）在跑
            "notify" -> {
                val shown = when {
                    isLinux -> LinuxDesktop.showNotification("Piko 自检", "这是一条测试通知。")
                    isMacOs -> MacOs.showNotification("Piko 自检", "这是一条测试通知。")
                    else -> WinRTSupport.showNotification("Piko 自检", "这是一条测试通知。")
                }
                report("shown=$shown")
                shown
            }
            else -> {
                report("unknown self test: $name")
                false
            }
        }
    }.getOrElse {
        report("self test crashed: ${it.stackTraceToString()}")
        false
    }
    report(if (passed) "PASS" else "FAIL")
    return if (passed) 0 else 1
}

private suspend fun currentLinkState(): LinkAssociationState? = when {
    WinRTSupport.isWindows -> WindowsLinkAssociation.state()
    isMacOs -> MacLinkAssociation.state()
    isLinux -> LinuxLinkAssociation.state()
    else -> null
}
