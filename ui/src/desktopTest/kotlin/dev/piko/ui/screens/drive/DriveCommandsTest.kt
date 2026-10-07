package dev.piko.ui.screens.drive

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 命令栏只放位置命令：进不进多选，除了左端「新建」让给「已选 N 项」，别的一样不变，清空回收站这类主操作也在。
 * 多选时一旦按选中的几项摆东西（批量下载、移动、删除）或把主操作让出去，这里的对比就不相等。
 */
class DriveCommandsTest {
    private fun inputs(place: CommandPlace, selecting: Boolean) = CommandInputs(
        place = place,
        atRoot = place == CommandPlace.ROOT,
        selecting = selecting,
        clipboardFull = true,
        itemCount = 5,
        allSelected = false,
        typeCount = 3,
        filtering = false,
        feedSupported = true,
    )

    @Test
    fun `selection changes nothing on the bar but the create menu`() {
        for (place in CommandPlace.entries) {
            val idle = driveCommands(inputs(place, selecting = false))
            val selecting = driveCommands(inputs(place, selecting = true))
            assertEquals(idle.copy(create = false), selecting, "$place")
        }
    }
}
