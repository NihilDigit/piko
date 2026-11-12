package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import dev.piko.shared.state.LoginState
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionSmokeTest {
    private val account = "smoke@piko.dev"

    @Test
    fun `immediate local account restore publishes before background coroutine advances`() = smoke { scope ->
        val localAccount = "restore@piko.dev"
        val store = MemorySessionStore().apply {
            accounts = SavedAccounts(current = localAccount, accounts = listOf(SavedAccount(localAccount)))
            sessions[localAccount] = Session(
                accessToken = "LOCAL",
                refreshToken = "REFRESH",
                sub = "local",
                expiresAt = kotlin.time.Clock.System.now().epochSeconds + 3600,
            )
        }
        val server = FakePikPakServer()
        val job = SupervisorJob()
        try {
            val manager = PikoClientManager(
                store,
                CoroutineScope(scope.coroutineContext + job),
                httpClient = server.httpClient(),
                restoreLocallyImmediately = true,
            )
            assertFalse(manager.isInitializing.value)
            assertEquals(localAccount, manager.currentClient.value?.account)
            assertEquals(0, server.count("POST /v1/auth/"))
            manager.currentClient.value?.close()
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `an offline cold start keeps the session and refreshes once the network is back`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemorySessionStore().apply {
            accounts = SavedAccounts(account, listOf(SavedAccount(account)))
            passwords[account] = "pw"
            sessions[account] = Session(accessToken = "OLD", refreshToken = "RT", sub = "UID", expiresAt = 1L)
        }
        server.offline = true

        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }
        assertNotNull(manager.currentClient.value)

        server.offline = false
        awaitUntil("session refreshed", timeoutMs = 15_000) { store.sessions[account]?.accessToken == "AT" }
        assertNotNull(manager.currentClient.value)
    }

    @Test
    fun `a rejected password surfaces the reason and stores nothing`() = smoke { scope ->
        val server = FakePikPakServer().apply { rejectSignIn = true }
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }

        val login = LoginState(manager, scope)
        login.updateAccount(account)
        login.updatePassword("wrong")
        login.login()
        awaitUntil("login failed") { !login.isLoggingIn && login.errorMessage != null }

        assertNull(manager.currentClient.value)
        assertTrue(store.passwords.isEmpty())
        assertNull(store.accounts.current)
    }

    @Test
    fun `logging out of one account switches to another and keeps its credentials`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }
        val other = "other@piko.dev"

        manager.login(account) { "pw-a" }.getOrThrow()
        manager.beginAddingAccount()
        manager.login(other) { "pw-b" }.getOrThrow()
        assertEquals(other, manager.currentClient.value?.account)
        assertEquals(false, manager.addingAccount.value)
        assertEquals(listOf(account, other), manager.accounts.value.accounts.map { it.account })

        manager.switchTo(account).getOrThrow()
        assertEquals(account, manager.currentClient.value?.account)
        assertEquals(account, store.accounts.current)

        manager.logout().join()
        assertEquals(other, manager.currentClient.value?.account)
        assertNull(store.passwords[account])
        assertEquals("pw-b", store.passwords[other])
        assertEquals(listOf(other), store.accounts.accounts.map { it.account })
    }

    @Test
    fun `logging out forgets the saved password`() = smoke { scope ->
        val server = FakePikPakServer()
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }

        val login = LoginState(manager, scope)
        login.updateAccount(account)
        login.updatePassword("pw")
        login.login()
        awaitUntil("login succeeded") { manager.currentClient.value != null }
        assertEquals("pw", store.passwords[account])

        manager.logout().join()
        assertNull(manager.currentClient.value)
        assertTrue(store.passwords.isEmpty())
        assertNull(store.sessions[account])
        assertNull(store.accounts.current)
    }

    @Test
    fun `google device login stores internal key safely and deduplicates with password login by sub`() = smoke { scope ->
        val server = FakePikPakServer().apply {
            deviceAuthPendingPolls.set(1)
            deviceLoginUser = FakePikPakServer.FakeUser(
                sub = "sub-google-42",
                name = "Alice",
                email = "a***@gmail.com",
                providerId = "google.com",
            )
            usersByUsername["alice@gmail.com"] = FakePikPakServer.FakeUser(
                sub = "sub-google-42",
                name = "Alice",
                email = "a***@gmail.com",
                providerId = "password",
            )
        }
        val store = MemorySessionStore()
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }

        val openedUrls = mutableListOf<String>()
        val login = LoginState(manager, scope)
        login.startGoogleLogin { openedUrls += it }
        awaitUntil("google login succeeded", timeoutMs = 15_000) { manager.currentClient.value != null }

        assertEquals(1, openedUrls.size)
        assertTrue(openedUrls.single().startsWith("https://mypikpak.com/auth?type=google&init=1&origin="))
        val slotKey = "pikpak:sub-google-42"
        assertEquals(slotKey, manager.currentClient.value?.account)
        val saved = assertNotNull(manager.accounts.value.find(slotKey))
        assertEquals("Alice", saved.displayName)
        assertNull(saved.publicAccountHandle)
        assertEquals("", manager.resolvePrefillContext(slotKey)?.loginAccount)

        // 同一个 PikPak 账号随后改用邮箱+密码登录：按 sub 复用同一存储槽位，不产生重复账号，且 deviceId 同步持久化
        manager.beginAddingAccount()
        manager.login("alice@gmail.com") { "pw-alice" }.getOrThrow()
        assertEquals(listOf(slotKey), manager.accounts.value.accounts.map { it.account })
        val afterPw = assertNotNull(manager.accounts.value.find(slotKey))
        assertEquals("alice@gmail.com", afterPw.loginAccount)
        assertTrue(afterPw.deviceId.isNotBlank())
        assertEquals(afterPw.deviceId, store.sessions[slotKey]?.deviceId)
        assertEquals("pw-alice", store.passwords[slotKey])

        // 再次通过 Google 登录同一账号：保留已保存的密码与槽位键，并将 SavedAccount 与 Session 的 deviceId 同步到新令牌绑定的设备号
        manager.beginAddingAccount()
        val tx = manager.startGoogleDeviceLogin().getOrThrow()
        manager.awaitGoogleDeviceLogin(tx).getOrThrow()
        assertEquals(listOf(slotKey), manager.accounts.value.accounts.map { it.account })
        assertEquals("pw-alice", store.passwords[slotKey])
        val afterSecondGoogle = assertNotNull(manager.accounts.value.find(slotKey))
        assertTrue(afterSecondGoogle.deviceId.isNotBlank())
        assertEquals(afterSecondGoogle.deviceId, store.sessions[slotKey]?.deviceId)
    }

    @Test
    fun `reauthenticating an expired google account rejects mismatched sub and skips captcha init`() = smoke { scope ->
        val server = FakePikPakServer().apply {
            rejectRefresh = true
            deviceLoginUser = FakePikPakServer.FakeUser(
                sub = "sub-wrong-user",
                name = "Bob",
                email = "b***@gmail.com",
                providerId = "google.com",
            )
        }
        val expiredKey = "pikpak:sub-original"
        val store = MemorySessionStore().apply {
            accounts = SavedAccounts(
                current = expiredKey,
                accounts = listOf(
                    SavedAccount(
                        account = expiredKey,
                        sub = "sub-original",
                        name = "Original",
                        email = "o***@gmail.com",
                        authMethod = dev.piko.shared.data.AccountAuthMethod.Google,
                    ),
                ),
            )
            sessions[expiredKey] = Session(
                accessToken = "EXPIRED",
                refreshToken = "REVOKED",
                sub = "sub-original",
                expiresAt = 1L,
            )
        }
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }

        // 无密码会话刷新被拒后直接回登录页，绝不浪费一次 /v1/shield/captcha/init
        assertNull(manager.currentClient.value)
        assertEquals(0, server.count("POST /v1/shield/captcha/init"))
        assertTrue(expiredKey in manager.expiredAccounts.value)

        val login = LoginState(manager, scope, manager.expiredPrefill)
        assertEquals("", login.account, "内部键 pikpak:<sub> 绝不能填入用户名输入框")
        login.startGoogleLogin {}
        awaitUntil("mismatch rejected", timeoutMs = 15_000) {
            login.browserAuth is dev.piko.shared.state.BrowserAuthState.Idle && login.errorMessage != null
        }
        assertNull(manager.currentClient.value)
        assertEquals(listOf(expiredKey), manager.accounts.value.accounts.map { it.account })
        assertEquals("sub-original", manager.accounts.value.find(expiredKey)?.sub)
    }

    @Test
    fun `google login does not overwrite legacy slot without sub and runtime 401 expiry returns to login`() = smoke { scope ->
        val server = FakePikPakServer().apply {
            deviceLoginUser = FakePikPakServer.FakeUser(
                sub = "sub-fresh-google",
                name = "Legacy Lookalike",
                email = "legacy@gmail.com",
                providerId = "google.com",
            )
        }
        val legacyKey = "legacy@gmail.com"
        val store = MemorySessionStore().apply {
            accounts = SavedAccounts(
                current = null,
                accounts = listOf(
                    SavedAccount(
                        account = legacyKey,
                        sub = "",
                        name = "Legacy",
                        email = "legacy@gmail.com",
                    ),
                ),
            )
            passwords[legacyKey] = "legacy-secret"
        }
        val manager = PikoClientManager(store, scope, server.httpClient())
        awaitUntil("restore finished") { !manager.isInitializing.value }

        // 续期未知 sub 的旧槽位时，Google 登录无法证明同一身份，必须拒绝覆盖并保留旧密码
        val mismatchTx = manager.startGoogleDeviceLogin(expectedAccount = legacyKey).getOrThrow()
        assertTrue(manager.awaitGoogleDeviceLogin(mismatchTx).isFailure)
        assertEquals("legacy-secret", store.passwords[legacyKey])

        // 正常新增 Google 登录进入独立 pikpak:<sub> 槽位，不侵占未记录 sub 的旧槽位
        val addTx = manager.startGoogleDeviceLogin().getOrThrow()
        val activeClient = manager.awaitGoogleDeviceLogin(addTx).getOrThrow()
        val googleKey = "pikpak:sub-fresh-google"
        assertEquals(googleKey, activeClient.account)
        assertEquals(setOf(legacyKey, googleKey), manager.accounts.value.accounts.map { it.account }.toSet())
        assertEquals("legacy-secret", store.passwords[legacyKey])

        // 运行期业务请求遭遇 401 且 refresh_token 被拒（4126）时，活动无密码会话自动失效并回退登录页
        server.rejectAccessTokens = true
        server.rejectRefresh = true
        runCatching { activeClient.prewarm() }
        awaitUntil("active google session invalidated to login") {
            manager.currentClient.value == null && googleKey in manager.expiredAccounts.value
        }
        assertEquals(googleKey, manager.expiredPrefill)
        assertNull(manager.accounts.value.current)
    }
}
