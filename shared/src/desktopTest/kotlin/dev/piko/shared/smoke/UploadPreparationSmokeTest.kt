package dev.piko.shared.smoke

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSelection
import dev.piko.shared.upload.UploadSourceInfo
import dev.piko.shared.upload.UploadTreeEntry
import dev.piko.shared.upload.UploadEnqueueResult
import dev.piko.shared.upload.UploadPreparationCompletion
import dev.piko.shared.upload.UploadPreparationIssueKind
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertFalse

class UploadPreparationSmokeTest {
    @Test
    fun `pausing a batch during preparation also pauses later files`() = smoke { scope ->
        checkBatchDuringPreparation(scope, remove = false)
    }

    @Test
    fun `removing a batch during preparation prevents later files from returning`() = smoke { scope ->
        checkBatchDuringPreparation(scope, remove = true)
    }

    private suspend fun checkBatchDuringPreparation(scope: CoroutineScope, remove: Boolean) {
        val describingSecond = Channel<Unit>(1)
        val describeResume = Channel<Unit>(1)
        val firstDescription = AtomicBoolean(true)
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String): UploadSourceInfo = runBlocking {
                if (uri == "two" && firstDescription.compareAndSet(true, false)) {
                    describingSecond.send(Unit)
                    describeResume.receive()
                }
                UploadSourceInfo("$uri.bin", 0, 1)
            }
        })
        f.ready()
        val uploading = Channel<Unit>(Channel.UNLIMITED)
        val uploadResume = Channel<Unit>(Channel.UNLIMITED)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                uploading.send(Unit)
                uploadResume.receive()
            }
            execute(request)
        }
        try {
            val accepted = assertIs<UploadEnqueueResult.Accepted>(f.uploads.enqueue(
                UploadSelection(files = listOf("one", "two")), f.target.id, f.target.name))
            withTimeout(5_000) { describingSecond.receive(); uploading.receive() }
            assertEquals(1, f.uploads.tasks.value.size)
            if (remove) f.uploads.removeBatch(accepted.preparationId) else f.uploads.pauseBatch(accepted.preparationId)
            describeResume.send(Unit)
            if (remove) {
                awaitUntil("准备取消且文件没有重新入队") { f.uploads.preparations.value.any { !it.isRunning } }
                assertTrue(f.uploads.tasks.value.isEmpty())
                assertEquals(UploadPreparationCompletion.STOPPED, f.uploads.preparations.value.single().result?.completion)
            } else {
                awaitUntil("后续准备的文件也暂停") {
                    f.uploads.tasks.value.size == 2 && f.uploads.tasks.value.values.all { it.status == UploadStatus.PAUSED }
                }
                assertEquals(0, f.server.instantCreates.get())
                repeat(2) { uploadResume.send(Unit) }
                f.uploads.resumeBatch(accepted.preparationId)
                awaitUntil("暂停的批次可以继续完成") { f.uploads.tasks.value.values.all { it.status == UploadStatus.COMPLETED } }
                assertEquals(2, f.server.instantCreates.get())
            }
        } finally { describeResume.trySend(Unit); uploadResume.close() }
    }

    @Test
    fun `multi-file upload batches pause resume and remove together`() = smoke { scope ->
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String) = UploadSourceInfo("$uri.bin", 0, 1)
        })
        f.ready()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                entered.send(Unit)
                release.receive()
            }
            execute(request)
        }
        try {
            f.uploads.enqueue(UploadSelection(files = listOf("one", "two", "three")), f.target.id, f.target.name)
            withTimeout(5_000) { repeat(3) { entered.receive() } }
            val batchId = assertNotNull(f.uploads.tasks.value.values.map { it.batchId }.distinct().single())
            assertEquals("one.bin 等 3 项", f.uploads.tasks.value.values.first().batchName)
            f.uploads.pauseBatch(batchId)
            awaitUntil("全部文件暂停") { f.uploads.tasks.value.values.all { it.status == UploadStatus.PAUSED } }
            repeat(3) { release.send(Unit) }
            f.uploads.resumeBatch(batchId)
            awaitUntil("整组继续并完成") { f.uploads.tasks.value.values.all { it.status == UploadStatus.COMPLETED } }
            assertEquals(3, f.server.children(f.target.id).size)
            f.uploads.removeBatch(batchId)
            assertTrue(f.uploads.tasks.value.isEmpty())
        } finally { release.close() }
    }

    @Test
    fun `four uploads overlap and freed slots drain the queue without duplicate claims`() = smoke { scope ->
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String) = UploadSourceInfo("$uri.bin", 0, 1)
        })
        f.ready()
        val entered = Channel<String>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    entered.send(requestedName(request.body))
                    release.receive()
                    execute(request)
                } finally { active.decrementAndGet() }
            } else execute(request)
        }
        try {
            f.uploads.enqueue(UploadSelection(files = (1..7).map { "file$it" }), f.target.id, f.target.name)
            val first = withTimeout(5_000) { List(4) { entered.receive() } }
            assertEquals(4, first.toSet().size)
            assertEquals(4, peak.get())
            assertTrue(entered.tryReceive().isFailure)
            release.send(Unit)
            val fifth = withTimeout(5_000) { entered.receive() }
            repeat(7) { release.send(Unit) }
            awaitUntil("全部文件完成") { f.uploads.tasks.value.size == 7 && f.uploads.tasks.value.values.all { it.status == UploadStatus.COMPLETED } }
            assertEquals(7, f.server.children(f.target.id).size)
            assertEquals(4, peak.get())
            val rest = withTimeout(5_000) { List(2) { entered.receive() } }
            assertEquals((1..7).map { "file$it.bin" }.toSet(), (first + fifth + rest).toSet())
            assertTrue(entered.tryReceive().isFailure)
        } finally { release.close() }
    }

    private open class Sources : PikoUploadSources {
        override fun describe(uri: String): UploadSourceInfo? = UploadSourceInfo("file.bin", 0, 1)
        override fun readAt(uri: String, offset: Long, length: Int) = ByteArray(length)
        override fun open(uri: String, offset: Long): RawSource = Buffer()
        override fun listFolder(uri: String): UploadFolder? = null
    }

    @Test
    fun `accepted upload preparation is visible before source enumeration and survives closing its page`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val account = assertNotNull(provider.currentClient.value).account
        val preferences = MemoryPreferences()
        val drive = PikoDriveRepository(provider, preferences)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val source = object : Sources() {
            override fun listFolder(uri: String): UploadFolder = runBlocking {
                entered.send(Unit); release.receive()
                UploadFolder("local-folder", listOf(UploadTreeEntry("local-file", "")))
            }
        }
        val uploads = PikoUploadCoordinator(provider, preferences, source, drive, scope)
        awaitUntil("上传存储恢复完成") { !uploads.isRestoring.value }
        val instant = InstantMagnetRepository(provider)
        val transfers = TransfersState(
            PikoDownloadCoordinator(provider, preferences, DirectoryStorage(Files.createTempDirectory("piko-upload-preparing").toFile()), scope),
            TaskRepository(provider, drive, instant), OfflinePackTracker(instant, drive, preferences), scope, drive, uploads,
            InstantSaveRecords(provider, null, scope), account,
            ArchiveExtractSession(provider, drive, ArchivePasswordVault(provider, MemorySessionStore(), preferences), scope),
            FolderVaultSession(drive, scope), listsServerWork = true,
        )
        val target = server.addFolder("target")
        val selection = UploadSelection(folders = listOf("local-folder"))
        uploads.request(selection)
        val page = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        page.launch(start = CoroutineStart.UNDISPATCHED) {
            uploads.enqueue(selection, target.id, target.name)
            uploads.clearRequest()
        }
        assertEquals(null, uploads.pendingRequest.value)
        assertEquals(1, uploads.preparations.value.size, "接受后立即登记，不能等 IO 协程启动")
        assertTrue(uploads.tasks.value.isEmpty())
        try {
            withTimeout(5_000) { entered.receive() }
            awaitUntil("任务页可查询正在准备的上传") { transfers.inProgress.any { it is TransferItem.UploadPreparing } }
            assertEquals(1, transfers.counts[TransferKind.UPLOAD])
            val work = transfers.inProgress.filterIsInstance<TransferItem.UploadPreparing>().single().work
            assertEquals(target.id, work.parentId)
            page.cancel()
            assertEquals(1, uploads.preparations.value.size)
            release.send(Unit)
            awaitUntil("离页后准备完成并交给逐文件队列") { uploads.preparations.value.isEmpty() && uploads.tasks.value.size == 1 }
            val task = uploads.tasks.value.values.single()
            assertEquals(account, task.account)
            assertEquals("local-file", task.sourceUri)
            assertEquals(work.id, task.batchId)
            assertEquals("local-folder", task.batchName)
            assertEquals(target.id, task.batchParentId)
            assertTrue(task.batchIsFolder)
            assertEquals("local-folder", server.node(task.parentId)?.name)
            awaitUntil("准备行由实际上传任务取代") { transfers.inProgress.none { it is TransferItem.UploadPreparing } }
        } finally { release.trySend(Unit); page.cancel() }
    }

    @Test
    fun `a source enumeration exception clears preparation and reports failure without crashing the owner`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val preferences = MemoryPreferences()
        val sources = object : Sources() {
            override fun listFolder(uri: String): UploadFolder = throw IOException("source unavailable")
        }
        val uploads = PikoUploadCoordinator(provider, preferences, sources, PikoDriveRepository(provider, preferences), scope)
        awaitUntil("上传存储恢复完成") { !uploads.isRestoring.value }
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { uploads.preparationOutcomes.first() }
        uploads.enqueue(UploadSelection(folders = listOf("local-folder")), "", "root")
        val work = withTimeout(5_000) { outcome.await() }
        assertEquals(UploadPreparationCompletion.FAILED, work.result?.completion)
        awaitUntil("异常结果可查询且不再执行") { uploads.preparations.value.singleOrNull()?.isRunning == false }
        assertEquals(UploadPreparationIssueKind.SOURCE_UNREADABLE, work.issues.single().kind)
        assertTrue(uploads.tasks.value.isEmpty())
        assertTrue(server.children("").isEmpty())
        assertTrue(uploads.forgetPreparation(work.id))
        assertTrue(uploads.preparations.value.isEmpty())
    }

    private class Rig(val scope: CoroutineScope, sources: PikoUploadSources) {
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = server.provider(PikPakClient("upload@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        val preferences = MemoryPreferences()
        val drive = PikoDriveRepository(provider, preferences)
        val uploads = PikoUploadCoordinator(provider, preferences, sources, drive, scope)
        val target = server.addFolder("target")
        suspend fun ready() { awaitUntil("上传存储恢复完成") { !uploads.isRestoring.value } }
        suspend fun finished() {
            awaitUntil("准备结果保留") { uploads.preparations.value.any { !it.isRunning } }
        }
    }

    private fun requestedName(body: Any): String = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        .jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun treeSources(entries: List<UploadTreeEntry> = listOf(UploadTreeEntry("one", ""))) = object : Sources() {
        override fun describe(uri: String) = UploadSourceInfo("$uri.bin", 0, 1)
        override fun listFolder(uri: String) = UploadFolder("local-folder", entries)
    }

    @Test
    fun `a lost root-directory response remains unknown and blocks another whole-folder preparation`() = smoke { scope ->
        val f = Rig(scope, treeSources())
        f.ready()
        var creates = 0
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "local-folder") {
                creates++; throw IOException("directory response lost")
            }
            response
        }
        val selection = UploadSelection(folders = listOf("private://ROOT-URI"))
        assertIs<UploadEnqueueResult.Accepted>(f.uploads.enqueue(selection, f.target.id, f.target.name))
        f.finished()
        val work = f.uploads.preparations.value.single()
        assertEquals(UploadPreparationCompletion.UNCONFIRMED, work.result?.completion)
        assertTrue(work.directories.isEmpty())
        assertTrue(work.files.isEmpty())
        assertTrue(work.hasUnconfirmedDirectory)
        assertEquals(1, f.server.children(f.target.id).size)
        assertTrue(f.uploads.tasks.value.isEmpty())
        assertFalse(work.toString().contains("private://ROOT-URI"))
        f.uploads.request(selection)
        repeat(3) { assertIs<UploadEnqueueResult.Rejected>(f.uploads.enqueue(selection, f.target.id, f.target.name)) }
        assertEquals(selection, f.uploads.pendingRequest.value)
        assertEquals(1, creates)
        assertTrue(f.uploads.forgetPreparation(work.id))
        assertEquals(1, f.server.children(f.target.id).size, "移除回执不删除失去响应的实际目录")
    }

    @Test
    fun `a later unknown subdirectory preserves queued files and lets independent siblings continue`() = smoke { scope ->
        val f = Rig(scope, treeSources(listOf(UploadTreeEntry("one", ""), UploadTreeEntry("blocked", "nested"), UploadTreeEntry("two", ""))))
        f.ready()
        var roots = 0
        var nested = 0
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files")) {
                when (requestedName(request.body)) {
                    "local-folder" -> roots++
                    "nested" -> { nested++; throw IOException("nested directory response lost") }
                }
            }
            response
        }
        val selection = UploadSelection(folders = listOf("local-root"))
        f.uploads.enqueue(selection, f.target.id, f.target.name)
        f.finished()
        val work = f.uploads.preparations.value.single()
        assertEquals(UploadPreparationCompletion.PARTIAL, work.result?.completion)
        assertEquals(setOf("one.bin", "two.bin"), work.files.map { it.path }.toSet())
        assertEquals(work.files.map { it.taskId }.toSet(), f.uploads.tasks.value.keys)
        assertEquals(1, work.directories.size)
        assertEquals("nested", work.issues.single { it.kind == UploadPreparationIssueKind.DIRECTORY_UNCONFIRMED }.path)
        assertEquals("nested/blocked.bin", work.issues.single { it.kind == UploadPreparationIssueKind.NOT_ATTEMPTED }.path)
        repeat(3) { assertIs<UploadEnqueueResult.Rejected>(f.uploads.enqueue(selection, f.target.id, f.target.name)) }
        assertEquals(1, roots)
        assertEquals(1, nested)
        val root = f.server.node(work.directories.single().id)
        assertNotNull(root)
        assertEquals("local-folder", root.name)
        assertEquals(listOf("nested"), f.server.children(root.id).filter { it.isFolder }.map { it.name })
    }

    @Test
    fun `a definite subdirectory refusal does not prevent a different subdirectory from entering the queue`() = smoke { scope ->
        val f = Rig(scope, treeSources(listOf(UploadTreeEntry("blocked", "denied"), UploadTreeEntry("good", "independent"))))
        f.ready()
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "denied")
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        f.uploads.enqueue(UploadSelection(folders = listOf("local-root")), f.target.id, f.target.name)
        f.finished()
        val work = f.uploads.preparations.value.single()
        assertEquals(UploadPreparationCompletion.PARTIAL, work.result?.completion)
        assertEquals(listOf("independent/good.bin"), work.files.map { it.path })
        assertEquals(setOf("", "independent"), work.directories.map { it.path }.toSet())
        assertFalse(work.hasUnconfirmedDirectory)
        assertEquals(1, work.issues.count { it.kind == UploadPreparationIssueKind.DIRECTORY_REFUSED })
        assertEquals(1, work.issues.count { it.kind == UploadPreparationIssueKind.NOT_ATTEMPTED })
    }

    @Test
    fun `unreadable independent files retain successful task ids and may be reselected without duplicating them`() = smoke { scope ->
        var unreadable = true
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String): UploadSourceInfo? = if (uri == "bad" && unreadable) null else UploadSourceInfo("$uri.bin", 0, 1)
        })
        f.ready()
        f.uploads.enqueue(UploadSelection(files = listOf("good", "bad")), f.target.id, f.target.name)
        f.finished()
        val work = f.uploads.preparations.value.single()
        assertEquals(UploadPreparationCompletion.PARTIAL, work.result?.completion)
        val original = work.files.single().taskId
        assertEquals("good", f.uploads.tasks.value.getValue(original).sourceUri)
        assertIs<UploadEnqueueResult.Rejected>(f.uploads.enqueue(UploadSelection(files = listOf("good")), f.target.id, f.target.name))
        unreadable = false
        assertIs<UploadEnqueueResult.Accepted>(f.uploads.enqueue(UploadSelection(files = listOf("bad")), f.target.id, f.target.name))
        awaitUntil("只将未读取成功的来源重新入队") { f.uploads.tasks.value.size == 2 }
        assertEquals(setOf("good", "bad"), f.uploads.tasks.value.values.map { it.sourceUri }.toSet())
        assertTrue(original in f.uploads.tasks.value)
    }

    @Test
    fun `empty or entirely unreadable local folders do not create remote empty directories`() = smoke { scope ->
        for (entries in listOf(emptyList(), listOf(UploadTreeEntry("unreadable", "sub")))) {
            val f = Rig(scope, object : Sources() {
                override fun describe(uri: String): UploadSourceInfo? = null
                override fun listFolder(uri: String) = UploadFolder("empty", entries)
            })
            f.ready()
            f.uploads.enqueue(UploadSelection(folders = listOf("root")), f.target.id, f.target.name)
            f.finished()
            assertEquals(UploadPreparationCompletion.FAILED, f.uploads.preparations.value.single().result?.completion)
            assertTrue(f.uploads.tasks.value.isEmpty())
            assertTrue(f.server.children(f.target.id).isEmpty())
        }
    }

    @Test
    fun `a late preparation uses its accepted account and emits an account-tagged handoff`() = smoke { scope ->
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val f = Rig(scope, object : Sources() {
            override fun listFolder(uri: String): UploadFolder = runBlocking { entered.send(Unit); release.receive(); UploadFolder("old-folder", listOf(UploadTreeEntry("file", ""))) }
        })
        f.ready()
        val handoff = scope.async(start = CoroutineStart.UNDISPATCHED) { f.uploads.enqueued.first() }
        f.uploads.enqueue(UploadSelection(folders = listOf("root")), f.target.id, f.target.name)
        withTimeout(5_000) { entered.receive() }
        val other = FakePikPakServer("B")
        (f.provider.currentClient as MutableStateFlow).value = other.client(account = "other@piko.dev")
        release.send(Unit)
        val event = withTimeout(5_000) { handoff.await() }
        assertEquals("upload@piko.dev", event.account)
        assertEquals(1, event.count)
        assertEquals(setOf("upload@piko.dev"), f.uploads.tasks.value.values.map { it.account }.toSet())
        assertEquals(listOf("old-folder"), f.server.children(f.target.id).map { it.name })
        assertTrue(other.children("").isEmpty())
    }

    @Test
    fun `cancellation after a directory request does not report that the remote directory was cancelled`() = smoke { scope ->
        val uploadScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val f = Rig(uploadScope, treeSources())
        f.ready()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "local-folder") {
                entered.send(Unit); withContext(NonCancellable) { release.receive() }
            }
            response
        }
        f.uploads.enqueue(UploadSelection(folders = listOf("root")), f.target.id, f.target.name)
        try {
            withTimeout(5_000) { entered.receive() }
            uploadScope.cancel()
            release.send(Unit)
            f.finished()
            val work = f.uploads.preparations.value.single()
            assertEquals(UploadPreparationCompletion.STOPPED, work.result?.completion)
            assertTrue(work.hasUnconfirmedDirectory)
            assertEquals(1, f.server.children(f.target.id).size)
            assertTrue(f.uploads.tasks.value.isEmpty())
        } finally { release.trySend(Unit); uploadScope.cancel() }
    }

    @Test
    fun `an empty directory id is unknown and never redirects prepared files to the account root`() = smoke { scope ->
        val f = Rig(scope, treeSources())
        f.ready()
        f.server.emptyCreatedFolderId = true
        f.uploads.enqueue(UploadSelection(folders = listOf("root")), f.target.id, f.target.name)
        f.finished()
        val work = f.uploads.preparations.value.single()
        assertEquals(UploadPreparationCompletion.UNCONFIRMED, work.result?.completion)
        assertTrue(work.hasUnconfirmedDirectory)
        assertTrue(work.directories.isEmpty())
        assertTrue(f.uploads.tasks.value.isEmpty())
        assertEquals(1, f.server.children(f.target.id).size)
        assertTrue(f.server.children("").all { it.isFolder })
    }

    @Test
    fun `one completed upload cannot release a URI held by another accepted preparation`() = smoke { scope ->
        val nextDescribe = AtomicBoolean(false)
        val readable = AtomicBoolean(true)
        val released = AtomicInteger(0)
        val enteredSource = Channel<Unit>(1)
        val resumeSource = Channel<Unit>(1)
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String): UploadSourceInfo? {
                if (uri == "marker") return UploadSourceInfo("marker.bin", 0, 1)
                if (nextDescribe.compareAndSet(true, false)) runBlocking { enteredSource.send(Unit); resumeSource.receive() }
                return if (readable.get()) UploadSourceInfo("file.bin", 0, 1) else null
            }
            override fun release(uri: String) { if (uri == "content://source/same") { released.incrementAndGet(); readable.set(false) } }
        })
        f.ready()
        val uploading = Channel<Unit>(1)
        val resumeUpload = Channel<Unit>(1)
        val first = AtomicBoolean(true)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "file.bin" && first.compareAndSet(true, false)) {
                uploading.send(Unit); resumeUpload.receive()
            }
            execute(request)
        }
        val selection = UploadSelection(files = listOf("content://source/same"))
        try {
            f.uploads.enqueue(selection, f.target.id, f.target.name)
            withTimeout(5_000) { uploading.receive() }
            val other = f.server.addFolder("other")
            nextDescribe.set(true)
            f.uploads.enqueue(selection, other.id, other.name)
            withTimeout(5_000) { enteredSource.receive() }
            f.uploads.enqueue(UploadSelection(files = listOf("marker")), f.target.id, f.target.name)
            resumeUpload.send(Unit)
            awaitUntil("后继任务完成，证明第一份上传已完成来源释放检查") { f.uploads.tasks.value.values.any { it.sourceUri == "marker" && it.status == UploadStatus.COMPLETED } }
            assertEquals(0, released.get(), "已接受但还没有文件任务的准备也拥有读取授权")
            resumeSource.send(Unit)
            awaitUntil("第二份准备和上传仍可继续") { f.uploads.tasks.value.size == 3 && f.uploads.tasks.value.values.all { it.status == UploadStatus.COMPLETED } }
            awaitUntil("全部使用者结束后交还授权") { released.get() > 0 }
        } finally { resumeSource.trySend(Unit); resumeUpload.trySend(Unit) }
    }

    @Test
    fun `a folder tree grant remains held through preparation and every queued child task`() = smoke { scope ->
        val released = AtomicInteger(0)
        val readable = AtomicBoolean(true)
        val sourceEntered = Channel<Unit>(1)
        val sourceResume = Channel<Unit>(1)
        val blockSecond = AtomicBoolean(true)
        val f = Rig(scope, object : Sources() {
            override fun permissionKey(uri: String) = if (uri == "marker" || uri == "marker-grant") "marker-grant" else "tree-grant"
            override fun describe(uri: String): UploadSourceInfo? {
                if (uri == "marker") return UploadSourceInfo("marker.bin", 0, 1)
                if (uri == "second" && blockSecond.compareAndSet(true, false)) runBlocking { sourceEntered.send(Unit); sourceResume.receive() }
                return if (readable.get()) UploadSourceInfo("$uri.bin", 0, 1) else null
            }
            override fun listFolder(uri: String) = UploadFolder("tree", listOf(UploadTreeEntry("first", ""), UploadTreeEntry("second", "")))
            override fun release(uri: String) { if (uri == "tree-grant") { readable.set(false); released.incrementAndGet() } }
        })
        f.ready()
        val secondUploading = Channel<Unit>(1)
        val secondResume = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "second.bin") {
                secondUploading.send(Unit); secondResume.receive()
            }
            execute(request)
        }
        try {
            f.uploads.enqueue(UploadSelection(folders = listOf("root-tree")), f.target.id, f.target.name)
            withTimeout(5_000) { sourceEntered.receive() }
            f.uploads.enqueue(UploadSelection(files = listOf("marker")), f.target.id, f.target.name)
            awaitUntil("后继任务完成，证明首个子文件已检查授权释放") { f.uploads.tasks.value.values.any { it.sourceUri == "marker" && it.status == UploadStatus.COMPLETED } }
            assertEquals(0, released.get())
            sourceResume.send(Unit)
            withTimeout(5_000) { secondUploading.receive() }
            awaitUntil("准备已经把引用交给子文件任务") { f.uploads.preparations.value.isEmpty() }
            assertEquals(0, released.get(), "不能在准备结束时释放仍有子文件使用的树授权")
            secondResume.send(Unit)
            awaitUntil("所有子文件完成后释放树授权") { f.uploads.tasks.value.values.all { it.status == UploadStatus.COMPLETED } && released.get() > 0 }
        } finally { sourceResume.trySend(Unit); secondResume.trySend(Unit) }
    }

    @Test
    fun `pending upload confirmation keeps its source grant until it is cancelled`() = smoke { scope ->
        val released = AtomicInteger(0)
        val f = Rig(scope, object : Sources() {
            override fun describe(uri: String) = UploadSourceInfo(if (uri == "marker") "marker.bin" else "file.bin", 0, 1)
            override fun release(uri: String) { if (uri == "content://source/same") released.incrementAndGet() }
        })
        f.ready()
        val selection = UploadSelection(files = listOf("content://source/same"))
        f.uploads.request(selection)
        f.uploads.enqueue(selection, f.target.id, f.target.name)
        f.uploads.enqueue(UploadSelection(files = listOf("marker")), f.target.id, f.target.name)
        awaitUntil("后继任务完成，确认原文件已检查释放") { f.uploads.tasks.value.values.any { it.sourceUri == "marker" && it.status == UploadStatus.COMPLETED } }
        assertEquals(0, released.get())
        f.uploads.clearRequest()
        awaitUntil("明确取消确认请求后释放") { released.get() > 0 }
    }

    @Test
    fun `an accepted file task keeps duplicate preparation blocked after its preparation row is gone`() = smoke { scope ->
        val f = Rig(scope, Sources())
        f.ready()
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        f.http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && requestedName(request.body) == "file.bin") {
                entered.send(Unit); release.receive()
            }
            execute(request)
        }
        try {
            val selection = UploadSelection(files = listOf("source"))
            f.uploads.enqueue(selection, f.target.id, f.target.name)
            withTimeout(5_000) { entered.receive() }
            awaitUntil("准备已交给文件任务") { f.uploads.preparations.value.isEmpty() }
            repeat(3) { assertIs<UploadEnqueueResult.Rejected>(f.uploads.enqueue(selection, f.target.id, f.target.name)) }
            assertEquals(1, f.uploads.tasks.value.size)
        } finally { release.trySend(Unit) }
    }

    @Test
    fun `restoring persistent tasks precedes accepting an overlapping upload and preserves the pending choice`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val account = assertNotNull(provider.currentClient.value).account
        val backing = MemoryPreferences()
        val old = UploadTask("old", account, "source", "file.bin", 0, 1, "", "root", status = UploadStatus.PAUSED)
        backing.saveUploadTasks(Json.encodeToString(ListSerializer(UploadTask.serializer()), listOf(old)))
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val preferences = object : PikoUserPreferences by backing {
            override suspend fun loadUploadTasks(): String { entered.send(Unit); release.receive(); return backing.loadUploadTasks() }
        }
        val uploads = PikoUploadCoordinator(provider, preferences, Sources(), PikoDriveRepository(provider, preferences), scope)
        try {
            withTimeout(5_000) { entered.receive() }
            val selection = UploadSelection(files = listOf("source"))
            uploads.request(selection)
            assertIs<UploadEnqueueResult.Rejected>(uploads.enqueue(selection, "", "root"))
            assertEquals(selection, uploads.pendingRequest.value)
            assertTrue(uploads.preparations.value.isEmpty())
            release.send(Unit)
            awaitUntil("读取已发布任务格式") { !uploads.isRestoring.value && uploads.tasks.value.containsKey(old.taskId) }
            assertIs<UploadEnqueueResult.Rejected>(uploads.enqueue(selection, "", "root"))
            assertEquals(setOf("old"), uploads.tasks.value.keys)
        } finally { release.trySend(Unit) }
    }
}
