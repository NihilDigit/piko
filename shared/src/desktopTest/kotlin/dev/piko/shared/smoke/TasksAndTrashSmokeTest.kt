package dev.piko.shared.smoke

import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.OfflineTasksState
import dev.piko.shared.data.DriveLibrary
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
     * 回收站是网盘页里的一个位置。防的是两件事：恢复后回收站列表不更新，以及回到网盘时停在恢复之前的缓存上。
     */
    @Test
    fun `restoring from trash shows the file when going back to the drive`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("a.mkv", trashed = true)
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repository, prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading }
        assertEquals(emptyList(), drive.files.map { it.name })

        repository.updateFolderStack(listOf(DriveLibrary.TRASH.crumb))
        awaitUntil("回收站加载完成") { drive.files.any { it.id == file.id } }
        drive.enterSelection(file.id)
        drive.restoreFromTrash(drive.selectedFileIds.toList())
        awaitUntil("回收站移除该文件") { drive.files.isEmpty() && !drive.isTrashActionRunning }
        assertEquals(false, drive.isSelectionMode)
        assertNull(drive.loadError)

        // 根目录的缓存是恢复之前列的，回去时先显示它，后台重列后才有这个文件
        drive.goBack()
        awaitUntil("网盘列表出现恢复的文件") { drive.files.any { it.id == file.id } }
    }

    /**
     * 防的是秒传进眼前这个目录后文件不出现、高亮落空：目录栈没变就不会重列，而列目录接口比写入晚
     * 一会儿才看得到新文件，只列一次多半扑空。
     */
    @Test
    fun `a highlighted file written a moment ago shows up in the folder on screen`() = smoke { scope ->
        val server = FakePikPakServer()
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repository, prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading }

        server.listingLagMs = 500
        val saved = server.addFile("E01.mkv", hash = "GCID01")
        drive.highlight(setOf(saved.id))
        // 测试里没有界面线程替状态类发快照通知，snapshotFlow 要靠这一下才看得到高亮的变化
        Snapshot.sendApplyNotifications()

        awaitUntil("新文件出现在列表里", timeoutMs = 5_000) { drive.files.any { it.id == saved.id } }
    }
}
