package dev.piko.smoke

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.MainActivity
import dev.piko.PikoApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外部磁力链进入秒传流程的入口。未登录也要记下这条链：登录后主界面会接着处理它。
 */
@RunWith(AndroidJUnit4::class)
class MagnetIntentSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instantRepository get() = PikoApplication.instance.instantMagnetRepository
    private val magnet = "magnet:?xt=urn:btih:${"c".repeat(40)}&dn=smoke"

    @After
    fun clearPending() {
        instantRepository.clearPendingMagnet()
    }

    /**
     * 防两件事：manifest 里 magnet: 协议的注册丢了（浏览器点链接不再唤起本应用）；
     * Activity 重建时把最初那条链再导入一次，秒传面板在用户关掉后又弹出来。
     */
    @Test
    fun magnetLinkIsCapturedOnceAndNotReimportedOnRecreate() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(magnet)).setPackage(context.packageName)
        assertNotNull("manifest 未注册 magnet: 协议", context.packageManager.resolveActivity(view, 0))

        ActivityScenario.launch<MainActivity>(Intent(view).setClass(context, MainActivity::class.java)).use { scenario ->
            awaitCondition("磁力链被记下") { instantRepository.pendingMagnetFlow.value == magnet }
            // 相当于用户关掉了秒传面板
            instantRepository.clearPendingMagnet()
            scenario.recreate()
            assertNull("重建不应再次导入同一条链", instantRepository.pendingMagnetFlow.value)
        }
    }

    /** 防的是分享文本里夹着磁力链时取不出来（聊天软件分享出来的通常不是纯链接）。 */
    @Test
    fun magnetEmbeddedInSharedTextIsExtracted() {
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "资源在这里 $magnet 速度很快")
            .setPackage(context.packageName)
        assertNotNull("manifest 未注册文本分享", context.packageManager.resolveActivity(share, 0))

        ActivityScenario.launch<MainActivity>(Intent(share).setClass(context, MainActivity::class.java)).use {
            awaitCondition("从分享文本中取出磁力链") { instantRepository.pendingMagnetFlow.value != null }
            assertEquals(magnet, instantRepository.pendingMagnetFlow.value)
        }
    }
}
