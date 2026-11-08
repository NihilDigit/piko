package dev.piko.shared.runtime

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PikoWorkloadGovernorTest {
    @Test
    fun `urgent playback pauses discovery cover prefetch and background sync until steady or closed`() = runBlocking {
        val governor = PikoWorkloadGovernor()
        assertEquals(WorkBudget(), governor.budget.value)

        val lease = governor.register(WorkPurpose.Playback, WorkPhase.Urgent)
        assertTrue(governor.budget.value.hasActivePlayback)
        assertTrue(governor.budget.value.hasUrgentPlayback)
        assertEquals(0, governor.budget.value.galleryDiscoveryConcurrency)
        assertEquals(0, governor.budget.value.coverPrefetchConcurrency)
        assertFalse(governor.budget.value.allowClipPrefetch)
        assertFalse(governor.budget.value.allowBackgroundSync)

        lease.updatePhase(WorkPhase.Steady)
        assertTrue(governor.budget.value.hasActivePlayback)
        assertFalse(governor.budget.value.hasUrgentPlayback)
        assertEquals(1, governor.budget.value.galleryDiscoveryConcurrency)
        assertEquals(2, governor.budget.value.coverPrefetchConcurrency)
        assertFalse(governor.budget.value.allowClipPrefetch)
        assertFalse(governor.budget.value.allowBackgroundSync)

        lease.updatePhase(WorkPhase.Paused)
        assertTrue(governor.budget.value.hasActivePlayback)
        assertFalse(governor.budget.value.hasUrgentPlayback)
        assertEquals(2, governor.budget.value.galleryDiscoveryConcurrency)
        assertEquals(4, governor.budget.value.coverPrefetchConcurrency)
        assertFalse(governor.budget.value.allowClipPrefetch)
        assertTrue(governor.budget.value.allowBackgroundSync)

        lease.updatePhase(WorkPhase.Urgent)
        assertEquals(0, governor.budget.value.galleryDiscoveryConcurrency)
        assertEquals(0, governor.budget.value.coverPrefetchConcurrency)

        lease.close()
        assertEquals(WorkBudget(), governor.budget.value)
    }

    @Test
    fun `switching focus between internal windows does not count as app foregrounding`() {
        val presence = AppPresence()

        // First window focuses -> transitions from initial unfocused set without grace elapsed
        assertFalse(presence.onWindowFocusChanged("main", true))
        assertTrue(presence.isAppActive.value)

        // Opening player window: player gains focus or main loses focus within grace window
        assertFalse(presence.onWindowFocusChanged("main", false))
        assertFalse(presence.onWindowFocusChanged("player-1", true))
        assertTrue(presence.isAppActive.value)

        // Closing player and returning to main within grace window
        presence.onWindowClosed("player-1")
        assertFalse(presence.onWindowFocusChanged("main", true))
        assertTrue(presence.isAppActive.value)
    }
}
