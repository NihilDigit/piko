package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 桌面的 Ctrl（⌘）点选与 Shift 点选：按眼前的顺序选一段，起点是最近一次单独点选的那一项。 */
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
}
