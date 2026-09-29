package dev.piko.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.shared.data.ChildFile
import dev.piko.shared.data.ScannedFile
import dev.piko.shared.data.isArchiveVolume
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.buildPlaylist
import dev.piko.shared.media.player.buildRawPlaylist
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.analyzeMediaBatch
import dev.piko.shared.naming.describeFolder
import dev.piko.shared.naming.normalizeAvCode
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.naming.workKeyOf
import dev.piko.shared.state.analyzeDriveFolder
import dev.piko.shared.state.buildDriveItems
import dev.piko.shared.state.describeDriveFolder
import dev.piko.shared.state.extractLinks
import dev.piko.shared.state.findDuplicates
import io.github.nihildigit.pikpak.FileStat
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 文件名解析在 Android 的正则引擎（ICU）上跑一遍。ICU 与 HotSpot 的正则不是一回事：ICU 的 \d 是全部 Unicode
 * 数字，文件名里的 𝟐（数学粗体）被当成数字抓出来，toInt() 解不了，1.1.0 的信息流一遍历到这样的文件就闪退。
 * 桌面测试跑在 HotSpot 上，这类问题一个也抓不到。CI 的模拟器是 API 34，只有老系统才有的差异（1.0.0 的 \p{IsHan}
 * 在 Android 8 上编译即崩）这里仍抓不到，靠 AndroidRegexGuardTest 扫源码。
 *
 * 做法是拿一组各种写法的文件名，把其中的数字逐位、整体换成别的书写系统的数字，喂给解析的各个入口，
 * 不许抛异常。结论对不对由桌面测试管，这里只管在 Android 上不崩。
 */
@RunWith(AndroidJUnit4::class)
class NamingUnicodeDigitsSmokeTest {
    @Test
    fun deviceRegexTreatsUnicodeDigitsAsDigits() {
        // 前提：设备上的 \d 真的认得这些数字，否则这个测试什么也没测到
        val digit = Regex("\\d")
        UNICODE_ZEROS.forEach { zero ->
            val two = String(Character.toChars(zero + 2))
            assertTrue("设备上的 \\d 不认 $two，换了正则引擎？", digit.matches(two))
        }
    }

    @Test
    fun namingSurvivesUnicodeDigits() {
        val failures = mutableListOf<String>()
        variants().forEach { (label, names) ->
            runCatching { exercise(names) }.onFailure { failures += "$label：$it" }
        }
        if (failures.isNotEmpty()) fail("${failures.size} 组文件名在解析时抛了异常：\n" + failures.take(20).joinToString("\n"))
    }

    /** 每组是同一目录里的一批文件名：原样一组，每种数字整体替换一组，再逐个数字位单独替换。 */
    private fun variants(): List<Pair<String, List<String>>> = buildList {
        add("原样" to BASE_NAMES)
        UNICODE_ZEROS.forEach { zero ->
            add("全换成 U+${zero.toString(16)}" to BASE_NAMES.map { replaceDigits(it, zero) { true } })
            BASE_NAMES.forEach { name ->
                val positions = name.indices.filter { name[it] in '0'..'9' }
                positions.forEach { at ->
                    add("$name 第 $at 位换成 U+${zero.toString(16)}" to listOf(replaceDigits(name, zero) { it == at }))
                }
            }
        }
    }

    private fun replaceDigits(name: String, zero: Int, where: (Int) -> Boolean): String = buildString {
        name.forEachIndexed { index, c ->
            if (c in '0'..'9' && where(index)) appendCodePoint(zero + (c - '0')) else append(c)
        }
    }

    /** 界面与后台用到文件名解析的入口都走一遍，与网盘页、信息流、查重、播放列表、粘贴链接调的是同一组函数。 */
    private fun exercise(names: List<String>) {
        val files = names.mapIndexed { index, name ->
            val folder = '.' !in name
            FileStat(kind = if (folder) FOLDER_KIND else FILE_KIND, id = "id$index", parentId = "root", name = name, size = "${(index + 1) * 1_000_000}")
        }
        names.forEach { name ->
            parseMediaName(name)
            workKeyOf(name)
            normalizeAvCode(name)
            isArchiveVolume(name)
            describeFolder(name)
            describeDriveFolder(name, null)
        }
        val structure = analyzeDriveFolder(files)
        buildDriveItems(files, structure, hideFolded = true, isExpanded = { true })
        buildDriveItems(files, structure, hideFolded = false, isExpanded = { false })
        describeDriveFolder("folder", names.map(::ChildFile))
        describeFolder("folder", names.map { MediaFileInput(it, 1_000_000) })
        analyzeMediaBatch(files.map { MediaFileInput(it.name, it.size.toLong()) })
        analyzeMediaBatch(files.map { MediaFileInput("Show/Season 1/${it.name}", it.size.toLong()) })
        findDuplicates(files.map { ScannedFile(it, "Movies/Season 2") }, rootName = "root")
        val playlist = files.filterNot { it.kind == FOLDER_KIND }.map { PlaylistEntry(fileId = it.id, name = it.name, label = it.name) }
        buildPlaylist(playlist)
        buildRawPlaylist(playlist)
        extractLinks(names.joinToString("\n") { "magnet:?xt=urn:btih:${"0".repeat(40)}&dn=$it" })
    }

    private companion object {
        const val FOLDER_KIND = "drive#folder"
        const val FILE_KIND = "drive#file"

        // 数学粗体（BMP 之外，一个数字两个 char）、全角、阿拉伯-印度、天城文、泰文的 0
        val UNICODE_ZEROS = listOf(0x1D7CE, 0xFF10, 0x0660, 0x0966, 0x0E50)

        /** 解析器各条规则认的写法各来一点：集数、季、年份、分辨率、番号、版本、分卷、相机与时间戳、范围、副本标记。 */
        val BASE_NAMES = listOf(
            "[Nekomoe kissaten] Frieren - 12 [1080p][CHS].mp4",
            "[SweetSub] Show S02E07 v2 [WebRip 1080p HEVC-10bit AAC].mkv",
            "Show.S01E01-E02.2160p.WEB-DL.DDP5.1.Atmos.H.265.mkv",
            "Show - 03.5 [720p].mp4",
            "第02季 第13集.mp4",
            "第2季",
            "Season 3",
            "S01-S03",
            "EP01-12 合集",
            "01~26",
            "2nd Season",
            "Vol.2",
            "CD1",
            "Movie.Title.1999.1080p.BluRay.x264.DTS-HD.MA.5.1.mkv",
            "Movie Title (2023) 4K HDR.mp4",
            "Movie Title (2).mp4",
            "Movie Title - 副本 (3).mkv",
            "1920x1080 sample.mp4",
            "ABP-123.mp4",
            "SSIS-001-C.mp4",
            "FC2-PPV-1234567.mp4",
            "IMG_20230105_143022.jpg",
            "VID_20231231_235959.mp4",
            "1695000000000.mp4",
            "1695000000.mp4",
            "Screenshot_2023-01-05-14-30-22.png",
            "archive.part01.rar",
            "archive.7z.001",
            "archive.z01",
            "Show 01.ass",
            "Show 01.sc.srt",
            "Show - 01 [BDRip 1080p FLAC 2.0].mka",
            "track 02 - 24bit 96kHz.flac",
            "Show12",
            "Title-2024",
            "[01][720P].mp4",
            "01.mp4",
            "02.mp4",
            "03.mp4",
        )
    }
}
