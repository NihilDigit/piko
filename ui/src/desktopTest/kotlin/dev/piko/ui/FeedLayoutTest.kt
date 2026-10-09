package dev.piko.ui

import androidx.compose.ui.unit.dp
import dev.piko.ui.workbench.TaskSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedLayoutTest {
    private fun fits(width: Int?, height: Int?, desktop: Boolean = false) =
        feedPanelFits(width?.dp, height?.dp, desktop, 360.dp)

    @Test
    fun `mobile split needs both content dimensions including exact boundaries`() {
        assertFalse(fits(null, null))
        assertFalse(fits(840, null))
        assertFalse(fits(839, 800))
        assertFalse(fits(1200, 479))
        assertTrue(fits(840, 480))
        assertTrue(fits(1100, 800))
        // 840dp 的窗口还要扣除 Rail，不能把窗口宽度直接当作内容宽度。
        assertFalse(fits(840 - 96, 800))
        assertFalse(fits(412, 900))
    }

    @Test
    fun `desktop keeps its width only rule`() {
        assertTrue(fits(840, 300, desktop = true))
        assertFalse(fits(839, 900, desktop = true))
        assertFalse(fits(null, 900, desktop = true))
    }

    @Test
    fun `resizing switches presentation without changing the feed task`() {
        assertEquals(listOf(true, false, true, false),
            listOf(1100 to 800, 700 to 800, 1100 to 800, 1100 to 400).map { (w, h) -> fits(w, h) })
        for (desktop in listOf(false, true)) {
            for (suspended in listOf(false, true)) {
                assertFalse(feedTaskActive(desktop, shown = false, suspended = suspended))
                assertEquals(!desktop || suspended, feedTaskActive(desktop, shown = true, suspended = suspended))
            }
        }
    }

    @Test
    fun `mobile active and suspended feed require confirmation before another task`() {
        for (suspended in listOf(false, true)) {
            for (next in listOf(TaskSlot.Task.ADD_LINK, TaskSlot.Task.DUPLICATES)) {
                var shown = true
                var starts = 0
                var closes = 0
                val slot = TaskSlot().apply {
                    exclusive = true
                    occupants = listOf(TaskSlot.Occupant(TaskSlot.Task.FEED, "信息流", "队列关闭", {
                        feedTaskActive(desktop = false, shown = shown, suspended = suspended)
                    }, { shown = false; closes++ }))
                }
                fun claim() = slot.claim("打开任务", "打开", next) { starts++ }
                claim()
                assertEquals(0, starts)
                slot.dismiss()
                assertTrue(shown)
                assertEquals(0, closes)
                claim()
                slot.confirm()
                assertFalse(shown)
                assertEquals(1, closes)
                assertEquals(1, starts)
            }
        }
    }

    @Test
    fun `feed opening still confirms before ending mobile add link or duplicates`() {
        for (task in listOf(TaskSlot.Task.ADD_LINK, TaskSlot.Task.DUPLICATES)) {
            val events = mutableListOf<String>()
            val slot = TaskSlot().apply {
                exclusive = true
                occupants = listOf(TaskSlot.Occupant(task, "原任务", "未保存内容", { true }, { events += "end" }))
            }
            slot.claim("打开信息流", "打开", TaskSlot.Task.FEED) { events += "start" }
            assertTrue(events.isEmpty())
            slot.confirm()
            assertEquals(listOf("end", "start"), events)
        }
    }
}
