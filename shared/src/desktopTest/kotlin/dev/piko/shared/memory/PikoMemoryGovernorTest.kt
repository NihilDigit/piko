package dev.piko.shared.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PikoMemoryGovernorTest {
    @Test
    fun pressureHasHysteresisAndVisibilityDoesNotResetIt() {
        val governor = PikoMemoryGovernor()
        governor.onHeapSample(96, 100)
        assertEquals(MemoryPolicy.Constrained, governor.policy.value)
        governor.onHeapSample(96, 100)
        assertEquals(MemoryPolicy.Critical, governor.policy.value)
        governor.onVisibilityChanged(false)
        assertEquals(MemoryPolicy.Background, governor.policy.value)
        governor.onVisibilityChanged(true)
        assertEquals(MemoryPolicy.Critical, governor.policy.value)
        repeat(2) { governor.onHeapSample(60, 100) }
        assertEquals(MemoryPolicy.Critical, governor.policy.value)
        governor.onHeapSample(60, 100)
        assertEquals(MemoryPolicy.Constrained, governor.policy.value)
        repeat(3) { governor.onHeapSample(60, 100) }
        assertEquals(MemoryPolicy.Normal, governor.policy.value)
    }

    @Test
    fun backgroundAndPlatformCriticalPreservePressure() {
        val governor = PikoMemoryGovernor()
        governor.onPlatformTrim(MemoryTrim.Background)
        assertEquals(MemoryPolicy.Background, governor.policy.value)
        governor.onPlatformTrim(MemoryTrim.Critical)
        assertEquals(MemoryPolicy.Background, governor.policy.value)
        governor.onVisibilityChanged(true)
        assertEquals(MemoryPolicy.Critical, governor.policy.value)
    }

    @Test
    fun permitSerializesAllKindsAndReleaseIsIdempotent() = runBlocking {
        val governor = PikoMemoryGovernor()
        val first = governor.acquire(MemoryWorkKind.Persistence, 10)
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            governor.acquire(MemoryWorkKind.TreeBuild, 10)
        }
        assertFalse(second.isCompleted)
        first.release()
        val next = withTimeout(2_000) { second.await() }
        first.release()
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            governor.acquire(MemoryWorkKind.SnapshotRead, 10)
        }
        assertFalse(third.isCompleted)
        next.release()
        withTimeout(2_000) { third.await() }.release()
    }

    @Test
    fun cancelledWaiterDoesNotLeakPermit() = runBlocking<Unit> {
        val governor = PikoMemoryGovernor()
        val first = governor.acquire(MemoryWorkKind.Persistence, 0)
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            governor.acquire(MemoryWorkKind.SnapshotRead, 0).release()
        }
        waiting.cancelAndJoin()
        first.release()
        withTimeout(2_000) { governor.acquire(MemoryWorkKind.TreeBuild, 0) }.release()
        assertFailsWith<IllegalArgumentException> { governor.acquire(MemoryWorkKind.TreeBuild, -1) }
    }

    @Test
    fun participantCanReenterGovernorAndUnregister() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val governor = PikoMemoryGovernor(scope)
            val applied = CompletableDeferred<Unit>()
            val unregister = governor.register(object : MemoryParticipant {
                override val id = "reentrant-test"
                override suspend fun applyPolicy(policy: MemoryPolicy) {
                    governor.onVisibilityChanged(true)
                    applied.complete(Unit)
                }
            })
            withTimeout(2_000) { applied.await() }
            unregister()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun peakEstimateSignalsPressureAndBudgetsHaveNoFixedFloor() = runBlocking {
        val governor = PikoMemoryGovernor()
        governor.onHeapSample(50, 100)
        governor.acquire(MemoryWorkKind.TreeBuild, 60).release()
        assertEquals(MemoryPolicy.Constrained, governor.policy.value)
        assertEquals(8L, MemoryPolicy.Constrained.cacheBudget(16))
        assertEquals(4L, MemoryPolicy.Background.cacheBudget(16))
    }
}
