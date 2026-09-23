package dev.piko.shared.smoke

import dev.piko.data.auth.InstantTarget
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.shared.state.InstantSheetState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 秒传工作台从解析到落盘的完整流程。两端都只剩布局，这里坏了两端一起坏。
 */
class InstantFlowSmokeTest {
    private val magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
    private val season = listOf(
        Triple("E01.mkv", 700L shl 20, "GCID01"),
        Triple("E02.mkv", 700L shl 20, "GCID02"),
        // 体积过了十分之一的门槛，只能靠次要目录名把它排除
        Triple("sample/sample.mkv", 300L shl 20, "GCID03"),
        Triple("info.nfo", 2048L, ""),
    )

    private fun sheet(server: FakePikPakServer, prefs: MemoryPreferences, scope: CoroutineScope): InstantSheetState {
        val provider = server.provider()
        return InstantSheetState(InstantMagnetRepository(provider), PikoDriveRepository(provider, prefs), prefs, scope, magnet)
    }

    /**
     * 防的是三件事同时出错也看不出来：记住的目标进了回收站仍被当成可用（getFileDetail 对回收站
     * 条目照样成功）；启发式把 sample 与无 gcid 的附属文件默认勾上；多文件不建目录直接平铺。
     */
    @Test
    fun `multi-file instant save falls back from a trashed target and lands in its own folder`() = smoke { scope ->
        val server = FakePikPakServer()
        val trashed = server.addFolder("旧目标", trashed = true)
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val prefs = MemoryPreferences(InstantTarget(trashed.id, trashed.name))
        val state = sheet(server, prefs, scope)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
        val target = state.target!!
        assertEquals("My Packs", target.name)
        assertEquals("", server.node(target.id)?.parentId, "回退目录应建在根目录")
        assertNotNull(state.targetNotice, "目标被替换时要告诉用户")
        assertEquals(setOf("E01.mkv", "E02.mkv"), state.selectedItems.map { it.file.name }.toSet())
        assertTrue(state.willCreateFolder)

        state.saveSelection()
        val saved = assertIs<InstantSaveOutcome.InstantSaved>(outcome.await())

        val folder = assertNotNull(server.node(saved.target.id))
        assertEquals("Show S01", folder.name)
        assertEquals(target.id, folder.parentId)
        assertEquals(setOf("GCID01", "GCID02"), server.children(folder.id).map { it.hash }.toSet())
        assertEquals(emptyList(), server.tasksSnapshot(), "全部可秒传时不该再建离线任务")
    }

    /**
     * 防的是重复文件：勾选里只要有一项云端没收录，就必须整条链交给离线，
     * 一个文件都不能先秒传，否则离线下完同一目录里会出现两份。
     */
    @Test
    fun `a selection with an unindexed file goes offline as one magnet and instant-saves nothing`() = smoke { scope ->
        val server = FakePikPakServer()
        val remembered = server.addFolder("电影")
        server.indexMagnet(magnet, resourceListBody("Show S01", season))
        val prefs = MemoryPreferences(InstantTarget(remembered.id, remembered.name))
        val state = sheet(server, prefs, scope)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }

        awaitUntil("解析完成且保存目标确定") { state.resolution != null && state.target != null }
        assertEquals(remembered.id, state.target?.id, "仍然存在的目标不该被替换")
        assertNull(state.targetNotice)

        state.toggleSelectAll()
        state.saveSelection()
        val offline = assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())

        assertEquals(remembered.id, offline.target.id)
        val task = server.tasksSnapshot().single()
        assertEquals(magnet, task.url)
        assertEquals(remembered.id, task.parentId)
        assertEquals(emptyList(), server.children(remembered.id), "离线分支不能先秒传任何文件")
    }

    /** 防的是外部分享进来的链接云端没收录时面板卡死：输入框收起、没有可点的出口。 */
    @Test
    fun `an unindexed magnet reopens the input and can still be submitted offline`() = smoke { scope ->
        val server = FakePikPakServer()
        val prefs = MemoryPreferences()
        val state = sheet(server, prefs, scope)
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) { state.outcomes.first() }
        assertTrue(!state.isInputVisible, "外部唤起时输入框先收起")

        awaitUntil("解析结束并给出说明") { !state.isResolving && state.errorMessage != null && state.target != null }
        assertTrue(state.isInputVisible)
        assertNull(state.resolution)

        state.submitOfflineTask()
        assertIs<InstantSaveOutcome.OfflineTaskCreated>(outcome.await())
        assertEquals(magnet, server.tasksSnapshot().single().url)
    }
}
