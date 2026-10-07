package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 桌面的 Ctrl（⌘）点选、Shift 点选与框选：Shift 按眼前的顺序选一段，起点是最近一次单独点选的那一项。 */
class DriveSelectionSmokeTest {

    @Test
    fun `ctrl click toggles and shift click selects the run from the last toggled item`() = smoke { scope ->
        val server = FakePikPakServer()
        listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt").forEach { server.addFile(it) }
        val prefs = MemoryPreferences()
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 5 }
        val ids = drive.displayedFiles.map { it.id }

        drive.toggleSelected(ids[1])
        assertTrue(drive.isSelectionMode)
        drive.selectRange(ids[3])
        assertEquals(ids.subList(1, 4).toSet(), drive.selectedFileIds.toSet())

        // 起点不随 Shift 点选移动：从同一个起点往回收，只会加，不会把已选的减掉
        drive.selectRange(ids[0])
        assertEquals(ids.subList(0, 4).toSet(), drive.selectedFileIds.toSet())

        // Ctrl 点选换了起点
        drive.toggleSelected(ids[2])
        assertFalse(ids[2] in drive.selectedFileIds)
        drive.selectRange(ids[4])
        assertEquals(ids.toSet(), drive.selectedFileIds.toSet())

        // 逐个取消到一项不剩，退出多选
        ids.forEach { drive.toggleSelected(it) }
        assertFalse(drive.isSelectionMode)
    }

    @Test
    fun `shift click without an anchor selects just that item`() = smoke { scope ->
        val server = FakePikPakServer()
        listOf("a.txt", "b.txt", "c.txt").forEach { server.addFile(it) }
        val prefs = MemoryPreferences()
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 3 }
        val ids = drive.displayedFiles.map { it.id }

        drive.selectRange(ids[2])
        assertEquals(setOf(ids[2]), drive.selectedFileIds.toSet())
        drive.selectRange(ids[0])
        assertEquals(ids.toSet(), drive.selectedFileIds.toSet())
    }

    @Test
    fun `select all enters selection like box selection, long press keeps an emptied selection open`() = smoke { scope ->
        val server = FakePikPakServer()
        listOf("a.txt", "b.txt", "c.txt").forEach { server.addFile(it) }
        val prefs = MemoryPreferences()
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 3 }

        // 桌面 Ctrl+A、命令栏、命令面板与空白处右键的全选都走这里，选上了就要在多选里
        drive.toggleSelectAll()
        assertTrue(drive.isSelectionMode)
        // 换筛选清掉选中：不在多选里的选中会漏过去，「移动所选」动到看不见的文件
        drive.updateTypeFilter(null)
        assertTrue(drive.selectedFileIds.isEmpty())

        drive.toggleSelectAll()
        drive.toggleSelectAll()
        assertFalse(drive.isSelectionMode)

        // 移动端长按进来的，取消到一项不剩仍在多选里，由顶栏的 ✕ 退出
        drive.enterSelection(drive.displayedFiles.first().id)
        drive.toggleSelectAll()
        drive.toggleSelectAll()
        assertTrue(drive.isSelectionMode)
    }

    @Test
    fun `box selection replaces the selection unless it started with a modifier`() = smoke { scope ->
        val server = FakePikPakServer()
        listOf("a.txt", "b.txt", "c.txt", "d.txt").forEach { server.addFile(it) }
        val prefs = MemoryPreferences()
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 4 }
        val ids = drive.displayedFiles.map { it.id }

        drive.toggleSelected(ids[0])
        // 不按修饰键开始框选：原来的选择作废，框住几项就是几项；框缩回来，缩出去的那项取消
        drive.selectBoxed(emptySet(), listOf(ids[1], ids[2]))
        assertEquals(setOf(ids[1], ids[2]), drive.selectedFileIds.toSet())
        drive.selectBoxed(emptySet(), listOf(ids[1]))
        assertEquals(setOf(ids[1]), drive.selectedFileIds.toSet())

        // 按着修饰键开始：保留按下时已选的
        val base = drive.selectedFileIds.toSet()
        drive.selectBoxed(base, listOf(ids[3]))
        assertEquals(setOf(ids[1], ids[3]), drive.selectedFileIds.toSet())

        // 什么也没框住，也没有要保留的，退出多选
        drive.selectBoxed(emptySet(), emptyList())
        assertFalse(drive.isSelectionMode)
    }
}
