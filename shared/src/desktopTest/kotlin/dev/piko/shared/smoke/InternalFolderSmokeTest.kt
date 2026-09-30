package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalFolderSmokeTest {
    @Test
    fun `internal folders disappear from browsing tree and search but raw cleanup still sees them`() = smoke {
        val server = FakePikPakServer()
        val temp = server.addFolder("Piko-Temp")
        server.addFolder(".piko")
        server.addFile("sample.mkv", temp.id, hash = "TEMP", content = byteArrayOf(1))
        val dramas = server.addFolder("Dramas")
        server.addFolder("Piko-Temp", dramas.id)
        server.addFile("sample.mkv", dramas.id, hash = "REAL", content = byteArrayOf(2))
        val drive = PikoDriveRepository(server.provider(), MemoryPreferences())
        assertEquals(listOf("Dramas"), drive.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow().map { it.name })
        assertEquals(listOf("Dramas"), drive.subfolders("").getOrThrow().map { it.name })
        assertTrue(drive.listAllFiles().getOrThrow().any { it.id == temp.id }, "临时目录清理仍需要原始列表")
        assertTrue(drive.listBrowsable(dramas.id, PikoFileSortOrder.NAME_ASC).getOrThrow().any { it.name == "Piko-Temp" }, "用户自己的同名子目录不得隐藏")
        val hits = drive.searchRecursive("sample").toList()
        assertEquals(listOf(dramas.id), hits.map { it.file.parentId })
    }
}
