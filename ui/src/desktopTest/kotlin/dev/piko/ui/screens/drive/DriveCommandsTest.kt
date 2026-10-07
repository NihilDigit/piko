package dev.piko.ui.screens.drive

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 命令栏只放位置命令：进不进多选，除了左端「新建」让给「已选 N 项」，别的一样不变，清空回收站这类主操作也在。
 * 多选时一旦按选中的几项摆东西（批量下载、移动、删除）或把主操作让出去，这里的对比就不相等。
 */
class DriveCommandsTest {
    private fun inputs(place: CommandPlace, selecting: Boolean, nameParsing: Boolean = true) = CommandInputs(
        place = place,
        atRoot = place == CommandPlace.ROOT,
        selecting = selecting,
        clipboardFull = true,
        itemCount = 5,
        allSelected = false,
        typeCount = 3,
        filtering = false,
        feedSupported = true,
        nameParsing = nameParsing,
    )

    /**
     * 查找重复与按番号规范命名作用于眼前这个目录的子树，移动端只在列表页眉的菜单里有。搜索结果、库、回收站、
     * 压缩包里没有一个能扫的目录，给了就扫到别处去；关了文件名解析认不出番号，规范命名扫出来必然是空的。
     */
    @Test
    fun `place commands only in a real folder and naming follows the parser switch`() {
        val folders = setOf(CommandPlace.ROOT, CommandPlace.FOLDER)
        for (place in CommandPlace.entries) {
            val on = driveCommands(inputs(place, selecting = false))
            assertEquals(place in folders, on.findDuplicates, "$place")
            assertEquals(place in folders, on.canonicalNaming, "$place")
            assertEquals(false, driveCommands(inputs(place, selecting = false, nameParsing = false)).canonicalNaming, "$place")
        }
    }

    @Test
    fun `selection changes nothing on the bar but the create menu`() {
        for (place in CommandPlace.entries) {
            val idle = driveCommands(inputs(place, selecting = false))
            val selecting = driveCommands(inputs(place, selecting = true))
            assertEquals(idle.copy(create = false), selecting, "$place")
        }
    }
}
