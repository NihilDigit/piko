package dev.piko.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdaptiveBarEvictionTest {
    private fun item(key: String, priority: Int, overflow: Boolean = true, divider: Boolean = false) = BarItem(
        key, priority,
        if (overflow) listOf(SheetAction(Icons.Outlined.Search, key, {})) else emptyList(),
        isDivider = divider,
    ) {}

    @Test fun ordinaryItemsFoldByPriorityThenFromTheRight() {
        val items = listOf(item("low", 10), item("high", 20), item("rightLow", 10), item("pinned", PinnedPriority))
        val shown = BooleanArray(items.size) { true }
        assertEquals(2, barItemToEvict(items, shown, allowPinned = false))
        shown[2] = false
        assertEquals(0, barItemToEvict(items, shown, allowPinned = false))
        shown[0] = false
        assertEquals(1, barItemToEvict(items, shown, allowPinned = false))
        shown[1] = false
        assertNull(barItemToEvict(items, shown, allowPinned = false))
        assertEquals(3, barItemToEvict(items, shown, allowPinned = true))
    }

    @Test fun pinnedFallbackRetainsTitlesAndDividersAndUsesRightmostAlternative() {
        val items = listOf(
            item("new", PinnedPriority),
            item("title", PinnedPriority, overflow = false),
            item("view", PinnedPriority),
            item("divider", PinnedPriority, divider = true),
        )
        val shown = BooleanArray(items.size) { true }
        assertEquals(2, barItemToEvict(items, shown, allowPinned = true))
        shown[2] = false
        assertEquals(0, barItemToEvict(items, shown, allowPinned = true))
        shown[0] = false
        assertNull(barItemToEvict(items, shown, allowPinned = true))
    }
}
