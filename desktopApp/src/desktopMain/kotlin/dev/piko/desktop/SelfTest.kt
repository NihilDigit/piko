package dev.piko.desktop

import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.desktop.winrt.WindowsLinkAssociation
import dev.piko.ui.platform.LinkAssociationState
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 装好的包里跑一段依赖系统真实行为的操作，写出结果后退出，不开窗口。给 CI 的安装冒烟用
 * （desktopApp/package/update-smoke/）：这些行为只有装在真机器上、由真的启动器跑起来才测得出，
 * JVM 单测里的注册表与 LaunchServices 都是假的。
 *
 * 带 `-Dpiko.selftest=<名字>` 启动，结果逐行追加到 `-Dpiko.selftest.out` 指的文件（启动器是窗口程序，
 * 标准输出接不出来），退出码 0 为通过。在单实例锁之前处理：开着的 Piko 不会把它当成后来者转交走。
 */
internal const val SELF_TEST_PROPERTY = "piko.selftest"
private const val SELF_TEST_OUT_PROPERTY = "piko.selftest.out"

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
                    else -> false
                }
                val state = currentLinkState()
                report("registered=$registered state=$state")
                registered && state == LinkAssociationState.Default
            }
            "link-unregister" -> runBlocking {
                val removed = if (WinRTSupport.isWindows) WindowsLinkAssociation.unregister() else false
                val state = currentLinkState()
                report("unregistered=$removed state=$state")
                removed && state == LinkAssociationState.NotDefault
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
    else -> null
}
