package dev.piko.shared.smoke

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.state.DriveScreenState
import io.github.nihildigit.pikpak.thumbnailUrlOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内容归档后的文件夹，服务端不再给 thumbnail_link（假服务端从不给文件夹封面，正对应这种情形），
 * 海报墙与图库里文件夹卡片的封面要从清单里的视频条目补。清单用开发期最早一版写下的格式：
 * 没有 restoredFileId，可选字段为空时不写出。
 */
class VaultFolderCoverSmokeTest {
    private val oldManifest = """
        {"entries":[
          {"id":"a1","name":"Frieren - 02.mkv","size":4096,"gcid":"GCIDEP02","addedAt":1759000000000},
          {"id":"a2","name":"Frieren - 01.ass","size":10,"gcid":"GCIDSUB01","addedAt":1759000000000},
          {"id":"a3","name":"Frieren - 01.mkv","size":4096,"gcid":"GCIDEP01","cid":"CID01","source":"magnet:?xt=urn:btih:${"a".repeat(40)}","addedAt":1759000000000}
        ]}
    """.trimIndent()

    @Test
    fun `a folder archived by an older build gets its cover once it scrolls into view`() = smoke { scope ->
        val server = FakePikPakServer()
        val frieren = server.addFolder("Frieren")
        server.addFile(".piko-vault-v1-0123456789abcdef.json", frieren.id, content = oldManifest.encodeToByteArray())
        val plain = server.addFolder("Plain")
        server.addFile("notes.txt", plain.id)
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        val drive = DriveScreenState(repo, prefs, scope)
        drive.load()
        awaitUntil("根目录列出") { !drive.isLoading && drive.files.size == 2 }
        assertEquals("", drive.files.single { it.id == frieren.id }.thumbnailLink, "没进过的文件夹还不知道有清单")

        drive.files.forEach { drive.onFolderVisible(it) }

        // 名字排第一的视频当封面，字幕不算
        val expected = thumbnailUrlOf("GCIDEP01")
        awaitUntil("文件夹卡片补上封面") { drive.files.single { it.id == frieren.id }.thumbnailLink == expected }
        assertEquals("", drive.files.single { it.id == plain.id }.thumbnailLink)
        // 记下之后再列不必重读清单
        val cached = repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow()
        assertEquals(expected, cached.single { it.id == frieren.id }.thumbnailLink)
    }

    @Test
    fun `the archived outer folder borrows a cover from a folder below it`() = smoke { scope ->
        val server = FakePikPakServer()
        val outer = server.addFolder("Anime")
        val season = server.addFolder("Season 1", outer.id)
        server.addFile(".piko-vault-v3-00000000000000ff.json", season.id, content = oldManifest.encodeToByteArray())
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(server.provider(), prefs)
        repo.vaultTrees.merge(mapOf(outer.id to setOf(season.id)))

        repo.fetchVaultCover(outer.id)

        val listed = repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow()
        assertEquals(thumbnailUrlOf("GCIDEP01"), listed.single { it.id == outer.id }.thumbnailLink)
    }

    @Test
    fun `restored entries do not cover a folder`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Restored")
        val restored = """
            {"entries":[{"id":"r1","name":"E01.mkv","size":4096,"gcid":"GCIDR1","addedAt":1759000000000,"restoredFileId":"N999"}]}
        """.trimIndent()
        server.addFile(".piko-vault-v2-0000000000000001.json", folder.id, content = restored.encodeToByteArray())
        val repo = PikoDriveRepository(server.provider(), MemoryPreferences())
        repo.listBrowsable(folder.id, PikoFileSortOrder.NAME_ASC).getOrThrow()

        repo.fetchVaultCover(folder.id)

        val listed = repo.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow()
        assertEquals("", listed.single { it.id == folder.id }.thumbnailLink)
    }
}
