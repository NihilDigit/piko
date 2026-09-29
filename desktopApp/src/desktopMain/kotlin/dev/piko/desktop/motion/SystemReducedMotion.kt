package dev.piko.desktop.motion

import dev.piko.desktop.isLinux
import dev.piko.desktop.isMacOs
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.log.PikoLog
import dev.piko.ui.theme.PikoMotionScale
import java.awt.KeyboardFocusManager
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BOOLEAN
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 系统的「减少动画」接到 [PikoMotionScale.systemScale]。Compose Desktop 自己不读任何系统设置，也不提供
 * MotionDurationScale（只有 iOS 有一份），所以三个平台各自读：
 * - Windows：SPI_GETCLIENTAREAANIMATION，即「设置 → 辅助功能 → 视觉效果 → 动画效果」。
 * - macOS：NSWorkspace.accessibilityDisplayShouldReduceMotion，即「辅助功能 → 显示 → 减弱动态效果」。
 * - Linux：GNOME 的 org.gnome.desktop.interface enable-animations。读不到（别的桌面、没有 gsettings）当作没开。
 *
 * 何时重读：Windows 上系统改设置时向顶层窗口广播 WM_SETTINGCHANGE，经 WindowsCaption 的窗口过程转到 [onSystemSettingChange]，
 * 当场生效。三个平台都在应用重新被激活时再读一次：改设置要切到系统设置里去，切回来就是一次激活。
 * macOS 的变化本可以订阅 accessibilityDisplayOptionsDidChangeNotification，但那要经 FFM 造一个 Objective-C
 * 观察者对象（block 或运行时建类），本机没有 Mac 验证，退而用激活时重读。
 */
internal object SystemReducedMotion {
    private const val TAG = "Motion"

    // 读系统设置可能要起进程（gsettings），不放在界面线程；单线程，免得两次读的结果乱序写回
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "Piko-ReducedMotion").apply { isDaemon = true } }

    @Volatile
    private var scale: PikoMotionScale? = null

    fun follow(target: PikoMotionScale) {
        scale = target
        refresh()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow") { event ->
            // 从别的应用切回来：之前没有活动窗口，现在有了
            if (event.oldValue == null && event.newValue != null) refresh()
        }
    }

    /** 系统设置变了（Windows 的 WM_SETTINGCHANGE），在工具包线程上调用。 */
    fun onSystemSettingChange() = refresh()

    private fun refresh() {
        val target = scale ?: return
        reader.execute {
            val reduced = runCatching { readReduced() }
                .onFailure { PikoLog.w(TAG, "读取系统的减少动画设置失败", it) }
                .getOrNull() ?: return@execute
            val value = if (reduced) 0f else 1f
            if (target.systemScale != value) {
                PikoLog.i(TAG, if (reduced) "系统开启了减少动画" else "系统关闭了减少动画")
                target.systemScale = value
            }
        }
    }

    /** 为 null 表示这个平台读不到。 */
    private fun readReduced(): Boolean? = when {
        WinRTSupport.isWindows -> !Windows.clientAreaAnimation()
        isMacOs -> Mac.shouldReduceMotion()
        isLinux -> Gnome.animationsEnabled()?.not()
        else -> null
    }

    private object Windows {
        private const val SPI_GETCLIENTAREAANIMATION = 0x1042

        private val systemParametersInfo = Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("user32", Arena.global()).find("SystemParametersInfoW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
        )

        fun clientAreaAnimation(): Boolean = Arena.ofConfined().use { arena ->
            val enabled = arena.allocate(JAVA_INT)
            val ok = systemParametersInfo.invokeWithArguments(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) as Int
            check(ok != 0) { "SystemParametersInfoW 失败" }
            enabled.get(JAVA_INT, 0) != 0
        }
    }

    /** 未在 Mac 上实测。objc_msgSend 在 arm64 上不是变参函数，按真实签名（无参数、返回 BOOL）取句柄。 */
    private object Mac {
        private val linker = Linker.nativeLinker()
        private val libobjc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
        private val getClass = linker.downcallHandle(libobjc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val registerName = linker.downcallHandle(libobjc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val msgSend = libobjc.find("objc_msgSend").orElseThrow()
        private val sendObject = linker.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        private val sendBool = linker.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS))

        fun shouldReduceMotion(): Boolean = Arena.ofConfined().use { arena ->
            val workspaceClass = getClass.invokeWithArguments(arena.allocateFrom("NSWorkspace")) as MemorySegment
            check(workspaceClass != MemorySegment.NULL) { "找不到 NSWorkspace" }
            // sharedWorkspace 是单例，不经 autorelease，不必包自动释放池
            val workspace = sendObject.invokeWithArguments(workspaceClass, selector(arena, "sharedWorkspace")) as MemorySegment
            sendBool.invokeWithArguments(workspace, selector(arena, "accessibilityDisplayShouldReduceMotion")) as Boolean
        }

        private fun selector(arena: Arena, name: String): MemorySegment =
            registerName.invokeWithArguments(arena.allocateFrom(name)) as MemorySegment
    }

    private object Gnome {
        fun animationsEnabled(): Boolean? {
            val process = runCatching {
                ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return null
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            if (process.exitValue() != 0) return null
            return when (process.inputStream.bufferedReader().readText().trim()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
}
