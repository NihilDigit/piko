package dev.piko.ui.screens.space

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometric Squarified Treemap layout (Bruls, Huizing, van Wijk, 2000).
 *
 * Kept free of Compose UI runtime types so layout invariants (area conservation, non-overlap,
 * aspect-ratio convergence, and long-tail aggregation) can be verified in fast JVM unit tests.
 */
data class TreemapInput<out T>(
    val id: String,
    val bytes: Long,
    val payload: T,
)

data class TreemapRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
    val area: Double get() = width * height

    fun aspectRatio(): Double {
        if (width <= 0.0 || height <= 0.0) return Double.POSITIVE_INFINITY
        return max(width / height, height / width)
    }

    fun contains(x: Double, y: Double): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun inset(horizontal: Double, vertical: Double = horizontal): TreemapRect {
        val nextWidth = (width - horizontal * 2.0).coerceAtLeast(0.0)
        val nextHeight = (height - vertical * 2.0).coerceAtLeast(0.0)
        return TreemapRect(
            left = left + (width - nextWidth) * 0.5,
            top = top + (height - nextHeight) * 0.5,
            width = nextWidth,
            height = nextHeight,
        )
    }
}

sealed interface TreemapTileContent<out T> {
    data class Single<out T>(val item: TreemapInput<T>) : TreemapTileContent<T>
    data class OtherGroup<out T>(val items: List<TreemapInput<T>>) : TreemapTileContent<T>
}

data class TreemapTile<out T>(
    val id: String,
    val bytes: Long,
    val bounds: TreemapRect,
    val content: TreemapTileContent<T>,
)

const val TREEMAP_OTHER_GROUP_ID = "__treemap_other__"

/**
 * Partitions [container] into non-overlapping rectangles proportional to item [TreemapInput.bytes]
 * using the Bruls-Huizing-van Wijk squarified treemap algorithm.
 *
 * Zero-byte or negative items are filtered out. When [enableGrouping] is true and at least two
 * trailing items fall below the readable area threshold (`minTileWidth * minTileHeight`), they are
 * collapsed into a single [TreemapTileContent.OtherGroup] tile so small files never collapse into
 * unreadable 1px slivers.
 */
fun <T> layoutTreemap(
    inputs: List<TreemapInput<T>>,
    container: TreemapRect,
    minTileWidth: Double = 36.0,
    minTileHeight: Double = 28.0,
    enableGrouping: Boolean = true,
    sliverSide: Double = 10.0,
    maxOtherAreaFraction: Double = 0.20,
    otherGroupId: String = TREEMAP_OTHER_GROUP_ID,
): List<TreemapTile<T>> {
    if (!container.left.isFinite() || !container.top.isFinite() ||
        !container.width.isFinite() || !container.height.isFinite() ||
        container.width <= 0.0 || container.height <= 0.0
    ) {
        return emptyList()
    }

    val positive = inputs
        .filter { it.bytes > 0L }
        .sortedByDescending { it.bytes }
    if (positive.isEmpty()) return emptyList()

    val totalBytes = positive.sumOf { it.bytes.toDouble() }
    if (!totalBytes.isFinite() || totalBytes <= 0.0) return emptyList()

    val totalArea = container.area
    if (!totalArea.isFinite() || totalArea <= 0.0) return emptyList()

    val prepared = prepareEntries(
        sorted = positive,
        totalBytes = totalBytes,
        totalArea = totalArea,
        minTileWidth = minTileWidth,
        minTileHeight = minTileHeight,
        enableGrouping = enableGrouping,
        sliverSide = sliverSide,
        maxOtherAreaFraction = maxOtherAreaFraction,
        otherGroupId = otherGroupId,
    )
    if (prepared.isEmpty()) return emptyList()

    val result = ArrayList<TreemapTile<T>>(prepared.size)
    var remainingRect = container
    var remainingWeight = prepared.sumOf { it.weight }
    val currentRow = ArrayList<PreparedEntry<T>>()

    for (entry in prepared) {
        if (currentRow.isEmpty()) {
            currentRow.add(entry)
            continue
        }
        val shortSide = min(remainingRect.width, remainingRect.height)
        if (shortSide <= 0.0 || remainingWeight <= 0.0 || remainingRect.area <= 0.0) {
            currentRow.add(entry)
            continue
        }
        val areaScale = remainingRect.area / remainingWeight
        val currentWorst = worstAspectRatio(currentRow, null, areaScale, shortSide)
        val candidateWorst = worstAspectRatio(currentRow, entry, areaScale, shortSide)
        if (candidateWorst <= currentWorst) {
            currentRow.add(entry)
        } else {
            val rowWeight = currentRow.sumOf { it.weight }
            remainingRect = placeRow(
                row = currentRow,
                rowWeight = rowWeight,
                remainingWeight = remainingWeight,
                rect = remainingRect,
                isLastRow = false,
                out = result,
            )
            remainingWeight = (remainingWeight - rowWeight).coerceAtLeast(0.0)
            currentRow.clear()
            currentRow.add(entry)
        }
    }

    if (currentRow.isNotEmpty()) {
        val rowWeight = currentRow.sumOf { it.weight }
        placeRow(
            row = currentRow,
            rowWeight = rowWeight,
            remainingWeight = remainingWeight,
            rect = remainingRect,
            isLastRow = true,
            out = result,
        )
    }

    return result
}

