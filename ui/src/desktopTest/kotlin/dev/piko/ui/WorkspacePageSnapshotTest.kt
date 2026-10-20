package dev.piko.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.ui.workbench.LocalWorkspaceSnapshotEnabled
import dev.piko.ui.workbench.WorkspacePageSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacePageSnapshotTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun disabledSnapshotsComposeContentWithoutAGraphicsContext() = runBlocking {
        for ((enabled, localEnabled) in listOf(false to true, true to false)) {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(EmptyApplier(), recomposer)
            var rendered = 0
            try {
                composition.setContent {
                    CompositionLocalProvider(LocalWorkspaceSnapshotEnabled provides localEnabled) {
                        WorkspacePageSnapshot(1, enabled, onSnapshot = { error("Unexpected capture") }) {
                            SideEffect { rendered++ }
                        }
                    }
                }
                assertEquals(1, rendered)
            } finally {
                composition.dispose()
                recomposer.close()
            }
        }
    }

    @Test
    fun restoringTabIdInsideSaveableStateProviderDoesNotDuplicateKeys() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val recomposer = Recomposer(coroutineContext)
            val collector = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(EmptyApplier(), recomposer)
            var activeId by mutableStateOf(1L)
            val observed = mutableListOf<Long>()
            try {
                composition.setContent {
                    val holder = rememberSaveableStateHolder()
                    key(activeId) {
                        holder.SaveableStateProvider(activeId) {
                            WorkspacePageSnapshot(activeId, enabled = false, onSnapshot = {}) {
                                SideEffect { observed += activeId }
                            }
                        }
                    }
                }
                Snapshot.withMutableSnapshot { activeId = 1102043229169875180L }
                Snapshot.sendApplyNotifications()
                yield()
                clock.sendFrame(1_000_000L)
                yield()
                assertEquals(listOf(1L, 1102043229169875180L), observed)
            } finally {
                composition.dispose()
                recomposer.cancel()
                collector.join()
            }
        }
    }
}
