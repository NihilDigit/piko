package dev.piko.smoke

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.MainActivity
import dev.piko.download.PikoDownloadService
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadServiceSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * 防的是下载服务在新系统上进不了前台：Android 14 起前台服务必须声明类型并持有对应权限，
     * 缺一项 startForeground 就抛异常。服务里把这个异常接住后会自行停止，所以只看「没崩」
     * 不够，要确认它真的处于前台；随后没有任务时它应自行退出，不留一个常驻通知。
     */
    @Test
    fun serviceEntersForegroundThenStepsDownWhenIdle() {
        ActivityScenario.launch(MainActivity::class.java).use {
            PikoDownloadService.start(context)
            awaitCondition("下载服务进入前台", timeoutMs = 5_000) { downloadService()?.foreground == true }
            awaitCondition("无任务时服务自行退出", timeoutMs = 10_000) { downloadService() == null }
        }
    }

    // getRunningServices 对第三方应用已弃用，但仍会返回本应用自己的服务，正好够用
    @Suppress("DEPRECATION")
    private fun downloadService(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .firstOrNull { it.service.className == PikoDownloadService::class.java.name }
}
