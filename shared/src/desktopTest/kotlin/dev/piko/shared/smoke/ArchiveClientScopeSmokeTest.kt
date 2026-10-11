package dev.piko.shared.smoke

import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.ArchiveRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.ArchiveBrowser
import dev.piko.shared.state.DriveScreenState
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveClientScopeSmokeTest {
    @Test
    fun `an archive page restarts its query after same account client reconstruction`() = smoke { scope ->
        val a = FakePikPakServer()
        val b = FakePikPakServer()
        a.addArchive("archive.zip", mapOf("old.txt" to byteArrayOf(1)))
        b.addArchive("archive.zip", mapOf("new.txt" to byteArrayOf(2)))
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = a.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/decompress/v1/list")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val first = PikPakClient("same@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<PikPakClient?>(first) }
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(provider, prefs)
        val browser = ArchiveBrowser(provider, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scratchFolder = { Result.success("") })
        val page = DriveScreenState(repository, prefs, scope, archives = browser)
        page.load()
        awaitUntil("主文件页已载入") { !page.isLoading && page.files.size == 1 }
        page.openArchive(page.files.single())
        withTimeout(5_000) { entered.receive() }
        provider.currentClient.value = b.client(account = first.account)
        awaitUntil("更换 client 后自动重新查询，而不是永久停在加载中") { !page.isLoading && page.files.map { it.name } == listOf("new.txt") }
        release.trySend(Unit)
        assertEquals(1L, repository.clientRevision.value)
        assertEquals(listOf("new.txt"), browser.cached(page.archiveView!!)?.map { it.name })
    }

    @Test
    fun `late archive listing cannot repopulate a cache after same account client reconstruction`() = smoke { scope ->
        val a = FakePikPakServer()
        val b = FakePikPakServer()
        val archive = a.addArchive("old.zip", mapOf("old.txt" to byteArrayOf(1))).node
        val replacement = b.addArchive("new.zip", mapOf("new.txt" to byteArrayOf(2))).node
        assertEquals(archive.id, replacement.id)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = a.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/decompress/v1/list")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val first = PikPakClient("same@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<PikPakClient?>(first) }
        val browser = ArchiveBrowser(provider, ArchivePasswordVault(provider, MemorySessionStore(), MemoryPreferences()), scratchFolder = { Result.success("") })
        val location = ArchiveLocation(archive.id, archive.hash, "")
        val late = scope.async { runCatching { browser.list(location) } }
        withTimeout(5_000) { entered.receive() }
        provider.currentClient.value = b.client(account = first.account)
        assertNull(browser.cached(location))
        assertEquals(listOf("new.txt"), browser.list(location).getOrThrow().map { it.name })
        release.send(Unit)
        assertTrue(late.await().isFailure)
        assertEquals(listOf("new.txt"), browser.cached(location)?.map { it.name })
    }

    @Test
    fun `archive priming finishes and deletes its scratch output with the client that submitted it`() = smoke { scope ->
        val a = FakePikPakServer("A")
        val b = FakePikPakServer("B")
        val archive = a.addArchive("archive.zip", mapOf("small.txt" to byteArrayOf(1))).node
        val scratch = a.addFolder("Piko-Temp")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = a.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/decompress/v1/progress")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val first = PikPakClient("a@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<PikPakClient?>(first) }
        val repository = ArchiveRepository(provider)
        val priming = scope.async { repository.prime(ArchiveLocation(archive.id, archive.hash, ""), "small.txt", "", scratch.id, first) }
        withTimeout(5_000) { entered.receive() }
        assertTrue(a.children(scratch.id).isNotEmpty())
        provider.currentClient.value = b.client(account = "b@piko.dev")
        release.send(Unit)
        priming.await().getOrThrow()
        assertTrue(a.children(scratch.id).isEmpty())
        assertEquals(0, b.count("POST /drive/v1/files:batchDelete"))
        assertEquals(0, b.decompressCalls.get())
    }
}