/**
 * Computes the inner preview rectangle for one level of nested children inside a folder tile,
 * reserving [headerHeight] at the top for the folder name and size label.
 * Returns null if the folder tile is smaller than [minContainerWidth] x [minContainerHeight].
 */
fun nestedPreviewBounds(
    tileBounds: TreemapRect,
    minContainerWidth: Double = 88.0,
    minContainerHeight: Double = 88.0,
    headerHeight: Double = 28.0,
    padding: Double = 6.0,
): TreemapRect? {
    if (tileBounds.width < minContainerWidth || tileBounds.height < minContainerHeight) return null
    val innerWidth = tileBounds.width - padding * 2.0
    val innerHeight = tileBounds.height - headerHeight - padding
    if (!innerWidth.isFinite() || !innerHeight.isFinite() || innerWidth <= 4.0 || innerHeight <= 4.0) {
        return null
    }
    return TreemapRect(
        left = tileBounds.left + padding,
        top = tileBounds.top + headerHeight,
        width = innerWidth,
        height = innerHeight,
    )
}

private class PreparedEntry<out T>(
    val id: String,
    val bytes: Long,
    val weight: Double,
    val content: TreemapTileContent<T>,
)

private fun <T> prepareEntries(
    sorted: List<TreemapInput<T>>,
    totalBytes: Double,
    totalArea: Double,
    minTileWidth: Double,
    minTileHeight: Double,
    enableGrouping: Boolean,
    sliverSide: Double,
    maxOtherAreaFraction: Double,
    otherGroupId: String,
): List<PreparedEntry<T>> {
    val minReadableArea = minTileWidth.coerceAtLeast(0.0) * minTileHeight.coerceAtLeast(0.0)
    if (!enableGrouping || minReadableArea <= 0.0 || sorted.size < 2) {
        return sorted.map { item ->
            PreparedEntry(
                id = item.id,
                bytes = item.bytes,
                weight = item.bytes.toDouble(),
                content = TreemapTileContent.Single(item),
            )
        }
    }

    val sliverArea = sliverSide.coerceAtLeast(0.0) * sliverSide.coerceAtLeast(0.0)
    val maxGroupArea = max(minReadableArea * 3.0, totalArea * maxOtherAreaFraction.coerceIn(0.0, 1.0))

    var groupedCount = 0
    var groupedArea = 0.0
    for (index in sorted.lastIndex downTo 0) {
        val item = sorted[index]
        val expectedArea = (item.bytes.toDouble() / totalBytes) * totalArea
        if (expectedArea >= minReadableArea) break
        if (expectedArea < sliverArea) {
            groupedCount++
            groupedArea += expectedArea
        } else {
            // Keep at least the largest item ungrouped unless every item is a sub-sliver dot.
            if (index == 0) break
            if (groupedArea + expectedArea > maxGroupArea) break
            groupedCount++
            groupedArea += expectedArea
        }
    }

    if (groupedCount < 2) {
        return sorted.map { item ->
            PreparedEntry(
                id = item.id,
                bytes = item.bytes,
                weight = item.bytes.toDouble(),
                content = TreemapTileContent.Single(item),
            )
        }
    }

    val splitIndex = sorted.size - groupedCount
    val result = ArrayList<PreparedEntry<T>>(splitIndex + 1)
    for (i in 0 until splitIndex) {
        val item = sorted[i]
        result.add(
            PreparedEntry(
                id = item.id,
                bytes = item.bytes,
                weight = item.bytes.toDouble(),
                content = TreemapTileContent.Single(item),
            ),
        )
    }
    val tail = sorted.subList(splitIndex, sorted.size).toList()
    val tailWeight = tail.sumOf { it.bytes.toDouble() }
    val tailBytes = saturatedSum(tail)
    result.add(
        PreparedEntry(
            id = otherGroupId,
            bytes = tailBytes,
            weight = tailWeight,
            content = TreemapTileContent.OtherGroup(tail),
        ),
    )
    return result
}

