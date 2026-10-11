package dev.piko.shared.smoke

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.presentation.HistoryCommandResult
import dev.piko.shared.presentation.HistoryCommands
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.HistoryEditCommand
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.HistoryEditSession
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HistoryEditSmokeTest {
    private fun repository(server: FakePikPakServer, http: HttpClient = server.httpClient()): PikoDriveRepository {
        val client = PikPakClient(account = "history@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        return PikoDriveRepository(server.provider(client), MemoryPreferences())
    }

    @Test
    fun `a refused clear is failed and does not remove play or recent records`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("video.mp4")
        val play = server.addEvent(EventType.PLAY, file.id)
        val recent = server.addEvent(EventType.RESTORE, file.id)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("events:clear")) throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        HistoryCommands(repo, session).submit(HistoryEditCommand.ClearPlay)
        awaitUntil("明确拒绝与待确认区分") { session.works.value.single().result != null }
        assertEquals(HistoryEditCompletion.FAILED, session.works.value.single().result!!.completion)
        assertEquals("未能清空播放历史", session.works.value.single().result!!.message)
        assertEquals(setOf(play.id, recent.id), server.eventsSnapshot().map { it.id }.toSet())
    }

    @Test
    fun `new plays after a lost clear response are preserved by read only recheck`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("video.mp4")
        val old = server.addEvent(EventType.PLAY, file.id)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith("events:clear")) {
                server.addEvent(EventType.PLAY, file.id)
                throw IOException("clear acknowledgement lost before a new play")
            }
            call
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        val id = assertIs<HistoryCommandResult.Accepted>(HistoryCommands(repo, session).submit(HistoryEditCommand.ClearPlay)).workId
        awaitUntil("存在新播放时无法认领清空结果") { session.works.value.single().result != null }
        assertEquals(HistoryEditCompletion.UNCONFIRMED, session.works.value.single().result!!.completion)
        assertTrue(session.recheck(id))
        awaitUntil("只读重新查询结束") { session.works.value.single().result != null }
        assertEquals(HistoryEditCompletion.UNCONFIRMED, session.works.value.single().result!!.completion)
        assertEquals(1, server.count("POST /drive/v1/events:clear"))
        assertEquals(1, server.eventsSnapshot().size)
        assertTrue(server.eventsSnapshot().single().id != old.id)
    }

    @Test
    fun `deleting a history entry continues after leaving the page and refreshes another history view`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("video.mp4")
        server.addEvent(EventType.PLAY, file.id)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("events:delete")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        val firstScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val first = DriveScreenState(repo, MemoryPreferences(), firstScope,
            navigation = dev.piko.shared.presentation.LibraryNavigation(repo, DriveLibrary.HISTORY, firstScope), historyEditSession = session)
        val other = DriveScreenState(repo, MemoryPreferences(), scope,
            navigation = dev.piko.shared.presentation.LibraryNavigation(repo, DriveLibrary.HISTORY, scope), historyEditSession = session)
        first.load()
        other.load()
        awaitUntil("两份历史视图已加载") { first.files.any { it.id == file.id } && other.files.any { it.id == file.id } }
        other.enterSelection(file.id)
        first.removeFromLibrary(listOf(file.id))
        withTimeout(5_000) { entered.receive() }
        firstScope.cancel()
        release.send(Unit)
        awaitUntil("离页后结果保留，另一份视图同步刷新") { session.works.value.single().result != null && other.files.isEmpty() && other.selectedFileIds.isEmpty() && !other.isSelectionMode }
        assertEquals(HistoryEditCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertNotNull(server.node(file.id), "删除历史不能删除文件")
        assertTrue(server.eventsSnapshot().isEmpty())
        assertTrue(other.selectedFileIds.isEmpty(), "其他视图不能保留已经移除的历史行作为选中对象")
        assertTrue(!other.isSelectionMode, "空历史页退出主动保持的多选模式")
    }

    @Test
    fun `clear play history is blocked by an unfinished play deletion but recent records are independent`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("video.mp4")
        val play = server.addEvent(EventType.PLAY, file.id)
        val recent = server.addEvent(EventType.RESTORE, file.id)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("events:delete")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        val commands = HistoryCommands(repo, session)
        assertIs<HistoryCommandResult.Accepted>(commands.submit(HistoryEditCommand.Remove(HistoryKind.PLAY, listOf(play.id))))
        withTimeout(5_000) { entered.receive() }
        assertIs<HistoryCommandResult.Rejected>(commands.submit(HistoryEditCommand.ClearPlay))
        assertIs<dev.piko.shared.presentation.ActionAvailability.Available>(commands.availability(HistoryEditCommand.Remove(HistoryKind.RECENT, listOf(recent.id))))
        release.send(Unit)
        awaitUntil("移除完成后允许清空") { session.works.value.single().result != null }
        assertIs<HistoryCommandResult.Accepted>(commands.submit(HistoryEditCommand.ClearPlay))
        awaitUntil("清空完成") { session.works.value.all { it.result != null } }
        assertEquals(listOf(recent.id), server.eventsSnapshot().map { it.id })
        assertNotNull(server.node(file.id))
    }

    @Test
    fun `lost response is verified by event identity and never repeats the delete`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("video.mp4")
        val event = server.addEvent(EventType.PLAY, file.id)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith("events:delete")) throw IOException("lost acknowledgement")
            call
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        HistoryCommands(repo, session).submit(HistoryEditCommand.Remove(HistoryKind.PLAY, listOf(event.id)))
        awaitUntil("丢失响应后核对事件已消失") { session.works.value.single().result != null }
        assertEquals(HistoryEditCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertEquals(1, server.count("POST /drive/v1/events:delete"))
        assertNotNull(server.node(file.id))
    }

    @Test
    fun `partly applied deletion retains confirmed ids and recheck does not remove new records`() = smoke { scope ->
        val server = FakePikPakServer()
        val first = server.addEvent(EventType.PLAY, server.addFile("first.mp4").id)
        val second = server.addEvent(EventType.PLAY, server.addFile("second.mp4").id)
        server.ignoredEventIds += second.id
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith("events:delete")) throw IOException("partial write with lost response")
            call
        }
        val repo = repository(server, http)
        val session = HistoryEditSession(repo, scope)
        val commands = HistoryCommands(repo, session)
        val accepted = assertIs<HistoryCommandResult.Accepted>(commands.submit(HistoryEditCommand.Remove(HistoryKind.PLAY, listOf(first.id, second.id))))
        awaitUntil("部分移除可查询") { session.works.value.single().result != null }
        assertEquals(HistoryEditCompletion.PARTIAL, session.works.value.single().result!!.completion)
        assertEquals(setOf(first.id), session.works.value.single().result!!.confirmedEvents)
        val fresh = server.addEvent(EventType.PLAY, first.fileId)
        assertTrue(session.recheck(accepted.workId))
        awaitUntil("重新查询完成") { session.works.value.single().result != null }
        assertEquals(1, server.count("POST /drive/v1/events:delete"))
        assertTrue(server.eventsSnapshot().any { it.id == fresh.id })
        assertIs<HistoryCommandResult.Rejected>(commands.submit(HistoryEditCommand.ClearPlay))
    }
}
