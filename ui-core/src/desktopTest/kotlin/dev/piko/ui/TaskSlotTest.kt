package dev.piko.ui

import dev.piko.ui.workbench.TaskSlot
import dev.piko.shared.presentation.ActionAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskSlotTest {
    @Test
    fun replacementIsBlockedBeforeConfirmationAndCheckedAgainWhenConfirming() {
        val slot = TaskSlot().apply { exclusive = true }
        var availability: ActionAvailability = ActionAvailability.Blocked("正在保存")
        var ended = 0
        var started = false
        slot.occupants = listOf(TaskSlot.Occupant(
            TaskSlot.Task.ADD_LINK, "添加链接", "草稿将被丢弃", { true }, { ended++ }, { availability },
        ))
        slot.claim("打开信息流", "打开", TaskSlot.Task.FEED) { started = true }
        assertNotNull(slot.blocked)
        assertNull(slot.pending)
        assertEquals(0, ended)
        assertEquals(false, started)

        availability = ActionAvailability.Available
        slot.claim("打开信息流", "打开", TaskSlot.Task.FEED) { started = true }
        assertNotNull(slot.pending)
        availability = ActionAvailability.Blocked("保存刚刚开始")
        slot.confirm()
        assertEquals("保存刚刚开始", slot.blocked?.reason)
        assertEquals(0, ended)
        assertEquals(false, started)
    }

    @Test
    fun duplicateResultsSurviveOpeningAnotherTask() {
        val slot = TaskSlot().apply { exclusive = true }
        var ended = 0
        var started = false
        slot.occupants = listOf(TaskSlot.Occupant(TaskSlot.Task.DUPLICATES, "查找重复", "结果将被丢弃", { true }, { ended++ }))
        slot.claim("添加链接", "添加", TaskSlot.Task.ADD_LINK) { started = true }
        assertTrue(started)
        assertEquals(0, ended)
        assertNull(slot.pending)
    }

    @Test
    fun replacingTheScanRootStillRequiresExplicitConfirmation() {
        val slot = TaskSlot().apply { exclusive = true }
        var ended = 0
        var started = false
        slot.occupants = listOf(TaskSlot.Occupant(TaskSlot.Task.DUPLICATES, "查找重复", "结果将被丢弃", { true }, { ended++ }))
        slot.claim("重新查找", "查找", TaskSlot.Task.DUPLICATES, replacesSame = true) { started = true }
        assertNotNull(slot.pending)
        assertEquals(0, ended)
        slot.confirm()
        assertTrue(started)
        assertEquals(1, ended)
    }

    @Test
    fun desktopReplacementAlsoPreservesTheReportUntilConfirmation() {
        val slot = TaskSlot()
        var ended = 0
        var started = false
        slot.occupants = listOf(TaskSlot.Occupant(TaskSlot.Task.DUPLICATES, "查找重复", "结果将被丢弃", { true }, { ended++ }))
        slot.claim("查找其他目录", "查找", TaskSlot.Task.DUPLICATES, replacesSame = true) { started = true }
        assertNotNull(slot.pending)
        assertEquals(false, started)
        slot.dismiss()
        assertEquals(0, ended)
        assertEquals(false, started)
        slot.claim("查找其他目录", "查找", TaskSlot.Task.DUPLICATES, replacesSame = true) { started = true }
        slot.confirm()
        assertEquals(1, ended)
        assertTrue(started)
    }
}
