package dev.piko.shared.smoke

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.ArchiveCommands
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ArchiveResultsSmokeTest {
    private fun client(http: HttpClient) = PikPakClient(
        account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0),
        retryPolicy = io.github.nihildigit.pikpak.RetryPolicy.None,
        httpClient = http,
    )

    @Test
    fun `completion remains queryable without a page or an outcome subscriber`() = smoke { scope ->
        val server = FakePikPakServer()
        val archive = server.addArchive("secret.zip", mapOf("a.txt" to byteArrayOf(1)), password = "secret")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val session = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope, 1.milliseconds)
        val file = drive.listAllFiles("").getOrThrow().single { it.id == archive.node.id }
        session.extractEntries(file, listOf("a.txt"), "secret")
        awaitUntil("无页面订阅仍保留解压结局") { session.completedJobs.size == 1 }
        val first = session.completedJobs.single()
        assertEquals(ArchiveCompletion.SUCCEEDED, assertIs<ArchiveJobStatus.Finished>(first.status).outcome)
        assertEquals("", first.password, "完成记录不保留明文密码")
        assertNotNull(first.remoteTaskId)
        assertTrue(session.jobs.isEmpty())

        session.extractEntries(file, listOf("a.txt"), "secret")
        awaitUntil("同一文件的下一次解压有独立记录") { session.completedJobs.size == 2 }
        assertNotEquals(first.id, session.completedJobs.last().id)
        val outputs = server.children("").map { it.id }.toSet()
        session.skip(first.id)
        assertEquals(1, session.completedJobs.size)
        assertEquals(outputs, server.children("").map { it.id }.toSet(), "移除结果记录不删除解压产出")
        session.clear()
        assertTrue(session.completedJobs.isEmpty())
    }

    @Test
    fun `query failure finishes as failed without blocking subsequent extraction`() = smoke { scope ->
        val server = FakePikPakServer()
        val archive = server.addArchive("pack.zip", mapOf("a.txt" to byteArrayOf(1)))
        val unavailable = AtomicBoolean(true)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (unavailable.get() && request.url.encodedPath.endsWith("/decompress/v1/progress")) throw IOException("offline")
            execute(request)
        }
        val provider = server.provider(client(http))
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val session = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope, 1.milliseconds)
        val file = drive.listAllFiles("").getOrThrow().single { it.id == archive.node.id }
        val commands = ArchiveCommands(session)
        session.extract(listOf(file))
        awaitUntil("进度不可查询时记为失败") { session.completedJobs.size == 1 }
        val record = session.completedJobs.single()
        assertEquals(ArchiveCompletion.FAILED, assertIs<ArchiveJobStatus.Finished>(record.status).outcome)
        assertIs<ActionAvailability.Available>(commands.startAvailability(listOf(file)))
    }

    @Test
    fun `a lost submission response finishes as failed without blocking`() = smoke { scope ->
        val server = FakePikPakServer()
        val archive = server.addArchive("pack.zip", mapOf("a.txt" to byteArrayOf(1)))
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.url.encodedPath.endsWith("/decompress/v1/decompress")) throw IOException("response lost")
            response
        }
        val provider = server.provider(client(http))
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val session = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope, 1.milliseconds)
        val file = drive.listAllFiles("").getOrThrow().single { it.id == archive.node.id }
        session.extract(listOf(file))
        awaitUntil("提交响应丢失记为失败") { session.completedJobs.size == 1 }
        val record = session.completedJobs.single()
        assertEquals(ArchiveCompletion.FAILED, assertIs<ArchiveJobStatus.Finished>(record.status).outcome)
        assertIs<ActionAvailability.Available>(ArchiveCommands(session).startAvailability(listOf(file)))
        assertTrue(session.jobs.isEmpty())
    }
}
