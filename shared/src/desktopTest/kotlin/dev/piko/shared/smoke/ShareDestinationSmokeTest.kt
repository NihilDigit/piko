package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.state.ShareSaveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareDestinationSmokeTest {
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
