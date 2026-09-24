package dev.piko.shared.media.player

import dev.piko.shared.naming.NamingFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerPlaylistTest {

    @Test
    fun `fansub season shrinks to episode numbers in natural order`() {
        val entries = buildPlaylist(
            listOf("[DBD-Raws][Steins;Gate][10][1080P][FLAC].mkv", "[DBD-Raws][Steins;Gate][2][1080P][FLAC].mkv")
                .mapIndexed { index, name -> PlaylistEntry(fileId = "id$index", name = name, label = "") },
        )
        assertEquals(listOf("2", "10"), entries.map { it.label })
    }

    @Test
    fun `a flattened bd pack splits into sections the player can stay inside`() {
        // 网盘里被拍平后的样子：没有目录，只剩视频
        val videos = NamingFixtures.flatten(NamingFixtures.load("steins-gate-dbd"))
            .filter { it.path.endsWith(".mkv") || it.path.endsWith(".mp4") }
        val list = buildPlaylist(videos.mapIndexed { i, f -> PlaylistEntry("id$i", f.path, "", size = f.size) })
        val bySection = list.groupBy { it.sectionLabel }

        val main = bySection.getValue("正片").map { it.label }
        assertEquals((1..23).map { it.toString().padStart(2, '0') } + listOf("23 Beta", "24"), main)
        assertEquals(listOf("25(SP)"), bySection.getValue("SP").map { it.label })
        // 剧场版另起一部作品，但分区名不必带上长长的作品名
        assertEquals(listOf("Steins;Gate Fuka Ryouiki no Deja vu"), bySection.getValue("剧场版").map { it.label })
        assertEquals("正片", list.first().sectionLabel)
    }

    @Test
    fun `a name that is a prefix of another is not stripped to nothing`() {
        assertEquals(listOf("Movie", "Movie Extended"), distinctLabels(listOf("Movie.mkv", "Movie Extended.mkv")))
    }

    @Test
    fun `versions of one episode form a group named by what sets them apart`() {
        val names = listOf(
            "[G] Show - 01 [1080p].mkv" to 2_000L, "[G] Show - 01 [720p].mkv" to 1_000L,
            "[G] Show - 02 [1080p].mkv" to 2_000L, "[G] Show - 02 [720p].mkv" to 1_000L,
        )
        val list = buildPlaylist(names.mapIndexed { i, (name, size) -> PlaylistEntry("id$i", name, "", size = size) })
        val first = list.filter { it.label == "01" }
        assertEquals(1, first.map { it.groupKey }.distinct().size)
        assertEquals(listOf("1080p", "720p"), first.map { it.versionLabel })
        assertEquals(listOf(true, false), first.map { it.primary })
        // 切到 720p 之后，下一集也放 720p；没有 720p 的组放体积最大的
        val second = list.filter { it.label == "02" }
        assertEquals("720p", preferredVersion(second, "720p").versionLabel)
        assertEquals("1080p", preferredVersion(second, "480p").versionLabel)
    }
}
