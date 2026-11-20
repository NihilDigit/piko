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

    /** 字幕只挂到自己那集，不进播放列表；语言取自解析器，mpv 按它匹配 slang。 */
    @Test
    fun `subtitle files attach to their own episode and stay out of the list`() {
        val videos = listOf(1, 2).map { n ->
            PlaylistEntry(fileId = "v$n", name = "[Grp] Show - 0$n [1080p].mkv", label = "", size = 700L shl 20)
        }
        val subtitles = listOf(
            SubtitleRef("s1", "[Grp] Show - 01 [1080p].chs.ass", null),
            SubtitleRef("s1t", "[Grp] Show - 01 [1080p].cht.ass", null),
            SubtitleRef("s2", "[Grp] Show - 02 [1080p].chs.ass", null),
        )
        val list = buildPlaylist(videos, subtitles)
        assertEquals(listOf("v1", "v2"), list.map { it.fileId })
        assertEquals(setOf("s1", "s1t"), list[0].subtitles.map { it.fileId }.toSet())
        assertEquals(listOf("s2"), list[1].subtitles.map { it.fileId })
        assertEquals("简", list[1].subtitles.single().language)
    }

    /**
     * 配不上任何视频的字幕被解析器当成正文，自成条目。它的序号落在视频之后，1.1.0 拿它去取视频，
     * 同目录里有这种字幕的视频一打开就崩。
     */
    @Test
    fun `subtitles without a matching video stay out of the list`() {
        val videos = listOf(1, 2).map { n ->
            PlaylistEntry(fileId = "v$n", name = "[Grp] Show - 0$n [1080p].mkv", label = "", size = 700L shl 20)
        }
        val subtitles = listOf(
            SubtitleRef("s3", "[Grp] Show - 03 [1080p].chs.ass", null),
            SubtitleRef("other", "Another Film.en.srt", null),
        )
        val list = buildPlaylist(videos, subtitles)
        assertEquals(listOf("v1", "v2"), list.map { it.fileId })
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

    @Test
    fun `standalone movies in one folder fall back to unified natural-sorted folder queue`() {
        val files = listOf(
            PlaylistEntry("m3", "Tenet (2020) [1080p].mkv", "", size = 5L shl 30),
            PlaylistEntry("m1", "Inception (2010) [1080p].mkv", "", size = 4L shl 30),
            PlaylistEntry("m2", "Interstellar (2014) [1080p].mkv", "", size = 6L shl 30),
        )
        val subtitles = listOf(
            SubtitleRef("s1", "Inception (2010) [1080p].cht.ass", null),
        )
        val list = buildPlaylist(files, subtitles, currentFileId = "m1")
        // 每部电影各自分区都只有 1 条，应退回当前文件夹自然排序的单一队列，并保留匹配的外挂字幕
        assertEquals(listOf("m1", "m2", "m3"), list.map { it.fileId })
        assertEquals(listOf(""), list.map { it.sectionKey }.distinct())
        assertEquals(listOf("s1"), list.first { it.fileId == "m1" }.subtitles.map { it.fileId })
    }

    @Test
    fun `opening a single-item section in a mixed folder falls back to current folder while multi-episode work stays grouped`() {
        val files = listOf(
            PlaylistEntry("e1", "[Grp] Show - 01 [1080p].mkv", "", size = 700L shl 20),
            PlaylistEntry("e2", "[Grp] Show - 02 [1080p].mkv", "", size = 700L shl 20),
            PlaylistEntry("solo", "Random Documentary 2026.mp4", "", size = 1200L shl 20),
        )
        // 打开多集剧集时保持解析分组
        val seriesQueue = buildPlaylist(files, currentFileId = "e1")
        val seriesSection = seriesQueue.first { it.fileId == "e1" }.sectionKey
        assertEquals(listOf("e1", "e2"), seriesQueue.filter { it.sectionKey == seriesSection }.map { it.fileId })

        // 打开同目录下只有单条目的独立文件时，队列退回当前文件夹全部可播文件
        val soloQueue = buildPlaylist(files, currentFileId = "solo")
        assertEquals(3, soloQueue.size)
        assertEquals(listOf(""), soloQueue.map { it.sectionKey }.distinct())
        assertEquals(setOf("e1", "e2", "solo"), soloQueue.map { it.fileId }.toSet())
    }
}
