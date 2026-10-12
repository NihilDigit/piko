package dev.piko.ui

import dev.piko.ui.screens.drive.taskSheetPageIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskSheetPageTest {
    @Test
    fun activeContextFollowsItsKeyAfterAnotherContextIsRemovedOrReordered() {
        val duplicate = "duplicate"
        val import = "import"

        assertEquals(1, taskSheetPageIndex(listOf(duplicate, import), import))
        assertEquals(0, taskSheetPageIndex(listOf(import), import))
        assertEquals(0, taskSheetPageIndex(listOf(import, duplicate), import))
    }

    @Test
    fun removedActiveContextFallsBackToRemainingContext() {
        assertEquals(0, taskSheetPageIndex(listOf("import"), "duplicate"))
        assertEquals(1, taskSheetPageIndex(listOf("duplicate", "import-two"), "import-two"))
        assertEquals(-1, taskSheetPageIndex(emptyList(), "duplicate"))
    }
}
