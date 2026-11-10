package dev.piko.shared.smoke

import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.presentation.DriveWorkspace
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.OfflineTasksState
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TasksAndTrashSmokeTest {

    @Test
    fun `polling lists queued tasks and stops with the caller`() = smoke { scope ->
        val server = FakePikPakServer()
        server.addTask("排队中", TaskPhase.PENDING)
        server.addTask("下载中", TaskPhase.RUNNING)
        server.addTask("已完成", TaskPhase.COMPLETE)
        val state = OfflineTasksState(TaskRepository(server.provider(), PikoDriveRepository(server.provider())), scope)

        val polling = scope.launch { state.pollWhileVisible(intervalMs = 50) }
        awaitUntil("polled at least three rounds") { server.count("GET /drive/v1/tasks") >= 3 }
        assertEquals(setOf("排队中", "下载中"), state.activeTasks.map { it.name }.toSet())
        assertEquals(3, state.tasks.size)

        polling.cancelAndJoin()
        val afterStop = server.count("GET /drive/v1/tasks")
        delay(150)
        assertEquals(afterStop, server.count("GET /drive/v1/tasks"))
    }

    @Test
    fun `a failed poll keeps the last list and marks it stale until the next success`() = smoke { scope ->
        val server = FakePikPakServer()
        server.addTask("下载中", TaskPhase.RUNNING)
        val state = OfflineTasksState(TaskRepository(server.provider(), PikoDriveRepository(server.provider())), scope)
        scope.launch { state.pollWhileVisible(intervalMs = 50) }
        awaitUntil("initial poll loaded") { state.tasks.isNotEmpty() }

        server.offline = true
        awaitUntil("poll failure recorded") { state.loadError != null }
        assertEquals(1, state.tasks.size)

        server.offline = false
        awaitUntil("stale marker cleared") { state.loadError == null }
    }

    @Test
    fun `restoring from trash shows the file when going back to the drive`() = smoke { scope ->
        val server = FakePikPakServer()
        val file = server.addFile("a.mkv", trashed = true)
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val drive = DriveScreenState(repository, prefs, scope, navigation = workspace)
        drive.load()
        awaitUntil("drive loaded") { !drive.isLoading }
        assertEquals(emptyList(), drive.files.map { it.name })

        workspace.updateFolderStack(listOf(DriveLibrary.TRASH.crumb))
        awaitUntil("trash loaded") { drive.files.any { it.id == file.id } }
        drive.enterSelection(file.id)
        drive.restoreFromTrash(drive.selectedFileIds.toList())
        awaitUntil("file removed from trash") { drive.files.isEmpty() && !file.trashed }
        assertEquals(false, drive.isSelectionMode)
        assertNull(drive.loadError)

        drive.goBack()
        awaitUntil("restored file visible in drive") { drive.files.any { it.id == file.id } }
    }

    @Test
    fun `a highlighted file written a moment ago shows up in the folder on screen`() = smoke { scope ->
        val server = FakePikPakServer()
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val workspace = DriveWorkspace(repository, backgroundScope = scope)
        val drive = DriveScreenState(repository, prefs, scope, navigation = workspace)
        drive.load()
        awaitUntil("drive loaded") { !drive.isLoading }

        val saved = server.addFile("E01.mkv", hash = "GCID01")
        drive.highlight(setOf(saved.id))
        Snapshot.sendApplyNotifications()

        awaitUntil("highlighted file fetched into listing", timeoutMs = 5_000) { drive.files.any { it.id == saved.id } }
    }
}
