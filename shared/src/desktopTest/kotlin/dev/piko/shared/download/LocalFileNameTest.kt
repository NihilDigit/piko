package dev.piko.shared.download

import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalFileNameTest {

    @Test
    fun `adds the extension an offline download left out of its name`() {
        // 实际遇到的名字：扩展名只在 file_extension 里，存下来的文件打不开
        val file = FileStat(name = "[银色子弹字幕组][名侦探柯南][第1214集 茜色的千秋樂][WEBRIP][繁日雙語MP4][1080P]", fileExtension = ".mp4")
        assertEquals("[银色子弹字幕组][名侦探柯南][第1214集 茜色的千秋樂][WEBRIP][繁日雙語MP4][1080P].mp4", localFileNameOf(file))
    }

    @Test
    fun `does not repeat an extension the name already has`() {
        // 扩展名照 FileNameSanitizer 的规矩转小写，要看的是没有补成 Movie.mkv.mkv
        assertEquals("Movie.mkv", localFileNameOf(FileStat(name = "Movie.MKV", fileExtension = ".mkv")))
    }

    @Test
    fun `takes the extension with or without a leading dot`() {
        assertEquals("Show 01.mp4", localFileNameOf(FileStat(name = "Show 01", fileExtension = "mp4")))
    }

    @Test
    fun `keeps a name that looks dotted when the api gives no extension`() {
        assertEquals("Show.S01.1080p", localFileNameOf(FileStat(name = "Show.S01.1080p", fileExtension = "")))
    }

    @Test
    fun `truncates overlong cjk file and folder names within utf8 byte budget while preserving episode suffix`() {
        val prefix = "【超长字幕组名称测试】关于我在异世界写跨平台客户端却因为单级文件名超过二百五十五字节限制而下载失败这档事".repeat(3)
        val ep1 = localFileNameOf(FileStat(name = "$prefix [第01集][1080P].mkv", fileExtension = "mkv"))
        val ep2 = localFileNameOf(FileStat(name = "$prefix [第02集][1080P].mkv", fileExtension = "mkv"))
        assertTrue(ep1.encodeToByteArray().size <= 180, "UTF-8 bytes=${ep1.encodeToByteArray().size}")
        assertTrue(ep2.encodeToByteArray().size <= 180, "UTF-8 bytes=${ep2.encodeToByteArray().size}")
        assertTrue(ep1.endsWith("[第01集][1080P].mkv"), ep1)
        assertTrue(ep2.endsWith("[第02集][1080P].mkv"), ep2)
        assertTrue(ep1 != ep2)

        val folder = dev.piko.data.repository.FileNameSanitizer.sanitizeFolderName("$prefix Season 01")
        assertTrue(folder.encodeToByteArray().size <= 120, "folder UTF-8 bytes=${folder.encodeToByteArray().size}")
        assertTrue(folder.endsWith("Season 01"), folder)
    }
}
