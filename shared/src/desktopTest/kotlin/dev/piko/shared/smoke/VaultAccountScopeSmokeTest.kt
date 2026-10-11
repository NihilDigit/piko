package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultAccountScopeSmokeTest {
    @Test
    fun `a pending root manifest cannot appear in another account or continue writing through its client`() = smoke { scope ->
        val oldServer = FakePikPakServer("A")
        val newServer = FakePikPakServer("B")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val oldHttp = oldServer.httpClient()
        oldHttp.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val oldClient = PikPakClient(account = "old@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = oldHttp)
        val newClient = newServer.client(account = "new@piko.dev")
        val current = MutableStateFlow<PikPakClient?>(oldClient)
        val repo = PikoDriveRepository(object : PikoClientProvider { override val currentClient = current }, MemoryPreferences())
        repo.enterAccount(oldClient.account)
        val oldStore = repo.vault
        oldStore.read("", emptyList()).getOrThrow()
        val entry = VaultEntry.create("old.txt", 1, "OLDGCID", null, 1)
        val writing = scope.async { oldStore.update("", VaultEdits.add(listOf(entry))) }
        withTimeout(5_000) { entered.receive() }
        try {
            assertEquals(listOf(entry), oldStore.cached("", emptyList()))
            current.value = newClient
            repo.enterAccount(newClient.account)
            val newStore = repo.vault
            assertTrue(newStore.read("", emptyList()).getOrThrow().isEmpty(), "根目录的空串 ID 不得把旧账号待写条目带进新账号")
            release.send(Unit)
            assertTrue(withTimeout(8_000) { writing.await() }.isSuccess)
            assertTrue(newServer.children("").isEmpty(), "旧事务之后的列出、写入、确认和删除仍使用旧 client")
            assertEquals(0, newServer.count("POST /drive/v1/files"))
            assertFalse(oldServer.children("").isEmpty())
        } finally { release.trySend(Unit) }
    }
}
