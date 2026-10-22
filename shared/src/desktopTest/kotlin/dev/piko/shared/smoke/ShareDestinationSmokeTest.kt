package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareSaveState
import dev.piko.shared.state.InstantSession
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.presentation.AddLinkViewModel
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.RateLimiter
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ShareDestinationSmokeTest {
    @Test
    fun `share browsing and saving survive collapsing and rebinding the add link presentation`() = smoke { scope ->
        val server = FakePikPakServer()
        val owner = server.addFolder("owner")
        val source = server.addFile("episode.mkv", owner.id, hash = "SHARED")
        val target = server.addFolder("target")
        server.shares["S1"] = listOf(source.id)
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val http = server.httpClient()
        http.plugin(HttpSend).intercept { request ->
            if (request.url.encodedPath == "/drive/v1/files" && request.method.value == "POST") {
                entered.send(Unit)
                release.receive()
            }
            execute(request)
        }
        val provider = server.provider(PikPakClient("share@piko.dev", "pw", sessionStore = InMemorySessionStore(),
            rateLimiter = RateLimiter(capacity = 1000, refillPerSecond = 1000.0), httpClient = http))
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val instant = InstantMagnetRepository(provider)
        val packs = OfflinePackTracker(instant, repo, prefs)
        val records = InstantSaveRecords(provider, null, scope)
        val preview = PreviewTempFolder(provider, repo, instant, scope)
        val session = InstantSession({ CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) }) { child, input ->
            InstantSheetState(instant, repo, prefs, preview, packs, records, child, input)
        }
        val model = AddLinkViewModel(session)
        model.open("https://mypikpak.com/s/S1")
        val draft = assertNotNull(model.state)
        awaitUntil("分享状态由业务会话载入统一解析") { !draft.isResolving && draft.resolution != null }
        val resolution = assertNotNull(draft.resolution)
        draft.setSourceSelected(setOf(0), true)
        assertTrue(model.discardNeedsConfirm)
        draft.changeTarget(PikoPathBreadcrumb(target.id, target.name))
        draft.performPrimaryAction()
        withTimeout(5_000) { entered.receive() }
        model.collapse()
        assertTrue(model.hasCollapsedWork)
        assertTrue(draft.isSaving, "顶层替换保护必须看见分享的保存状态")
        model.discard()
        model.open("another link")
        draft.updateInput("another input")
        assertSame(draft, model.state)
        assertEquals("https://mypikpak.com/s/S1", draft.input)
        assertSame(resolution, draft.resolution)
        val rebound = AddLinkViewModel(session)
        assertSame(resolution, rebound.state?.resolution, "界面绑定重建不能重建分享解析状态")
        release.send(Unit)
        awaitUntil("收起状态下分享保存完成并记录回执") { !draft.isSaving && records.records.value.isNotEmpty() }
        assertEquals(listOf(source.name), server.children(target.id).map { it.name })
        model.discard()
        assertTrue(model.state == null)
    }

    @Test fun selectedFolderAndExplicitRootOverrideTheServerDefault() = smoke { scope ->
        val server = FakePikPakServer()
        val owner = server.addFolder("Owner")
        val source = server.addFile("episode.mkv", owner.id, hash = "SHARED")
        val target = server.addFolder("Dramas")
        server.shares["S1"] = listOf(source.id)
        val state = ShareSaveState(PikoDriveRepository(server.provider(), MemoryPreferences()), scope, "S1")
        awaitUntil("分享已打开") { !state.isLoading && state.info != null }
        state.toggle(state.entries.single())
        state.save(PikoPathBreadcrumb(target.id, target.name))
        awaitUntil("转存完成") { !state.isSaving }
        assertEquals(listOf(source.name), server.children(target.id).map { it.name })
        state.toggle(state.entries.single())
        state.save(PikoDriveRepository.ROOT_BREADCRUMB)
        awaitUntil("根目录转存完成") { !state.isSaving }
        assertTrue(server.children("").any { it.name == source.name })
        assertTrue(server.children("").none { it.name == "Pack From Shared" })
    }

    /**
     * 防的是转存时开着规范命名却只改了面板上的预览：转存接口不收名称，要在转存后找出新来的条目改名。
     * 目标目录里原有的条目不能动，与原有名字撞上的不改，改成的能撤销。
     */
    @Test fun canonicalNamesAreAppliedAfterRestore() = smoke { scope ->
        val server = FakePikPakServer()
        val owner = server.addFolder("Owner")
        val video = server.addFile("[xxx.com]abc00123hhb.mp4", owner.id, hash = "V")
        val subtitle = server.addFile("[xxx.com]abc00123hhb.zh.srt", owner.id, hash = "S")
        val clashing = server.addFile("xyz00456.mp4", owner.id, hash = "X")
        val target = server.addFolder("Inbox")
        server.addFile("XYZ-456.mp4", target.id, hash = "OLD")
        server.addFile("abc00999.mp4", target.id, hash = "KEEP")
        server.shares["S1"] = listOf(video.id, subtitle.id, clashing.id)
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        val state = ShareSaveState(repo, scope, "S1")
        awaitUntil("分享已打开") { !state.isLoading && state.info != null }
        assertEquals("ABC-123.mp4", state.canonicalPreview(state.entries.first { it.name.endsWith(".mp4") && it.name.startsWith("[") }))
        state.toggleAll()
        state.save(PikoPathBreadcrumb(target.id, target.name), canonicalNames = true)
        awaitUntil("转存与改名完成") { !state.isSaving }
        assertEquals(
            setOf("ABC-123.mp4", "ABC-123.zh.srt", "xyz00456.mp4", "XYZ-456.mp4", "abc00999.mp4"),
            server.children(target.id).map { it.name }.toSet(),
        )
        repo.changes.undoLast()
        awaitUntil("撤销完成") { server.children(target.id).any { it.name == "[xxx.com]abc00123hhb.mp4" } }
        assertTrue(server.children(target.id).any { it.name == "[xxx.com]abc00123hhb.zh.srt" })
    }
}
