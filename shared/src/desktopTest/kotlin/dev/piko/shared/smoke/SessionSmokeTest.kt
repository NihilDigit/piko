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
}
