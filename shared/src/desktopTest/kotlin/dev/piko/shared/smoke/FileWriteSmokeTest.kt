package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteCompletion
import dev.piko.shared.state.FileWriteSession
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileWriteSmokeTest {
    private fun drive(server: FakePikPakServer, http: HttpClient = server.httpClient()): PikoDriveRepository {
        val client = PikPakClient(account = "smoke@piko.dev", password = "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        return PikoDriveRepository(server.provider(client), MemoryPreferences())
    }
    private fun FileCommands.accept(command: FileWriteCommand): Long = assertIs<FileCommandResult.Accepted>(submit(command)).workId

    @Test
    fun `late cleanup of the previous account preserves work already accepted by the new account`() = smoke { scope ->
        val oldServer = FakePikPakServer("A")
        val newServer = FakePikPakServer("B")
        val oldFile = oldServer.addFile("old.txt")
        val newFile = newServer.addFile("new.txt")
        val oldEntered = Channel<Unit>(1)
        val oldRelease = Channel<Unit>(1)
        val newEntered = Channel<Unit>(1)
        val newRelease = Channel<Unit>(1)
        fun client(server: FakePikPakServer, account: String, entered: Channel<Unit>, release: Channel<Unit>): PikPakClient {
            val http = server.httpClient()
            http.plugin(HttpSend).intercept { request ->
                if (request.method == HttpMethod.Patch) {
                    entered.send(Unit)
                    withContext(NonCancellable) { release.receive() }
                }
                execute(request)
            }
            return PikPakClient(account = account, password = "pw", sessionStore = InMemorySessionStore(),
                rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http)
        }
        val oldClient = client(oldServer, "old@piko.dev", oldEntered, oldRelease)
        val newClient = client(newServer, "new@piko.dev", newEntered, newRelease)
        val current = MutableStateFlow<PikPakClient?>(oldClient)
        val provider = object : dev.piko.shared.data.PikoClientProvider { override val currentClient = current }
        val repo = PikoDriveRepository(provider, MemoryPreferences())
        repo.enterAccount(oldClient.account)
        val session = FileWriteSession(repo, scope, 10)
        FileCommands(repo, session).accept(FileWriteCommand.Rename(oldFile.id, "old-changed.txt"))
        withTimeout(5_000) { oldEntered.receive() }
        try {
            current.value = newClient
            repo.enterAccount(newClient.account)
            val id = FileCommands(repo, session).accept(FileWriteCommand.Rename(newFile.id, "new-changed.txt"))
            withTimeout(5_000) { newEntered.receive() }
            session.clear(oldClient.account)
            assertEquals(listOf(id), session.works.value.map { it.id })
            oldRelease.send(Unit)
            newRelease.send(Unit)
            awaitUntil("新账号操作不被旧账号释放清掉") { session.works.value.single().result != null }
            assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
            assertEquals("new-changed.txt", newFile.name)
            assertTrue(repo.changes.state.value.available.filterIsInstance<DriveChangeJournal.Change.Rename>().all { change -> change.renames.all { it.id == newFile.id } })
        } finally {
            oldRelease.trySend(Unit)
            newRelease.trySend(Unit)
        }
    }

    @Test
    fun `independent writes proceed while conflicting actions are blocked before submission`() = smoke { scope ->
        val server = FakePikPakServer()
        val source = server.addFile("one.txt")
        val other = server.addFile("other.txt")
        val target = server.addFolder("target")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith(":batchMove")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, pollMillis = 10)
        val commands = FileCommands(repo, session)
        val move = FileWriteCommand.Move(listOf(source.id), PikoPathBreadcrumb(target.id, target.name))
        val id = commands.accept(move)
        withTimeout(5_000) { entered.receive() }
        assertIs<FileCommandResult.Rejected>(commands.submit(FileWriteCommand.Delete(listOf(source.id))))
        assertEquals(ActionAvailability.Available, commands.availability(FileWriteCommand.Star(listOf(source.id), true)))
        val renameId = commands.accept(FileWriteCommand.Rename(other.id, "changed.txt"))
        awaitUntil("独立文件不被移动阻塞") { session.works.value.find { it.id == renameId }?.result != null }
        assertEquals("changed.txt", other.name)
        assertTrue(session.works.value.find { it.id == id }!!.isRunning)
        release.send(Unit)
        awaitUntil("移动完成且结果可查询") { session.works.value.find { it.id == id }?.result != null }
        assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.find { it.id == id }!!.result!!.completion)
        assertEquals(target.id, source.parentId)
        assertEquals(0, server.count("POST /drive/v1/files:batchDelete"))
    }

    @Test
    fun `successful batch acknowledgement does not conceal partial writes and recheck has its own inverse`() = smoke { scope ->
        val server = FakePikPakServer()
        val first = server.addFile("first.txt")
        val second = server.addFile("second.txt")
        val target = server.addFolder("target")
        server.ignoredBatchIds += second.id
        val repo = drive(server)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val id = commands.accept(FileWriteCommand.Move(listOf(first.id, second.id), PikoPathBreadcrumb(target.id, target.name)))
        awaitUntil("部分完成可查询") { session.works.value.single().result != null }
        val partial = session.works.value.single().result!!
        assertEquals(FileWriteCompletion.PARTIAL, partial.completion)
        assertEquals(setOf(first.id), partial.confirmed)
        assertEquals(setOf(second.id), partial.unconfirmed)
        assertEquals(1, partial.undoChangeIds.size)
        assertEquals(ActionAvailability.Available, commands.availability(FileWriteCommand.Rename(first.id, "later.txt")), "已确认子集不被未确认的其他项阻塞")
        first.name = "later.txt"
        second.parentId = target.id
        assertTrue(session.recheck(id))
        awaitUntil("仅查询未确认部分") { session.works.value.single().result?.completion == FileWriteCompletion.SUCCEEDED }
        val final = session.works.value.single().result!!
        assertEquals(setOf(first.id, second.id), final.confirmed)
        assertEquals(2, final.undoChangeIds.size)
        assertEquals(1, server.count("POST /drive/v1/files:batchMove"), "重新查询不重新发送移动")
        server.ignoredBatchIds.clear()
        assertTrue(repo.changes.undo(final.undoChangeIds.last()))
        awaitUntil("撤销后来确认的子集") { repo.changes.state.value.undo[final.undoChangeIds.last()]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("", second.parentId)
        assertEquals(target.id, first.parentId)
        assertEquals("later.txt", first.name)
    }

    @Test
    fun `closing the source page preserves execution and the result can be undone elsewhere`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before.txt")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = drive(server, http)
        val writes = FileWriteSession(repo, scope, 10)
        val pageScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val page = dev.piko.shared.state.DriveScreenState(repo, MemoryPreferences(), pageScope, fileWriteSession = writes)
        page.rename(file.id, "after.txt")
        withTimeout(5_000) { entered.receive() }
        pageScope.cancel()
        release.send(Unit)
        awaitUntil("原页面释放后仍有业务结果") { writes.works.value.single().result != null }
        assertEquals("after.txt", file.name)
        assertIs<FileCommandResult.Rejected>(page.fileActions.submit(FileWriteCommand.Rename(file.id, "late.txt")))
        val commands = FileCommands(repo, writes)
        val inverse = writes.works.value.single().result!!.undoChangeIds.single()
        assertIs<FileCommandResult.Accepted>(commands.undo(inverse))
        withTimeout(5_000) { entered.receive() }
        release.send(Unit)
        awaitUntil("其他页面撤销已完成") { repo.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("before.txt", file.name)
    }

    @Test
    fun `inverse and writes block only overlapping subjects and not unrelated root operations`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before.txt")
        val other = server.addFile("other.txt")
        val target = server.addFolder("target")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val gateRename = AtomicBoolean(false)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith(":batchMove") || (request.method == HttpMethod.Patch && gateRename.get())) {
                entered.send(Unit); release.receive()
            }
            execute(request)
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val rename = commands.accept(FileWriteCommand.Rename(file.id, "after.txt"))
        awaitUntil("第一次改名完成") { session.works.value.find { it.id == rename }?.result != null }
        val inverse = session.works.value.find { it.id == rename }!!.result!!.undoChangeIds.single()
        val move = commands.accept(FileWriteCommand.Move(listOf(file.id), PikoPathBreadcrumb(target.id, target.name)))
        withTimeout(5_000) { entered.receive() }
        assertIs<FileCommandResult.Rejected>(commands.undo(inverse), "移动同一项时不能撤销它的名称")
        release.send(Unit)
        awaitUntil("移动已结束") { session.works.value.find { it.id == move }?.result != null }
        gateRename.set(true)
        assertIs<FileCommandResult.Accepted>(commands.undo(inverse))
        withTimeout(5_000) { entered.receive() }
        assertIs<FileCommandResult.Rejected>(commands.submit(FileWriteCommand.Delete(listOf(file.id))))
        assertEquals(ActionAvailability.Available, commands.availability(FileWriteCommand.CreateFolder(PikoDriveRepository.ROOT_BREADCRUMB, "unrelated")))
        assertEquals(ActionAvailability.Available, commands.availability(FileWriteCommand.Copy(listOf(other.id), PikoDriveRepository.ROOT_BREADCRUMB)))
        assertEquals(ActionAvailability.Available, commands.filesAvailability(listOf(other.id)))
        release.send(Unit)
        awaitUntil("撤销完成释放关联信息") { repo.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertTrue(repo.changes.state.value.undo[inverse]!!.activeFileIds.isEmpty())
        assertEquals("before.txt", file.name)
    }

    @Test
    fun `blocked cut paste retains clipboard and does not submit a second write`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("before.txt")
        val target = server.addFolder("target")
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith(":batchMove")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, 10)
        val page = dev.piko.shared.state.DriveScreenState(repo, MemoryPreferences(), scope, fileWriteSession = session)
        page.load()
        awaitUntil("剪切来源已列出") { page.files.any { it.id == file.id } }
        page.putOnClipboard(listOf(file.id), cut = true)
        FileCommands(repo, session).accept(FileWriteCommand.Move(listOf(file.id), PikoPathBreadcrumb(target.id, target.name)))
        withTimeout(5_000) { entered.receive() }
        page.navigation.navigateToFolder(PikoPathBreadcrumb(target.id, target.name))
        page.paste()
        assertNotNull(page.clipboardFlow.value)
        release.send(Unit)
        awaitUntil("只有原来的移动完成") { session.works.value.single().result != null }
        assertEquals(1, server.count("POST /drive/v1/files:batchMove"))
    }

    @Test
    fun `lost mutation response is verified without replaying the request`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith(":batchMove")) throw IOException("response lost after write")
            call
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, 10)
        FileCommands(repo, session).accept(FileWriteCommand.Move(listOf(file.id), PikoPathBreadcrumb(target.id, target.name)))
        awaitUntil("丢失应答后的实际结果") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertEquals(1, server.count("POST /drive/v1/files:batchMove"))
        assertNotNull(session.works.value.single().result!!.undoChangeIds.singleOrNull())
    }

    @Test
    fun `copy follows the accepted remote task rather than declaring completion at acceptance`() = smoke { scope ->
        val server = FakePikPakServer().apply { copyPhase = TaskPhase.RUNNING }
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val repo = drive(server)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val command = FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(target.id, target.name))
        commands.accept(command)
        awaitUntil("已接受的远端复制任务") { session.works.value.single().copies.isNotEmpty() }
        assertTrue(session.works.value.single().isRunning)
        assertTrue(server.children(target.id).isEmpty())
        assertIs<FileCommandResult.Rejected>(commands.submit(command))
        server.tasksSnapshot().single().phase = TaskPhase.COMPLETE
        awaitUntil("复制真实完成") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.SUCCEEDED, session.works.value.single().result!!.completion)
        assertEquals(listOf("one.txt"), server.children(target.id).map { it.name })
        assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))
    }

    @Test
    fun `lost copy task identity stays unconfirmed and recheck cannot create a second copy`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("one.txt")
        val target = server.addFolder("target")
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val call = execute(request)
            if (request.url.encodedPath.endsWith(":batchCopy")) throw IOException("copy response lost")
            call
        }
        val repo = drive(server, http)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val command = FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(target.id, target.name))
        val id = commands.accept(command)
        awaitUntil("复制待确认") { session.works.value.single().result != null }
        assertEquals(FileWriteCompletion.UNCONFIRMED, session.works.value.single().result!!.completion)
        assertIs<FileCommandResult.Rejected>(commands.submit(command))
        assertFalse(session.recheck(id), "没有远端标识时不提供无效查询，也不能重新提交")
        assertEquals(1, server.tasksSnapshot().size)
        assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))
    }

    @Test
    fun `star restore and delete are verified through actual service state`() = smoke { scope ->
        val server = FakePikPakServer()
        val star = server.addFile("star.txt")
        val trash = server.addFile("trash.txt", trashed = true)
        val repo = drive(server)
        val session = FileWriteSession(repo, scope, 10)
        val commands = FileCommands(repo, session)
        val starred = commands.accept(FileWriteCommand.Star(listOf(star.id), true))
        val restored = commands.accept(FileWriteCommand.Restore(listOf(trash.id)))
        awaitUntil("星标与恢复已确认") { listOf(starred, restored).all { id -> session.works.value.find { it.id == id }?.result != null } }
        assertTrue(star.starred)
        assertFalse(trash.trashed)
        commands.accept(FileWriteCommand.Delete(listOf(trash.id)))
        awaitUntil("永久删除已确认") { session.works.value.all { it.result != null } }
        assertTrue(session.works.value.all { it.result!!.completion == FileWriteCompletion.SUCCEEDED })
        assertEquals(null, server.node(trash.id))
    }
}
