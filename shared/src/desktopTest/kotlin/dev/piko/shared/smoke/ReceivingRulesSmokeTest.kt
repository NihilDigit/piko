package dev.piko.shared.smoke

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.ShareCommands
import dev.piko.shared.presentation.ShareCommandResult
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveTargetContext
import dev.piko.shared.state.ShareSaveCommand
import dev.piko.shared.state.ShareSaveSession
import dev.piko.shared.state.ShareSource
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceivingRulesSmokeTest {
    private class Rig(val scope: CoroutineScope) {
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = server.provider(PikPakClient("receiving@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val preferences = MemoryPreferences()
        val drive = PikoDriveRepository(provider, preferences)
        val writes = FileWriteSession(drive, scope)
        val commands = FileCommands(drive, writes)
        val parent = server.addFolder("parent")
        val child = server.addFolder("child", parent.id)
        val other = server.addFolder("other")
        val target = PikoPathBreadcrumb(child.id, child.name)
        val instant = InstantMagnetRepository(provider)
        fun state(context: SaveTargetContext = SaveTargetContext({ target }, flowOf(target), commands::receivingAvailability, commands.receivingChanges)) = InstantSheetState(
            instant, drive, preferences, PreviewTempFolder(provider, drive, instant, scope), OfflinePackTracker(instant, drive, preferences),
            InstantSaveRecords(provider, null, scope), scope, "https://example.invalid/file", context)
        suspend fun learn() { drive.listAllFiles().getOrThrow(); drive.listAllFiles(parent.id).getOrThrow() }
    }

    @Test
    fun `moving an ancestor blocks incoming saves and shares while unrelated receiving folders stay available`() = smoke { scope ->
        val f = Rig(scope)
        f.learn()
        val state = f.state()
        awaitUntil("离线保存主操作可用") { state.primaryAction?.enabled == true }
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("files:batchMove")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val id = assertIs<FileCommandResult.Accepted>(f.commands.submit(FileWriteCommand.Move(listOf(f.parent.id), PikoPathBreadcrumb(f.other.id, f.other.name)))).workId
        withTimeout(5_000) { entered.receive() }
        assertIs<ActionAvailability.Blocked>(f.commands.receivingAvailability(f.child.id))
        assertEquals(ActionAvailability.Available, f.commands.receivingAvailability(f.other.id))
        awaitUntil("业务规则变化使保存按钮不可用") { state.primaryAction?.enabled == false && state.saveBlockReason != null }
        state.submitOfflineTask()
        assertTrue(f.server.tasksSnapshot().isEmpty())
        val source = f.server.addFile("shared.mp4")
        val shares = ShareCommands(f.drive, ShareSaveSession(f.drive, scope), targetAvailability = f.commands::receivingAvailability)
        assertIs<ShareCommandResult.Rejected>(shares.submit(ShareSaveCommand("S", listOf(ShareSource(source.id, source.name, false, 0, "")), f.target, emptyList()), "token"))
        assertEquals(0, f.server.count("POST /drive/v1/share/restore"))
        release.send(Unit)
        awaitUntil("父目录移动结束后规则解除") { f.writes.works.value.first { it.id == id }.result != null && state.primaryAction?.enabled == true }
        assertEquals(ActionAvailability.Available, f.commands.receivingAvailability(f.child.id))
    }

    @Test
    fun `a target rename is independent of incoming files addressed by directory id`() = smoke { scope ->
        val f = Rig(scope)
        f.learn()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val id = assertIs<FileCommandResult.Accepted>(f.commands.submit(FileWriteCommand.Rename(f.parent.id, "renamed"))).workId
        withTimeout(5_000) { entered.receive() }
        assertEquals(ActionAvailability.Available, f.commands.receivingAvailability(f.child.id))
        val state = f.state()
        awaitUntil("改名期间仍可提交到同一目录 ID") { state.primaryAction?.enabled == true }
        state.submitOfflineTask()
        awaitUntil("独立离线提交完成") { !state.isSaving && f.server.tasksSnapshot().size == 1 }
        assertEquals(f.child.id, f.server.tasksSnapshot().single().parentId)
        release.send(Unit)
        awaitUntil("改名完成") { f.writes.works.value.first { it.id == id }.result != null }
    }

    @Test
    fun `a stale enabled action rechecks its target before sending any offline request`() = smoke { scope ->
        val f = Rig(scope)
        var blocked = false
        val state = f.state(SaveTargetContext({ f.target }, flowOf(f.target),
            { if (blocked) ActionAvailability.Blocked("目标已经开始删除") else ActionAvailability.Available }))
        awaitUntil("旧按钮状态可用") { state.primaryAction?.enabled == true }
        state.submitOfflineTask()
        blocked = true
        awaitUntil("执行前重新检查目标") { !state.isSaving && state.errorMessage == "目标已经开始删除" }
        assertTrue(f.server.tasksSnapshot().isEmpty())
        assertTrue(f.instant.offlineSubmissions.works.value.isEmpty())
    }

    @Test
    fun `an uncertain ancestor move keeps its receiving target blocked until read-only confirmation`() = smoke { scope ->
        val f = Rig(scope)
        f.learn()
        var moved = false
        var hideDetails = true
        f.http.plugin(HttpSend).intercept { request ->
            val path = request.url.encodedPath
            if (moved && hideDetails && request.method == HttpMethod.Get && path.endsWith("/files/${f.parent.id}")) throw IOException("confirmation unavailable")
            val response = execute(request)
            if (path.endsWith("files:batchMove")) { moved = true; throw IOException("acknowledgement lost") }
            response
        }
        val id = assertIs<FileCommandResult.Accepted>(f.commands.submit(FileWriteCommand.Move(listOf(f.parent.id), PikoPathBreadcrumb(f.other.id, f.other.name)))).workId
        awaitUntil("目录移动待确认") { f.writes.works.value.first { it.id == id }.result != null }
        assertIs<ActionAvailability.Blocked>(f.commands.receivingAvailability(f.child.id))
        hideDetails = false
        assertTrue(f.writes.recheck(id))
        awaitUntil("只读查询解除目标阻止") { f.commands.receivingAvailability(f.child.id) == ActionAvailability.Available }
        assertEquals(1, f.server.count("POST /drive/v1/files:batchMove"))
        assertNotNull(f.server.node(f.child.id))
    }

    @Test
    fun `undoing an ancestor rename still allows independent incoming saves`() = smoke { scope ->
        val f = Rig(scope)
        f.learn()
        f.drive.rename(f.parent.id, "renamed").getOrThrow()
        val change = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(f.parent.id, "parent", "renamed")), "rename")
        f.drive.changes.record(change)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) { entered.send(Unit); release.receive() }
            execute(request)
        }
        assertTrue(f.drive.changes.undo(change))
        withTimeout(5_000) { entered.receive() }
        assertEquals(ActionAvailability.Available, f.commands.receivingAvailability(f.child.id))
        assertIs<FileCommandResult.Accepted>(f.commands.submit(FileWriteCommand.CreateFolder(f.target, "independent")))
        awaitUntil("撤销改名期间创建独立目录") { f.server.children(f.child.id).any { it.name == "independent" } }
        release.send(Unit)
        awaitUntil("名称撤销完成") { f.drive.changes.state.value.undo[change.id]?.status == DriveChangeJournal.UndoStatus.UNDONE }
    }
}
