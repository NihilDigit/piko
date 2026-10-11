package dev.piko.shared.smoke

import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackRef
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.OfflineSubmitException
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.shared.state.SaveTargetContext
import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineSubmissionSmokeTest {
    private class Rig(val scope: CoroutineScope) {
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = server.provider(PikPakClient("offline@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val preferences = MemoryPreferences()
        val drive = PikoDriveRepository(provider, preferences)
        val instant = InstantMagnetRepository(provider)
        val packs = OfflinePackTracker(instant, drive, preferences)
        val records = InstantSaveRecords(provider, null, scope)
        val target = server.addFolder("target")
        val bread = PikoPathBreadcrumb(target.id, target.name)
        val url = "https://example.invalid/private-file?token=PRIVATE"
        fun loseResponse() { http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) throw IOException("acknowledgement lost")
            response
        } }
        fun state(context: SaveTargetContext = SaveTargetContext.fixed(bread), input: String = url) = InstantSheetState(
            instant, drive, preferences, PreviewTempFolder(provider, drive, instant, scope), packs, records, scope, input, context)
    }

    @Test
    fun `a lost response retains an opaque submission and blocks repeats only for that target`() = smoke { scope ->
        val f = Rig(scope)
        f.loseResponse()
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { f.instant.offlineSubmissions.outcomes.first() }
        val error = assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(f.url, f.target.id).exceptionOrNull())
        val work = f.instant.offlineSubmissions.works.value.single()
        assertEquals(OfflineSubmitStatus.UNCONFIRMED, work.status)
        assertEquals(work.id, error.workId)
        assertEquals(work, outcome.await())
        assertFalse(work.toString().contains("PRIVATE"))
        assertEquals(1, f.server.tasksSnapshot().size)
        repeat(4) { assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(f.url, f.target.id).exceptionOrNull()) }
        assertEquals(1, f.server.count("POST /drive/v1/files"))
        f.instant.enqueueOfflineTask(f.url, "other")
        assertEquals(2, f.server.count("POST /drive/v1/files"))
        assertTrue(f.instant.offlineSubmissions.forget(work.id))
        assertEquals(2, f.server.tasksSnapshot().size, "移除本地回执不取消已创建的远端任务")
    }

    @Test
    fun `simultaneous clicks reserve one submission before any network request`() = smoke { scope ->
        val f = Rig(scope)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val first = scope.async { f.instant.enqueueOfflineTask(f.url, f.target.id) }
        withTimeout(5_000) { entered.receive() }
        val work = f.instant.offlineSubmissions.works.value.single()
        assertTrue(work.isRunning)
        assertFalse(f.instant.offlineSubmissions.forget(work.id))
        repeat(8) { assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(f.url, f.target.id).exceptionOrNull()) }
        release.send(Unit)
        assertIs<CreateUrlResult.Queued>(first.await().getOrThrow())
        assertEquals(1, f.server.tasksSnapshot().size)
        assertTrue(f.instant.offlineSubmissions.works.value.isEmpty(), "已知任务转交云端列表，不保留第二个进度来源")
    }

    @Test
    fun `a definite refusal remains retryable and never becomes an unknown accepted request`() = smoke { scope ->
        val f = Rig(scope)
        var refused = true
        f.http.plugin(HttpSend).intercept { request ->
            if (refused && request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(f.url, f.target.id).exceptionOrNull())
        assertEquals(OfflineSubmitStatus.REFUSED, f.instant.offlineSubmissions.works.value.single().status)
        assertTrue(f.server.tasksSnapshot().isEmpty())
        refused = false
        assertIs<CreateUrlResult.Queued>(f.instant.enqueueOfflineTask(f.url, f.target.id).getOrThrow())
        assertEquals(1, f.server.tasksSnapshot().size)
    }

    @Test
    fun `an unknown draft freezes its input and original target until explicitly forgotten`() = smoke { scope ->
        val f = Rig(scope)
        f.loseResponse()
        val currentFolder = MutableStateFlow(f.bread)
        val state = f.state(SaveTargetContext({ currentFolder.value }, currentFolder))
        awaitUntil("目标及主操作就绪") { state.primaryAction?.enabled == true && state.target == f.bread }
        state.submitOfflineTask()
        awaitUntil("未知回执进入草稿保护") { !state.isSaving && state.hasPendingOfflineSubmission }
        assertFalse(state.canEdit)
        assertFalse(state.primaryAction!!.enabled)
        assertTrue(state.hasPickedFiles)
        state.updateInput("https://example.invalid/other")
        assertEquals(f.url, state.input)
        val other = f.server.addFolder("other")
        currentFolder.value = PikoPathBreadcrumb(other.id, other.name)
        awaitUntil("可编辑状态仍被冻结") { !state.canEdit }
        assertEquals(f.bread, state.target)
        repeat(4) { state.submitOfflineTask() }
        assertEquals(1, f.server.tasksSnapshot().size)
        val id = f.instant.offlineSubmissions.works.value.single().id
        assertTrue(state.containsOfflineSubmission(id))
        assertTrue(f.instant.offlineSubmissions.forget(id))
        awaitUntil("明确移除后恢复编辑与目录跟随") { state.canEdit && state.target?.id == other.id }
        assertEquals(1, f.server.tasksSnapshot().size)
    }

    @Test
    fun `an unknown pack submission is not adopted or pruned and cannot be submitted again`() = smoke { scope ->
        val f = Rig(scope)
        f.loseResponse()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { f.packs.run("offline@piko.dev") }
        suspend fun submit() = f.packs.submit(f.url, f.target.id, "pack", setOf("one.mkv"), 2, 200, 100)
        assertIs<OfflineSubmitException>(submit().exceptionOrNull())
        assertTrue(f.packs.jobs.value.isEmpty())
        repeat(3) { assertIs<OfflineSubmitException>(submit().exceptionOrNull()) }
        assertEquals(1, f.server.tasksSnapshot().count { it.url == f.url })
        assertEquals(1, f.instant.offlineSubmissions.works.value.count { it.target.id == f.target.id })
    }

    @Test
    fun `resubmitting a failed cloud task shares the same unknown submission guard`() = smoke { scope ->
        val f = Rig(scope)
        val task = assertIs<CreateUrlResult.Queued>(f.instant.enqueueOfflineTask(f.url, f.target.id).getOrThrow()).task
            .copy(phase = TaskPhase.ERROR, params = mapOf("url" to f.url, "parent_folder_id" to f.target.id))
        f.loseResponse()
        val repository = TaskRepository(f.provider, f.drive, f.instant)
        assertIs<OfflineSubmitException>(repository.resubmitTask(task).exceptionOrNull())
        repeat(3) { assertIs<OfflineSubmitException>(repository.resubmitTask(task).exceptionOrNull()) }
        assertEquals(2, f.server.tasksSnapshot().size)
        assertTrue(f.server.tasksSnapshot().any { it.id == task.id }, "未知的新提交不能删除原任务引用")
    }

    @Test
    fun `clearing an account prevents its late response from reinserting a record`() = smoke { scope ->
        val f = Rig(scope)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) { entered.send(Unit); release.receive(); throw IOException("late lost response") }
            response
        }
        val request = scope.async { f.instant.enqueueOfflineTask(f.url, f.target.id) }
        withTimeout(5_000) { entered.receive() }
        val owner = assertNotNull(f.provider.currentClient.value).account
        (f.provider.currentClient as MutableStateFlow).value = f.server.client(account = "new@piko.dev")
        f.instant.offlineSubmissions.clear(owner)
        release.send(Unit)
        assertIs<OfflineSubmitException>(request.await().exceptionOrNull())
        assertTrue(f.instant.offlineSubmissions.works.value.isEmpty())
        assertEquals(1, f.server.tasksSnapshot().size)
    }

    @Test
    fun `a no-task response requires a ready single file receipt rather than an empty envelope or folder root`() = smoke { scope ->
        val f = Rig(scope)
        f.server.offlineEmptyResponse = true
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(f.url, f.target.id).exceptionOrNull())
        assertEquals(OfflineSubmitStatus.UNCONFIRMED, f.instant.offlineSubmissions.works.value.single().status)
        f.server.offlineEmptyResponse = false
        f.server.offlineEmptyTaskResponse = true
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask("https://example.invalid/no-task-id", f.target.id).exceptionOrNull())
        f.server.offlineEmptyTaskResponse = false
        f.server.offlineImmediateFile = f.server.addFolder("partial folder", f.target.id)
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask("https://example.invalid/folder", f.target.id).exceptionOrNull())
        f.server.unheldHashes += "PENDING"
        f.server.offlineImmediateFile = f.server.addFile("pending.mp4", f.target.id, hash = "PENDING")
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask("https://example.invalid/pending", f.target.id).exceptionOrNull())
        val file = f.server.addFile("ready.mp4", f.target.id, hash = "READY")
        f.server.offlineImmediateFile = file
        assertIs<CreateUrlResult.InstantComplete>(f.instant.enqueueOfflineTask("https://example.invalid/ready", f.target.id).getOrThrow())
        val complete = f.instant.offlineSubmissions.works.value.single { it.status == OfflineSubmitStatus.COMPLETE }
        assertEquals(file.id, complete.outputId)
        assertTrue(f.server.tasksSnapshot().isEmpty())
    }

    @Test
    fun `an immediate pack output without a task remains inspectable and is never pruned`() = smoke { scope ->
        val f = Rig(scope)
        val folder = f.server.addFolder("output", f.target.id)
        val keep = f.server.addFile("one.mkv", folder.id, hash = "ONE")
        val other = f.server.addFile("two.mkv", folder.id, hash = "TWO")
        f.server.offlineImmediateFile = folder
        scope.launch(start = CoroutineStart.UNDISPATCHED) { f.packs.run("offline@piko.dev") }
        assertIs<OfflineSubmitException>(f.packs.submit(f.url, f.target.id, "pack", setOf("one.mkv"), 2, 200, 100).exceptionOrNull())
        val work = f.instant.offlineSubmissions.works.value.single()
        assertEquals(OfflineSubmitStatus.UNCONFIRMED, work.status)
        assertEquals(folder.id, work.outputId)
        assertTrue(f.packs.jobs.value.isEmpty())
        assertNotNull(f.server.node(keep.id))
        assertNotNull(f.server.node(other.id))
        assertEquals(0, f.server.count("DELETE /drive/v1/files"))
    }

    @Test
    fun `batch saving keeps an unknown row while handing successful submissions to cloud tasks`() = smoke { scope ->
        val f = Rig(scope)
        val second = "https://example.invalid/second-file"
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") &&
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().contains("PRIVATE")) throw IOException("first response lost")
            response
        }
        val state = f.state(input = "${f.url}\n$second")
        awaitUntil("批量两条 URL 就绪") { state.batch?.canSaveAll == true }
        val batch = assertNotNull(state.batch)
        batch.saveAll()
        awaitUntil("成功行移除，未知行保留") { !batch.isSaving && batch.rows.size == 1 && batch.rows.single().state.hasPendingOfflineSubmission }
        assertFalse(batch.canSaveAll)
        assertFalse(state.canEdit)
        assertEquals(2, f.server.tasksSnapshot().size)
        repeat(3) { batch.saveAll() }
        assertEquals(2, f.server.tasksSnapshot().size)
        assertEquals(1, f.instant.offlineSubmissions.works.value.size)
    }

    @Test
    fun `equivalent magnet spellings cannot bypass an unknown submission while signed URLs stay distinct`() = smoke { scope ->
        val f = Rig(scope)
        f.loseResponse()
        val hash = "0".repeat(40)
        val first = "magnet:?xt=urn:btih:$hash&dn=one"
        assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(first, f.target.id).exceptionOrNull())
        for (other in listOf("magnet:?xt=urn:btih:$hash&dn=two&tr=https://tracker.invalid", "magnet:?xt=urn:btih:${"A".repeat(32)}", hash))
            assertIs<OfflineSubmitException>(f.instant.enqueueOfflineTask(other, f.target.id).exceptionOrNull())
        assertEquals(1, f.server.tasksSnapshot().size)
        f.instant.enqueueOfflineTask("https://example.invalid/file?token=one", f.target.id)
        f.instant.enqueueOfflineTask("https://example.invalid/file?token=two", f.target.id)
        assertEquals(3, f.server.tasksSnapshot().size)
    }

    @Test
    fun `a pack retry owns the new task before slow old-task deletion and cannot race another retry or discard`() = smoke { scope ->
        val f = Rig(scope)
        val old = assertIs<CreateUrlResult.Queued>(f.instant.enqueueOfflineTask(f.url, f.target.id).getOrThrow()).task
        val account = "offline@piko.dev"
        val job = OfflinePackJob(account, old.id, f.url, f.target.id, "pack", setOf("one.mkv"), 2, 200, 1,
            stage = OfflinePackStage.FAILED, finishedAtMs = 2)
        f.preferences.saveOfflinePacks(Json.encodeToString(ListSerializer(OfflinePackJob.serializer()), listOf(job)))
        scope.launch(start = CoroutineStart.UNDISPATCHED) { f.packs.run(account) }
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Delete && request.url.encodedPath.endsWith("/drive/v1/tasks")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        val retry = scope.async { f.packs.retry(old.id) }
        withTimeout(5_000) { entered.receive() }
        val current = f.packs.jobs.value.single()
        assertTrue(current.taskId != old.id)
        assertEquals(OfflinePackStage.QUEUED, current.stage)
        assertTrue(OfflinePackRef(account, current.taskId) in f.packs.busy.value)
        repeat(3) { f.packs.retry(old.id); assertTrue(f.packs.retry(current.taskId).isFailure); assertTrue(f.packs.discard(current.taskId).isFailure) }
        assertEquals(2, f.server.count("POST /drive/v1/files"))
        assertEquals(2, f.server.tasksSnapshot().size)
        release.send(Unit)
        assertTrue(retry.await().isSuccess)
        assertTrue(f.packs.busy.value.isEmpty())
        assertEquals(listOf(current.taskId), f.server.tasksSnapshot().map { it.id })
        assertEquals(current.taskId, f.packs.jobs.value.single().taskId)
    }
}
