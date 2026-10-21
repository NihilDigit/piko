package dev.piko.shared.smoke

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.MoveHistory
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.TrashSession
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchUxSmokeTest {
    @Test
    fun `paste notice counts only files moved into the target`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("目标")
        val moving = server.addFile("move.txt")
        val existing = server.addFile("existing.txt", parentId = target.id)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val state = DriveScreenState(repo, prefs, scope, navigation = workspace, clipboard = workspace.clipboard)
        val root = listOf(PikoDriveRepository.ROOT_BREADCRUMB)
        workspace.clipboard.put(dev.piko.shared.data.DriveClipboard(
            mapOf(moving.id to "", existing.id to target.id), true, root))
        workspace.updateFolderStack(root + PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("目标目录包含原有文件") { state.loadedFolderId == target.id && !state.isLoading }
        val notice = scope.async(start = CoroutineStart.UNDISPATCHED) { state.pasteNotices.first() }
        state.paste()
        assertEquals("已将 1 项移动到「目标」", withTimeout(5_000) { notice.await() }.message)
        assertEquals(setOf(moving.id, existing.id), server.children(target.id).map { it.id }.toSet())
    }

    @Test
    fun `copy reports rejected commands without accepting a write`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("目标")
        val file = server.addFile("file.txt")
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val state = DriveScreenState(repo, prefs, scope)
        state.load()
        awaitUntil("来源目录加载") { !state.isLoading && state.files.size == 2 }
        assertFalse(state.copy(emptyList(), target.id, target.name))
        assertFalse(state.copy(listOf(target.id), target.id, target.name))
        assertEquals(0, server.count("POST /drive/v1/files:batchCopy"))
        assertTrue(state.copy(listOf(file.id), target.id, target.name))
        awaitUntil("接受的复制完成") { server.children(target.id).size == 1 }
    }

    @Test
    fun `queued history mutations cannot write into a different account`() = smoke { scope ->
        val server = FakePikPakServer()
        val current = MutableStateFlow<PikPakClient?>(server.client(account = "first@piko.dev"))
        val provider = object : dev.piko.shared.data.PikoClientProvider { override val currentClient = current }
        val stored = MutableStateFlow("[]")
        var writes = 0
        val prefs = object : PikoUserPreferences by MemoryPreferences() {
            override val recentMoveTargetsFlow = stored
            override suspend fun saveRecentMoveTargets(serialized: String) { writes++; stored.value = serialized }
        }
        val history = MoveHistory(prefs, PikoDriveRepository(provider, prefs), scope)
        history.remember(listOf(PikoPathBreadcrumb("old", "旧账号目标")))
        history.remove("new")
        history.clear()
        current.value = server.client(account = "second@piko.dev")
        val secondHistory = """[[{"id":"new","name":"新账号目标"}]]"""
        stored.value = secondHistory
        scope.async { Unit }.await()
        assertEquals(0, writes)
        assertEquals(secondHistory, stored.value)
    }

    @Test
    fun `stopped cleanup undo resumes after its confirmed restore batch`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = (1..205).map { server.addFile("$it.txt") }
        val http = server.httpClient()
        val client = PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val repo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val trash = TrashSession(repo, scope)
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("files:batchUntrash")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        try {
            trash.start(files.map { it.id })
            awaitUntil("清理完成") { trash.progress == null && trash.completedCount == 205 }
            trash.undo()
            withTimeout(5_000) { entered.receive() }
            trash.requestStop()
            release.send(Unit)
            awaitUntil("撤销在第一批后停止") { !trash.isUndoing }
            assertEquals(100, files.count { !it.trashed })
            assertTrue(entered.tryReceive().isFailure)
            repeat(2) { release.send(Unit) }
            trash.undo()
            awaitUntil("只恢复剩余项目") { !trash.isUndoing && files.none { it.trashed } }
            assertEquals(3, server.count("POST /drive/v1/files:batchUntrash"))
        } finally { release.close() }
    }

    @Test
    fun `copy stop retains accepted cloud receipts and leaves later chunks unsubmitted`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = (1..105).map { server.addFile("$it.txt") }
        val target = server.addFolder("副本")
        val http = server.httpClient()
        val client = PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val repo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val writes = dev.piko.shared.state.FileWriteSession(repo, scope, 10)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("files:batchCopy")) { entered.send(Unit); release.receive() }
            execute(request)
        }
        try {
            val id = assertNotNull(writes.submit(dev.piko.shared.state.FileWriteCommand.Copy(files.map { it.id }, PikoPathBreadcrumb(target.id, target.name))))
            withTimeout(5_000) { entered.receive() }
            assertTrue(writes.requestStop(id))
            release.send(Unit)
            awaitUntil("停止后保留云端回执") { writes.works.value.single().result != null }
            val work = writes.works.value.single()
            val result = assertNotNull(work.result)
            assertEquals(1, work.copies.size)
            assertEquals(100, result.unconfirmed.size)
            assertEquals(5, result.notAttempted.size)
            assertEquals(1, server.count("POST /drive/v1/files:batchCopy"))
            assertTrue(writes.recheck(id))
            awaitUntil("已提交复制仍能继续查询") { writes.works.value.single().result?.confirmed?.size == 100 }
        } finally { release.trySend(Unit) }
    }

    @Test
    fun `recent targets remove and clear serialize with new destinations`() = smoke { scope ->
        val stored = MutableStateFlow("")
        val prefs = object : PikoUserPreferences by MemoryPreferences() {
            override val recentMoveTargetsFlow = stored
            override suspend fun saveRecentMoveTargets(serialized: String) { stored.value = serialized }
        }
        val server = FakePikPakServer()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val history = MoveHistory(prefs, repo, scope)
        val root = PikoDriveRepository.ROOT_BREADCRUMB
        fun path(id: String) = listOf(root, PikoPathBreadcrumb(id, id))
        history.remember(path("a"))
        history.remember(path("b"))
        withTimeout(5_000) { history.targets.first { it.size == 2 } }
        history.remove("a")
        assertEquals("b", withTimeout(5_000) { history.targets.first { it.size == 1 } }.single().last().id)
        history.clear()
        history.remember(path("c"))
        assertEquals("c", withTimeout(5_000) { history.targets.first { it.singleOrNull()?.last()?.id == "c" } }.single().last().id)
        history.clear()
        withTimeout(5_000) { history.targets.first { it.isEmpty() } }
    }

    @Test
    fun `cut exits selection and successful paste offers its original folder`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("file.txt")
        val target = server.addFolder("目标")
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val state = DriveScreenState(repo, prefs, scope, navigation = workspace, clipboard = workspace.clipboard)
        state.load()
        awaitUntil("来源目录加载") { !state.isLoading && state.files.size == 2 }
        state.enterSelection(file.id)
        state.putOnClipboard(listOf(file.id), cut = true)
        assertFalse(state.isSelectionMode)
        val clip = assertNotNull(state.clipboardFlow.value)
        assertEquals(listOf(PikoDriveRepository.ROOT_BREADCRUMB), clip.originStack)
        workspace.updateFolderStack(clip.originStack + PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("进入目标目录") { state.loadedFolderId == target.id && !state.isLoading }
        val notice = scope.async(start = CoroutineStart.UNDISPATCHED) { state.pasteNotices.first() }
        state.paste()
        assertNull(state.clipboardFlow.value)
        val result = withTimeout(5_000) { notice.await() }
        assertEquals("已将 1 项移动到「目标」", result.message)
        assertEquals(clip.originStack, result.originStack)
        assertEquals(target.id, file.parentId)
    }

    @Test
    fun `copy keeps clipboard until cancelled and preserves the original stack`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("file.txt")
        val target = server.addFolder("副本")
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repo, backgroundScope = scope)
        val state = DriveScreenState(repo, prefs, scope, navigation = workspace, clipboard = workspace.clipboard)
        state.load()
        awaitUntil("来源目录加载") { !state.isLoading && state.files.size == 2 }
        state.enterSelection(file.id)
        state.putOnClipboard(listOf(file.id), cut = false)
        assertFalse(state.isSelectionMode)
        val clip = assertNotNull(state.clipboardFlow.value)
        workspace.updateFolderStack(clip.originStack + PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("进入目标目录") { state.loadedFolderId == target.id && !state.isLoading }
        val notice = scope.async(start = CoroutineStart.UNDISPATCHED) { state.pasteNotices.first() }
        state.paste()
        assertEquals("已将 1 项复制到「副本」", withTimeout(5_000) { notice.await() }.message)
        assertEquals(clip, state.clipboardFlow.value)
        assertEquals("", file.parentId)
        assertEquals(1, server.children(target.id).size)
        state.clearClipboard()
        assertNull(state.clipboardFlow.value)
    }

    @Test
    fun `stopping cleanup finishes current batch and retries only remaining files`() = smoke { scope ->
        val server = FakePikPakServer()
        val files = (1..205).map { server.addFile("$it.txt") }
        val http = server.httpClient()
        val client = PikPakClient("smoke@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0))
        val repo = PikoDriveRepository(server.provider(client), MemoryPreferences())
        val trash = TrashSession(repo, scope)
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath.endsWith("files:batchTrash")) {
                entered.send(Unit)
                release.receive()
            }
            execute(request)
        }
        try {
            trash.start(files.map { it.id })
            withTimeout(5_000) { entered.receive() }
            trash.requestStop()
            assertTrue(trash.stopping)
            release.send(Unit)
            awaitUntil("停止后保留已完成批次") { trash.progress == null }
            assertEquals(100, trash.completedCount)
            assertEquals(100, files.count { it.trashed })
            assertEquals(105, assertNotNull(trash.failure).remainingIds.size)
            assertTrue(entered.tryReceive().isFailure)
            repeat(2) { release.send(Unit) }
            trash.retry()
            awaitUntil("重试剩余批次完成") { trash.progress == null && trash.completedCount == 205 }
            assertTrue(files.all { it.trashed })
            assertEquals(205, assertNotNull(trash.undoChange).ids.size)
        } finally { release.close() }
    }
}