private fun <T> saturatedSum(items: List<TreemapInput<T>>): Long {
    var sum = 0L
    for (item in items) {
        val next = sum + item.bytes
        if (next < sum) return Long.MAX_VALUE
        sum = next
    }
    return sum
}

private fun <T> worstAspectRatio(
    row: List<PreparedEntry<T>>,
    extra: PreparedEntry<T>?,
    areaScale: Double,
    shortSide: Double,
): Double {
    if (shortSide <= 0.0 || areaScale <= 0.0) return Double.POSITIVE_INFINITY
    var sumArea = 0.0
    var minArea = Double.POSITIVE_INFINITY
    var maxArea = 0.0
    for (entry in row) {
        val area = entry.weight * areaScale
        if (area <= 0.0) continue
        sumArea += area
        if (area < minArea) minArea = area
        if (area > maxArea) maxArea = area
    }
    if (extra != null) {
        val area = extra.weight * areaScale
        if (area > 0.0) {
            sumArea += area
            if (area < minArea) minArea = area
            if (area > maxArea) maxArea = area
        }
    }
    if (sumArea <= 0.0 || minArea <= 0.0 || !minArea.isFinite()) return Double.POSITIVE_INFINITY
    val sideSquared = shortSide * shortSide
    val sumSquared = sumArea * sumArea
    return max(
        (sideSquared * maxArea) / sumSquared,
        sumSquared / (sideSquared * minArea),
    )
}

private fun <T> placeRow(
    row: List<PreparedEntry<T>>,
    rowWeight: Double,
    remainingWeight: Double,
    rect: TreemapRect,
    isLastRow: Boolean,
    out: MutableList<TreemapTile<T>>,
): TreemapRect {
    if (row.isEmpty()) return rect
    val fraction = if (isLastRow || remainingWeight <= 0.0) {
        1.0
    } else {
        (rowWeight / remainingWeight).coerceIn(0.0, 1.0)
    }

    return if (rect.width >= rect.height) {
        // Lay out a vertical strip on the left edge of rect.
        val stripRight = if (isLastRow) rect.right else rect.left + rect.width * fraction
        val stripWidth = (stripRight - rect.left).coerceAtLeast(0.0)
        var cursorY = rect.top
        for (i in row.indices) {
            val entry = row[i]
            val itemBottom = if (i == row.lastIndex || rowWeight <= 0.0) {
                rect.bottom
            } else {
                (cursorY + rect.height * (entry.weight / rowWeight)).coerceAtMost(rect.bottom)
            }
            val itemHeight = (itemBottom - cursorY).coerceAtLeast(0.0)
            out.add(
                TreemapTile(
                    id = entry.id,
                    bytes = entry.bytes,
                    bounds = TreemapRect(rect.left, cursorY, stripWidth, itemHeight),
                    content = entry.content,
                ),
            )
            cursorY = itemBottom
        }
        TreemapRect(
            left = stripRight,
            top = rect.top,
            width = (rect.right - stripRight).coerceAtLeast(0.0),
            height = rect.height,
        )
    } else {
        // Lay out a horizontal strip on the top edge of rect.
        val stripBottom = if (isLastRow) rect.bottom else rect.top + rect.height * fraction
        val stripHeight = (stripBottom - rect.top).coerceAtLeast(0.0)
        var cursorX = rect.left
        for (i in row.indices) {
            val entry = row[i]
            val itemRight = if (i == row.lastIndex || rowWeight <= 0.0) {
                rect.right
            } else {
                (cursorX + rect.width * (entry.weight / rowWeight)).coerceAtMost(rect.right)
            }
            val itemWidth = (itemRight - cursorX).coerceAtLeast(0.0)
            out.add(
                TreemapTile(
                    id = entry.id,
                    bytes = entry.bytes,
                    bounds = TreemapRect(cursorX, rect.top, itemWidth, stripHeight),
                    content = entry.content,
                ),
            )
            cursorX = itemRight
        }
        TreemapRect(
            left = rect.left,
            top = stripBottom,
            width = rect.width,
            height = (rect.bottom - stripBottom).coerceAtLeast(0.0),
        )
    }
}
