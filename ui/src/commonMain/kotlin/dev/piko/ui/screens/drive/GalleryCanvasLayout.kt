package dev.piko.ui.screens.drive

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

internal enum class GalleryShape(val ratio: Double) {
    Landscape(16.0 / 9.0), Portrait(9.0 / 16.0), Square(1.0);

    companion object {
        fun nearest(ratio: Double?): GalleryShape {
            if (ratio == null || !ratio.isFinite() || ratio <= 0.0) return Landscape
            return entries.minBy { abs(ln(ratio / it.ratio)) }
        }
    }
}

internal data class GalleryLayoutItem(val id: String, val shape: GalleryShape)
internal data class GalleryOccurrenceKey(val column: Int, val ordinal: Long)
internal data class GalleryPlacement(
    val key: GalleryOccurrenceKey,
    val fileId: String?,
    val shape: GalleryShape,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

internal data class GalleryAnchor(
    val fileId: String?,
    val ordinal: Long,
    val column: Int,
    val fraction: Double,
)

/**
 * Each lane is an infinite periodic band. Only the viewport and one complete tile on each
 * side are materialized. A discovery update cuts new bands in beyond the visited range;
 * sparse band boundaries retain the exact geometry when scrolling back through history.
 * Boundaries grow with catalog revisions, never with scrolling distance.
 */
internal class GalleryCanvasLayout(private var restoredAnchor: GalleryAnchor? = null) {
    var scrollOffset: Double = 0.0
        private set

    private var configuration: Configuration? = null
    private var items: List<GalleryLayoutItem> = emptyList()
    private var lanes: List<Lane> = emptyList()
    private var lastFrame: List<GalleryPlacement> = emptyList()
    private var hasScrolled = restoredAnchor != null

    val checkpointCount: Int get() = lanes.sumOf { it.segments.size }
    val retainedCatalogEntryCount: Int
        get() = lanes.flatMap { it.segments }.map { it.catalog }.distinct().sumOf { it.entries.size }
    val viewportHeight: Double get() = configuration?.height ?: 0.0

    fun scrollBy(delta: Double) {
        if (delta.isFinite() && delta != 0.0) {
            hasScrolled = true
            scrollOffset += delta
        }
    }

    fun anchor(): GalleryAnchor? {
        val visible = lastFrame.filter { it.top < configuration!!.height && it.top + it.height > 0 }
        val placement = visible.filter { it.fileId != null }.minWithOrNull(
            compareBy<GalleryPlacement> { max(0.0, it.top) }.thenBy { it.key.column },
        ) ?: visible.firstOrNull() ?: return restoredAnchor
        return GalleryAnchor(placement.fileId, placement.key.ordinal, placement.key.column, -placement.top / placement.height)
    }

    fun frame(
        items: List<GalleryLayoutItem>,
        columns: Int,
        width: Double,
        height: Double,
        gap: Double,
    ): List<GalleryPlacement> {
        require(columns > 0 && width > 0 && height > 0 && gap >= 0)
        val next = Configuration(columns, width, height, gap)
        if (next != configuration) {
            val anchor = anchor() ?: restoredAnchor
            configuration = next
            this.items = items.toList()
            lanes = createLanes(items, next)
            if (anchor != null) restore(anchor, next)
            restoredAnchor = null
        } else if (this.items.isEmpty() && items.isNotEmpty()) {
            this.items = items.toList()
            lanes = createLanes(items, next)
        } else if (!hasScrolled && this.items != items && this.items.map { it.id } == items.map { it.id }) {
            // The first covers can reveal portrait dimensions after initial measurement.
            // Correct that untouched first frame; appends and already browsed bands stay fixed.
            val anchor = anchor()
            this.items = items.toList()
            lanes = createLanes(items, next)
            if (anchor != null) restore(anchor, next)
        } else if (this.items != items) {
            this.items = items.toList()
            val catalogs = catalogs(items, next)
            lanes.forEachIndexed { column, lane -> lane.update(catalogs[column]) }
        }
        lastFrame = lanes.flatMapIndexed { column, lane ->
            lane.window(scrollOffset, height).map { slot ->
                GalleryPlacement(
                    GalleryOccurrenceKey(column, slot.ordinal), slot.item.id, slot.item.shape,
                    column * (next.tileWidth + gap), slot.top - scrollOffset,
                    next.tileWidth, slot.height,
                )
            }
        }
        return lastFrame
    }

    private fun restore(anchor: GalleryAnchor, config: Configuration) {
        val column = lanes.indexOfFirst { lane -> lane.segments.single().catalog.entries.any { it.id == anchor.fileId && it.id != null } }
            .takeIf { it >= 0 } ?: anchor.column.coerceIn(lanes.indices)
        val lane = lanes[column]
        val segment = lane.segments.single()
        val catalog = segment.catalog
        val index = catalog.entries.indexOfFirst { it.id == anchor.fileId }.coerceAtLeast(0)
        val cycle = floor(anchor.ordinal.toDouble() / catalog.entries.size).toLong()
        val ordinal = cycle * catalog.entries.size + index
        val slot = segment.slot(ordinal)
        scrollOffset = slot.top + anchor.fraction * slot.height
    }

    private fun createLanes(items: List<GalleryLayoutItem>, config: Configuration): List<Lane> =
        catalogs(items, config).mapIndexed { column, catalog ->
            val offset = if (column % 2 == 0) 0.0 else (config.tileWidth / GalleryShape.Landscape.ratio + config.gap) / 2
            Lane(mutableListOf(Segment(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, offset, 0, catalog)))
        }

    private fun catalogs(items: List<GalleryLayoutItem>, config: Configuration): List<Catalog> {
        val assigned = List(config.columns) { mutableListOf<Entry>() }
        val extents = DoubleArray(config.columns) { column ->
            if (column % 2 == 0) 0.0 else (config.tileWidth / GalleryShape.Landscape.ratio + config.gap) / 2
        }
        items.forEach { item ->
            val column = extents.indices.minBy { extents[it] }
            assigned[column] += Entry(item.id, item.shape)
            extents[column] += config.tileWidth / item.shape.ratio + config.gap
        }
        return List(config.columns) { column ->
            val entries = assigned[column]
            val maxHeight = entries.maxOfOrNull { config.tileWidth / it.shape.ratio }
                ?: config.tileWidth / GalleryShape.Landscape.ratio
            var extent = entries.sumOf { config.tileWidth / it.shape.ratio + config.gap }
            // Viewport buffers can repeat the existing cycle. Decoration is only needed
            // when real content cannot fill the viewport; sparse cycles also leave room
            // so one real video does not appear at both clipped edges simultaneously.
            val minimumExtent = if (extent >= config.height) config.height else config.height + maxHeight + config.gap
            while (extent < minimumExtent || entries.isEmpty()) {
                val shape = GalleryShape.entries[(column * 7 + entries.size * 11) % GalleryShape.entries.size]
                entries += Entry(null, shape)
                extent += config.tileWidth / shape.ratio + config.gap
            }
            Catalog(entries, config.tileWidth, config.gap)
        }
    }

    private data class Configuration(val columns: Int, val width: Double, val height: Double, val gap: Double) {
        val tileWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1.0)
    }

    private data class Entry(val id: String?, val shape: GalleryShape)
    private class Catalog(val entries: List<Entry>, val width: Double, val gap: Double) {
        val starts = DoubleArray(entries.size + 1).also { starts ->
            entries.forEachIndexed { index, entry -> starts[index + 1] = starts[index] + width / entry.shape.ratio + gap }
        }
        val extent = starts.last()

        fun ordinalAt(relativeY: Double): Long {
            val cycle = floor(relativeY / extent).toLong()
            val within = relativeY - cycle * extent
            var low = 0
            var high = entries.lastIndex
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (starts[mid] <= within) low = mid else high = mid - 1
            }
            return cycle * entries.size + low
        }

        fun top(ordinal: Long): Double {
            val cycle = floor(ordinal.toDouble() / entries.size).toLong()
            val index = (ordinal - cycle * entries.size).toInt()
            return cycle * extent + starts[index]
        }

        fun entry(ordinal: Long): Entry = entries[((ordinal % entries.size + entries.size) % entries.size).toInt()]
    }

    private data class Slot(val ordinal: Long, val top: Double, val height: Double, val pitch: Double, val item: Entry)
    private data class Segment(val start: Double, val end: Double, val origin: Double, val firstOrdinal: Long, val catalog: Catalog) {
        fun at(y: Double): Slot = slot(catalog.ordinalAt(y - origin))
        fun slot(localOrdinal: Long): Slot {
            val entry = catalog.entry(localOrdinal)
            val height = catalog.width / entry.shape.ratio
            return Slot(firstOrdinal + localOrdinal, origin + catalog.top(localOrdinal), height, height + catalog.gap, entry)
        }

        fun cropped(start: Double, end: Double): Segment {
            val first = catalog.ordinalAt(start - origin + EPSILON)
            val after = catalog.ordinalAt(end - origin - EPSILON) + 1
            val count = after - first
            if (count >= catalog.entries.size) return copy(start = start, end = end)
            // A revision must not retain thousands of unseen candidates just to replay
            // a handful of visited tiles. Keep only this finite slice of its old band.
            val slice = Catalog(List(count.toInt()) { catalog.entry(first + it) }, catalog.width, catalog.gap)
            return Segment(start, end, origin + catalog.top(first), firstOrdinal + first, slice)
        }
    }

    private class Lane(var segments: MutableList<Segment>) {
        private var visitedStart = Double.POSITIVE_INFINITY
        private var visitedEnd = Double.NEGATIVE_INFINITY
        private var firstOrdinal = 0L
        private var afterOrdinal = 0L

        private fun at(y: Double): Slot = segments.first { y >= it.start && y < it.end }.at(y)

        fun window(offset: Double, height: Double): List<Slot> {
            var first = at(offset)
            if (first.top + first.height <= offset) first = at(first.top + first.pitch + EPSILON)
            first = at(first.top - EPSILON)
            val slots = mutableListOf(first)
            var slot = first
            while (slot.top < offset + height) {
                slot = at(slot.top + slot.pitch + EPSILON)
                slots += slot
            }
            if (first.top < visitedStart) {
                visitedStart = first.top
                firstOrdinal = first.ordinal
            }
            val end = slot.top + slot.pitch
            if (end > visitedEnd) {
                visitedEnd = end
                afterOrdinal = slot.ordinal + 1
            }
            return slots
        }

        fun update(catalog: Catalog) {
            if (!visitedStart.isFinite()) return
            if (segments.first().catalog.entries == catalog.entries) return
            val history = segments.filter { it.end > visitedStart && it.start < visitedEnd }
                .map { it.cropped(start = maxOf(it.start, visitedStart), end = minOf(it.end, visitedEnd)) }
            segments = mutableListOf(Segment(Double.NEGATIVE_INFINITY, visitedStart, visitedStart, firstOrdinal, catalog)).apply {
                addAll(history)
                add(Segment(visitedEnd, Double.POSITIVE_INFINITY, visitedEnd, afterOrdinal, catalog))
            }
        }
    }

    private companion object {
        const val EPSILON = 0.00001
    }
}
