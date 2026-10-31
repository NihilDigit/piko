package dev.piko.ui.screens.drive

import kotlin.math.abs
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
 * 有限瀑布流画布：所有列顶部齐平，从 0 开始向下按最短列排列，每个独特视频只出现一次，到底即止，不循环重复。
 * 首屏尚未滚动时允许封面解码出的真实宽高比纠正初始排布；
 * 一旦开始滚动或恢复位置，已放置的卡片固定列、顺序与高度，新发现的卡片只接在最短列的末尾。
 */
internal class GalleryCanvasLayout(private var restoredAnchor: GalleryAnchor? = null) {
    var scrollOffset: Double = 0.0
        private set

    private var configuration: Configuration? = null
    private var rawItemsRef: List<GalleryLayoutItem>? = null
    private var items: List<GalleryLayoutItem> = emptyList()
    private val fixedShapes = LinkedHashMap<String, GalleryShape>()
    private var lanes: List<Lane> = emptyList()
    private var lastFrame: List<GalleryPlacement> = emptyList()
    private var hasScrolled = restoredAnchor != null

    val checkpointCount: Int get() = lanes.size
    val retainedCatalogEntryCount: Int get() = lanes.sumOf { it.slots.size }
    val viewportHeight: Double get() = configuration?.height ?: 0.0
    val maxScrollOffset: Double
        get() {
            val height = configuration?.height ?: return 0.0
            val contentBottom = lanes.maxOfOrNull { it.bottom } ?: 0.0
            return (contentBottom - height).coerceAtLeast(0.0)
        }

    fun scrollBy(delta: Double): Double {
        if (!delta.isFinite() || delta == 0.0) return 0.0
        val before = scrollOffset
        val limit = if (configuration != null) maxScrollOffset else Double.POSITIVE_INFINITY
        scrollOffset = (scrollOffset + delta).coerceIn(0.0, limit)
        val consumed = scrollOffset - before
        if (consumed != 0.0) hasScrolled = true
        return consumed
    }

    fun anchor(): GalleryAnchor? {
        val height = configuration?.height ?: return restoredAnchor
        val visible = lastFrame.filter { it.top < height && it.top + it.height > 0 }
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
        if (items !== rawItemsRef || next != configuration || lanes.isEmpty()) {
            rawItemsRef = items
            if (!hasScrolled) {
                fixedShapes.clear()
                items.forEach { fixedShapes[it.id] = it.shape }
            } else {
                items.forEach { if (it.id !in fixedShapes) fixedShapes[it.id] = it.shape }
            }
            val effectiveItems = items.map { it.copy(shape = fixedShapes[it.id] ?: it.shape) }
            if (next != configuration) {
                val anchor = anchor() ?: restoredAnchor
                configuration = next
                this.items = effectiveItems
                lanes = buildLanes(effectiveItems, next)
                if (anchor != null) restore(anchor)
                restoredAnchor = null
            } else if (this.items != effectiveItems || lanes.isEmpty()) {
                val anchor = if (!hasScrolled) anchor() else null
                this.items = effectiveItems
                lanes = buildLanes(effectiveItems, next)
                if (anchor != null) restore(anchor)
            }
        }
        scrollOffset = scrollOffset.coerceIn(0.0, maxScrollOffset)
        lastFrame = lanes.flatMapIndexed { column, lane ->
            lane.window(scrollOffset, height).map { slot ->
                GalleryPlacement(
                    GalleryOccurrenceKey(column, slot.ordinal), slot.id, slot.shape,
                    column * (next.tileWidth + gap), slot.top - scrollOffset,
                    next.tileWidth, slot.height,
                )
            }
        }
        return lastFrame
    }

    private fun restore(anchor: GalleryAnchor) {
        val targetId = anchor.fileId ?: return
        for (lane in lanes) {
            val slot = lane.slots.firstOrNull { it.id == targetId } ?: continue
            scrollOffset = (slot.top + anchor.fraction * slot.height).coerceIn(0.0, maxScrollOffset)
            return
        }
    }

    private fun buildLanes(items: List<GalleryLayoutItem>, config: Configuration): List<Lane> {
        val assigned = List(config.columns) { mutableListOf<Slot>() }
        val extents = DoubleArray(config.columns)
        items.forEachIndexed { index, item ->
            val column = extents.indices.minBy { extents[it] }
            val top = extents[column]
            val ordinal = assigned[column].size.toLong()
            val ratio = masonryRatio(item, column, ordinal.toInt(), index)
            val height = config.tileWidth / ratio
            assigned[column] += Slot(ordinal, top, height, item.id, item.shape)
            extents[column] = top + height + config.gap
        }
        return assigned.map { slots -> Lane(slots) }
    }

    /**
     * 顶部齐平（y = 0）的前提下，让常规横屏视频在 16:9、4:3 与 5:4 三档画幅间错落居中裁切：
     * - 第一排按列交替不同高度（16:9 / 4:3 / 5:4 / 3:2），从第一行底沿起即形成无空白空洞的自然阶梯；
     * - 后续卡片结合文件 ID 的稳定特征与列内位置交替画幅，避免全 16:9 视频退化为死板的对齐网格；
     * - 竖屏与方形视频保留自身比例。
     */
    private fun masonryRatio(item: GalleryLayoutItem, column: Int, rowInColumn: Int, index: Int): Double {
        if (item.shape != GalleryShape.Landscape) return item.shape.ratio
        if (rowInColumn == 0) {
            return FIRST_ROW_LANDSCAPE_RATIOS[column % FIRST_ROW_LANDSCAPE_RATIOS.size]
        }
        val hash = item.id.fold(index * 31 + column * 17) { acc, ch -> acc * 31 + ch.code } and 0x7fffffff
        return LANDSCAPE_MASONRY_RATIOS[(hash.toLong() + rowInColumn.toLong()).mod(LANDSCAPE_MASONRY_RATIOS.size)]
    }

    private data class Configuration(val columns: Int, val width: Double, val height: Double, val gap: Double) {
        val tileWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(1.0)
    }

    private data class Slot(val ordinal: Long, val top: Double, val height: Double, val id: String, val shape: GalleryShape) {
        val bottom: Double get() = top + height
    }

    private class Lane(val slots: List<Slot>) {
        val bottom: Double get() = slots.lastOrNull()?.bottom ?: 0.0

        fun window(offset: Double, height: Double): List<Slot> {
            if (slots.isEmpty()) return emptyList()
            var low = 0
            var high = slots.lastIndex
            while (low < high) {
                val mid = (low + high) / 2
                if (slots[mid].bottom <= offset) low = mid + 1 else high = mid
            }
            val start = (low - 1).coerceAtLeast(0)
            val result = mutableListOf<Slot>()
            for (index in start..slots.lastIndex) {
                val slot = slots[index]
                result += slot
                if (slot.top >= offset + height) break
            }
            return result
        }
    }

    private companion object {
        val FIRST_ROW_LANDSCAPE_RATIOS = doubleArrayOf(
            16.0 / 9.0,
            4.0 / 3.0,
            5.0 / 4.0,
            3.0 / 2.0,
            16.0 / 10.0,
        )
        val LANDSCAPE_MASONRY_RATIOS = doubleArrayOf(
            16.0 / 9.0,
            4.0 / 3.0,
            16.0 / 9.0,
            5.0 / 4.0,
            3.0 / 2.0,
        )
    }
}
