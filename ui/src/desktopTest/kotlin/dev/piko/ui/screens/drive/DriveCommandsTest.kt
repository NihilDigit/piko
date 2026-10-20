package dev.piko.ui.screens.drive

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveCommandsTest {
    private fun inputs(place: CommandPlace, selecting: Boolean, nameParsing: Boolean = true, selectedVideoCount: Int = 0, feedSupported: Boolean = true) = CommandInputs(
        place = place,
        atRoot = place == CommandPlace.ROOT,
        selecting = selecting,
        clipboardFull = true,
        itemCount = 5,
        allSelected = false,
        typeCount = 3,
        filtering = false,
        feedSupported = feedSupported,
        selectedVideoCount = selectedVideoCount,
        nameParsing = nameParsing,
    )

    /**
     * 查找重复与按番号规范命名作用于眼前这个目录的子树，窄窗口统一放在浏览顶栏的「更多」里。搜索结果、库、回收站、
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
    fun `selection swaps create for select all and needs videos for feed`() {
        for (place in CommandPlace.entries) {
            val idle = driveCommands(inputs(place, selecting = false))
            val selecting = driveCommands(inputs(place, selecting = true))
            assertEquals(false, idle.selectAll, "$place")
            assertEquals(idle.copy(create = false, selectAll = selecting.selectAll, feed = false), selecting, "$place")
        }
    }
    @Test
    fun `selected videos open a fixed feed from browsable places`() {
        for (place in CommandPlace.entries) {
            val selected = inputs(place, selecting = true, selectedVideoCount = 2)
            assertEquals(place != CommandPlace.TRASH && place != CommandPlace.ARCHIVE,
                driveCommands(selected).feed, "$place")
            assertEquals(false, driveCommands(inputs(place, selecting = true, selectedVideoCount = 2, feedSupported = false)).feed)
            assertEquals(false, driveCommands(inputs(place, selecting = true)).feed)
        }
    }

    @Test
    fun `empty folders retain paste when the clipboard has files`() {
        for (place in CommandPlace.entries) {
            for (clipboardFull in listOf(false, true)) {
                val commands = driveCommands(CommandInputs(
                    place = place,
                    atRoot = place == CommandPlace.ROOT,
                    selecting = false,
                    clipboardFull = clipboardFull,
                    itemCount = 0,
                    allSelected = false,
                    typeCount = 0,
                    filtering = false,
                    feedSupported = false,
                    nameParsing = true,
                ))
                assertEquals(clipboardFull && place in setOf(CommandPlace.ROOT, CommandPlace.FOLDER), commands.paste, "$place")
                assertEquals(false, commands.findDuplicates, "$place")
                assertEquals(false, commands.canonicalNaming, "$place")
                assertEquals(false, commands.emptyPlace, "$place")
            }
        }
    }

}
