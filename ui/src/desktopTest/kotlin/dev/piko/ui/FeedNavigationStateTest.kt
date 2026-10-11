package dev.piko.ui

import dev.piko.shared.presentation.FileLocation
import dev.piko.ui.navigation.Screen
import dev.piko.ui.workbench.FeedNavigationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedNavigationStateTest {
    private fun state(scope: CoroutineScope) = FeedNavigationState(scope, { object : FileLocation {} }, { emptyList() }, {}, {}, { true })
    @Test
    fun returningFromTheMobileDetourResumesItsOriginAfterNestedNavigation() = runBlocking {
        val state = state(this)
        val origin = object : FileLocation {}
        val browse = Screen.Browse(listOf("folder" to "folder"), "file", 1)
        state.returnLocation = origin
        state.markBrowse(browse)
        assertNull(state.returningFrom(Screen.VideoPlayer("file", "file")))
        assertTrue(state.returnLocation === origin)
        assertTrue(state.returningFrom(browse.copy()) === origin, "导航条目重建后按固定身份匹配")
        assertNull(state.returnLocation)
        assertNull(state.returningFrom(browse))
    }

    @Test
    fun closingTheFeedWhileBrowsingDoesNotReopenItWhenReturning() = runBlocking {
        val state = state(this)
        val browse = Screen.Browse(emptyList(), "file", 1)
        state.returnLocation = object : FileLocation {}
        state.markBrowse(browse)
        state.opened = true
        state.close()
        assertNull(state.returningFrom(browse))
        assertNull(state.returnLocation)
        assertTrue(!state.opened)
    }

    @Test
    fun rebindingTheViewDoesNotRestartAnOpeningOrOpenedFeed() = runBlocking {
        val ready = MutableStateFlow(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var opens = 0
        val origin = object : FileLocation {}
        val state = FeedNavigationState(this, { origin }, { emptyList() }, {
            opens++
            started.complete(Unit)
            release.await()
        }, {}, { true })
        state.prepare(ready)
        assertEquals(0, opens)
        ready.value = true
        withTimeout(5_000) { started.await() }
        state.prepare(flowOf(true))
        assertEquals(1, opens)
        assertTrue(state.home === origin)
        release.complete(Unit)
        withTimeout(5_000) { while (!state.opened) delay(10) }
        state.prepare(flowOf(true))
        assertEquals(1, opens)
        state.close()
    }

    @Test
    fun anOldOpeningCannotClearTheStateOfARapidlyReopenedFeed() = runBlocking {
        val parent = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        var opens = 0
        val state = FeedNavigationState(scope, { object : FileLocation {} }, { emptyList() }, {
            opens++
            if (opens == 1) {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { oldRelease.await() }
            } else {
                newStarted.complete(Unit)
                newRelease.await()
            }
        }, {}, { true })
        state.prepare(flowOf(true))
        withTimeout(5_000) { oldStarted.await() }
        val oldJob = parent.children.single()
        state.close()
        state.prepare(flowOf(true))
        withTimeout(5_000) { newStarted.await() }
        oldRelease.complete(Unit)
        withTimeout(5_000) { oldJob.join() }
        state.prepare(flowOf(true))
        assertEquals(2, opens)
        newRelease.complete(Unit)
        withTimeout(5_000) { while (!state.opened) delay(10) }
        state.close()
        parent.cancel()
    }
}
