package dev.piko.shared.smoke

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.data.OfflinePackJob
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.ReceivingActivity
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveTargetContext
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadPreparationCompletion
import dev.piko.shared.upload.UploadSourceInfo
import dev.piko.shared.upload.UploadTreeEntry
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReceivingReverseSmokeTest {
    private open class Sources : PikoUploadSources {
        override fun describe(uri: String) = UploadSourceInfo("file.bin", 0, 1)
        override fun readAt(uri: String, offset: Long, length: Int) = ByteArray(length)
        override fun open(uri: String, offset: Long): RawSource = Buffer()
        override fun listFolder(uri: String): UploadFolder? = null
    }
    private class Rig(val scope: CoroutineScope, sources: PikoUploadSources = Sources()) {
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = server.provider(PikPakClient("reverse@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val preferences = MemoryPreferences()
        val drive = PikoDriveRepository(provider, preferences)
        val instant = InstantMagnetRepository(provider)
        val records = InstantSaveRecords(provider, null, scope)
        val uploads = PikoUploadCoordinator(provider, preferences, sources, drive, scope)
        val extracts = ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), preferences), scope)
        val packs = OfflinePackTracker(instant, drive, preferences)
        val activity = ReceivingActivity(records, uploads, instant.offlineSubmissions, extracts, packs, scope)
        val commands = FileCommands(drive, FileWriteSession(drive, scope), receiving = activity)
        val parent = server.addFolder("parent")
        val target = server.addFolder("target", parent.id)
        val other = server.addFolder("other", parent.id)
        suspend fun ready() { drive.listAllFiles().getOrThrow(); drive.listAllFiles(parent.id).getOrThrow(); awaitUntil("上传恢复完成") { !uploads.isRestoring.value } }
        fun sheet(magnet: String) = InstantSheetState(instant, drive, preferences, PreviewTempFolder(provider, drive, instant, scope),
            OfflinePackTracker(instant, drive, preferences), records, scope, magnet, SaveTargetContext.fixed(PikoPathBreadcrumb(target.id, target.name)))
    }
    private fun name(body: Any) = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject["name"]?.jsonPrimitive?.content.orEmpty()

    @Test
    fun `acknowledged cloud work keeps dependency protection until observed completion or acknowledged removal`() = smoke { scope ->
        val f = Rig(scope)
        f.ready()
        val tasks = TaskRepository(f.provider, f.drive, f.instant)
        val queued = f.instant.enqueueOfflineTask("https://example.invalid/cloud", f.target.id).getOrThrow() as io.github.nihildigit.pikpak.CreateUrlResult.Queued
        assertTrue(f.instant.offlineSubmissions.works.value.isEmpty(), "不复制云端进度行")
        assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(f.target.id, "new-target")))
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.other.id))))
        f.server.completeTask(queued.task.id, "output", listOf("one.mkv"))
        tasks.getTasks().getOrThrow()
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        val second = f.instant.enqueueOfflineTask("https://example.invalid/second", f.target.id).getOrThrow() as io.github.nihildigit.pikpak.CreateUrlResult.Queued
        tasks.getTasks().getOrThrow()
        assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        tasks.deleteTasks(listOf(second.task.id)).getOrThrow()
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        assertEquals(listOf("one.mkv"), f.server.tree(f.server.tasksSnapshot().single().fileId))
    }

    @Test
    fun `an accepted instant save protects its target tree while metadata and unrelated trees remain independent`() = smoke { scope ->
        val f = Rig(scope)
        f.ready()
        val magnet = "magnet:?xt=urn:btih:" + "b".repeat(40)
        f.server.indexMagnet(magnet, resourceListBody("One", listOf(Triple("one.mkv", 10L, "HASH"))))
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "one.mkv") { entered.send(Unit); release.receive() }
            execute(request)
        }
        val state = f.sheet(magnet)
        awaitUntil("秒传可执行") { state.canSaveSelection }
        state.saveSelection()
        try {
            withTimeout(5_000) { entered.receive() }
            assertIs<FileCommandResult.Rejected>(f.commands.submit(FileWriteCommand.Delete(listOf(f.target.id))))
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Move(listOf(f.parent.id), PikoDriveRepository.ROOT_BREADCRUMB)))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(f.parent.id, "new-parent")))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Star(listOf(f.target.id), true)))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.other.id))))
            assertIs<ActionAvailability.Blocked>(f.commands.filesAvailability(listOf(f.parent.id)))
            assertEquals(ActionAvailability.Available, f.commands.filesAvailability(listOf(f.other.id)))
            release.send(Unit)
            awaitUntil("保存完成解除保护") { !state.isSaving && f.records.attempts.value.isEmpty() }
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
        } finally { release.trySend(Unit) }
    }

    @Test
    fun `source enumeration is already an accepted upload dependency before any file task exists`() = smoke { scope ->
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val f = Rig(scope, object : Sources() {
            override fun listFolder(uri: String): UploadFolder = runBlocking { entered.send(Unit); release.receive(); UploadFolder("folder", listOf(UploadTreeEntry("file", ""))) }
        })
        f.ready()
        f.uploads.enqueue(dev.piko.shared.upload.UploadSelection(folders = listOf("local-folder")), f.target.id, f.target.name)
        try {
            withTimeout(5_000) { entered.receive() }
            assertTrue(f.uploads.tasks.value.isEmpty())
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Move(listOf(f.parent.id), PikoDriveRepository.ROOT_BREADCRUMB)))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(f.target.id, "renamed-target")))
            assertEquals(ActionAvailability.Available, f.commands.filesAvailability(listOf(f.other.id)))
        } finally { release.trySend(Unit) }
    }

    @Test
    fun `an unknown upload directory retains its target protection until its result is explicitly removed`() = smoke { scope ->
        val f = Rig(scope, object : Sources() {
            override fun listFolder(uri: String) = UploadFolder("folder", listOf(UploadTreeEntry("file", "")))
        })
        f.ready()
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "folder") throw IOException("lost folder response")
            response
        }
        f.uploads.enqueue(dev.piko.shared.upload.UploadSelection(folders = listOf("local-folder")), f.target.id, f.target.name)
        awaitUntil("目录准备待确认") { f.uploads.preparations.value.singleOrNull()?.result?.completion == UploadPreparationCompletion.UNCONFIRMED }
        assertIs<FileCommandResult.Rejected>(f.commands.submit(FileWriteCommand.Delete(listOf(f.parent.id))))
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(f.target.id, "new-target")))
        assertTrue(f.uploads.forgetPreparation(f.uploads.preparations.value.single().id))
        assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Delete(listOf(f.parent.id))))
        assertEquals(1, f.server.children(f.target.id).size)
    }

    @Test
    fun `archive extraction protects both its input and receiving directory without blocking stars`() = smoke { scope ->
        val f = Rig(scope)
        f.ready()
        val archive = f.server.addArchive("pack.zip", mapOf("one.txt" to byteArrayOf(1)), f.target.id)
        val file = f.drive.listAllFiles(f.target.id).getOrThrow().single { it.id == archive.node.id }
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("/decompress/v1/progress")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        f.extracts.extractEntries(file, listOf("one.txt"), "")
        try {
            withTimeout(5_000) { entered.receive() }
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Delete(listOf(f.target.id))))
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.Rename(file.id, "other.zip")))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Copy(listOf(file.id), PikoPathBreadcrumb(f.other.id, f.other.name))))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Star(listOf(file.id), true)))
            release.send(Unit)
            awaitUntil("解压结束后解除输入与目标保护") { f.extracts.jobs.isEmpty() && f.extracts.completedJobs.isNotEmpty() }
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(file.id, "other.zip")))
        } finally { release.trySend(Unit) }
    }

    @Test
    fun `a restored pruning job protects output paths before its first listing and preserves selected files`() = smoke { scope ->
        val f = Rig(scope)
        f.ready()
        val task = (f.instant.enqueueOfflineTask("https://example.invalid/pack", f.target.id).getOrThrow() as io.github.nihildigit.pikpak.CreateUrlResult.Queued).task
        f.server.completeTask(task.id, "pack", listOf("one.mkv", "unselected.txt"))
        val output = f.server.tasksSnapshot().single().fileId
        val one = f.server.children(output).single { it.name == "one.mkv" }
        f.drive.listAllFiles(f.target.id).getOrThrow()
        f.drive.listAllFiles(output).getOrThrow()
        val job = OfflinePackJob("reverse@piko.dev", task.id, "https://example.invalid/pack", f.target.id, "pack", setOf("one.mkv"), 2, 200, 1,
            stage = OfflinePackStage.PRUNING) // 已发布的旧记录可以没有 outputId。
        f.preferences.saveOfflinePacks(Json.encodeToString(ListSerializer(OfflinePackJob.serializer()), listOf(job)))
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/drive/v1/files") &&
                request.url.parameters["parent_id"] == output) { entered.send(Unit); release.receive() }
            execute(request)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { f.packs.run("reverse@piko.dev") }
        try {
            withTimeout(5_000) { entered.receive() }
            assertEquals(output, f.packs.jobs.value.single().outputId)
            assertIs<FileCommandResult.Rejected>(f.commands.submit(FileWriteCommand.Rename(one.id, "renamed.mkv")))
            assertIs<FileCommandResult.Rejected>(f.commands.submit(FileWriteCommand.Move(listOf(one.id), PikoPathBreadcrumb(f.other.id, f.other.name))))
            assertIs<ActionAvailability.Blocked>(f.commands.availability(FileWriteCommand.CreateFolder(PikoPathBreadcrumb(output, "pack"), "new")))
            assertIs<ActionAvailability.Blocked>(f.commands.receivingAvailability(output))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(f.target.id, "new-target")))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Star(listOf(one.id), true)))
            release.send(Unit)
            awaitUntil("裁剪完成保留已选文件") { f.packs.jobs.value.single().stage == OfflinePackStage.DONE }
            assertEquals(listOf("one.mkv"), f.server.tree(output))
            assertEquals(ActionAvailability.Available, f.commands.availability(FileWriteCommand.Rename(one.id, "renamed.mkv")))
        } finally { release.trySend(Unit) }
    }
}
