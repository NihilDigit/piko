package dev.piko.shared.state

import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveRevealTest {
    @Test fun highlightedFolderIsVisibleWhenTheParsedStructureBecomesReady() = smoke {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server = FakePikPakServer()
        val root = server.addFolder("Reveal test")
        repeat(3) { server.addFile("Frieren - 0${it + 1}.mkv", root.id) }
        val target = server.addFolder("Fonts", root.id)
        val prefs = object : PikoUserPreferences by MemoryPreferences() {
            override val heuristicFilterFlow = MutableStateFlow(true)
        }
        val observations = CopyOnWriteArrayList<Boolean>()
        try {
            val state = withContext(dispatcher) {
                val repository = PikoDriveRepository(server.provider(), prefs)
                repository.updateFolderStack(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb(root.id, root.name)))
                DriveScreenState(repository, prefs, scope).also { it.highlight(setOf(target.id)); it.load() }
            }
            scope.launch {
                snapshotFlow {
                    if (state.isDisplayStructureReady && state.files.any { it.id == target.id })
                        state.displayItems.any { it is DriveListItem.File && it.file.id == target.id } else null
                }.collect { it?.let(observations::add) }
            }
            awaitUntil("解析后定位目录可见") { state.isDisplayStructureReady && state.displayItems.any { it is DriveListItem.File && it.file.id == target.id } }
            assertTrue(observations.all { it }, "不能先发布隐藏目标的结构，再在下一帧展开")
        } finally {
            scope.cancel()
            dispatcher.close()
            DriveViewMemory.showAll.remove(root.id)
            DriveViewMemory.expanded.keys.filter { it.startsWith("${root.id}|") }.forEach(DriveViewMemory.expanded::remove)
        }
    }

    @Test fun requestingTheSameTargetAgainHasItsOwnRevision() = smoke { scope ->
        val prefs = MemoryPreferences()
        val state = DriveScreenState(PikoDriveRepository(FakePikPakServer().provider(), prefs), prefs, scope)
        state.highlight(setOf("target"))
        val revision = state.highlightRevision
        state.highlight(setOf("target"))
        assertEquals(revision + 1, state.highlightRevision)
        assertEquals(setOf("target"), state.highlightedFileIds)
    }
}
