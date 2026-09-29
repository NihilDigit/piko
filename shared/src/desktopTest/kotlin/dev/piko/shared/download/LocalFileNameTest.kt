package dev.piko.shared.download

import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
