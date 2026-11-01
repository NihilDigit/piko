package dev.piko.ui.screens.space

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SquarifiedTreemapTest {
    private fun input(id: String, bytes: Long) = TreemapInput(id = id, bytes = bytes, payload = id)

    private fun assertValidPartition(tiles: List<TreemapTile<*>>, container: TreemapRect, epsilon: Double = 1e-6) {
        assertTrue(tiles.isNotEmpty(), "Expected non-empty tiles")
        var areaSum = 0.0
        for (tile in tiles) {
            val b = tile.bounds
            assertTrue(b.width > 0.0 && b.height > 0.0, "Tile ${tile.id} must have positive dimensions: $b")
            assertTrue(b.left >= container.left - epsilon, "Tile ${tile.id} overflows left: $b")
            assertTrue(b.top >= container.top - epsilon, "Tile ${tile.id} overflows top: $b")
            assertTrue(b.right <= container.right + epsilon, "Tile ${tile.id} overflows right: $b")
            assertTrue(b.bottom <= container.bottom + epsilon, "Tile ${tile.id} overflows bottom: $b")
            areaSum += b.area
        }
        assertTrue(
            abs(areaSum - container.area) <= epsilon * container.area.coerceAtLeast(1.0),
            "Area not conserved: expected ${container.area}, actual $areaSum",
        )
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                val a = tiles[i].bounds
                val b = tiles[j].bounds
                val overlapX = min(a.right, b.right) - max(a.left, b.left)
                val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                val intersection = if (overlapX > 0.0 && overlapY > 0.0) overlapX * overlapY else 0.0
                assertTrue(
                    intersection <= epsilon,
                    "Tiles ${tiles[i].id} ($a) and ${tiles[j].id} ($b) overlap by $intersection",
                )
            }
        }
    }

    @Test
    fun `conserves area and avoids overlap across non-zero origin containers`() {
        val container = TreemapRect(left = 16.5, top = 32.25, width = 960.0, height = 540.0)
        val items = listOf(
            input("movies", 48_000_000_000L),
            input("series", 31_500_000_000L),
            input("music", 12_200_000_000L),
            input("photos", 8_400_000_000L),
            input("backups", 5_100_000_000L),
            input("docs", 1_900_000_000L),
        )
        val tiles = layoutTreemap(items, container)
        assertEquals(6, tiles.size)
        assertValidPartition(tiles, container)

        val totalBytes = items.sumOf { it.bytes }.toDouble()
        for (tile in tiles) {
            val expectedArea = container.area * (tile.bytes.toDouble() / totalBytes)
            assertTrue(
                abs(tile.bounds.area - expectedArea) <= 1e-4,
                "Tile ${tile.id} area ${tile.bounds.area} != expected $expectedArea",
            )
        }
    }

    @Test
    fun `squarified aspect ratios converge near squares compared to slice-and-dice`() {
        // Canonical example from Bruls, Huizing, van Wijk (2000): weights [6, 6, 4, 3, 2, 2, 1] in a 600x400 rect.
        val container = TreemapRect(0.0, 0.0, 600.0, 400.0)
        val weights = listOf(600L, 600L, 400L, 300L, 200L, 200L, 100L)
        val items = weights.mapIndexed { idx, w -> input("n$idx", w) }

        val tiles = layoutTreemap(items, container, enableGrouping = false)
        assertEquals(7, tiles.size)
        assertValidPartition(tiles, container)

        val worstAspect = tiles.maxOf { it.bounds.aspectRatio() }
        val meanAspect = tiles.sumOf { it.bounds.aspectRatio() } / tiles.size
        // In a 600x400 rect, pure slice-and-dice produces a 25x400 strip (worst aspect ratio 16.0).
        // Bruls-Huizing-van Wijk (2000) Fig. 3 places the final 1-unit cell at 60x166.67 (exact 25/9 = 2.777...)
        // with mean aspect ratio ~1.68 across all 7 cells.
        assertEquals(25.0 / 9.0, worstAspect, 1e-6)
        assertTrue(meanAspect < 1.75, "Expected mean aspect ratio < 1.75, got $meanAspect")
    }

    @Test
    fun `aggregates tiny trailing items into OtherGroup when two or more fall below readable threshold`() {
        val container = TreemapRect(0.0, 0.0, 800.0, 600.0)
        val large = listOf(
            input("big-1", 50_000_000_000L),
            input("big-2", 30_000_000_000L),
            input("big-3", 20_000_000_000L),
        )
        val tiny = (1..40).map { idx -> input("tiny-$idx", (idx * 50_000).toLong()) }

        val tiles = layoutTreemap(
            inputs = tiny + large, // Intentionally unsorted to verify internal descending sort.
            container = container,
            minTileWidth = 36.0,
            minTileHeight = 28.0,
            enableGrouping = true,
        )
        assertValidPartition(tiles, container)
        assertEquals(4, tiles.size, "Expected 3 large tiles + 1 aggregated OtherGroup tile")

        val groupTile = tiles.single { it.content is TreemapTileContent.OtherGroup }
        val group = assertIs<TreemapTileContent.OtherGroup<String>>(groupTile.content)
        assertEquals(40, group.items.size)
        assertEquals(tiny.sumOf { it.bytes }, groupTile.bytes)
        // Grouped items remain sorted largest-first for sheet display.
        assertEquals("tiny-40", group.items.first().id)
        assertEquals("tiny-1", group.items.last().id)

        // A single tiny trailing item must not be wrapped into a 1-item OtherGroup.
        val singleTinyTiles = layoutTreemap(
            inputs = large + listOf(input("only-tiny", 100_000L)),
            container = container,
            minTileWidth = 36.0,
            minTileHeight = 28.0,
            enableGrouping = true,
        )
        assertEquals(4, singleTinyTiles.size)
        assertTrue(singleTinyTiles.none { it.content is TreemapTileContent.OtherGroup })
    }

    @Test
    fun `filters zero or negative byte items and guards against empty or degenerate containers`() {
        val normalContainer = TreemapRect(0.0, 0.0, 500.0, 400.0)
        assertTrue(layoutTreemap<String>(emptyList(), normalContainer).isEmpty())

        val zeroAndNegative = listOf(
            input("zero", 0L),
            input("negative", -1024L),
        )
        assertTrue(layoutTreemap(zeroAndNegative, normalContainer).isEmpty())

        val mixed = zeroAndNegative + listOf(input("valid-a", 300L), input("valid-b", 100L))
        val validTiles = layoutTreemap(mixed, normalContainer, enableGrouping = false)
        assertEquals(listOf("valid-a", "valid-b"), validTiles.map { it.id })
        assertValidPartition(validTiles, normalContainer)

        // Zero, negative, and non-finite container dimensions must safely return emptyList without division by zero.
        for (badContainer in listOf(
            TreemapRect(0.0, 0.0, 0.0, 400.0),
            TreemapRect(0.0, 0.0, 500.0, 0.0),
            TreemapRect(0.0, 0.0, -10.0, 200.0),
            TreemapRect(0.0, 0.0, Double.NaN, 200.0),
            TreemapRect(0.0, 0.0, 200.0, Double.POSITIVE_INFINITY),
        )) {
            assertTrue(layoutTreemap(mixed, badContainer).isEmpty(), "Expected empty result for $badContainer")
        }
    }

    @Test
    fun `nestedPreviewBounds enforces minimum folder size and produces valid inner container`() {
        assertNull(nestedPreviewBounds(TreemapRect(0.0, 0.0, 87.0, 120.0), minContainerWidth = 88.0, minContainerHeight = 88.0))
        assertNull(nestedPreviewBounds(TreemapRect(0.0, 0.0, 120.0, 87.0), minContainerWidth = 88.0, minContainerHeight = 88.0))

        val outer = TreemapRect(10.0, 20.0, 200.0, 150.0)
        val inner = requireNotNull(
            nestedPreviewBounds(outer, minContainerWidth = 88.0, minContainerHeight = 88.0, headerHeight = 28.0, padding = 6.0),
        )
        assertEquals(16.0, inner.left, 1e-6)
        assertEquals(48.0, inner.top, 1e-6)
        assertEquals(188.0, inner.width, 1e-6)
        assertEquals(116.0, inner.height, 1e-6)

        val nestedTiles = layoutTreemap(
            inputs = listOf(input("c1", 600L), input("c2", 300L), input("c3", 100L)),
            container = inner,
            enableGrouping = false,
        )
        assertValidPartition(nestedTiles, inner)
    }
}
