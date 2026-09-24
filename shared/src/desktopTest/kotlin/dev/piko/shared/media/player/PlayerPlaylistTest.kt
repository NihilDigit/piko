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
}
