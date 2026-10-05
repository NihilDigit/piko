package dev.piko.shared.smoke

import dev.piko.shared.data.ArchivePasswordVault
import dev.piko.shared.data.LeasedFile
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.ArchiveBrowser
import dev.piko.shared.state.DriveScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * 压缩包当文件夹：进包、逐层列、要密码，以及从没解压过的包里打开一个文件。
 * 后者经一次引导解压让服务端建出包内文件树，解压出来的东西随即删掉，见 ArchiveRepository.prime。
 */
class ArchiveBrowseSmokeTest {

    private class Setup(val server: FakePikPakServer, val drive: DriveScreenState, val scratchId: String, val prefs: MemoryPreferences)

    private fun setup(scope: CoroutineScope, build: FakePikPakServer.() -> Unit): Setup {
        val server = FakePikPakServer().apply(build)
        val scratch = server.addFolder("Piko-Temp")
        val prefs = MemoryPreferences()
        val browser = ArchiveBrowser(server.provider(), prefs, scratchFolder = { Result.success(scratch.id) })
        val drive = DriveScreenState(PikoDriveRepository(server.provider(), prefs), prefs, scope, archives = browser)
        return Setup(server, drive, scratch.id, prefs)
    }

    @Test
    fun `an archive opens like a folder and an entry of a fresh archive becomes leasable`() = smoke { scope ->
        val video = ByteArray(2048) { it.toByte() }
        val setup = setup(scope) {
            addArchive(
                "pack.zip",
                mapOf("readme.txt" to "hi".encodeToByteArray(), "season/ep01.mkv" to video, "season/ep02.mkv" to video.copyOf(1024)),
                // 第一次解压不出树，与线上常见的情形相同
                missedPrimes = 1,
            )
        }
        val server = setup.server
        val drive = setup.drive
        val scratchId = setup.scratchId
        drive.load()
        awaitUntil("根目录列出压缩包") { !drive.isLoading && drive.files.any { it.name == "pack.zip" } }

        drive.openArchive(drive.files.single { it.name == "pack.zip" })
        awaitUntil("进了压缩包") { drive.archiveView != null && !drive.isLoading && drive.files.size == 2 }
        assertEquals(listOf("season", "readme.txt"), drive.files.map { it.name })
        assertTrue(drive.isVirtualPlace)

        val season = drive.files.first { it.isFolder }
        drive.openFolder(season.id, season.name)
        awaitUntil("进了包里的子目录") { drive.archiveView?.path == "season/" && !drive.isLoading && drive.files.size == 2 }
        val episode = drive.files.first { it.name == "ep01.mkv" }
        assertNull(LeasedFile.resolvedFileOf(episode.id), "没解压过的包不给 gcid，还借不出来")

        val ready = assertNotNull(drive.prepareArchiveEntry(episode))
        val leased = assertNotNull(LeasedFile.resolvedFileOf(ready.id))
        assertEquals(server.entryGcid("season/ep01.mkv"), leased.gcid)
        assertEquals(video.size.toLong(), leased.size)
        assertEquals(scratchId, ready.parentId, "借出的对象放进 Piko-Temp")
        assertEquals(2, server.decompressCalls.get(), "第一次没出树，再解压一次")
        assertTrue(server.children(scratchId).isEmpty(), "引导解压出来的东西都删掉了")
        // 这一层换成了重列过的一份，播放列表里的下一集也带着 gcid
        assertTrue(drive.files.filterNot { it.isFolder }.all { LeasedFile.resolvedFileOf(it.id) != null })

        // 同一个包不再引导
        drive.prepareArchiveEntry(drive.files.first { it.name == "ep02.mkv" })
        assertEquals(2, server.decompressCalls.get())

        // 在包里改不了任何东西
        drive.moveToTrash(listOf(ready.id))
        assertTrue(drive.files.any { it.name == "ep01.mkv" })
    }

    @Test
    fun `an encrypted archive asks for a password and remembers the one that worked`() = smoke { scope ->
        val setup = setup(scope) {
            addArchive("secret.7z", mapOf("a.txt" to ByteArray(3)), password = "open-sesame")
        }
        val drive = setup.drive
        drive.load()
        awaitUntil("根目录列出压缩包") { !drive.isLoading && drive.files.any { it.name == "secret.7z" } }

        drive.openArchive(drive.files.single { it.name == "secret.7z" })
        awaitUntil("要密码") { drive.archivePasswordRequest != null }
        assertEquals("secret.7z", drive.archivePasswordRequest!!.archiveName)
        assertTrue(drive.files.isEmpty(), "没列出来的包不显示上一个目录的内容")

        drive.submitArchivePassword("wrong")
        awaitUntil("密码错误") { drive.archivePasswordRequest?.incorrect == true }

        drive.submitArchivePassword("open-sesame")
        awaitUntil("列出了包里的文件") { drive.archivePasswordRequest == null && drive.files.map { it.name } == listOf("a.txt") }
        assertEquals(listOf("open-sesame"), ArchivePasswordVault(setup.prefs).passwords.first())
    }

    @Test
    fun `a saved password opens the archive without asking and cancelling leaves the archive`() = smoke { scope ->
        val setup = setup(scope) {
            addArchive("secret.zip", mapOf("a.txt" to ByteArray(3)), password = "pw2")
            addArchive("other.zip", mapOf("b.txt" to ByteArray(3)), password = "unknown")
        }
        val drive = setup.drive
        ArchivePasswordVault(setup.prefs).remember("pw1")
        ArchivePasswordVault(setup.prefs).remember("pw2")
        drive.load()
        awaitUntil("根目录列出压缩包") { !drive.isLoading && drive.files.size == 2 }

        drive.openArchive(drive.files.single { it.name == "secret.zip" })
        awaitUntil("存过的密码试中了") { drive.archiveView != null && drive.files.map { it.name } == listOf("a.txt") }
        assertNull(drive.archivePasswordRequest)

        drive.navigateUp()
        awaitUntil("回到根目录") { drive.archiveView == null && !drive.isLoading && drive.files.size == 2 }
        drive.openArchive(drive.files.single { it.name == "other.zip" })
        awaitUntil("都不对才问") { drive.archivePasswordRequest != null }
        drive.dismissArchivePassword()
        awaitUntil("取消即退出压缩包") { drive.archiveView == null && drive.files.size == 2 }
    }
}
