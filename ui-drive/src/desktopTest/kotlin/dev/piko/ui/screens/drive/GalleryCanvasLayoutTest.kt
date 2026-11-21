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

    private fun assertNoOverlap(frame: List<GalleryPlacement>) {
        assertEquals(frame.size, frame.map { it.key }.toSet().size)
        frame.groupBy { it.key.column }.values.forEach { lane ->
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
    fun `near max hash plus large row does not overflow ratio index`() {
        val row = 10_000
        // Construct an ID whose seeded fold is exactly Int.MAX_VALUE in column zero.
        var seed = row * 31
        repeat(7) { seed *= 31 }
        var remainder = (Int.MAX_VALUE.toLong() - seed.toLong()).mod(1L shl 32)
        val chars = CharArray(7)
        for (i in chars.indices.reversed()) {
            chars[i] = (remainder % 31).toInt().toChar()
            remainder /= 31
        }
        val id = chars.concatToString()
        assertEquals(Int.MAX_VALUE, id.fold(row * 31) { acc, ch -> acc * 31 + ch.code })
        val pool = items(row) + GalleryLayoutItem(id, GalleryShape.Landscape)
        val layout = GalleryCanvasLayout()
        frame(layout, pool, columns = 1)
        layout.scrollBy(1e12)
        val last = frame(layout, pool, columns = 1).last()
        assertEquals(id, last.fileId)
        assertTrue(last.height > 0 && last.height.isFinite())
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
    fun `finite stream clamps scroll between zero and maxScrollOffset without repeating items`() {
        val layout = GalleryCanvasLayout()
        val pool = items(150, mixed = true)
        frame(layout, pool)
        assertTrue(layout.maxScrollOffset > 0.0)
        // Scrolling above top clamps at 0
        assertEquals(0.0, layout.scrollBy(-500.0), 0.0001)
        assertEquals(0.0, layout.scrollOffset, 0.0001)
        // Scrolling past bottom clamps at maxScrollOffset
        layout.scrollBy(1e8)
        assertEquals(layout.maxScrollOffset, layout.scrollOffset, 0.0001)
        val bottomFrame = frame(layout, pool)
        assertNoOverlap(bottomFrame)
        assertEquals(bottomFrame.size, bottomFrame.mapNotNull { it.fileId }.toSet().size)
    }

    @Test
    fun `lanes start flush at the top and stagger bottoms across landscape items`() {
        val frame = frame(GalleryCanvasLayout(), items(100))
        val lane0 = frame.first { it.key.column == 0 && it.key.ordinal == 0L }
        val lane1 = frame.first { it.key.column == 1 && it.key.ordinal == 0L }
        assertEquals(0.0, lane0.top, 0.0001)
        assertEquals(0.0, lane1.top, 0.0001)
        assertTrue(abs(lane1.height - lane0.height) > 10.0)
        assertNoOverlap(frame)
        assertTrue(frame.none { it.fileId == null })
    }

    @Test
    fun `small pool renders each item once without cycling or glyph placeholders`() {
        for (count in listOf(0, 1, 3)) {
            val layout = GalleryCanvasLayout()
            val frame = frame(layout, items(count))
            assertEquals(count, frame.size)
            assertEquals(count, frame.mapNotNull { it.fileId }.toSet().size)
            assertEquals(0.0, layout.maxScrollOffset, 0.0001)
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
        assertNoOverlap(corrected)
        assertSameGeometry(corrected, frame(layout, learned + items(120).drop(100)))
    }

    @Test
    fun `cover dimensions do not reflow already placed items after user scrolls`() {
        val layout = GalleryCanvasLayout()
        val unknown = items(100)
        frame(layout, unknown)
        layout.scrollBy(10.0)
        val before = frame(layout, unknown)
        val learned = unknown.map { it.copy(shape = GalleryShape.Portrait) }
        assertSameGeometry(before, frame(layout, learned))
        val restored = GalleryCanvasLayout(assertNotNull(layout.anchor()))
        val returned = frame(restored, unknown)
        assertSameGeometry(returned, frame(restored, learned))
    }

    @Test
    fun `append and newly discovered ratios preserve viewport and all visited history`() {
        val layout = GalleryCanvasLayout()
        val pool = items(60)
        val origin = frame(layout, pool)
        layout.scrollBy(150.0)
        val before = frame(layout, pool)
        val updated = items(120, mixed = true)
        assertSameGeometry(before, frame(layout, updated))
        layout.scrollBy(-150.0)
        assertSameGeometry(origin, frame(layout, updated))
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
        val placement = resized.first { it.fileId == anchor.fileId }
        assertEquals(anchor.fraction, -placement.top / placement.height, 0.0001)
        val directResize = frame(layout, pool, width = 720.0, height = 540.0, columns = 3)
        assertEquals(resized, directResize)
    }
}
