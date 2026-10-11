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
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http,
    )

    @Test
    fun `completion remains queryable without a page or an outcome subscriber`() = smoke { scope ->
        val server = FakePikPakServer()
        val archive = server.addArchive("secret.zip", mapOf("a.txt" to byteArrayOf(1)), password = "secret")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val session = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope)
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
    fun `query failure is unconfirmed and recheck follows the same remote task`() = smoke { scope ->
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
        awaitUntil("进度不可查询时保留待确认结果") { session.completedJobs.size == 1 }
        val record = session.completedJobs.single()
        assertEquals(ArchiveCompletion.UNCONFIRMED, assertIs<ArchiveJobStatus.Finished>(record.status).outcome)
        assertNotNull(record.remoteTaskId)
        assertIs<ActionAvailability.Blocked>(commands.startAvailability(listOf(file)))
        assertTrue(session.canRecheck(record.id))
        val submissions = server.decompressCalls.get()

        unavailable.set(false)
        session.recheck(record.id)
        awaitUntil("恢复查询后确认同一任务已完成") {
            (session.completedJobs.singleOrNull()?.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.SUCCEEDED
        }
        assertEquals(record.id, session.completedJobs.single().id)
        assertEquals(record.remoteTaskId, session.completedJobs.single().remoteTaskId)
        assertEquals(submissions, server.decompressCalls.get(), "重新查询不能重新提交解压")
        assertIs<ActionAvailability.Available>(commands.startAvailability(listOf(file)))
    }

    @Test
    fun `a lost submission response does not imply that extraction failed`() = smoke { scope ->
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
        val session = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), prefs), scope)
        val file = drive.listAllFiles("").getOrThrow().single { it.id == archive.node.id }
        session.extract(listOf(file))
        awaitUntil("提交响应丢失留下待确认结果") { session.completedJobs.size == 1 }
        val record = session.completedJobs.single()
        assertEquals(ArchiveCompletion.UNCONFIRMED, assertIs<ArchiveJobStatus.Finished>(record.status).outcome)
        assertTrue(server.children("").any { it.name == "pack" }, "服务端实际已执行")
        assertEquals(1, server.decompressCalls.get())
        assertFalse(session.canRecheck(record.id), "没有远端任务 ID，不能假装可以查询")
        assertIs<ActionAvailability.Blocked>(ArchiveCommands(session).startAvailability(listOf(file)))
        session.recheck(record.id)
        assertEquals(1, server.decompressCalls.get())
        assertTrue(session.jobs.isEmpty())
    }
}
