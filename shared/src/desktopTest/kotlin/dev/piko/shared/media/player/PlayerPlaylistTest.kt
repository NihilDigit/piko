package dev.piko.shared.media.player

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
    fun `movies beside a season do not stop the episodes from shrinking`() {
        val labels = distinctLabels(
            listOf(
                "[DBD-Raws][Steins;Gate][01][1080P][FLAC].mkv",
                "[DBD-Raws][Steins;Gate][23][1080P][FLAC].mkv",
                "[DBD-Raws][Steins;Gate][23][Beta.Ver][1080P][FLAC].mkv",
                "[DBD-Raws][Steins;Gate][25(SP)][1080P][FLAC].mkv",
                "[DBD-Raws][Steins;Gate][PV].mp4",
                "[DBD-Raws][Steins;Gate Fuka Ryouiki no Deja vu][Movie][1080P][FLAC].mkv",
            ),
        )
        assertEquals(
            listOf("01", "23", "[23][Beta.Ver]", "25(SP)", "PV", "[DBD-Raws][Steins;Gate Fuka Ryouiki no Deja vu][Movie]"),
            labels,
        )
    }

    @Test
    fun `a name that is a prefix of another is not stripped to nothing`() {
        assertEquals(listOf("Movie", "Movie Extended"), distinctLabels(listOf("Movie.mkv", "Movie Extended.mkv")))
    }
}
