package dev.piko.ui.screens.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryPrefetchWindowTest {
    @Test
    fun emptyAndShortDatasetsHaveNoOutOfBoundsWork() {
        assertTrue(galleryPrefetchWindow(0, 100).isEmpty())
        assertEquals(0..2, galleryPrefetchWindow(3, 1))
        assertEquals(0..0, galleryPrefetchWindow(1, 0))
    }

    @Test
    fun includesBothWindowEndpointsWithoutShiftingAtDatasetEdges() {
        assertEquals(88..164, galleryPrefetchWindow(1000, 100))
        assertEquals(0..64, galleryPrefetchWindow(1000, 0))
        assertEquals(987..999, galleryPrefetchWindow(1000, 999))
    }

    @Test
    fun staleAnchorsAreClampedAndLargeCountsDoNotOverflow() {
        assertEquals(0..64, galleryPrefetchWindow(100, -5))
        assertEquals(87..99, galleryPrefetchWindow(100, 1000))
        val window = galleryPrefetchWindow(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE - 1, window.last)
        assertTrue(window.count() <= 77)
    }

    @Test
    fun movingFarAwayRemovesEveryOldCandidate() {
        val old = galleryPrefetchWindow(10000, 100).toSet()
        val next = galleryPrefetchWindow(10000, 1000).toSet()
        assertTrue(old.intersect(next).isEmpty())
        assertEquals(77, next.size)
    }
}
