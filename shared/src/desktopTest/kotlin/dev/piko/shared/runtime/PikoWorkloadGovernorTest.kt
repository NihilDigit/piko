package dev.piko.shared.runtime

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PikoWorkloadGovernorTest {
    @Test
    fun playbackPresenceDoesNotGloballySuspendUnrelatedWork() = runBlocking {
        val governor = PikoWorkloadGovernor()
        val lease = governor.register(WorkPurpose.Playback, WorkPhase.Urgent)
        assertTrue(governor.budget.value.hasUrgentPlayback)
        for (phase in listOf(WorkPhase.Urgent, WorkPhase.Steady, WorkPhase.Paused)) {
            lease.updatePhase(phase)
            val budget = governor.budget.value
            assertTrue(budget.allowClipPrefetch)
            assertTrue(budget.allowBackgroundSync)
            assertEquals(WorkBudget.DEFAULT_GALLERY_DISCOVERY_CONCURRENCY, budget.galleryDiscoveryConcurrency)
            assertEquals(WorkBudget.DEFAULT_COVER_PREFETCH_CONCURRENCY, budget.coverPrefetchConcurrency)
            assertEquals(phase != WorkPhase.Paused, budget.hasActivePlayback)
        }
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
