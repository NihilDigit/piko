package dev.piko.shared.smoke

import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.InstantSaveIncomplete
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.SaveTargetContext
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RateLimiter
import io.github.nihildigit.pikpak.ResolvedFile
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

class InstantSaveReceiptSmokeTest {
    private fun provider(server: FakePikPakServer, http: HttpClient) = server.provider(PikPakClient(
        "instant@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
        rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
    private fun name(body: Any): String = Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        .jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
    private fun item(path: String, gcid: String = path) = InstantFileItem(ResolvedFile(path, 100, gcid), true)

    @Test
    fun `entering a confirmed container or nested directory cannot duplicate its unknown files`() = smoke { scope ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        val provider = provider(server, http)
        val records = InstantSaveRecords(provider, null, scope)
        val parent = server.addFolder("parent")
        val container = server.addFolder("Show", parent.id)
        val selected = listOf(item("one.mkv"), item("sub/two.mkv"))
        val id = assertNotNull(records.beginAttempt("instant@piko.dev", "Show", PikoPathBreadcrumb(parent.id, parent.name),
            selected.map { it.file }, containerName = "Show", keepStructure = true))
        records.observeContainer(id, "instant@piko.dev", container.id)
        assertTrue(records.pendingConflict("instant@piko.dev", container.id, listOf(selected.first().file)))
        assertFalse(records.pendingConflict("instant@piko.dev", container.id, listOf(item("other.mkv").file)))
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body).endsWith(".mkv"))
                throw IOException("file acknowledgement lost")
            response
        }
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider)
            .instantSave(selected, container.id, keepStructure = true).exceptionOrNull()).receipt
        records.finishAttempt(id, receipt)
        assertEquals(2, server.instantCreates.get())
        assertTrue(records.pendingConflict("instant@piko.dev", container.id, listOf(selected.first().file)))
        assertTrue(records.pendingConflict("instant@piko.dev", container.id, listOf(selected.last().file), keepStructure = true))
        assertTrue(records.pendingConflict("instant@piko.dev", receipt.folders.getValue("sub"), listOf(selected.last().file)))
        assertFalse(records.pendingConflict("instant@piko.dev", parent.id, listOf(selected.first().file)))
        assertFalse(records.pendingConflict("instant@piko.dev", container.id, listOf(item("other.mkv").file)))
        assertTrue(records.forgetAttempt(id))
        assertFalse(records.pendingConflict("instant@piko.dev", container.id, listOf(selected.first().file)))
        assertEquals(setOf("one.mkv", "sub/two.mkv"), server.tree(container.id).toSet())
    }

    @Test
    fun `one definite refusal does not cancel successful sibling saves or discard their ids`() = smoke { _ ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "bad.mkv")
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val files = listOf(item("one.mkv"), item("bad.mkv"), item("three.mkv"))
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider(server, http)).instantSave(files).exceptionOrNull()).receipt
        assertEquals(setOf("one.mkv", "three.mkv"), receipt.confirmed.keys)
        assertEquals(setOf("bad.mkv"), receipt.failed)
        assertTrue(receipt.unconfirmed.isEmpty())
        assertEquals(receipt.confirmed.values.toSet(), server.children("").map { it.id }.toSet())
        assertEquals(2, server.instantCreates.get())
    }

    @Test
    fun `an incomplete save publishes its outcome without losing the queryable receipt`() = smoke { scope ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "unknown.mkv")
                throw IOException("acknowledgement lost")
            response
        }
        val provider = provider(server, http)
        val records = InstantSaveRecords(provider, null, scope)
        val files = listOf(item("one.mkv"), item("unknown.mkv"))
        val id = assertNotNull(records.beginAttempt("instant@piko.dev", "test save", PikoDriveRepository.ROOT_BREADCRUMB, files.map { it.file }))
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { records.outcomes.first() }
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider).instantSave(files).exceptionOrNull()).receipt
        records.finishAttempt(id, receipt)
        assertEquals(id, outcome.await().id)
        assertEquals(receipt.message, outcome.await().message)
        assertEquals(receipt, records.attempts.value.single().receipt)
        assertTrue(records.attempts.value.single().finishedAtMs != null)
        assertEquals(2, server.children("").size)
    }

    @Test
    fun `a lost successful create response stays uncertain while confirmed siblings keep their ids`() = smoke { _ ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "unknown.mkv")
                throw IOException("acknowledgement lost")
            response
        }
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider(server, http))
            .instantSave(listOf(item("one.mkv"), item("unknown.mkv"))).exceptionOrNull()).receipt
        assertEquals(setOf("one.mkv"), receipt.confirmed.keys)
        assertEquals(setOf("unknown.mkv"), receipt.unconfirmed)
        assertTrue(receipt.failed.isEmpty())
        assertEquals(2, server.children("").size, "未知回执不能解释为服务端没有创建")
    }

    @Test
    fun `a lost directory response records the unknown directory and leaves every file unsubmitted`() = smoke { _ ->
        val server = FakePikPakServer()
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "sub")
                throw IOException("directory acknowledgement lost")
            response
        }
        val files = listOf(item("sub/one.mkv"), item("sub/two.mkv"))
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider(server, http)).instantSave(files, keepStructure = true).exceptionOrNull()).receipt
        assertEquals("sub", receipt.unconfirmedFolder)
        assertEquals(files.map { it.file.path }.toSet(), receipt.notAttempted)
        assertTrue(receipt.confirmed.isEmpty())
        assertEquals(0, server.instantCreates.get())
        assertTrue(server.children("").single().isFolder)
    }

    @Test
    fun `a moved preview remains confirmed when its rename fails and the same preview is consumed only once`() = smoke { _ ->
        val server = FakePikPakServer()
        val temp = server.addFolder("temp")
        val target = server.addFolder("target")
        val original = server.addFile("one.mkv", temp.id, hash = "SAME")
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.method == HttpMethod.Patch) throw IOException("rename unavailable")
            execute(request)
        }
        val receipt = assertIs<InstantSaveIncomplete>(InstantMagnetRepository(provider(server, http)).instantSave(
            listOf(item("one.mkv", "SAME"), item("two.mkv", "SAME")), target.id, reuse = mapOf("SAME" to original.id),
            names = mapOf("one.mkv" to "renamed.mkv")).exceptionOrNull()).receipt
        assertEquals(setOf("one.mkv", "two.mkv"), receipt.confirmed.keys)
        assertEquals(setOf("one.mkv"), receipt.namingUnconfirmed)
        assertEquals(2, receipt.confirmed.values.toSet().size)
        assertEquals(target.id, original.parentId)
        assertEquals(1, server.instantCreates.get(), "一个预览副本不能同时代表两个保存条目")
    }

    private class Rig(val server: FakePikPakServer, val http: HttpClient, val scope: CoroutineScope) {
        val provider = providerFor(server, http)
        val prefs = MemoryPreferences().apply { (bundleSubtitlesFlow as MutableStateFlow<Boolean>).value = true }
        val drive = PikoDriveRepository(provider, prefs)
        val instant = InstantMagnetRepository(provider)
        val records = InstantSaveRecords(provider, null, scope)
        val packs = OfflinePackTracker(instant, drive, prefs)
        val previews = PreviewTempFolder(provider, drive, instant, scope)
        fun state(magnet: String, target: PikoPathBreadcrumb) = InstantSheetState(instant, drive, prefs, previews, packs, records, scope,
            magnet, SaveTargetContext.fixed(target))
        companion object {
            private fun providerFor(server: FakePikPakServer, http: HttpClient) = server.provider(PikPakClient(
                "instant@piko.dev", "pw", sessionStore = InMemorySessionStore(), httpClient = http,
                rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0)))
        }
    }

    private val magnet = "magnet:?xt=urn:btih:" + "f".repeat(40)

    @Test
    fun `batch retry checks only the remaining subtitle bytes instead of the already saved video`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        val secondMagnet = "magnet:?xt=urn:btih:" + "e".repeat(40)
        server.indexMagnet(magnet, resourceListBody("Episode", listOf(Triple("Episode.mkv", 100L, "VIDEO"), Triple("Episode.zh.srt", 10L, "SUB"))))
        server.indexMagnet(secondMagnet, resourceListBody("Other", listOf(Triple("Other.mkv", 100L, "OTHER"))))
        var refuse = true
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (refuse && request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body).endsWith(".srt"))
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val rig = Rig(server, http, scope)
        val state = rig.state("$magnet\n$secondMagnet", PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("批量两条链接均可保存") { state.batch?.canSaveAll == true }
        val batch = assertNotNull(state.batch)
        batch.saveAll()
        awaitUntil("成功行移除，失败字幕可以单独继续") { !batch.isSaving && batch.rows.size == 1 && batch.rows.single().state.retryFileCount == 1 }
        assertEquals(10L, batch.neededBytes)
        assertEquals(0, batch.offlineCount)
        server.quotaUsage = server.quotaLimit - 10
        refuse = false
        val completed = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        batch.saveAll()
        awaitUntil("批量仅重试字幕后结束执行") { !batch.isSaving }
        assertTrue(batch.saveErrors.isEmpty(), "实际成功的行不能被晚到的呈现状态记成失败：${batch.saveErrors}")
        assertIs<InstantSaveOutcome.InstantSaved>(completed.await())
        assertEquals(3, server.instantCreates.get())
        assertEquals(2, rig.records.records.value.size)
        assertTrue(rig.records.attempts.value.isEmpty())
    }

    @Test
    fun `a lost container response blocks every save entry and refreshes the actual directory facts`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        server.quotaUsage = server.quotaLimit - 200
        server.indexMagnet(magnet, resourceListBody("Show", listOf(Triple("E01.mkv", 100L, "ONE"), Triple("E02.mkv", 100L, "TWO"), Triple("info.nfo", 50L, ""))))
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "Show")
                throw IOException("container acknowledgement lost")
            response
        }
        val rig = Rig(server, http, scope)
        val page = dev.piko.shared.state.DriveScreenState(rig.drive, rig.prefs, scope)
        page.restoreFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(target.id, target.name)))
        page.load()
        awaitUntil("目标文件夹缓存为空") { !page.isLoading && page.loadedFolderId == target.id && page.files.isEmpty() }
        val state = rig.state(magnet, PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("解析及余量就绪") { state.resolution != null && state.target != null && state.remainingBytes != null }
        state.items.indices.forEach { state.setItemSelected(it, true) }
        state.saveSelectionInstantly()
        awaitUntil("目录响应丢失后保留未执行文件，其他浏览页看到实际创建") {
            !state.isSaving && state.saveIssue?.unconfirmedFolder == "Show" && page.files.any { it.name == "Show" }
        }
        repeat(4) { state.saveSelection(); state.saveSelectionInstantly(); state.submitOfflineTask() }
        assertEquals(1, server.children(target.id).size)
        assertEquals(0, server.instantCreates.get())
        assertEquals(0, server.tasksSnapshot().size)
        assertEquals(1, rig.records.attempts.value.size)
        val otherMagnet = "magnet:?xt=urn:btih:" + "d".repeat(40)
        server.indexMagnet(otherMagnet, resourceListBody("Show", listOf(Triple("Other01.mkv", 100L, "OTHER1"), Triple("Other02.mkv", 100L, "OTHER2"), Triple("info.nfo", 50L, ""))))
        val other = rig.state(otherMagnet, PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("另一份内容解析完成") { other.resolution != null && other.target != null }
        other.items.indices.forEach { other.setItemSelected(it, true) }
        awaitUntil("内容不同但未知容器同名也被阻止") { other.saveBlockReason != null }
        other.saveSelectionInstantly()
        assertEquals(1, server.children(target.id).size)
        assertEquals(0, server.instantCreates.get())
    }

    @Test
    fun `saving the same content under a different output name is independent of an unknown save`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        val otherMagnet = "magnet:?xt=urn:btih:" + "e".repeat(40)
        server.indexMagnet(magnet, resourceListBody("First", listOf(Triple("first.mkv", 100L, "SAME"))))
        server.indexMagnet(otherMagnet, resourceListBody("Second", listOf(Triple("second.mkv", 100L, "SAME"))))
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "first.mkv")
                throw IOException("first acknowledgement lost")
            response
        }
        val rig = Rig(server, http, scope)
        val destination = PikoPathBreadcrumb(target.id, target.name)
        val first = rig.state(magnet, destination)
        awaitUntil("第一个草稿就绪") { first.resolution != null && first.target != null }
        first.saveSelection()
        awaitUntil("第一个结果待确认") { !first.isSaving && first.saveIssue?.requiresInspection == true }
        val second = rig.state(otherMagnet, destination)
        awaitUntil("不同输出名的草稿仍可提交") { second.resolution != null && second.primaryAction?.enabled == true }
        val result = scope.async(start = CoroutineStart.UNDISPATCHED) { second.outcomes.first() }
        second.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(result.await())
        assertEquals(setOf("first.mkv", "second.mkv"), server.children(target.id).map { it.name }.toSet())
        assertEquals(1, rig.records.attempts.value.size)
        assertEquals(1, rig.records.records.value.size)
    }

    @Test
    fun `batch success does not end or discard another row whose save is still uncertain`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        val secondMagnet = "magnet:?xt=urn:btih:" + "e".repeat(40)
        server.indexMagnet(magnet, resourceListBody("Episode", listOf(Triple("Episode.mkv", 100L, "VIDEO"), Triple("Episode.zh.srt", 10L, "SUB"))))
        server.indexMagnet(secondMagnet, resourceListBody("Other", listOf(Triple("Other.mkv", 100L, "OTHER"))))
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body).endsWith(".srt"))
                throw IOException("subtitle acknowledgement lost")
            response
        }
        val rig = Rig(server, http, scope)
        val state = rig.state("$magnet\n$secondMagnet", PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("批量两条链接均可保存") { state.batch?.canSaveAll == true }
        val batch = assertNotNull(state.batch)
        batch.saveAll()
        awaitUntil("成功行移除，不确定行保留") { !batch.isSaving && batch.rows.size == 1 && batch.saveErrors.isNotEmpty() }
        assertEquals(dev.piko.shared.state.InstantBatchRowStatus.NEEDS_REVIEW, batch.rows.single().status)
        assertTrue(!batch.canSaveAll)
        assertEquals(1, rig.records.attempts.value.size)
        assertEquals(1, rig.records.records.value.size)
        assertEquals(3, server.instantCreates.get())
        batch.saveAll()
        assertEquals(3, server.instantCreates.get())
    }

    @Test
    fun `retry reuses the accepted container and nested directories instead of rebuilding a partial tree`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        server.quotaUsage = server.quotaLimit - 200
        server.indexMagnet(magnet, resourceListBody("Show", listOf(Triple("E01.mkv", 100L, "ONE"), Triple("sub/E02.mkv", 100L, "TWO"), Triple("info.nfo", 50L, ""))))
        var refuse = true
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (refuse && request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body) == "E02.mkv")
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val rig = Rig(server, http, scope)
        val state = rig.state(magnet, PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("解析及余量就绪") { state.resolution != null && state.target != null && state.remainingBytes != null }
        state.items.indices.forEach { state.setItemSelected(it, true) }
        assertNotNull(state.savePlan?.fallback)
        state.saveSelectionInstantly()
        awaitUntil("部分目录树已有结果") { !state.isSaving && state.saveIssue != null }
        val receipt = state.saveIssue!!
        val container = receipt.folders.getValue("")
        val sub = receipt.folders.getValue("sub")
        val firstId = receipt.confirmed.getValue("E01.mkv")
        refuse = false
        val result = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(result.await())
        assertEquals(listOf(container), server.children(target.id).map { it.id })
        assertEquals(listOf(sub), server.children(container).filter { it.isFolder }.map { it.id })
        assertEquals(setOf("E01.mkv", "sub/E02.mkv"), server.tree(container).toSet())
        assertEquals(firstId, server.node(firstId)?.id)
        assertEquals(2, server.instantCreates.get())
        assertEquals(1, rig.records.records.value.size)
    }

    @Test
    fun `retry saves only a definitely failed subtitle and preserves the original task identity`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        server.indexMagnet(magnet, resourceListBody("Episode", listOf(Triple("Episode.mkv", 100L, "VIDEO"), Triple("Episode.zh.srt", 10L, "SUB"))))
        var refuse = true
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (refuse && request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body).endsWith(".srt"))
                throw PikPakException(7, "permission_denied", httpStatus = 403)
            execute(request)
        }
        val rig = Rig(server, http, scope)
        val state = rig.state(magnet, PikoPathBreadcrumb(target.id, target.name))
        val page = dev.piko.shared.state.DriveScreenState(rig.drive, rig.prefs, scope)
        page.restoreFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(target.id, target.name)))
        page.load()
        awaitUntil("另一份浏览上下文已载入目标") { !page.isLoading && page.loadedFolderId == target.id }
        awaitUntil("磁力及配套字幕就绪") { state.resolution != null && state.target != null && state.selectedItems.size == 2 }
        state.saveSelection()
        awaitUntil("保留部分保存及具体文件结果") { !state.isSaving && state.saveIssue != null }
        val receipt = assertNotNull(state.saveIssue)
        val id = rig.records.attempts.value.single().id
        assertEquals(setOf("Episode.mkv"), receipt.confirmed.keys)
        assertEquals(setOf("Episode.zh.srt"), receipt.failed)
        assertEquals(1, state.retryFileCount)
        val videoId = receipt.confirmed.getValue("Episode.mkv")
        awaitUntil("部分保存也使其他浏览上下文重新读取实际文件") { page.files.any { it.id == videoId } }
        assertTrue(!state.canEdit)
        refuse = false
        val result = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        state.saveSelection()
        assertIs<InstantSaveOutcome.InstantSaved>(result.await())
        assertEquals(2, server.instantCreates.get())
        assertEquals(videoId, server.children(target.id).single { it.name.endsWith(".mkv") }.id)
        assertEquals(2, server.children(target.id).size)
        assertTrue(rig.records.attempts.value.isEmpty())
        assertEquals(id, rig.records.records.value.single().id)
        assertEquals(2, rig.records.records.value.single().fileCount)
    }

    @Test
    fun `an uncertain saved subtitle cannot be resubmitted by repeated save or a second draft`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("target")
        server.indexMagnet(magnet, resourceListBody("Episode", listOf(Triple("Episode.mkv", 100L, "VIDEO"), Triple("Episode.zh.srt", 10L, "SUB"))))
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            val response = execute(request)
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/drive/v1/files") && name(request.body).endsWith(".srt"))
                throw IOException("subtitle acknowledgement lost")
            response
        }
        val rig = Rig(server, http, scope)
        val destination = PikoPathBreadcrumb(target.id, target.name)
        val state = rig.state(magnet, destination)
        awaitUntil("磁力及配套字幕就绪") { state.resolution != null && state.target != null && state.selectedItems.size == 2 }
        state.saveSelection()
        awaitUntil("保存回执待确认") { !state.isSaving && state.saveIssue?.unconfirmed?.isNotEmpty() == true }
        repeat(4) { state.saveSelection(); state.saveSelectionInstantly() }
        assertEquals(0, state.retryFileCount)
        assertEquals(false, state.primaryAction?.enabled)
        val other = rig.state(magnet, destination)
        awaitUntil("第二个草稿解析完成") { other.resolution != null && other.target != null && other.selectedItems.size == 2 }
        other.saveSelection()
        awaitUntil("跨草稿入口也阻止未知重复保存") { !other.isSaving && other.errorMessage != null }
        assertEquals(2, server.instantCreates.get())
        assertEquals(2, server.children(target.id).size)
        assertEquals(1, rig.records.attempts.value.size)
    }
}
