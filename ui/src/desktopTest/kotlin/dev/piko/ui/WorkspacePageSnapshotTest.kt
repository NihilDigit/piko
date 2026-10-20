package dev.piko.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import dev.piko.ui.workbench.LocalWorkspaceSnapshotEnabled
import dev.piko.ui.workbench.WorkspacePageSnapshot
import kotlinx.coroutines.runBlocking
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
}
