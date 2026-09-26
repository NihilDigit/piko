package dev.piko.shared.state

import dev.piko.shared.data.ScannedFile
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateGroupingTest {

    private var nextId = 0

    private fun file(
        path: String,
        hash: String = "H${nextId}",
        size: Long = 1_000_000L + nextId,
        created: String = "2026-01-01T00:00:00.000+08:00",
    ): ScannedFile {
        val id = "f${nextId++}"
        val stat = FileStat(
            kind = FileKind.FILE, id = id, name = path.substringAfterLast('/'),
            size = size.toString(), hash = hash, createdTime = created,
        )
        return ScannedFile(stat, path.substringBeforeLast('/', ""))
    }

    private fun versionPaths(files: List<ScannedFile>, rootName: String? = null): List<List<String>> =
        findDuplicates(files, rootName).versions.map { group -> group.rows.map { it.file.path }.sorted() }

    @Test
    fun `identical groups keep the oldest copy and fall back to the shorter path`() {
        val newer = file("a/b/Movie.mkv", hash = "X", size = 500, created = "2026-03-01T00:00:00.000+08:00")
        val oldest = file("deep/er/path/Movie.mkv", hash = "X", size = 500, created = "2025-12-31T23:00:00.000+00:00")
        val tieLong = file("x/y/Movie (1).mkv", hash = "Y", size = 700)
        val tieShort = file("Movie.mkv", hash = "Y", size = 700)
        // 同 gcid 不同大小、空文件、缺 gcid 的都不算
        val otherSize = file("c/Movie.mkv", hash = "X", size = 501)
        val empties = listOf(file("e1.txt", hash = "E", size = 0), file("e2.txt", hash = "E", size = 0))
        val unhashed = listOf(file("u1.bin", hash = "", size = 9), file("u2.bin", hash = "", size = 9))

        val report = findDuplicates(listOf(newer, oldest, tieLong, tieShort, otherSize) + empties + unhashed)

        assertEquals(2, report.identical.size)
        val x = report.identical.single { group -> group.rows.any { it.file.id == newer.file.id } }
        // 时区不同也按真实时刻比较：23:00 UTC 早于 2026-03-01
        assertEquals(oldest.file.id, x.keptId)
        assertEquals(500, x.reclaimableBytes)
        val y = report.identical.single { group -> group.rows.any { it.file.id == tieShort.file.id } }
        assertEquals(tieShort.file.id, y.keptId)
    }

    @Test
    fun `copies with the most stacked markers go first regardless of age`() {
        val stacked = file("Clip (1)(1).mp4", hash = "Z", size = 800, created = "2025-01-01T00:00:00.000+08:00")
        val copied = file("Clip - Copy.mp4", hash = "Z", size = 800, created = "2025-02-01T00:00:00.000+08:00")
        val original = file("sorted/Clip.mp4", hash = "Z", size = 800, created = "2026-01-01T00:00:00.000+08:00")

        val group = findDuplicates(listOf(stacked, copied, original)).identical.single()

        assertEquals(original.file.id, group.keptId)
        assertEquals(listOf(original, copied, stacked).map { it.file.id }, group.rows.map { it.file.id })
        assertEquals(2, copyMarkerCount("x - Copy (2).mkv"))
        assertEquals(1, copyMarkerCount("x - 副本.mkv"))
        // 年份不是副本序号
        assertEquals(0, copyMarkerCount("Movie (2019).mkv"))
    }

    @Test
    fun `the same episode from a fansub and a scene release forms one version group`() {
        val files = listOf(
            file("anime/Title/[Group] Title - 03 [1080p].mkv"),
            file("anime/Title/[Group] Title - 04 [1080p].mkv"),
            file("tv/Title.S01E03.720p.mkv"),
        )
        val report = findDuplicates(files)

        assertEquals(listOf(listOf("anime/Title/[Group] Title - 03 [1080p].mkv", "tv/Title.S01E03.720p.mkv")), versionPaths(files))
        val group = report.versions.single()
        assertNull(group.keptId)
        assertEquals(0, group.reclaimableBytes)
        // 组内相同的标签不显示，只留能区分两版的
        assertEquals(
            mapOf("[Group] Title - 03 [1080p].mkv" to listOf("Group", "1080p"), "Title.S01E03.720p.mkv" to listOf("720p")),
            group.rows.associate { it.file.name to it.details },
        )
    }

    @Test
    fun `different episodes seasons and specials stay apart`() {
        val files = listOf(
            file("a/[Group] Title - 03 [1080p].mkv"),
            file("b/[Other] Title - 04 [720p].mkv"),
            file("c/Title.S02E03.720p.mkv"),
            file("d/Title - 03.5 [1080p].mkv"),
            file("e/Title - SP03 [1080p].mkv"),
        )
        assertEquals(emptyList(), versionPaths(files))
    }

    @Test
    fun `a v2 revision is a version but a beta episode is not`() {
        val files = listOf(
            file("a/[Group] Show - 01v2 [1080p].mkv"),
            file("b/[Group] Show - 01 [1080p].mkv"),
            file("sg/[DBD-Raws][Steins;Gate][23][1080P][BDRip][HEVC-10bit][FLAC].mkv"),
            file("sg/[DBD-Raws][Steins;Gate][23][Beta.Ver][1080P][BDRip][HEVC-10bit][FLAC].mkv"),
        )
        assertEquals(listOf(listOf("a/[Group] Show - 01v2 [1080p].mkv", "b/[Group] Show - 01 [1080p].mkv")), versionPaths(files))
    }

    @Test
    fun `season from the folder name keeps an unnumbered season 2 file away from season 1`() {
        val files = listOf(
            file("Title S2/[Group] Title - 03 [1080p].mkv"),
            file("tv/Title.S01E03.720p.mkv"),
            file("tv/Title.S02E03.720p.mkv"),
        )
        assertEquals(
            listOf(listOf("Title S2/[Group] Title - 03 [1080p].mkv", "tv/Title.S02E03.720p.mkv")),
            versionPaths(files),
        )
        // 扫描起点本身就是季目录时，起点名同样生效
        val inRoot = listOf(file("[Group] Title - 03 [1080p].mkv"), file("more/Title.S01E03.720p.mkv"))
        assertEquals(emptyList(), versionPaths(inRoot, rootName = "Title 第2季"))
    }

    @Test
    fun `uncertain names are left out`() {
        val files = listOf(
            // 作品名只能取自目录、集号是裸数字
            file("2023/Camera/IMG_1234.mp4"),
            file("2024/Camera/IMG_1234.mp4"),
            file("x/01.mkv"),
            file("y/01.mkv"),
            file("c1/Lesson 03.mp4"),
            file("c2/Lesson 03.mp4"),
            // 电影不带年份无从区分翻拍
            file("m1/Dune.1984.1080p.BluRay.mkv"),
            file("m2/Dune.2021.2160p.UHD.BluRay.x265.mkv"),
            file("m3/Dune.mkv"),
        )
        assertEquals(emptyList(), versionPaths(files))
    }

    @Test
    fun `movies with the same year merge and identical copies count once`() {
        val copy1 = file("Movies/Dune (2021) 1080p.mp4", hash = "D", size = 4_000)
        val copy2 = file("backup/Dune (2021) 1080p.mp4", hash = "D", size = 4_000, created = "2027-01-01T00:00:00.000+08:00")
        val uhd = file("m/Dune.2021.2160p.UHD.BluRay.x265.mkv", hash = "U", size = 9_000)
        val report = findDuplicates(listOf(copy1, copy2, uhd))

        assertEquals(copy1.file.id, report.identical.single().keptId)
        val rows = report.versions.single().rows
        assertEquals(listOf(uhd.file.id, copy1.file.id), rows.map { it.file.id })
        assertEquals(listOf(0, 1), rows.map { it.sameCopies })
    }

    @Test
    fun `av releases of one code are versions of each other`() {
        val files = listOf(file("a/SSIS-123.mp4"), file("b/SSIS-123-C.mp4"), file("c/SSIS-124.mp4"))
        assertEquals(listOf(listOf("a/SSIS-123.mp4", "b/SSIS-123-C.mp4")), versionPaths(files))
    }

    @Test
    fun `release year ignores resolutions and checksums`() {
        assertEquals(2017, releaseYear("Blade.Runner.2049.2017.1920x1080.mkv"))
        assertNull(releaseYear("[Group] Title - 03 [2160p][AB2019CD].mkv"))
        assertTrue(seasonFromDirectories(listOf("进击的巨人 第三季", "Subs")) == 3)
    }
}
