package dev.piko.smoke

import android.content.Context
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.PikoApplication
import dev.piko.data.auth.AndroidPikoSessionStore
import dev.piko.data.auth.DataStoreSessionStore
import dev.piko.data.auth.dataStore
import dev.piko.shared.data.PikoClientManager
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 落在设备上的登录态：会话能在「重启」后恢复，密码不以明文落盘，FileProvider 不外露它们。
 * 这些都依赖真实的 DataStore、AndroidKeyStore 与 FileProvider 配置，只能在设备上验证。
 */
@RunWith(AndroidJUnit4::class)
class SessionStorageSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * 防的是冷启动后需要重新登录：会话经 DataStore 序列化往返、上次账号的记录、恢复流程三者
     * 任一出错都会表现为这样。会话未过期时 SDK 不发请求，所以这里不需要网络也不需要账号。
     */
    @Test
    fun savedSessionRestoresOnColdStartWithoutNetwork() = runBlocking {
        val store = AndroidPikoSessionStore(context, PikoApplication.instance.sessionManager)
        val account = "smoke-restore@piko.dev"
        val previousAccount = store.loadLastAccount()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val expiresAt = System.currentTimeMillis() / 1000 + 3_600
            store.save(account, Session(accessToken = "AT", refreshToken = "RT", sub = "UID", expiresAt = expiresAt))
            store.saveLastAccount(account)

            // 新建一个 manager 就是一次冷启动的恢复流程，读的是同一份落盘数据
            val manager = PikoClientManager(store, scope)
            withTimeout(10_000) { manager.isInitializing.first { !it } }
            assertNotNull("冷启动未能恢复登录态", manager.currentClient.value)
        } finally {
            scope.cancel()
            store.clear(account)
            if (previousAccount != null) store.saveLastAccount(previousAccount) else store.clearLastAccount()
        }
    }

    /** 防的是密码以明文进 DataStore：配合关闭备份，文件被拷走也拿不到密码。 */
    @Test
    fun savedPasswordIsSealedAndReadsBack() = runBlocking {
        val store = DataStoreSessionStore(context)
        val account = "smoke-cipher@piko.dev"
        try {
            store.saveCredentials(account, "s3cret-密码")
            val raw = context.dataStore.data.first()[stringPreferencesKey("pikpak_password_$account")]
            assertNotNull(raw)
            assertFalse("密码以明文落盘", raw!!.contains("s3cret"))
            assertEquals("s3cret-密码", store.loadCredentials(account))
        } finally {
            store.clearCredentials(account)
        }
    }

    /** 防的是升级后旧版本存下的明文密码读不出来，老用户被迫重新登录，或明文一直留着。 */
    @Test
    fun legacyPlaintextPasswordStillWorksAndGetsSealed() = runBlocking {
        val store = DataStoreSessionStore(context)
        val account = "smoke-legacy@piko.dev"
        val key = stringPreferencesKey("pikpak_password_$account")
        try {
            context.dataStore.edit { it[key] = "legacy-pw" }
            assertEquals("legacy-pw", store.loadCredentials(account))
            assertFalse("读过一次后仍是明文", context.dataStore.data.first()[key] == "legacy-pw")
            assertEquals("legacy-pw", store.loadCredentials(account))
        } finally {
            store.clearCredentials(account)
        }
    }

    /**
     * 防两件事：分享或打开已下载文件时 getUriForFile 抛异常（旧配置指向的目录与实际下载目录
     * 对不上）；FileProvider 的授权范围覆盖到 DataStore 所在的内部目录。
     */
    @Test
    fun downloadsAreShareableButDataStoreIsNot() {
        val authority = "${context.packageName}.fileprovider"
        val downloads = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Piko").apply { mkdirs() }
        val downloaded = File(downloads, "smoke.txt").apply { writeText("smoke") }
        try {
            assertEquals("content", FileProvider.getUriForFile(context, authority, downloaded).scheme)
        } finally {
            downloaded.delete()
        }

        val preferences = File(context.filesDir, "datastore/piko_preferences.preferences_pb")
        try {
            FileProvider.getUriForFile(context, authority, preferences)
            fail("DataStore 文件不应能经 FileProvider 授权出去")
        } catch (_: IllegalArgumentException) {
        }
    }
}
