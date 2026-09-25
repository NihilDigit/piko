package dev.piko.desktop.winrt

import dev.piko.desktop.update.WindowsInstaller
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
    fun nonInstalledProcess_isNotTreatedAsInstalled() {
        // 协议与通知登记只由 MSI 装的那份写。先前按「命令行以 .exe 结尾」判断，测试进程的 java.exe
        // 也算，跑一次测试就把本机的 magnet 协议改成 java.exe
        assertNull(WindowsInstaller.installedExecutable())
    }
}
