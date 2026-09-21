package dev.piko.desktop.winrt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WinRT 冒烟测试。跑在 Windows CI（JDK 25）上时走真实 WinRT，
 * 刻意"大声失败"：原生栈起不来就红，而不是像业务代码那样静默降级。
 */
class WinRTSupportTest {

    @Test
    fun accentColor_matchesPlatform() {
        val accent = WinRTSupport.getSystemAccentColor()
        if (WinRTSupport.isWindows) {
            assertTrue(
                accent != null,
                "Windows 上必须能读到系统强调色。读不到说明 FFM 桥没起来，" +
                    "多半是测试运行时 JDK < 22（要求 JDK 25，见 desktopApp jvmToolchain）。",
            )
        } else {
            assertNull(accent, "非 Windows 必须返回 null，由 Fluent 默认值接管。")
        }
    }

    @Test
    fun darkModeQuery_matchesPlatform() {
        if (!WinRTSupport.isWindows) {
            assertFalse(
                WinRTSupport.isSystemInDarkMode(),
                "非 Windows 必须返回 false（浅色兜底）。",
            )
        }
        // Windows 上只保证不抛，深/浅取决于 CI 机器主题，不断言具体值。
        WinRTSupport.isSystemInDarkMode()
    }

    @Test
    fun displayLease_acquiresAndReleasesCleanly() {
        val lease = WinRTSupport.acquireDisplayRequest()
        try {
            if (WinRTSupport.isWindows) {
                assertTrue(lease != null, "Windows 上必须能拿到 DisplayRequest lease。")
            } else {
                assertNull(lease, "非 Windows 必须返回 null。")
            }
        } finally {
            lease?.close()
        }
    }

    @Test
    fun toastCall_neverThrows() {
        // 不断言送达（取决于系统通知设置与 AUMID 注册），只保证调用路径不抛。
        // Windows CI 上这会真发一条可见通知，属于预期行为。
        WinRTSupport.showNotification("Piko 冒烟测试", "这条通知来自自动化测试，可忽略。")
    }

    @Test
    fun openHelpers_tolerateMissingFiles() {
        // 不存在的路径也不许抛，内部自行处理。
        WinRTSupport.openFolder(java.io.File(System.getProperty("java.io.tmpdir"), "piko-test-nope"))
    }

    @Test
    fun appUserModelId_canBeSet() {
        // 进程级 AUMID 设置：Windows 上必须成功（shell32 直调，无需打包身份）。
        if (WinRTSupport.isWindows) {
            assertTrue(WinRTSupport.ensureAppUserModelId(), "SetCurrentProcessExplicitAppUserModelID 必须成功。")
        } else {
            assertFalse(WinRTSupport.ensureAppUserModelId())
        }
    }

    @Test
    fun magnetProtocolCheck_neverThrows() {
        // 测试进程是 java.exe（非安装版），只保证只读检查不抛、不写注册表。
        // 真正的注册发生在 MSI 安装版首次启动，Windows 真机联调时验证。
        WinRTSupport.ensureMagnetProtocolHandler()
    }
}
