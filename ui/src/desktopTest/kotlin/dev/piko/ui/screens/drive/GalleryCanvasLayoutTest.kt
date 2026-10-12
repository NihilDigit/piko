package dev.piko.ui.screens.drive

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GalleryCanvasLayoutTest {
    private fun items(count: Int, mixed: Boolean = false) = List(count) {
        GalleryLayoutItem("video-$it", if (mixed) GalleryShape.entries[it % 3] else GalleryShape.Landscape)
    }

    private fun frame(layout: GalleryCanvasLayout, items: List<GalleryLayoutItem>, width: Double = 1000.0, height: Double = 700.0, columns: Int = 5) =
        layout.frame(items, columns, width, height, 6.0)

    private fun assertContinuous(frame: List<GalleryPlacement>, height: Double = 700.0) {
        assertEquals(frame.size, frame.map { it.key }.toSet().size)
        frame.groupBy { it.key.column }.values.forEach { lane ->
            assertTrue(lane.first().top + lane.first().height <= 0.0001, "one complete tile before viewport")
            assertTrue(lane.last().top >= height, "one complete tile after viewport")
            lane.zipWithNext().forEach { (a, b) ->
                assertTrue(abs(b.top - a.top - a.height - 6.0) < 0.0001, "no overlap or unexpected hole")
            }
        }
    }

    private fun assertSameGeometry(before: List<GalleryPlacement>, after: List<GalleryPlacement>) {
        assertEquals(before.map { it.key to it.fileId }, after.map { it.key to it.fileId })
        before.zip(after).forEach { (old, new) ->
            assertEquals(old.shape, new.shape)
            assertEquals(old.left, new.left)
            assertEquals(old.width, new.width)
            assertEquals(old.top, new.top, 0.0001)
            assertEquals(old.height, new.height)
        }
    }

    @Test
    fun `shape follows nearest aspect including wide cinema and invalid dimensions`() {
        assertEquals(GalleryShape.Landscape, GalleryShape.nearest(21.0 / 9.0))
        assertEquals(GalleryShape.Portrait, GalleryShape.nearest(9.0 / 16.0))
        assertEquals(GalleryShape.Square, GalleryShape.nearest(1.1))
        assertEquals(GalleryShape.Landscape, GalleryShape.nearest(Double.NaN))
        assertEquals(GalleryShape.Landscape, GalleryShape.nearest(0.0))
    }

    @Test
    fun `mixed bands cover viewport without overlaps at large positive and negative offsets`() {
        val layout = GalleryCanvasLayout()
        val pool = items(150, mixed = true)
        for (delta in listOf(0.0, 137.0, 810.0, -2194.0, 1e8, -2e8)) {
            layout.scrollBy(delta)
            val frame = frame(layout, pool)
            assertContinuous(frame)
            frame.forEach { assertTrue(abs(it.width / it.height - it.shape.ratio) < 0.0001) }
            assertEquals(1, frame.map { it.width }.toSet().size)
        }
    }

    @Test
    fun `landscape lanes alternate by half a tile pitch and fill top immediately`() {
        val frame = frame(GalleryCanvasLayout(), items(100))
        val lane0 = frame.first { it.key.column == 0 && it.key.ordinal == 0L }
        val lane1 = frame.first { it.key.column == 1 && it.key.ordinal == 0L }
        assertEquals((lane0.height + 6) / 2, lane1.top - lane0.top, 0.0001)
        assertContinuous(frame)
        assertTrue(frame.none { it.fileId == null })
    }

    @Test
    fun `single item never repeats inside viewport and empty pool only pads geometry`() {
        for (count in listOf(0, 1, 3)) {
            val layout = GalleryCanvasLayout()
            repeat(40) {
                val frame = frame(layout, items(count))
                assertContinuous(frame)
                val visibleIds = frame.filter { it.top < 700 && it.top + it.height > 0 }.mapNotNull { it.fileId }
                assertEquals(visibleIds.size, visibleIds.toSet().size)
                if (count == 0) assertTrue(frame.all { it.fileId == null })
                layout.scrollBy(211.0)
            }
        }
    }

    @Test
    fun `first discovery fills an initially empty viewport and padding has mixed shapes`() {
        val layout = GalleryCanvasLayout()
        val empty = frame(layout, emptyList())
        assertEquals(GalleryShape.entries.toSet(), empty.map { it.shape }.toSet())
        val populated = frame(layout, items(120, mixed = true))
        assertTrue(populated.none { it.fileId == null })
        assertContinuous(populated)
    }

    @Test
    fun `mixed content uses balanced lane heights before adding decoration`() {
        val skewed = List(100) { GalleryLayoutItem("$it", if (it % 5 == 0) GalleryShape.Portrait else GalleryShape.Landscape) }
        val placements = frame(GalleryCanvasLayout(), skewed)
        assertTrue(placements.none { it.fileId == null })
        assertContinuous(placements)
    }

    @Test
    fun `twelve mixed videos filling desktop viewport never add decoration for buffers`() {
        val layout = GalleryCanvasLayout()
        val mixed = items(12, mixed = true)
        repeat(40) {
            val placements = frame(layout, mixed, width = 1184.0, height = 688.0, columns = 4)
            assertTrue(placements.none { it.fileId == null })
            assertContinuous(placements, height = 688.0)
            layout.scrollBy(173.0)
        }
    }

    @Test
    fun `cover dimensions correct untouched first frame while append geometry stays fixed`() {
        val layout = GalleryCanvasLayout()
        val unknown = items(100)
        val initial = frame(layout, unknown)
        val learned = unknown.map { if (it.id == "video-0") it.copy(shape = GalleryShape.Portrait) else it }
        val corrected = frame(layout, learned)
        assertEquals(GalleryShape.Landscape, initial.first { it.fileId == "video-0" }.shape)
        assertEquals(GalleryShape.Portrait, corrected.first { it.fileId == "video-0" }.shape)
        assertEquals(0.0, corrected.first { it.fileId == "video-0" && it.key.ordinal == 0L }.top, 0.0001)
        assertContinuous(corrected)
        assertSameGeometry(corrected, frame(layout, learned + items(120).drop(100)))
    }

    @Test
    fun `cover dimensions do not reflow a band after the user scrolls or returns`() {
        val layout = GalleryCanvasLayout()
        val unknown = items(100)
        frame(layout, unknown)
        layout.scrollBy(1.0)
        val before = frame(layout, unknown)
        val learned = unknown.map { it.copy(shape = GalleryShape.Portrait) }
        assertSameGeometry(before, frame(layout, learned))
        val restored = GalleryCanvasLayout(assertNotNull(layout.anchor()))
        val returned = frame(restored, unknown)
        assertSameGeometry(returned, frame(restored, learned))
    }

    @Test
    fun `cycling reconstructs same content and geometry on reverse scrolling`() {
        val layout = GalleryCanvasLayout()
        val pool = items(101, mixed = true)
        val initial = frame(layout, pool)
        repeat(2000) { layout.scrollBy(103.0); frame(layout, pool) }
        repeat(2000) { layout.scrollBy(-103.0); frame(layout, pool) }
        assertEquals(initial, frame(layout, pool))
        assertEquals(5, layout.checkpointCount)
    }

    @Test
    fun `append and newly discovered ratios preserve viewport and all visited history`() {
        val layout = GalleryCanvasLayout()
        val pool = items(45)
        val origin = frame(layout, pool)
        layout.scrollBy(1800.0)
        val before = frame(layout, pool)
        val updated = items(90, mixed = true)
        assertEquals(before, frame(layout, updated))
        repeat(40) { layout.scrollBy(137.0); frame(layout, updated) }
        layout.scrollBy(-5480.0)
        assertEquals(before, frame(layout, updated))
        layout.scrollBy(-1800.0)
        assertEquals(origin, frame(layout, updated))
    }

    @Test
    fun `return and resize preserve the same anchor and fractional position`() {
        val layout = GalleryCanvasLayout()
        val pool = items(200, mixed = true)
        frame(layout, pool)
        layout.scrollBy(963.0)
        frame(layout, pool)
        val anchor = assertNotNull(layout.anchor())
        val restored = GalleryCanvasLayout(anchor)
        val resized = frame(restored, pool, width = 720.0, height = 540.0, columns = 3)
        val placement = resized.first { it.fileId == anchor.fileId && abs(-it.top / it.height - anchor.fraction) < 0.0001 }
        assertEquals(anchor.fraction, -placement.top / placement.height, 0.0001)
        val directResize = frame(layout, pool, width = 720.0, height = 540.0, columns = 3)
        assertEquals(resized, directResize)
    }

    @Test
    fun `render count and checkpoint memory stay bounded during endless scrolling`() {
        val layout = GalleryCanvasLayout()
        val pool = items(3000, mixed = true)
        val minimumHeight = ((1000.0 - 24) / 5) / GalleryShape.Landscape.ratio
        val bound = 5 * (ceil(700 / (minimumHeight + 6)).toInt() + 4)
        repeat(5000) {
            layout.scrollBy(if (it % 2 == 0) 1e6 else -999111.0)
            assertTrue(frame(layout, pool).size <= bound)
        }
        assertEquals(5, layout.checkpointCount)
    }

    @Test
    fun `demand driven appends retain visited tiles rather than full historical catalogs`() {
        val layout = GalleryCanvasLayout()
        val pool = items(3000, mixed = true)
        var visitedTileBudget = frame(layout, pool.take(100)).size
        repeat(100) { revision ->
            layout.scrollBy(170.0)
            val before = frame(layout, pool.take(100 + revision * 20))
            val after = frame(layout, pool.take(120 + revision * 20))
            assertSameGeometry(before, after)
            assertContinuous(after)
            visitedTileBudget += before.size
        }
        assertTrue(layout.retainedCatalogEntryCount < pool.size + visitedTileBudget,
            "retained ${layout.retainedCatalogEntryCount} entries for 2100 current candidates")
    }
}
