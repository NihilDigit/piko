package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.ShareCommands
import dev.piko.shared.presentation.ShareCommandResult
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.ShareSaveCommand
import dev.piko.shared.state.ShareSaveCompletion
import dev.piko.shared.state.ShareSaveSession
import dev.piko.shared.state.ShareSaveState
import dev.piko.shared.state.ShareSaveWork
import dev.piko.shared.state.ShareSource
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class ShareSaveSessionSmokeTest {
    private class Rig(val scope: CoroutineScope, val server: FakePikPakServer = FakePikPakServer(), val http: HttpClient = server.httpClient()) {
        val provider = server.provider(PikPakClient("share@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val preferences = MemoryPreferences()
        val repository = PikoDriveRepository(provider, preferences)
        val session = ShareSaveSession(repository, scope, pollDelay = Duration.ZERO, identityDelay = Duration.ZERO)
        val commands = ShareCommands(repository, session)
        val owner = server.addFolder("owner")
        val target = server.addFolder("target")
        fun source(name: String = "abc00123.mp4") = server.addFile(name, owner.id, hash = name)
        fun command(sources: List<FakePikPakServer.Node>, canonical: Boolean = false): ShareSaveCommand {
            server.shares["S1"] = sources.map { it.id }
            return ShareSaveCommand("S1", sources.map { ShareSource(it.id, it.name, it.isFolder, it.size, it.hash) },
                PikoPathBreadcrumb(target.id, target.name), emptyList(), canonical)
        }
        fun submit(command: ShareSaveCommand) = assertIs<ShareCommandResult.Accepted>(commands.submit(command, "PRIVATE-TOKEN")).workId
        suspend fun finished(id: Long): ShareSaveWork {
            awaitUntil("转存结局保留") { session.works.value.any { it.id == id && it.result != null } }
            return session.works.value.single { it.id == id }
        }
    }

    private fun requestedName(body: Any) = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        .jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
    private fun origin(source: FakePikPakServer.Node) = mapOf("original_share_id" to "S1", "original_file_id" to source.id)

    @Test
    fun `ordinary sharing submits directly without listing or naming the target directory`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        val work = f.finished(f.submit(f.command(listOf(source))))
        assertEquals(ShareSaveCompletion.SUCCEEDED, work.result!!.completion)
        assertEquals(listOf(source.name), f.server.children(f.target.id).map { it.name })
        assertEquals(0, f.server.count("GET /drive/v1/files"))
        assertEquals(1, f.server.count("POST /drive/v1/share/restore"))
        assertTrue(work.undoChangeIds.isEmpty())
        assertFalse(work.toString().contains("PRIVATE-TOKEN"))
    }

    @Test
    fun `canonical naming excludes concurrent unrelated files and undo preserves a later unrelated rename`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        var unrelated: FakePikPakServer.Node? = null
        var copiedId: String? = null
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        var held = false
        f.http.plugin(HttpSend).intercept { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/share/restore")) {
                val response = execute(request)
                copiedId = f.server.children(f.target.id).single().id
                unrelated = f.server.addFile("xyz00456.mp4", f.target.id, hash = "OTHER")
                response
            } else {
                if (!held && request.method == HttpMethod.Get && copiedId != null && path.endsWith("/files/$copiedId")) {
                    held = true; entered.send(Unit); release.receive()
                }
                execute(request)
            }
        }
        val id = f.submit(f.command(listOf(source), canonical = true))
        withTimeout(5_000) { entered.receive() }
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), shareSaves = f.session)
        assertIs<ActionAvailability.Blocked>(files.availability(FileWriteCommand.Rename(copiedId!!, "manual.mp4")))
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Star(listOf(copiedId!!), true)))
        assertEquals(ActionAvailability.Available, files.availability(FileWriteCommand.Rename(unrelated!!.id, "other.mp4")))
        assertIs<ActionAvailability.Blocked>(files.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        assertFalse(f.session.forget(id))
        release.send(Unit)
        val work = f.finished(id)
        assertEquals(ShareSaveCompletion.SUCCEEDED, work.result!!.completion)
        assertEquals("ABC-123.mp4", f.server.node(copiedId!!)?.name)
        assertEquals("xyz00456.mp4", unrelated!!.name)
        f.repository.rename(unrelated!!.id, "later.mp4").getOrThrow()
        val later = DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(unrelated!!.id, "xyz00456.mp4", "later.mp4")), "later")
        f.repository.changes.record(later)
        val inverse = work.undoChangeIds.single()
        assertIs<FileCommandResult.Accepted>(files.undo(inverse))
        awaitUntil("只撤销本次命名") { f.repository.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals(source.name, f.server.node(copiedId!!)?.name)
        assertEquals("later.mp4", unrelated!!.name)
        assertTrue(f.repository.changes.state.value.available.any { it.id == later.id })
        assertNotNull(f.server.node(copiedId!!), "命名撤销不删除转存文件")
    }

    @Test
    fun `a preexisting copy of the same source is excluded from automatic naming`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        val old = f.server.addFile(source.name, f.target.id, hash = source.hash, params = origin(source))
        val work = f.finished(f.submit(f.command(listOf(source), canonical = true)))
        assertEquals(ShareSaveCompletion.SUCCEEDED, work.result!!.completion)
        assertEquals(source.name, old.name)
        val newId = work.associatedOutputs.getValue(source.id)
        assertTrue(newId != old.id)
        assertEquals("ABC-123.mp4", f.server.node(newId)?.name)
    }

    @Test
    fun `missing provenance preserves all original names rather than claiming the whole directory delta`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.server.includeShareProvenance = false
        val work = f.finished(f.submit(f.command(listOf(source), canonical = true)))
        assertEquals(ShareSaveCompletion.PARTIAL, work.result!!.completion)
        assertEquals(setOf(source.id), work.confirmedSources)
        assertEquals(1, work.namingSkipped)
        assertTrue(work.associatedOutputs.isEmpty())
        assertTrue(work.undoChangeIds.isEmpty())
        assertEquals(listOf(source.name), f.server.children(f.target.id).map { it.name })
    }

    @Test
    fun `a second matching origin appearing during confirmation prevents automatic naming`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        var reads = 0
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/v1/files") && request.url.parameters["parent_id"] == f.target.id) {
                if (++reads == 2) f.server.addFile(source.name, f.target.id, hash = source.hash, params = origin(source))
            }
            response
        }
        val work = f.finished(f.submit(f.command(listOf(source), canonical = true)))
        assertEquals(ShareSaveCompletion.PARTIAL, work.result!!.completion)
        assertTrue(work.associatedOutputs.isEmpty())
        assertTrue(f.server.children(f.target.id).all { it.name == source.name })
        assertEquals(2, f.server.children(f.target.id).size)
        assertTrue(work.undoChangeIds.isEmpty())
    }

    @Test
    fun `a lost ordinary restore response remains uncertain and repeated submission is blocked`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.url.encodedPath.endsWith("/share/restore")) throw IOException("restore acknowledgement lost")
            response
        }
        val command = f.command(listOf(source))
        val work = f.finished(f.submit(command))
        assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion)
        assertEquals(setOf(source.id), work.result!!.unconfirmedSources)
        repeat(4) { assertIs<ShareCommandResult.Rejected>(f.commands.submit(command, "PRIVATE-TOKEN")) }
        assertEquals(1, f.server.count("POST /drive/v1/share/restore"))
        assertEquals(1, f.server.children(f.target.id).size)
        assertFalse(f.session.canRecheck(work.id), "缺少远端引用和提交前快照时不能伪装成可自动核对")
        assertTrue(f.session.forget(work.id))
        assertEquals(1, f.server.children(f.target.id).size)
    }

    @Test
    fun `a known remote task is rechecked without submitting another restore`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.server.shareRestoreQueued = true
        var failQuery = true
        f.http.plugin(HttpSend).intercept { request ->
            if (failQuery && request.url.encodedPath.contains("/drive/v1/tasks/")) throw IOException("task query unavailable")
            execute(request)
        }
        val work = f.finished(f.submit(f.command(listOf(source))))
        assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion)
        assertNotNull(work.remoteTaskId)
        val previous = work.result
        failQuery = false
        assertTrue(f.session.recheck(work.id))
        awaitUntil("已知任务只读查询完成") { f.session.works.value.single().result?.let { it !== previous && it.completion == ShareSaveCompletion.SUCCEEDED } == true }
        assertEquals(1, f.server.count("POST /drive/v1/share/restore"))
        assertEquals(listOf(source.name), f.server.children(f.target.id).map { it.name })
    }

    @Test
    fun `a lost canonical restore response remains uncertain despite unique new source identities`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.url.encodedPath.endsWith("/share/restore")) throw IOException("restore acknowledgement lost")
            response
        }
        val work = f.finished(f.submit(f.command(listOf(source), canonical = true)))
        assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion)
        assertTrue(work.confirmedSources.isEmpty())
        assertTrue(work.associatedOutputs.isEmpty())
        assertTrue(work.undoChangeIds.isEmpty())
        assertFalse(f.session.canRecheck(work.id))
        assertEquals(source.name, f.server.children(f.target.id).single().name)
        assertEquals(1, f.server.count("POST /drive/v1/share/restore"))
    }

    @Test
    fun `confirming a canonical copy later only queries the task and leaves its names unchanged`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.server.shareRestoreQueued = true
        var failQuery = true
        f.http.plugin(HttpSend).intercept { request ->
            if (failQuery && request.url.encodedPath.contains("/drive/v1/tasks/")) throw IOException("query unavailable")
            execute(request)
        }
        val work = f.finished(f.submit(f.command(listOf(source), canonical = true)))
        assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion)
        failQuery = false
        assertTrue(f.session.recheck(work.id))
        awaitUntil("只读确认转存，命名仍未处理") { f.session.works.value.single().result?.completion == ShareSaveCompletion.PARTIAL }
        val confirmed = f.session.works.value.single()
        assertEquals(setOf(source.id), confirmed.confirmedSources)
        assertEquals(1, confirmed.namingSkipped)
        val copy = f.server.children(f.target.id).single()
        assertEquals(source.name, copy.name)
        assertEquals(0, f.server.count("PATCH /drive/v1/files/${copy.id}"))
        assertEquals(1, f.server.count("POST /drive/v1/share/restore"))
        assertTrue(confirmed.undoChangeIds.isEmpty())
    }

    @Test
    fun `another client copying the same source cannot confirm an unsent restore or authorize naming`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        var external: FakePikPakServer.Node? = null
        f.http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/share/restore")) {
                external = f.server.addFile(source.name, f.target.id, hash = source.hash, params = origin(source))
                throw IOException("request never sent")
            }
            execute(request)
        }
        val command = f.command(listOf(source), canonical = true)
        val work = f.finished(f.submit(command))
        assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion)
        assertEquals(setOf(source.id), work.result!!.unconfirmedSources)
        assertTrue(work.associatedOutputs.isEmpty())
        assertTrue(work.undoChangeIds.isEmpty())
        assertEquals(source.name, external!!.name)
        assertEquals(0, f.server.count("POST /drive/v1/share/restore"))
        assertEquals(0, f.server.count("PATCH /drive/v1/files/${external!!.id}"))
        assertIs<ShareCommandResult.Rejected>(f.commands.submit(command, "PRIVATE-TOKEN"))
    }

    @Test
    fun `visible partial folder roots never confirm an unfinished or failed remote copy`() = smoke { scope ->
        for (phase in listOf(TaskPhase.RUNNING, TaskPhase.ERROR, null)) {
            val f = Rig(scope)
            val source = f.server.addFolder("abc00123", f.owner.id)
            f.server.addFile("xyz00456.mp4", source.id, hash = "CHILD")
            f.server.shareRestoreQueued = true
            f.server.shareRestorePhase = phase ?: TaskPhase.RUNNING
            var partial: FakePikPakServer.Node? = null
            f.http.plugin(HttpSend).intercept { request ->
                if (phase == null && request.url.encodedPath.contains("/drive/v1/tasks/")) throw IOException("query unavailable")
                val response = execute(request)
                if (request.url.encodedPath.endsWith("/share/restore")) {
                    partial = f.server.addFolder(source.name, f.target.id, params = origin(source))
                    f.server.addFile("xyz00456.mp4", partial!!.id, hash = "CHILD")
                }
                response
            }
            val command = f.command(listOf(source), canonical = true)
            val work = f.finished(f.submit(command))
            assertEquals(ShareSaveCompletion.UNCONFIRMED, work.result!!.completion, "remote phase $phase")
            assertEquals(setOf(source.id), work.result!!.unconfirmedSources)
            assertTrue(work.confirmedSources.isEmpty())
            assertTrue(work.associatedOutputs.isEmpty())
            assertTrue(work.undoChangeIds.isEmpty())
            assertEquals(source.name, partial!!.name)
            assertEquals(0, f.server.count("PATCH /drive/v1/files/${partial!!.id}"))
            assertTrue(f.session.canRecheck(work.id))
            assertIs<ShareCommandResult.Rejected>(f.commands.submit(command, "PRIVATE-TOKEN"))
        }
    }

    @Test
    fun `leaving the share page does not cancel an accepted restore or lose its result`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.command(listOf(source))
        val pageScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val state = ShareSaveState(f.repository, pageScope, "S1", session = f.session)
        awaitUntil("分享已打开") { !state.isLoading && state.info != null }
        state.toggle(state.entries.single())
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/share/restore")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        state.save(PikoPathBreadcrumb(f.target.id, f.target.name))
        withTimeout(5_000) { entered.receive() }
        val id = f.session.works.value.single().id
        pageScope.cancel()
        state.selectedIds.clear()
        release.send(Unit)
        val result = f.finished(id)
        assertEquals(ShareSaveCompletion.SUCCEEDED, result.result!!.completion)
        assertEquals(listOf(source.name), f.server.children(f.target.id).map { it.name })
    }

    @Test
    fun `an own-share refusal is failed rather than an uncertain copy`() = smoke { scope ->
        val f = Rig(scope)
        val source = f.source()
        f.http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/share/restore")) throw PikPakException(9, "file_restore_own", httpStatus = 200)
            execute(request)
        }
        val work = f.finished(f.submit(f.command(listOf(source))))
        assertEquals(ShareSaveCompletion.FAILED, work.result!!.completion)
        assertEquals("无法转存自己发布的分享", work.result!!.message)
        assertTrue(work.result!!.unconfirmedSources.isEmpty())
        assertTrue(f.server.children(f.target.id).isEmpty())
    }

    @Test
    fun `a queried final swap step stays in one inverse recipe and undo restores both folder identities`() = smoke { scope ->
        val f = Rig(scope)
        val a = f.server.addFolder("ABC-123", f.owner.id)
        val b = f.server.addFolder("XYZ-456", f.owner.id)
        f.server.addFile("XYZ-456.mp4", a.id, hash = "X")
        f.server.addFile("ABC-123.mp4", b.id, hash = "A")
        var temporaryId: String? = null
        var hideDetail = false
        var loseFinalResponses = true
        f.http.plugin(HttpSend).intercept { request ->
            val path = request.url.encodedPath
            if (hideDetail && request.method == HttpMethod.Get && path.endsWith("/files/$temporaryId")) throw IOException("confirmation unavailable")
            val response = execute(request)
            if (request.method == HttpMethod.Patch) {
                val name = requestedName(request.body)
                if (name.contains(".piko-swap")) temporaryId = path.substringAfterLast('/')
                else if (loseFinalResponses && temporaryId != null && path.endsWith("/files/$temporaryId")) { hideDetail = true; throw IOException("final rename acknowledgement lost") }
            }
            response
        }
        val work = f.finished(f.submit(f.command(listOf(a, b), canonical = true)))
        assertEquals(ShareSaveCompletion.PARTIAL, work.result!!.completion)
        assertEquals(1, work.pendingRenames.size)
        val inverse = work.undoChangeIds.single()
        val files = FileCommands(f.repository, FileWriteSession(f.repository, scope), shareSaves = f.session)
        assertIs<FileCommandResult.Rejected>(files.undo(inverse))
        val beforeQuery = work.result
        val patches = f.server.count("PATCH /drive/v1/files/$temporaryId")
        loseFinalResponses = false
        hideDetail = false
        assertTrue(f.session.recheck(work.id))
        awaitUntil("只读查询确认最后一步") { f.session.works.value.single().result?.let { it !== beforeQuery && it.completion == ShareSaveCompletion.SUCCEEDED } == true }
        val confirmed = f.session.works.value.single()
        assertEquals(patches, f.server.count("PATCH /drive/v1/files/$temporaryId"), "核对不能再次改名")
        assertEquals(listOf(inverse), confirmed.undoChangeIds, "同一文件的临时步骤与最终步骤不能拆成并行撤销")
        val recipe = f.repository.changes.state.value.available.filterIsInstance<DriveChangeJournal.Change.Rename>().single { it.id == inverse }
        assertEquals(3, recipe.renames.size)
        assertIs<FileCommandResult.Accepted>(files.undo(inverse))
        awaitUntil("一次撤销恢复整个名称互换") { f.repository.changes.state.value.undo[inverse]?.status == DriveChangeJournal.UndoStatus.UNDONE }
        assertEquals("ABC-123", f.server.node(confirmed.associatedOutputs.getValue(a.id))?.name)
        assertEquals("XYZ-456", f.server.node(confirmed.associatedOutputs.getValue(b.id))?.name)
        assertEquals(2, f.server.children(f.target.id).size)
    }
}
