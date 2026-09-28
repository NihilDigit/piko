package dev.piko.shared.smoke

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.DriveScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 移动、移入回收站与重命名都能撤销：反向再做一次，回到原来的样子。 */
class DriveUndoSmokeTest {

    @Test
    fun `move, trash and rename are undone in reverse order`() = smoke { scope ->
        val server = FakePikPakServer()
        val target = server.addFolder("目标")
        val a = server.addFile("a.txt")
        val b = server.addFile("b.txt")
        val prefs = MemoryPreferences()
        val repository = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repository, prefs, scope)
        drive.load()
        awaitUntil("网盘列表加载完成") { !drive.isLoading && drive.displayedFiles.size == 3 }

        drive.move(listOf(a.id, b.id), target.id, target.name)
        awaitUntil("移动完成") { a.parentId == target.id && b.parentId == target.id }
        awaitUntil("列表刷新") { drive.displayedFiles.size == 1 }
        awaitUntil("移动记下") { repository.changes.latest.value is DriveChangeJournal.Change.Move }

        drive.moveToTrash(listOf(target.id))
        awaitUntil("移入回收站") { target.trashed }
        // 服务端先变，改动随后才记进日志：只等服务端就撤，慢的机器上撤掉的是上一次的移动
        awaitUntil("移入回收站记下") { repository.changes.latest.value is DriveChangeJournal.Change.Trash }

        // 最近的先撤：先从回收站恢复，再把两个文件移回根目录
        assertTrue(drive.undoLast())
        awaitUntil("从回收站恢复") { !target.trashed }
        assertTrue(drive.undoLast())
        awaitUntil("移回原处") { a.parentId == "" && b.parentId == "" }

        drive.rename(a.id, "c.txt")
        awaitUntil("重命名") { a.name == "c.txt" }
        awaitUntil("重命名记下") { repository.changes.latest.value is DriveChangeJournal.Change.Rename }
        assertTrue(drive.undoLast())
        awaitUntil("改回原名") { a.name == "a.txt" }
        assertEquals("", a.parentId)

        // 都撤完了
        assertFalse(drive.undoLast())
    }
}
