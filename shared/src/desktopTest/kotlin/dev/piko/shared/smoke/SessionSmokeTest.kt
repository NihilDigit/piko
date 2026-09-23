package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.state.LoginState
import io.github.nihildigit.pikpak.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 登录态的恢复、失败与退出。PikoClientManager 自建 SDK 客户端，这里经 httpClient 参数接到假服务端。 */
class SessionSmokeTest {
    private val account = "smoke@piko.dev"

    /**
     * 防的是离线冷启动被踢回登录页：access token 过期、refresh 因断网失败时，旧实现关掉 client，
     * 用户连已下载的文件都打不开。现在应保留登录态，网络恢复后在后台补上 refresh。
     */
    @Test
    fun `an offline cold start keeps the session and refreshes once the network is back`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemorySessionStore().apply {
            lastAccount = account
            passwords[account] = "pw"
            sessions[account] = Session(accessToken = "OLD", refreshToken = "RT", sub = "UID", expiresAt = 1L)
        }
        server.offline = true

        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("恢复流程结束") { !manager.isInitializing.value }
        assertNotNull(manager.currentClient.value, "断网不应等同于登录失效")

        server.offline = false
        awaitUntil("网络恢复后会话被刷新", timeoutMs = 15_000) { store.sessions[account]?.accessToken == "AT" }
        assertNotNull(manager.currentClient.value)
    }

    /** 防的是密码错误时界面卡在加载中、或把错误的密码存下来。 */
    @Test
    fun `a rejected password surfaces the reason and stores nothing`() = smoke { scope ->
        val server = FakePikPakServer().apply { rejectSignIn = true }
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("恢复流程结束") { !manager.isInitializing.value }

        val login = LoginState(manager, scope)
        login.updateAccount(account)
        login.updatePassword("wrong")
        login.login()
        awaitUntil("登录请求结束") { !login.isLoggingIn && login.errorMessage != null }

        assertNull(manager.currentClient.value)
        assertTrue(store.passwords.isEmpty(), "失败的密码不能落盘")
        assertNull(store.lastAccount)
    }

    /** 防的是退出登录后明文密码仍留在磁盘上：旧实现从不调用 clearCredentials。 */
    @Test
    fun `logging out forgets the saved password`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("恢复流程结束") { !manager.isInitializing.value }

        val login = LoginState(manager, scope)
        login.updateAccount(account)
        login.updatePassword("pw")
        login.login()
        awaitUntil("登录成功") { manager.currentClient.value != null }
        assertEquals("pw", store.passwords[account])

        manager.logout()
        assertNull(manager.currentClient.value)
        assertTrue(store.passwords.isEmpty())
        assertNull(store.sessions[account])
        assertNull(store.lastAccount)
    }
}
