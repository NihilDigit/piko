package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.OfflineTasksState
import dev.piko.shared.state.TrashScreenState
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TasksAndTrashSmokeTest {

    /**
     * 防两件事：排队中的任务从列表里消失（SDK 的默认 phaseFilter 不含 PENDING），
     * 以及离开页面后轮询还在后台每几秒发一次请求。
     */
    @Test
    fun `polling lists queued tasks and stops with the caller`() = smoke { scope ->
        val server = FakePikPakServer()
        server.addTask("排队中", TaskPhase.PENDING)
        server.addTask("下载中", TaskPhase.RUNNING)
        server.addTask("已完成", TaskPhase.COMPLETE)
        val state = OfflineTasksState(TaskRepository(server.provider(), PikoDriveRepository(server.provider())), scope)

        val polling = scope.launch { state.pollWhileVisible(intervalMs = 50) }
        awaitUntil("至少轮询三轮") { server.count("GET /drive/v1/tasks") >= 3 }
        assertEquals(setOf("排队中", "下载中"), state.activeTasks.map { it.name }.toSet())
        assertEquals(3, state.tasks.size)

        polling.cancelAndJoin()
        val afterStop = server.count("GET /drive/v1/tasks")
        delay(300)
        assertEquals(afterStop, server.count("GET /drive/v1/tasks"), "停止后不应再有请求")
    }

    /** 防的是断网一轮把列表清空：拉取失败要保留上次的任务，并挂出陈旧标记，恢复后撤掉。 */
    @Test
    fun `a failed poll keeps the last list and marks it stale until the next success`() = smoke { scope ->
        val server = FakePikPakServer()
        server.addTask("下载中", TaskPhase.RUNNING)
        val state = OfflineTasksState(TaskRepository(server.provider(), PikoDriveRepository(server.provider())), scope)
        scope.launch { state.pollWhileVisible(intervalMs = 50) }
        awaitUntil("首次加载完成") { state.tasks.isNotEmpty() }

        server.offline = true
        awaitUntil("失败被记录") { state.loadError != null }
        assertEquals(1, state.tasks.size)

        server.offline = false
        awaitUntil("恢复后陈旧标记撤掉") { state.loadError == null }
    }

    /**
     * 防的是恢复后网盘列表不更新：恢复发生在回收站界面，网盘列表不在前台、不会自己重拉，
     * 只能靠仓库层的 refreshEvents。Desktop 旧实现就漏了这一步。
     */
    @Test
    fun `restoring from trash refreshes the drive listing that is off screen`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("a.mkv", trashed = true)
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repository, prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading }
        assertEquals(emptyList(), drive.files.map { it.name })

        val trash = TrashScreenState(repository, scope)
        trash.load()
        awaitUntil("回收站加载完成") { trash.files.isNotEmpty() }
        trash.enterSelection(file.id)
        trash.restore(trash.selectedFileIds.toList())

        awaitUntil("网盘列表出现恢复的文件") { drive.files.any { it.id == file.id } }
        awaitUntil("回收站移除该文件") { trash.files.isEmpty() }
        assertEquals(false, trash.isSelectionMode)
        assertNull(trash.loadError)
    }
}
