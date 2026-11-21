package dev.piko.ui.screens.drive

import dev.piko.shared.state.FolderMapLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame

class FolderMapStateTest {
    @Test
    fun lateResultsCannotOverwriteANewerRequestForTheSameFolder() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val old = CompletableDeferred<FolderMapLevel>()
            val fresh = CompletableDeferred<FolderMapLevel>()
            val other = CompletableDeferred<FolderMapLevel>()
            var calls = 0
            val state = FolderMapState(
                load = { id -> if (id == "other") other.await() else if (calls++ == 0) old.await() else fresh.await() },
                scope = scope,
                saveOpen = {},
                savePinned = {},
            )
            state.expand("folder")
            state.refresh("other")
            state.refresh("folder")
            val latest = FolderMapLevel.Loaded(emptyList())
            fresh.complete(latest)
            old.complete(FolderMapLevel.Failed("old"))
            assertSame(latest, state.levels["folder"])
            val independent = FolderMapLevel.Loaded(emptyList())
            other.complete(independent)
            assertSame(independent, state.levels["other"])
        } finally {
            scope.cancel()
        }
    }
}
