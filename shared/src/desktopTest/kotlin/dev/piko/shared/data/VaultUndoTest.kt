package dev.piko.shared.data

import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.smoke
import dev.piko.shared.smoke.awaitUntil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultUndoTest {
    @Test
    fun `undo after an uncertain deletion does not duplicate an original that remains`() = smoke {
        val server = FakePikPakServer()
        val folder = server.addFolder("Dramas")
        server.addFile("one.mkv", folder.id, hash = "GCIDONE", content = byteArrayOf(1, 2, 3))
        val drive = PikoDriveRepository(server.provider(), MemoryPreferences())
        val entry = VaultEntry.create("one.mkv", 3, "GCIDONE", "source", 0)
        val completed = async(start = CoroutineStart.UNDISPATCHED) { drive.changes.events.first { it.change == null } }
        val change = DriveChangeJournal.Change.Vault(emptyMap(), "归档", recreateOnRevert = mapOf(folder.id to listOf(entry)))
        drive.changes.record(change)
        awaitUntil("撤销记录可用") { drive.changes.latest.value === change }
        drive.changes.undo(change)
        assertEquals("已撤销", completed.await().message)
        assertEquals(0, server.instantCreates.get())
        assertEquals(listOf("one.mkv"), server.tree(folder.id))
    }
}
